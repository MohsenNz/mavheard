package com.mavheart.h12

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import com.mavheart.h12.proxy.SerialHub
import java.io.IOException

enum class LinkState { ARMED, DISARMED, UNKNOWN }

data class UiState(
    val running: Boolean = false,
    val link: LinkState = LinkState.UNKNOWN,
    val sourceDescription: String? = null,
    val sysid: Int? = null,
    val compid: Int? = null,
    val mavlinkVersion: Int? = null,
    val baseMode: Int? = null,
    val customMode: Long? = null,
    /** MAV_TYPE of the last ArduPilot heartbeat. */
    val vehicleType: Int? = null,
    val rawBytes: Long = 0,
    val heartbeats: Int = 0,
    val firstBytesHex: String? = null,
    val error: String? = null,
    /** True when the open source can send bytes to the flight controller. */
    val canWrite: Boolean = false,
    val commandResult: CommandResult? = null,
) {
    /** Arm/disarm is allowed: writable link, fresh heartbeat, no command in flight. */
    val canCommand: Boolean
        get() = running && canWrite && link != LinkState.UNKNOWN && sysid != null &&
            commandResult !is CommandResult.Sending

    /** Flight modes are only decoded and changeable for ArduCopter (mode numbers differ per vehicle). */
    val isCopter: Boolean get() = vehicleType != null && isCopterType(vehicleType)

    /** Current mode name, or null if unknown / not a Copter / no fresh heartbeat. */
    val modeName: String?
        get() = if (isCopter && customMode != null && link != LinkState.UNKNOWN) copterModeName(customMode.toInt()) else null

    /** Why [canCommand] is false, for the UI; null when it is true. */
    val commandBlockedReason: String?
        get() = when {
            canCommand -> null
            !running -> null
            !canWrite -> "Read-only link: cannot send commands"
            link == LinkState.UNKNOWN || sysid == null -> "No recent heartbeat"
            else -> null // command in flight
        }
}

sealed interface CommandResult {
    data object Sending : CommandResult
    data class Accepted(val text: String) : CommandResult
    data class Rejected(val reason: String) : CommandResult
    data object Timeout : CommandResult
    data class Failed(val message: String) : CommandResult
}

private const val ACK_TIMEOUT_MS = 3000L
private sealed class PendingCommand(val commandId: Int, val acceptedText: String) {
    class Arm(val arm: Boolean, val force: Boolean) :
        PendingCommand(CMD_ARM_DISARM, if (arm) "Arm command accepted" else "Disarm command accepted")
    class SetMode(val mode: CopterMode) :
        PendingCommand(CMD_DO_SET_MODE, "Mode change accepted: ${mode.label}")
}

const val FRESH_MS = 3000L

/** Same freshness rule as the script: a heartbeat older than 3 s means UNKNOWN. */
fun linkState(lastHbMs: Long?, lastArmed: Boolean?, nowMs: Long): LinkState = when {
    lastHbMs == null || lastArmed == null || nowMs - lastHbMs >= FRESH_MS -> LinkState.UNKNOWN
    lastArmed -> LinkState.ARMED
    else -> LinkState.DISARMED
}

/**
 * Reads [ByteSource]s on Dispatchers.IO, scans for ArduPilot heartbeats and publishes [UiState].
 * The only things it ever writes are the user-initiated arm/disarm ([requestArm]) and Copter
 * set-mode ([requestMode]) COMMAND_LONGs, and only on a source that is writable.
 */
class HeartbeatMonitor(
    private val scope: CoroutineScope,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    /**
     * Candidates tried in order; first one that opens wins. By default the only candidate is a
     * subscription to the [SerialHub], which owns the real port (direct, then TCP).
     */
    private val sources: List<() -> ByteSource> = listOf({ SerialHub.instance.subscribe() }),
) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var job: Job? = null
    private val commands = Channel<PendingCommand>(Channel.CONFLATED)

    fun start() {
        if (job?.isActive == true) return
        _state.value = UiState(running = true)
        job = scope.launch(Dispatchers.IO) { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.update { it.copy(running = false, link = LinkState.UNKNOWN) }
    }

    /**
     * Asks the loop to send an arm/disarm command to the last-seen ArduPilot system. Returns false
     * (and sends nothing) if [UiState.canCommand] is false. The outcome appears in
     * [UiState.commandResult]. [force] only applies to disarm.
     */
    fun requestArm(arm: Boolean, force: Boolean = false): Boolean =
        submit(PendingCommand.Arm(arm, force)) { true }

    /**
     * Asks the loop to switch an ArduCopter to [modeId] (MAV_CMD_DO_SET_MODE). Refused unless the
     * vehicle is a Copter, the mode is in [CopterMode.selectableModes], and [UiState.canCommand].
     */
    fun requestMode(modeId: Int): Boolean {
        val mode = CopterMode.fromId(modeId)?.takeIf { it.selectable } ?: return false
        return submit(PendingCommand.SetMode(mode)) { it.isCopter }
    }

    private fun submit(cmd: PendingCommand, extraCheck: (UiState) -> Boolean): Boolean {
        var accepted = false
        _state.update {
            accepted = it.canCommand && extraCheck(it)
            if (accepted) it.copy(commandResult = CommandResult.Sending) else it
        }
        if (accepted) commands.trySend(cmd)
        return accepted
    }

    private fun openFirst(): ByteSource? {
        val errors = ArrayList<String>()
        for (factory in sources) {
            try { return factory() } catch (e: IOException) { errors += e.message ?: e.toString() }
        }
        _state.update { it.copy(error = errors.joinToString("\n")) }
        return null
    }

    private suspend fun run() {
        val source = openFirst() ?: run { _state.update { it.copy(running = false) }; return }
        _state.update { it.copy(sourceDescription = source.description, canWrite = source.canWrite) }
        while (commands.tryReceive().isSuccess) { /* drop commands left from a previous run */ }
        val scanner = HeartbeatScanner()
        val ackScanner = CommandAckScanner()
        var pending: PendingCommand? = null
        var pendingSince = 0L
        var seq = 0
        var lastSys = 0
        var lastComp = 0
        val buf = ByteArray(4096)
        var raw = 0L
        var count = 0
        var lastHb: Long? = null
        var lastArmed: Boolean? = null
        try {
            while (true) {
                val n = source.read(buf, 500)
                currentCoroutineContext().ensureActive() // stale run must not touch state
                val now = clockMs()
                if (n > 0) {
                    if (raw == 0L) {
                        val hex = buf.take(minOf(n, 32)).joinToString(" ") { "%02x".format(it) }
                        _state.update { it.copy(firstBytesHex = hex) }
                    }
                    raw += n
                    for (hb in scanner.feed(buf, n)) {
                        if (hb.autopilot != ARDUPILOT_AUTOPILOT_ID) continue
                        count++
                        lastHb = now
                        lastArmed = hb.armed
                        lastSys = hb.sysid
                        lastComp = hb.compid
                        _state.update {
                            it.copy(
                                sysid = hb.sysid, compid = hb.compid, mavlinkVersion = hb.version,
                                baseMode = hb.baseMode, customMode = hb.customMode, vehicleType = hb.type,
                            )
                        }
                    }
                }
                _state.update {
                    it.copy(
                        link = linkState(lastHb, lastArmed, now), rawBytes = raw, heartbeats = count,
                        // These can change when the hub (re)connects to another source:
                        sourceDescription = source.description, canWrite = source.canWrite,
                    )
                }

                // --- arm/disarm and set-mode: the only write path ---
                val p = pending
                if (p != null) {
                    var result: CommandResult? = null
                    if (n > 0) for (ack in ackScanner.feed(buf, n)) {
                        if (ack.commandId != p.commandId || ack.inProgress) continue
                        result = if (ack.accepted) CommandResult.Accepted(p.acceptedText)
                        else CommandResult.Rejected(ack.description)
                    }
                    if (result == null && now - pendingSince >= ACK_TIMEOUT_MS) result = CommandResult.Timeout
                    if (result != null) {
                        pending = null
                        _state.update { it.copy(commandResult = result) }
                    }
                } else {
                    val cmd = commands.tryReceive().getOrNull()
                    if (cmd != null) {
                        try {
                            source.write(
                                when (cmd) {
                                    is PendingCommand.Arm ->
                                        buildArmFrame(lastSys, lastComp, cmd.arm, cmd.force, seq++ and 0xFF)
                                    is PendingCommand.SetMode ->
                                        buildSetModeFrame(lastSys, lastComp, cmd.mode.id, seq++ and 0xFF)
                                },
                            )
                            pending = cmd
                            pendingSince = clockMs()
                        } catch (e: IOException) {
                            currentCoroutineContext().ensureActive()
                            _state.update { it.copy(commandResult = CommandResult.Failed(e.message ?: "write failed")) }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            _state.update { it.copy(error = e.message, running = false, link = LinkState.UNKNOWN) }
        } finally {
            source.close()
        }
    }
}
