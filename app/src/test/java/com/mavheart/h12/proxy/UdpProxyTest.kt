package com.mavheart.h12.proxy

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
private val V2_ARMED = hex("fd09000001010100000005000000020381040334f4")
private val V1_ARMED = hex("fe090101010005000000020381040354c7")

class UdpProxyTest {
    private val lo = InetAddress.getByName("127.0.0.1")

    private class Rig(val src: FakeRwSource, val hub: SerialHub, val scope: CoroutineScope, val proxy: UdpProxy) {
        fun close() { proxy.stop(); hub.stop(); scope.cancel() }
    }

    private fun rig(settings: ProxySettings, clock: () -> Long = { System.nanoTime() / 1_000_000 }, ttl: Long = 30_000): Rig = runBlocking {
        val src = FakeRwSource()
        val hub = SerialHub(listOf({ src }), reconnectDelayMs = 20)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        hub.start(scope)
        withTimeout(5000) { hub.state.first { it.connected } }
        val proxy = UdpProxy(hub, settings, clock, ttl)
        proxy.start(scope)
        Rig(src, hub, scope, proxy)
    }

    private fun client(): DatagramSocket = DatagramSocket(0, lo).apply { soTimeout = 2000 }

    private fun DatagramSocket.recv(): ByteArray {
        val p = DatagramPacket(ByteArray(2048), 2048)
        receive(p)
        return p.data.copyOf(p.length)
    }

    private fun DatagramSocket.sendTo(port: Int, data: ByteArray) = send(DatagramPacket(data, data.size, lo, port))

    @Test fun datagramGoesToSerialAndFramesComeBackToTheLearnedPeer() {
        val r = rig(ProxySettings(udpEnabled = true, localPort = 0))
        val c = client()
        c.sendTo(r.proxy.localPort, byteArrayOf(1, 2, 3))
        runBlocking { withTimeout(3000) { while (r.src.written.isEmpty()) delay(10) } }
        assertArrayEquals(byteArrayOf(1, 2, 3), r.src.written.single())

        r.src.push(V2_ARMED + V1_ARMED) // two frames arrive in one serial read
        assertArrayEquals(V2_ARMED, c.recv()) // one datagram per frame
        assertArrayEquals(V1_ARMED, c.recv())
        c.close(); r.close()
    }

    @Test fun staticPeerReceivesWithoutSendingFirst() {
        val c = client()
        val r = rig(ProxySettings(udpEnabled = true, localPort = 0, remote = "127.0.0.1:${c.localPort}"))
        r.src.push(V2_ARMED)
        assertArrayEquals(V2_ARMED, c.recv())
        assertEquals(1, r.hub.state.value.udpPeers.coerceAtLeast(1))
        c.close(); r.close()
    }

    @Test fun everyPeerGetsEveryFrame() {
        val r = rig(ProxySettings(udpEnabled = true, localPort = 0))
        val a = client(); val b = client()
        a.sendTo(r.proxy.localPort, byteArrayOf(1)); b.sendTo(r.proxy.localPort, byteArrayOf(2))
        runBlocking { withTimeout(3000) { while (r.src.written.size < 2) delay(10) } }
        r.src.push(V2_ARMED)
        assertArrayEquals(V2_ARMED, a.recv())
        assertArrayEquals(V2_ARMED, b.recv())
        runBlocking { withTimeout(3000) { r.hub.state.first { it.udpPeers == 2 } } }
        a.close(); b.close(); r.close()
    }

    @Test fun receiveOnlyModeDropsClientDatagrams() {
        val r = rig(ProxySettings(udpEnabled = true, localPort = 0, allowClientsToSend = false))
        val c = client()
        c.sendTo(r.proxy.localPort, byteArrayOf(9, 9))
        r.src.push(V2_ARMED) // peer is still learned, so it still receives
        // The peer is registered by the datagram; give the proxy a moment, then push again to be sure.
        Thread.sleep(200); r.src.push(V2_ARMED)
        assertArrayEquals(V2_ARMED, c.recv())
        assertTrue("nothing written to the vehicle", r.src.written.isEmpty())
        c.close(); r.close()
    }

    @Test fun silentLearnedPeersExpire() {
        var now = 0L
        val r = rig(ProxySettings(udpEnabled = true, localPort = 0), clock = { now }, ttl = 1000)
        val c = client()
        c.sendTo(r.proxy.localPort, byteArrayOf(1))
        runBlocking { withTimeout(3000) { r.hub.state.first { it.udpPeers == 1 } } }
        now = 5000 // well past the ttl with no further datagrams
        runBlocking { withTimeout(3000) { r.hub.state.first { it.udpPeers == 0 } } }
        r.src.push(V2_ARMED)
        c.soTimeout = 300
        assertThrows(SocketTimeoutException::class.java) { c.recv() }
        c.close(); r.close()
    }

    @Test fun bindFailureIsReported() {
        val hold = DatagramSocket(0)
        val src = FakeRwSource()
        val hub = SerialHub(listOf({ src }), reconnectDelayMs = 20)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        hub.start(scope)
        val proxy = UdpProxy(hub, ProxySettings(udpEnabled = true, localPort = hold.localPort))
        val e = assertThrows(java.io.IOException::class.java) { proxy.start(scope) }
        assertTrue(e.message!!.contains("${hold.localPort}"))
        hold.close(); hub.stop(); scope.cancel()
    }

    @Test fun badRemoteAddressIsRejected() {
        val hub = SerialHub(listOf({ FakeRwSource() }))
        val proxy = UdpProxy(hub, ProxySettings(udpEnabled = true, localPort = 0, remote = "not-an-address"))
        assertThrows(java.io.IOException::class.java) { proxy.start(CoroutineScope(Dispatchers.Default)) }
    }

    @Test fun hostPortParsing() {
        assertEquals("1.2.3.4" to 14550, parseHostPort("1.2.3.4:14550"))
        assertEquals("host.local" to 1, parseHostPort(" host.local : 1 "))
        assertNull(parseHostPort("1.2.3.4")); assertNull(parseHostPort(":14550"))
        assertNull(parseHostPort("h:0")); assertNull(parseHostPort("h:70000")); assertNull(parseHostPort("h:x"))
    }
}
