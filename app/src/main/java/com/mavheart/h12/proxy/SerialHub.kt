package com.mavheart.h12.proxy

import com.mavheart.h12.ByteSource
import com.mavheart.h12.SerialPort
import com.mavheart.h12.TcpByteSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

data class HubState(
    /** The hub has been started (and is trying to keep a source open). */
    val running: Boolean = false,
    /** A source is currently open. */
    val connected: Boolean = false,
    val sourceDescription: String? = null,
    val canWrite: Boolean = false,
    val error: String? = null,
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val udpPeers: Int = 0,
    val udpError: String? = null,
)

/**
 * The single owner of the serial source (direct /dev/ttyHS1, else the TCP bridge). Everything else
 * ([HubByteSource] subscribers: the status monitor, the UDP proxy) reads from and writes through
 * it, because two readers on one tty would each get only part of the data.
 *
 * It reopens the source with a backoff if it drops, so it can run for a long time in the background.
 */
class SerialHub(
    /** Candidates tried in order; the first one that opens wins. */
    private val sources: List<() -> ByteSource> = listOf(
        { SerialPort.open() },
        { TcpByteSource.open() },
    ),
    private val reconnectDelayMs: Long = 2000,
) {
    private val _state = MutableStateFlow(HubState())
    val state: StateFlow<HubState> = _state.asStateFlow()

    private val subscribers = CopyOnWriteArrayList<HubByteSource>()
    private val writeLock = Any()
    @Volatile private var source: ByteSource? = null
    private var job: Job? = null

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        _state.value = HubState(running = true)
        job = scope.launch(Dispatchers.IO) { run() }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        for (s in subscribers) s.markClosed()
        subscribers.clear()
        _state.value = HubState()
    }

    /** A new reader/writer view of the source. Throws if the hub is not running. */
    fun subscribe(): ByteSource {
        if (!_state.value.running) throw IOException("serial hub is not running")
        return HubByteSource(this).also { subscribers += it }
    }

    internal fun unsubscribe(s: HubByteSource) { subscribers.remove(s) }

    /** Writes [data] to the source. Calls from different threads never interleave. */
    internal fun write(data: ByteArray) {
        synchronized(writeLock) {
            val src = source ?: throw IOException("no serial source is open")
            src.write(data)
            _state.update { it.copy(txBytes = it.txBytes + data.size) }
        }
    }

    internal fun description(): String? = _state.value.sourceDescription
    internal fun canWrite(): Boolean = _state.value.connected && _state.value.canWrite

    /** Called by the UDP proxy to publish its peer count / bind error. */
    fun setUdpStatus(peers: Int, error: String?) {
        _state.update { if (it.udpPeers == peers && it.udpError == error) it else it.copy(udpPeers = peers, udpError = error) }
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
        while (true) {
            val src = openFirst()
            if (src == null) {
                delay(reconnectDelayMs)
                continue
            }
            source = src
            _state.update {
                it.copy(connected = true, sourceDescription = src.description, canWrite = src.canWrite, error = null)
            }
            try {
                val buf = ByteArray(4096)
                while (true) {
                    val n = src.read(buf, 500)
                    currentCoroutineContext().ensureActive()
                    if (n > 0) {
                        val chunk = buf.copyOf(n)
                        for (s in subscribers) s.offer(chunk)
                        _state.update { it.copy(rxBytes = it.rxBytes + n) }
                    }
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                synchronized(writeLock) { source = null }
                src.close()
                _state.update { it.copy(connected = false, canWrite = false) }
            }
            delay(reconnectDelayMs)
        }
    }

    companion object {
        /** The process-wide hub shared by the service and the UI. */
        val instance = SerialHub()
    }
}

/** A subscriber's view of the [SerialHub]. Receive-only unless the hub's source is writable. */
class HubByteSource internal constructor(private val hub: SerialHub) : ByteSource {
    private val queue = LinkedBlockingDeque<ByteArray>(256)
    @Volatile private var closed = false

    override val description: String get() = hub.description()?.let { "$it via hub" } ?: "serial hub (not connected)"
    override val canWrite: Boolean get() = hub.canWrite()

    /** Returns 0 on timeout, including while the hub is reconnecting; throws only once closed. */
    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        if (closed) throw IOException("hub subscription closed")
        val c = queue.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return 0
        val n = minOf(c.size, buf.size)
        c.copyInto(buf, 0, 0, n)
        if (n < c.size) queue.offerFirst(c.copyOfRange(n, c.size))
        return n
    }

    override fun write(data: ByteArray) {
        if (closed) throw IOException("hub subscription closed")
        hub.write(data)
    }

    override fun close() { markClosed(); hub.unsubscribe(this) }

    internal fun markClosed() { closed = true }

    /** Called from the hub read loop. Never blocks: when full, the oldest chunk is dropped. */
    internal fun offer(chunk: ByteArray) {
        if (closed) return
        if (!queue.offerLast(chunk)) { queue.pollFirst(); queue.offerLast(chunk) }
    }
}
