package com.mavheart.h12.proxy

import com.divpundir.mavlink.definitions.common.MavCmd
import com.divpundir.mavlink.definitions.common.MavResult
import com.mavheart.h12.ackFrame
import org.junit.Assert.*
import org.junit.Test

private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

// Same vectors as HeartbeatScannerTest (generated with crc_x25 from h12_mavlink_heartbeat.py).
private val V1_ARMED = hex("fe090101010005000000020381040354c7")
private val V2_ARMED = hex("fd09000001010100000005000000020381040334f4")
private val V2_SIGNED = hex("fd090100010101000000050000000203810403d30c000102030405060708090a0b0c")

class MavFrameSplitterTest {
    @Test fun v1AndV2AndSignedFramesComeOutWhole() {
        val sp = MavFrameSplitter()
        val out = sp.feed(V1_ARMED + V2_ARMED + V2_SIGNED)
        assertEquals(3, out.size)
        assertArrayEquals(V1_ARMED, out[0])
        assertArrayEquals(V2_ARMED, out[1])
        assertArrayEquals(V2_SIGNED, out[2]) // signature kept
    }

    @Test fun nonHeartbeatMessagesPassToo() { // unlike HeartbeatScanner, this is a general splitter
        val ack = ackFrame(MavCmd.COMPONENT_ARM_DISARM, MavResult.ACCEPTED)
        assertArrayEquals(ack, MavFrameSplitter().feed(ack).single())
    }

    @Test fun junkAndPartialFramesAreHandled() {
        val sp = MavFrameSplitter()
        // a stray FD magic and a half frame: nothing complete yet
        assertTrue(sp.feed(byteArrayOf(0x00, 0x55) + V2_ARMED.copyOfRange(0, 8)).isEmpty())
        val out = sp.feed(V2_ARMED.copyOfRange(8, V2_ARMED.size) + byteArrayOf(0x13) + V1_ARMED)
        assertEquals(2, out.size)
        assertArrayEquals(V2_ARMED, out[0])
        assertArrayEquals(V1_ARMED, out[1])
    }

    @Test fun byteAtATime() {
        val sp = MavFrameSplitter()
        val all = ArrayList<ByteArray>()
        for (b in V2_ARMED + V1_ARMED) all += sp.feed(byteArrayOf(b))
        assertEquals(2, all.size)
        assertArrayEquals(V2_ARMED, all[0])
        assertArrayEquals(V1_ARMED, all[1])
    }

    @Test fun badCrcIsDropped() {
        val bad = V2_ARMED.copyOf().also { it[it.size - 1] = 0 }
        assertTrue(MavFrameSplitter().feed(bad).isEmpty())
        // ...and a good frame right after it is still found
        assertEquals(1, MavFrameSplitter().feed(bad + V2_ARMED).size)
    }

    @Test fun unknownMessageNeedsAFollowingFrameStart() {
        val sp = MavFrameSplitter(crcExtraOf = { null }) // dialect knows nothing: structural check only
        assertTrue(sp.feed(V2_ARMED).isEmpty()) // waits for the next byte
        val out = sp.feed(V1_ARMED)
        assertEquals(1, out.size) // first frame released once a frame start follows
        assertArrayEquals(V2_ARMED, out[0])
    }

    @Test fun dialectKnowsHeartbeat() {
        assertEquals(50, dialectCrcExtra(0))
        assertNotNull(dialectCrcExtra(76))
    }
}
