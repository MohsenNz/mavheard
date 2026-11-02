package com.mavheart.h12

data class Heartbeat(
    val version: Int,
    val sysid: Int,
    val compid: Int,
    val autopilot: Int,
    val armed: Boolean,
    val baseMode: Int,
    val customMode: Long,
    val systemStatus: Int,
)

const val ARDUPILOT_AUTOPILOT_ID = 3
private const val HEARTBEAT_CRC_EXTRA = 50
private const val ARMED_FLAG = 0x80

/** Port of crc_x25 (MCRF4XX) from h12_mavlink_heartbeat.py. */
fun crcX25(data: ByteArray, from: Int, to: Int, extra: Int = -1): Int {
    var crc = 0xFFFF
    fun step(b: Int) {
        var tmp = b xor (crc and 0xFF)
        tmp = tmp xor ((tmp shl 4) and 0xFF)
        crc = ((crc shr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp shr 4)) and 0xFFFF
    }
    for (i in from until to) step(data[i].toInt() and 0xFF)
    if (extra >= 0) step(extra)
    return crc
}

/**
 * Port of HeartbeatScanner: finds CRC-valid standard HEARTBEAT frames (MAVLink 1 and 2) in a byte
 * stream that may contain junk or partial frames. Not thread-safe.
 */
class HeartbeatScanner {
    private var buf = ByteArray(8192)
    private var size = 0

    private fun b(i: Int) = buf[i].toInt() and 0xFF

    fun feed(chunk: ByteArray, len: Int = chunk.size): List<Heartbeat> {
        if (size + len > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + len))
        System.arraycopy(chunk, 0, buf, size, len)
        size += len

        val found = ArrayList<Heartbeat>()
        var i = 0
        while (i < size) {
            val magic = b(i)
            if (magic != 0xFD && magic != 0xFE) { i++; continue }
            val v2 = magic == 0xFD
            val headerSize = if (v2) 10 else 6
            if (size - i < headerSize) break // partial header

            val length = b(i + 1)
            val msgid = if (v2) b(i + 7) or (b(i + 8) shl 8) or (b(i + 9) shl 16) else b(i + 5)
            if (msgid != 0 || length != 9) { i++; continue }
            if (v2 && (b(i + 2) and 0x01.inv()) != 0) { i++; continue } // unknown incompat flags

            val signed = v2 && (b(i + 2) and 0x01) != 0
            val frameSize = headerSize + length + 2 + if (signed) 13 else 0
            if (size - i < frameSize) break // partial frame

            val crcOffset = i + headerSize + length
            val received = b(crcOffset) or (b(crcOffset + 1) shl 8)
            val computed = crcX25(buf, i + 1, crcOffset, HEARTBEAT_CRC_EXTRA)
            if (received != computed) { i++; continue }

            val p = i + headerSize
            val baseMode = b(p + 6)
            found += Heartbeat(
                version = if (v2) 2 else 1,
                sysid = if (v2) b(i + 5) else b(i + 3),
                compid = if (v2) b(i + 6) else b(i + 4),
                autopilot = b(p + 5),
                armed = baseMode and ARMED_FLAG != 0,
                baseMode = baseMode,
                customMode = b(p).toLong() or (b(p + 1).toLong() shl 8) or
                    (b(p + 2).toLong() shl 16) or (b(p + 3).toLong() shl 24),
                systemStatus = b(p + 7),
            )
            i += frameSize
        }
        System.arraycopy(buf, i, buf, 0, size - i)
        size -= i
        return found
    }
}
