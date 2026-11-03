package com.mavheart.h12.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridges the [SerialHub] and UDP, in both directions, over one socket bound to 0.0.0.0:[ProxySettings.localPort].
 *
 * - Serial -> UDP: the byte stream is cut into whole MAVLink frames ([MavFrameSplitter]) and each
 *   frame is sent as one datagram to every peer.
 * - UDP -> serial: each datagram from a peer is written to the hub unchanged (only if
 *   [ProxySettings.allowClientsToSend]).
 * - Peers: the optional static [ProxySettings.remote] (client mode, never expires) plus every
 *   address that sent us a datagram (server mode), forgotten after [peerTtlMs] of silence.
 */
class UdpProxy(
    private val hub: SerialHub,
    private val settings: ProxySettings,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val peerTtlMs: Long = 30_000,
) {
    private var socket: DatagramSocket? = null
    private var staticPeer: InetSocketAddress? = null
    private val learned = ConcurrentHashMap<InetSocketAddress, Long>()
    private var jobs: List<Job> = emptyList()

    /** The port actually bound (differs from the setting only when 0 was requested). */
    val localPort: Int get() = socket?.localPort ?: -1

    /** Binds the socket and starts forwarding. Throws [IOException] if it cannot bind or resolve the peer. */
    fun start(scope: CoroutineScope) {
        val peer = settings.remote.takeIf { it.isNotBlank() }?.let { r ->
            val (host, port) = parseHostPort(r) ?: throw IOException("bad remote address: $r")
            InetSocketAddress(host, port).also { if (it.isUnresolved) throw IOException("cannot resolve $host") }
        }
        val s = DatagramSocket(null as java.net.SocketAddress?)
        try {
            s.reuseAddress = true
            s.soTimeout = 500
            s.bind(InetSocketAddress(settings.localPort)) // wildcard: all interfaces
        } catch (e: IOException) {
            s.close()
            throw IOException("UDP port ${settings.localPort}: ${e.message}")
        }
        socket = s
        staticPeer = peer
        hub.setUdpStatus(peers = if (peer != null) 1 else 0, error = null)
        jobs = listOf(
            scope.launch(Dispatchers.IO) { serialToUdp(s) },
            scope.launch(Dispatchers.IO) { udpToSerial(s) },
        )
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        socket?.close()
        socket = null
        learned.clear()
        hub.setUdpStatus(peers = 0, error = null)
    }

    private fun peers(): List<InetSocketAddress> {
        val now = clockMs()
        learned.entries.removeIf { now - it.value > peerTtlMs }
        val list = ArrayList<InetSocketAddress>(learned.keys)
        staticPeer?.let { if (it !in list) list += it }
        return list
    }

    private suspend fun serialToUdp(s: DatagramSocket) {
        val sub = try { hub.subscribe() } catch (e: IOException) { return }
        val splitter = MavFrameSplitter()
        val buf = ByteArray(4096)
        try {
            while (currentCoroutineContext().isActive) {
                val n = sub.read(buf, 500)
                val targets = peers()
                if (n > 0 && targets.isNotEmpty()) {
                    for (frame in splitter.feed(buf, n)) {
                        for (t in targets) {
                            try { s.send(DatagramPacket(frame, frame.size, t)) } catch (_: IOException) {}
                        }
                    }
                } else if (n > 0) {
                    splitter.feed(buf, n) // keep the framing state in step even with no peers
                }
                hub.setUdpStatus(targets.size, null)
            }
        } catch (_: IOException) {
            // hub stopped (subscription closed)
        } finally {
            sub.close()
        }
    }

    private suspend fun udpToSerial(s: DatagramSocket) {
        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        while (currentCoroutineContext().isActive) {
            pkt.length = buf.size
            try {
                s.receive(pkt)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                if (s.isClosed) return else continue
            }
            val from = InetSocketAddress(pkt.address, pkt.port)
            if (from != staticPeer) learned[from] = clockMs()
            if (settings.allowClientsToSend) {
                try { hub.write(pkt.data.copyOf(pkt.length)) } catch (_: IOException) { /* no source right now */ }
            }
        }
    }
}
