package com.mavheart.h12

import org.junit.Assert.*
import org.junit.Test

private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

// Vectors generated with crc_x25 from h12_mavlink_heartbeat.py (independent of the Kotlin CRC).
private val V1_DISARMED = hex("fe09010101000500000002030104033aea")
private val V1_ARMED = hex("fe090101010005000000020381040354c7")
private val V2_DISARMED = hex("fd0900000101010000000500000002030104035ad9")
private val V2_ARMED = hex("fd09000001010100000005000000020381040334f4")
private val V2_SIGNED_ARMED = hex("fd090100010101000000050000000203810403d30c000102030405060708090a0b0c")
private val V2_NON_ARDUPILOT = hex("fd09000001010100000005000000020c810403c89e")

class HeartbeatScannerTest {
    @Test fun mavlink1Disarmed() {
        val hb = HeartbeatScanner().feed(V1_DISARMED).single()
        assertEquals(1, hb.version); assertFalse(hb.armed)
        assertEquals(1, hb.sysid); assertEquals(1, hb.compid)
        assertEquals(ARDUPILOT_AUTOPILOT_ID, hb.autopilot)
        assertEquals(5L, hb.customMode); assertEquals(0x01, hb.baseMode); assertEquals(4, hb.systemStatus)
    }

    @Test fun vehicleTypeIsParsed() { // all vectors carry MAV_TYPE 2 (quadrotor)
        assertEquals(2, HeartbeatScanner().feed(V1_DISARMED).single().type)
        assertEquals(2, HeartbeatScanner().feed(V2_ARMED).single().type)
        assertTrue(isCopterType(HeartbeatScanner().feed(V2_ARMED).single().type))
    }

    @Test fun mavlink1Armed() = assertTrue(HeartbeatScanner().feed(V1_ARMED).single().armed)

    @Test fun mavlink2DisarmedAndArmed() {
        val s = HeartbeatScanner()
        val d = s.feed(V2_DISARMED).single()
        assertEquals(2, d.version); assertFalse(d.armed)
        val a = s.feed(V2_ARMED).single()
        assertTrue(a.armed); assertEquals(0x81, a.baseMode)
    }

    @Test fun signedFrameConsumesSignature() {
        val s = HeartbeatScanner()
        val hbs = s.feed(V2_SIGNED_ARMED + V2_DISARMED)
        assertEquals(listOf(true, false), hbs.map { it.armed })
    }

    @Test fun frameSplitAcrossChunks() {
        val s = HeartbeatScanner()
        for (cut in 1 until V2_ARMED.size) {
            assertTrue(s.feed(V2_ARMED.copyOfRange(0, cut)).isEmpty())
            assertTrue(s.feed(V2_ARMED.copyOfRange(cut, V2_ARMED.size)).single().armed)
        }
    }

    @Test fun byteAtATime() {
        val s = HeartbeatScanner()
        val out = (V1_ARMED + V2_DISARMED).flatMap { s.feed(byteArrayOf(it)) }
        assertEquals(listOf(true, false), out.map { it.armed })
    }

    @Test fun junkBeforeAndBetweenFrames() {
        val junk = hex("00ff01fdfe09fd0900") // includes false magics
        val hbs = HeartbeatScanner().feed(junk + V2_ARMED + junk + V1_DISARMED)
        assertEquals(listOf(true, false), hbs.map { it.armed })
    }

    @Test fun badCrcRejected() {
        val bad = V2_ARMED.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertTrue(HeartbeatScanner().feed(bad).isEmpty())
    }

    @Test fun unknownIncompatFlagsRejected() {
        val f = V2_ARMED.copyOf().also { it[2] = 0x02 }
        assertTrue(HeartbeatScanner().feed(f).isEmpty())
    }

    @Test fun nonArduPilotIsParsedWithItsAutopilotId() {
        // The scanner reports it; HeartbeatMonitor filters on autopilot == 3.
        assertEquals(12, HeartbeatScanner().feed(V2_NON_ARDUPILOT).single().autopilot)
    }

    @Test fun nonHeartbeatMessageIgnored() {
        // MAVLink1 SYS_STATUS-like frame (msgid 1, len 9): never reported.
        val f = V1_ARMED.copyOf().also { it[5] = 1 }
        assertTrue(HeartbeatScanner().feed(f).isEmpty())
    }

    @Test fun crcX25KnownValue() {
        // CRC-16/MCRF4XX check value for "123456789" is 0x6F91.
        val d = "123456789".toByteArray()
        assertEquals(0x6F91, crcX25(d, 0, d.size))
    }

    @Test fun linkStateFreshnessRule() {
        assertEquals(LinkState.UNKNOWN, linkState(null, null, 1000))
        assertEquals(LinkState.ARMED, linkState(1000, true, 3999))
        assertEquals(LinkState.DISARMED, linkState(1000, false, 3999))
        assertEquals(LinkState.UNKNOWN, linkState(1000, true, 4000))
    }
}
