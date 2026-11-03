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

    /** Why [canCommand] is false, for the UI; null when it is true. */
    val commandBlockedReason: String?
        get() = when {
            canCommand -> null
            !running -> null
            !canWrite -> "Read-only link: cannot arm/disarm"
            link == LinkState.UNKNOWN || sysid == null -> "No recent heartbeat"
            else -> null // command in flight
        }
}

sealed interface CommandResult {
    data object Sending : CommandResult
    data class Accepted(val arm: Boolean) : CommandResult
    data class Rejected(val reason: String) : CommandResult
    data object Timeout : CommandResult
    data class Failed(val message: String) : CommandResult
}

private const val ACK_TIMEOUT_MS = 3000L
private class PendingCommand(val arm: Boolean, val force: Boolean)

const val FRESH_MS = 3000L

/** Same freshness rule as the script: a heartbeat older than 3 s means UNKNOWN. */
fun linkState(lastHbMs: Long?, lastArmed: Boolean?, nowMs: Long): LinkState = when {
    lastHbMs == null || lastArmed == null || nowMs - lastHbMs >= FRESH_MS -> LinkState.UNKNOWN
    lastArmed -> LinkState.ARMED
    else -> LinkState.DISARMED
}

/**
 * Reads [ByteSource]s on Dispatchers.IO, scans for ArduPilot heartbeats and publishes [UiState].
 * The only thing it ever writes is the user-confirmed arm/disarm COMMAND_LONG ([requestArm]),
 * and only on a source that was opened writable.
 */
class HeartbeatMonitor(
    private val scope: CoroutineScope,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Candidates tried in order; first one that opens wins. */
    private val sources: List<() -> ByteSource> = listOf(
        { SerialPort.open() },
        { TcpByteSource.open() },
    ),
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
    fun requestArm(arm: Boolean, force: Boolean = false): Boolean {
        var accepted = false
        _state.update {
            accepted = it.canCommand
            if (accepted) it.copy(commandResult = CommandResult.Sending) else it
        }
        if (accepted) commands.trySend(PendingCommand(arm, force))
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
        val ackScanner = ArmAckScanner()
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
                                baseMode = hb.baseMode, customMode = hb.customMode,
                            )
                        }
                    }
                }
                _state.update {
                    it.copy(link = linkState(lastHb, lastArmed, now), rawBytes = raw, heartbeats = count)
                }

                // --- arm/disarm: the only write path ---
                val p = pending
                if (p != null) {
                    var result: CommandResult? = null
                    if (n > 0) for (ack in ackScanner.feed(buf, n)) {
                        if (ack.inProgress) continue
                        result = if (ack.accepted) CommandResult.Accepted(p.arm)
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
                            source.write(buildArmFrame(lastSys, lastComp, cmd.arm, cmd.force, seq++ and 0xFF))
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
