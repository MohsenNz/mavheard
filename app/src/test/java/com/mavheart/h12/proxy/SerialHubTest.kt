package com.mavheart.h12.proxy

import com.mavheart.h12.HeartbeatMonitor
import com.mavheart.h12.LinkState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
private val V2_ARMED = hex("fd09000001010100000005000000020381040334f4")

class SerialHubTest {
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun SerialHub.awaitConnected() = withTimeout(5000) { state.first { it.connected } }

    @Test fun fansOutToEverySubscriber() = runBlocking {
        val src = FakeRwSource()
        val hub = SerialHub(listOf({ src }), reconnectDelayMs = 20)
        val sc = scope(); hub.start(sc); hub.awaitConnected()
        val a = hub.subscribe(); val b = hub.subscribe()
        src.push(byteArrayOf(1, 2, 3))
        val buf = ByteArray(16)
        assertEquals(3, withTimeout(2000) { var n = 0; while (n == 0) n = a.read(buf, 100); n })
        assertEquals(3, withTimeout(2000) { var n = 0; while (n == 0) n = b.read(buf, 100); n })
        assertEquals(3L, hub.state.value.rxBytes)
        hub.stop(); sc.cancel()
    }

    @Test fun writesGoToTheSourceAndAreCounted() = runBlocking {
        val src = FakeRwSource()
        val hub = SerialHub(listOf({ src }), reconnectDelayMs = 20)
        val sc = scope(); hub.start(sc); hub.awaitConnected()
        val sub = hub.subscribe()
        assertTrue(sub.canWrite)
        sub.write(byteArrayOf(9, 8))
        assertArrayEquals(byteArrayOf(9, 8), src.written.single())
        assertEquals(2L, hub.state.value.txBytes)
        hub.stop(); sc.cancel()
    }

    @Test fun readOnlySourceRefusesWrites() = runBlocking {
        val src = FakeRwSource(writable = false)
        val hub = SerialHub(listOf({ src }), reconnectDelayMs = 20)
        val sc = scope(); hub.start(sc); hub.awaitConnected()
        val sub = hub.subscribe()
        assertFalse(sub.canWrite)
        assertThrows(IOException::class.java) { sub.write(byteArrayOf(1)) }
        hub.stop(); sc.cancel()
    }

    @Test fun slowSubscriberDropsOldestInsteadOfBlocking() = runBlocking {
        val src = FakeRwSource()
        val hub = SerialHub(listOf({ src }), reconnectDelayMs = 20)
        val sc = scope(); hub.start(sc); hub.awaitConnected()
        val sub = hub.subscribe() // never read while the data flows
        repeat(400) { src.push(byteArrayOf((it and 0x7F).toByte())) }
        withTimeout(5000) { hub.state.first { it.rxBytes >= 400 } } // the hub kept reading
        var chunks = 0
        val buf = ByteArray(16)
        while (sub.read(buf, 10) > 0) chunks++
        assertTrue("kept at most 256 chunks, got $chunks", chunks in 1..256)
        hub.stop(); sc.cancel()
    }

    @Test fun reconnectsAfterTheSourceDrops() = runBlocking {
        val first = FakeRwSource(description = "first"); val second = FakeRwSource(description = "second")
        var opened = 0
        val hub = SerialHub(listOf({ if (opened++ == 0) first else second }), reconnectDelayMs = 20)
        val sc = scope(); hub.start(sc); hub.awaitConnected()
        val sub = hub.subscribe()
        first.failReads = true
        withTimeout(5000) { hub.state.first { it.connected && it.sourceDescription == "second" } }
        assertTrue(first.closed)
        second.push(byteArrayOf(7))
        val buf = ByteArray(4)
        assertEquals(1, withTimeout(2000) { var n = 0; while (n == 0) n = sub.read(buf, 100); n }) // same subscription still works
        hub.stop(); sc.cancel()
    }

    @Test fun keepsRetryingWhenNoSourceOpens() = runBlocking {
        var attempts = 0
        val hub = SerialHub(listOf({ attempts++; throw IOException("nope") }), reconnectDelayMs = 20)
        val sc = scope(); hub.start(sc)
        withTimeout(5000) { hub.state.first { it.error == "nope" } }
        delay(150)
        assertTrue("retried, attempts=$attempts", attempts >= 3)
        assertFalse(hub.state.value.connected)
        hub.stop(); sc.cancel()
    }

    @Test fun subscribeNeedsARunningHub() {
        assertThrows(IOException::class.java) { SerialHub(listOf({ FakeRwSource() })).subscribe() }
    }

    @Test fun stopClosesSubscriptionsAndTheSource() = runBlocking {
        val src = FakeRwSource()
        val hub = SerialHub(listOf({ src }), reconnectDelayMs = 20)
        val sc = scope(); hub.start(sc); hub.awaitConnected()
        val sub = hub.subscribe()
        hub.stop()
        assertThrows(IOException::class.java) { sub.read(ByteArray(4), 10) }
        withTimeout(5000) { while (!src.closed) delay(10) }
        assertFalse(hub.state.value.running)
        sc.cancel()
    }

    @Test fun heartbeatMonitorWorksThroughTheHub() = runBlocking {
        val src = FakeRwSource()
        val hub = SerialHub(listOf({ src }), reconnectDelayMs = 20)
        val sc = scope(); hub.start(sc); hub.awaitConnected()
        val m = HeartbeatMonitor(sc, sources = listOf({ hub.subscribe() }))
        m.start()
        src.push(V2_ARMED)
        val s = withTimeout(5000) { m.state.first { it.link == LinkState.ARMED } }
        assertTrue(s.canWrite)
        assertTrue(s.sourceDescription!!.endsWith("via hub"))
        m.stop(); hub.stop(); sc.cancel()
    }

    @Test fun monitorPicksUpSourceThatConnectsAfterItSubscribed() = runBlocking {
        val src = FakeRwSource(description = "late")
        var attempts = 0
        val hub = SerialHub(listOf({ if (attempts++ < 2) throw IOException("not yet") else src }), reconnectDelayMs = 30)
        val sc = scope(); hub.start(sc)
        val m = HeartbeatMonitor(sc, sources = listOf({ hub.subscribe() }))
        m.start() // subscribes while the hub has no source yet
        val s = withTimeout(5000) { m.state.first { it.sourceDescription == "late via hub" && it.canWrite } }
        assertEquals("late via hub", s.sourceDescription)
        m.stop(); hub.stop(); sc.cancel()
    }
}
