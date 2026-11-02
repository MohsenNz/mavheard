package com.mavheart.h12

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

private class FakeSource(private val chunks: ArrayDeque<ByteArray>) : ByteSource {
    var closed = false
    override val description = "fake"
    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        val c = chunks.removeFirstOrNull()
        if (c == null) { Thread.sleep(20); return 0 }
        c.copyInto(buf); return c.size
    }
    override fun close() { closed = true }
}

class HeartbeatMonitorTest {
    private val armed = hex("fd09000001010100000005000000020381040334f4")
    private val other = hex("fd09000001010100000005000000020c810403c89e")

    @Test fun reportsArmedCountsAndClosesSource() = runBlocking {
        val src = FakeSource(ArrayDeque(listOf(armed, other)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val m = HeartbeatMonitor(scope, sources = listOf({ src }))
        m.start()
        withTimeout(5000) { m.state.first { it.link == LinkState.ARMED && it.rawBytes > armed.size } }
        val s = m.state.value
        assertEquals(1, s.heartbeats) // non-ArduPilot ignored
        assertEquals(2, s.mavlinkVersion)
        assertEquals("fake", s.sourceDescription)
        m.stop()
        withTimeout(5000) { while (!src.closed) delay(10) }
        scope.cancel()
    }

    @Test fun fallsBackToNextSourceAndReportsAllErrors() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ok = FakeSource(ArrayDeque())
        val m = HeartbeatMonitor(scope, sources = listOf({ throw IOException("eacces") }, { ok }))
        m.start()
        withTimeout(5000) { m.state.first { it.sourceDescription == "fake" } }
        m.stop(); scope.cancel()

        val m2 = HeartbeatMonitor(CoroutineScope(Dispatchers.Default),
            sources = listOf({ throw IOException("eacces") }, { throw IOException("refused") }))
        m2.start()
        val s = withTimeout(5000) { m2.state.first { it.error != null } }
        assertTrue(s.error!!.contains("eacces") && s.error!!.contains("refused"))
    }
}
