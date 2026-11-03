package com.mavheart.h12

import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.definitions.common.CommandAck
import com.divpundir.mavlink.definitions.common.CommandLong
import com.divpundir.mavlink.definitions.common.MavCmd
import com.divpundir.mavlink.definitions.common.MavResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Builds a v2 COMMAND_ACK frame the way a flight controller would. */
internal fun ackFrame(command: MavCmd, result: MavResult): ByteArray {
    val payload = CommandAck(
        command = MavEnumValue.of(command),
        result = MavEnumValue.of(result),
    ).serializeV2()
    val f = ByteArray(10 + payload.size + 2)
    f[0] = 0xFD.toByte(); f[1] = payload.size.toByte(); f[5] = 1; f[6] = 1; f[7] = 77
    payload.copyInto(f, 10)
    val crc = crcX25(f, 1, 10 + payload.size, CommandAck.crcExtra.toInt() and 0xFF)
    f[10 + payload.size] = (crc and 0xFF).toByte(); f[11 + payload.size] = (crc shr 8).toByte()
    return f
}

class ArmCommandTest {
    private fun decode(frame: ByteArray): CommandLong {
        assertEquals(0xFD, frame[0].toInt() and 0xFF)
        val len = frame[1].toInt() and 0xFF
        assertEquals(frame.size, 10 + len + 2)
        assertEquals(76, frame[7].toInt() and 0xFF)
        val crc = crcX25(frame, 1, 10 + len, CommandLong.crcExtra.toInt() and 0xFF)
        assertEquals(crc and 0xFF, frame[10 + len].toInt() and 0xFF)
        assertEquals(crc shr 8, frame[11 + len].toInt() and 0xFF)
        val payload = ByteArray(33).also { frame.copyInto(it, 0, 10, 10 + len) }
        return CommandLong.deserialize(payload)
    }

    @Test fun armFrame() {
        val f = buildArmFrame(1, 1, arm = true, force = false, seq = 7)
        assertEquals(7, f[4].toInt())
        assertEquals(GCS_SYSID, f[5].toInt() and 0xFF)
        assertEquals(GCS_COMPID, f[6].toInt() and 0xFF)
        val m = decode(f)
        assertEquals(400u, m.command.value)
        assertEquals(1f, m.param1, 0f)
        assertEquals(0f, m.param2, 0f)
        assertEquals(1u.toUByte(), m.targetSystem)
    }

    @Test fun disarmFrame() {
        val m = decode(buildArmFrame(2, 3, arm = false, force = false, seq = 0))
        assertEquals(0f, m.param1, 0f)
        assertEquals(0f, m.param2, 0f)
        assertEquals(2u.toUByte(), m.targetSystem)
        assertEquals(3u.toUByte(), m.targetComponent)
    }

    @Test fun forceDisarmFrame() {
        val m = decode(buildArmFrame(1, 1, arm = false, force = true, seq = 0))
        assertEquals(0f, m.param1, 0f)
        assertEquals(21196f, m.param2, 0f)
    }

    @Test fun forceIsIgnoredWhenArming() {
        val m = decode(buildArmFrame(1, 1, arm = true, force = true, seq = 0))
        assertEquals(0f, m.param2, 0f)
    }

    @Test fun setModeFrame() {
        val f = buildSetModeFrame(1, 1, customMode = 16, seq = 3)
        assertEquals(3, f[4].toInt())
        val m = decode(f)
        assertEquals(176u, m.command.value)
        assertEquals(1f, m.param1, 0f)
        assertEquals(16f, m.param2, 0f)
        assertEquals(1u.toUByte(), m.targetSystem)
    }

    @Test fun ackScannerAcceptedAndRejectedAcrossChunks() {
        val sc = CommandAckScanner()
        val ok = ackFrame(MavCmd.COMPONENT_ARM_DISARM, MavResult.ACCEPTED)
        assertTrue(sc.feed(byteArrayOf(0x55) + ok.copyOfRange(0, 5)).isEmpty())
        val r = sc.feed(ok.copyOfRange(5, ok.size))
        assertEquals(1, r.size)
        assertTrue(r[0].accepted)

        val denied = sc.feed(ackFrame(MavCmd.COMPONENT_ARM_DISARM, MavResult.FAILED))
        assertFalse(denied.single().accepted)
        assertEquals("FAILED", denied.single().description)
    }

    @Test fun ackScannerReportsCommandIdAndIgnoresBadCrc() {
        val sc = CommandAckScanner()
        val other = sc.feed(ackFrame(MavCmd.REQUEST_MESSAGE, MavResult.ACCEPTED)).single()
        assertEquals(512, other.commandId)
        assertEquals(CMD_DO_SET_MODE, sc.feed(ackFrame(MavCmd.DO_SET_MODE, MavResult.ACCEPTED)).single().commandId)
        val bad = ackFrame(MavCmd.COMPONENT_ARM_DISARM, MavResult.ACCEPTED).also { it[it.size - 1] = 0 }
        assertTrue(sc.feed(bad).isEmpty())
    }
}

private class WritableSource(private val canWriteFlag: Boolean) : ByteSource {
    val written = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
    private val inbox = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    @Volatile var closed = false
    override val description = "fake-rw"
    override val canWrite get() = canWriteFlag
    fun push(b: ByteArray) { inbox.add(b) }
    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        val c = inbox.poll() ?: run { Thread.sleep(10); return 0 }
        c.copyInto(buf); return c.size
    }
    override fun write(data: ByteArray) {
        if (!canWriteFlag) throw java.io.IOException("read-only")
        written += data
    }
    override fun close() { closed = true }
}

class ArmFlowTest {
    // Valid ArduPilot DISARMED-ish heartbeat from sysid 1 / compid 1 (same vector as HeartbeatMonitorTest, armed).
    private val hb = "fd09000001010100000005000000020381040334f4".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun monitor(src: WritableSource, scope: CoroutineScope) = HeartbeatMonitor(scope, sources = listOf({ src }))

    @Test fun refusedOnReadOnlySourceAndWithoutHeartbeat() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ro = WritableSource(false)
        val m = monitor(ro, scope)
        m.start()
        ro.push(hb)
        withTimeout(5000) { m.state.first { it.heartbeats == 1 } }
        assertFalse(m.state.value.canCommand)
        assertFalse(m.requestArm(true))
        assertTrue(ro.written.isEmpty())
        m.stop(); scope.cancel()

        val rw = WritableSource(true)
        val m2 = monitor(rw, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        m2.start()
        withTimeout(5000) { m2.state.first { it.sourceDescription != null } }
        assertFalse(m2.requestArm(true)) // writable but no heartbeat yet
        assertTrue(rw.written.isEmpty())
        m2.stop()
    }

    @Test fun sendsFrameAndReportsAccepted() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val src = WritableSource(true)
        val m = monitor(src, scope)
        m.start()
        src.push(hb)
        withTimeout(5000) { m.state.first { it.canCommand } }
        assertTrue(m.requestArm(arm = false, force = true))
        assertFalse(m.requestArm(true)) // second tap while Sending is refused
        withTimeout(5000) { while (src.written.isEmpty()) delay(10) }
        assertEquals(1, src.written.size)
        assertEquals(76, src.written[0][7].toInt())

        src.push(ackFrame(MavCmd.COMPONENT_ARM_DISARM, MavResult.ACCEPTED))
        val s = withTimeout(5000) { m.state.first { it.commandResult is CommandResult.Accepted } }
        assertEquals(CommandResult.Accepted("Disarm command accepted"), s.commandResult)
        m.stop(); scope.cancel()
    }

    @Test fun reportsRejection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val src = WritableSource(true)
        val m = monitor(src, scope)
        m.start()
        src.push(hb)
        withTimeout(5000) { m.state.first { it.canCommand } }
        m.requestArm(true)
        withTimeout(5000) { while (src.written.isEmpty()) delay(10) }
        src.push(ackFrame(MavCmd.COMPONENT_ARM_DISARM, MavResult.FAILED))
        val s = withTimeout(5000) { m.state.first { it.commandResult is CommandResult.Rejected } }
        assertEquals("FAILED", (s.commandResult as CommandResult.Rejected).reason)
        m.stop(); scope.cancel()
    }

    /** A valid ArduPilot HEARTBEAT v2 from sysid 1 / compid 1. */
    private fun heartbeat(type: Int, customMode: Int, baseMode: Int = 0x51): ByteArray {
        val f = ByteArray(21)
        f[0] = 0xFD.toByte(); f[1] = 9; f[5] = 1; f[6] = 1
        f[10] = customMode.toByte(); f[14] = type.toByte(); f[15] = 3; f[16] = baseMode.toByte(); f[17] = 4; f[18] = 3
        val crc = crcX25(f, 1, 19, 50)
        f[19] = (crc and 0xFF).toByte(); f[20] = (crc shr 8).toByte()
        return f
    }

    @Test fun modeNameFollowsHeartbeatAndOnlyForCopter() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val src = WritableSource(true)
        val m = monitor(src, scope)
        m.start()
        src.push(heartbeat(type = 2, customMode = 16))
        assertEquals("PosHold", withTimeout(5000) { m.state.first { it.modeName != null } }.modeName)
        m.stop(); scope.cancel()

        val plane = WritableSource(true)
        val m2 = monitor(plane, CoroutineScope(SupervisorJob() + Dispatchers.Default))
        m2.start()
        plane.push(heartbeat(type = 1, customMode = 5))
        val s = withTimeout(5000) { m2.state.first { it.heartbeats == 1 } }
        assertNull(s.modeName)
        assertFalse(m2.requestMode(CopterMode.LOITER.id)) // not a Copter
        assertTrue(plane.written.isEmpty())
        m2.stop()
    }

    @Test fun modeChangeSendsFrameAndMatchesOnlyItsAck() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val src = WritableSource(true)
        val m = monitor(src, scope)
        m.start()
        src.push(heartbeat(type = 2, customMode = 0))
        withTimeout(5000) { m.state.first { it.canCommand } }
        assertFalse(m.requestMode(CopterMode.FLIP.id)) // not in the selectable subset
        assertFalse(m.requestMode(99))
        assertTrue(m.requestMode(CopterMode.LAND.id))
        withTimeout(5000) { while (src.written.isEmpty()) delay(10) }
        val f = src.written.single()
        assertEquals(76, f[7].toInt())
        assertEquals(176, (f[10 + 28].toInt() and 0xFF) or ((f[10 + 29].toInt() and 0xFF) shl 8))

        src.push(ackFrame(MavCmd.COMPONENT_ARM_DISARM, MavResult.ACCEPTED)) // wrong command: ignored
        delay(300)
        assertEquals(CommandResult.Sending, m.state.value.commandResult)
        src.push(ackFrame(MavCmd.DO_SET_MODE, MavResult.ACCEPTED))
        val s = withTimeout(5000) { m.state.first { it.commandResult is CommandResult.Accepted } }
        assertEquals(CommandResult.Accepted("Mode change accepted: Land"), s.commandResult)
        m.stop(); scope.cancel()
    }
}
