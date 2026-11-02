package com.mavheart.h12

import kotlinx.coroutines.CoroutineScope
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
)

const val FRESH_MS = 3000L

/** Same freshness rule as the script: a heartbeat older than 3 s means UNKNOWN. */
fun linkState(lastHbMs: Long?, lastArmed: Boolean?, nowMs: Long): LinkState = when {
    lastHbMs == null || lastArmed == null || nowMs - lastHbMs >= FRESH_MS -> LinkState.UNKNOWN
    lastArmed -> LinkState.ARMED
    else -> LinkState.DISARMED
}

/**
 * Reads [ByteSource]s on Dispatchers.IO, scans for ArduPilot heartbeats and publishes [UiState].
 * Never writes to the port.
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
        _state.update { it.copy(sourceDescription = source.description) }
        val scanner = HeartbeatScanner()
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
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            _state.update { it.copy(error = e.message, running = false, link = LinkState.UNKNOWN) }
        } finally {
            source.close()
        }
    }
}
