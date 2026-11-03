package com.mavheart.h12.proxy

import com.divpundir.mavlink.definitions.ardupilotmega.ArdupilotmegaDialect
import com.mavheart.h12.crcX25

/** CRC extra for a message id from mavlink-kotlin's ardupilotmega dialect, or null if unknown. */
fun dialectCrcExtra(msgId: Int): Int? =
    ArdupilotmegaDialect.resolveCompanionOrNull(msgId.toUInt())?.crcExtra?.toInt()?.and(0xFF)

/**
 * Cuts a byte stream that may hold junk or partial frames into whole MAVLink v1/v2 frames (signed
 * v2 included), so each UDP datagram carries exactly one frame. Frames of known messages are
 * accepted only if their CRC matches; frames of messages unknown to the dialect are accepted only
 * if they are followed by another frame magic byte (structural check). Not thread-safe.
 */
class MavFrameSplitter(private val crcExtraOf: (Int) -> Int? = ::dialectCrcExtra) {
    private var buf = ByteArray(8192)
    private var size = 0

    private fun b(i: Int) = buf[i].toInt() and 0xFF

    fun feed(chunk: ByteArray, len: Int = chunk.size): List<ByteArray> {
        if (size + len > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + len))
        System.arraycopy(chunk, 0, buf, size, len)
        size += len

        val frames = ArrayList<ByteArray>()
        var i = 0
        while (i < size) {
            val magic = b(i)
            if (magic != 0xFD && magic != 0xFE) { i++; continue }
            val v2 = magic == 0xFD
            val headerSize = if (v2) 10 else 6
            if (size - i < headerSize) break // partial header

            val length = b(i + 1)
            val msgid = if (v2) b(i + 7) or (b(i + 8) shl 8) or (b(i + 9) shl 16) else b(i + 5)
            if (v2 && (b(i + 2) and 0x01.inv()) != 0) { i++; continue } // unknown incompat flags

            val signed = v2 && (b(i + 2) and 0x01) != 0
            val frameSize = headerSize + length + 2 + if (signed) 13 else 0
            if (size - i < frameSize) break // partial frame

            val crcOffset = i + headerSize + length
            val extra = crcExtraOf(msgid)
            if (extra != null) {
                val received = b(crcOffset) or (b(crcOffset + 1) shl 8)
                if (received != crcX25(buf, i + 1, crcOffset, extra)) { i++; continue }
            } else {
                // Unknown message: cannot check the CRC, so require a following frame start.
                if (i + frameSize >= size) break // wait for the next byte
                val next = b(i + frameSize)
                if (next != 0xFD && next != 0xFE) { i++; continue }
            }
            frames += buf.copyOfRange(i, i + frameSize)
            i += frameSize
        }
        System.arraycopy(buf, i, buf, 0, size - i)
        size -= i
        return frames
    }
}
