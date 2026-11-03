package com.mavheart.h12

import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.definitions.common.CommandAck
import com.divpundir.mavlink.definitions.common.CommandLong
import com.divpundir.mavlink.definitions.common.MavCmd
import com.divpundir.mavlink.definitions.common.MavResult

/** Our identity on the link: a ground control station. */
const val GCS_SYSID = 255
const val GCS_COMPID = 190

/** MAV_CMD_COMPONENT_ARM_DISARM param2 magic that skips safety checks (works in flight). */
const val FORCE_MAGIC = 21196f

const val CMD_ARM_DISARM = 400
private const val MSG_COMMAND_LONG = 76
private const val MSG_COMMAND_ACK = 77

const val CMD_DO_SET_MODE = 176
private const val MODE_FLAG_CUSTOM = 1f // MAV_MODE_FLAG_CUSTOM_MODE_ENABLED

/**
 * Builds a MAVLink v2 COMMAND_LONG frame. Payload serialization comes from mavlink-kotlin;
 * framing and CRC use [crcX25].
 */
private fun buildCommandLongFrame(
    cmd: MavCmd, targetSys: Int, targetComp: Int, param1: Float, param2: Float, seq: Int,
): ByteArray {
    val msg = CommandLong(
        targetSystem = targetSys.toUByte(),
        targetComponent = targetComp.toUByte(),
        command = MavEnumValue.of(cmd),
        confirmation = 0u,
        param1 = param1,
        param2 = param2,
    )
    val payload = msg.serializeV2()
    val frame = ByteArray(10 + payload.size + 2)
    frame[0] = 0xFD.toByte()
    frame[1] = payload.size.toByte()
    frame[2] = 0 // incompat flags: unsigned
    frame[3] = 0 // compat flags
    frame[4] = seq.toByte()
    frame[5] = GCS_SYSID.toByte()
    frame[6] = GCS_COMPID.toByte()
    frame[7] = (MSG_COMMAND_LONG and 0xFF).toByte()
    frame[8] = ((MSG_COMMAND_LONG shr 8) and 0xFF).toByte()
    frame[9] = ((MSG_COMMAND_LONG shr 16) and 0xFF).toByte()
    payload.copyInto(frame, 10)
    val crc = crcX25(frame, 1, 10 + payload.size, CommandLong.crcExtra.toInt() and 0xFF)
    frame[10 + payload.size] = (crc and 0xFF).toByte()
    frame[11 + payload.size] = ((crc shr 8) and 0xFF).toByte()
    return frame
}

/** COMMAND_LONG for MAV_CMD_COMPONENT_ARM_DISARM (400). [force] only applies to disarm. */
fun buildArmFrame(
    targetSys: Int, targetComp: Int, arm: Boolean, force: Boolean, seq: Int,
): ByteArray = buildCommandLongFrame(
    MavCmd.COMPONENT_ARM_DISARM, targetSys, targetComp,
    param1 = if (arm) 1f else 0f,
    param2 = if (force && !arm) FORCE_MAGIC else 0f,
    seq = seq,
)

/** COMMAND_LONG for MAV_CMD_DO_SET_MODE (176) with a custom (vehicle-specific) mode number. */
fun buildSetModeFrame(targetSys: Int, targetComp: Int, customMode: Int, seq: Int): ByteArray =
    buildCommandLongFrame(
        MavCmd.DO_SET_MODE, targetSys, targetComp,
        param1 = MODE_FLAG_CUSTOM, param2 = customMode.toFloat(), seq = seq,
    )

/** A decoded COMMAND_ACK: which command it answers and the result. */
data class CommandAckInfo(
    val commandId: Int, val accepted: Boolean, val inProgress: Boolean, val description: String,
)

/**
 * Finds CRC-valid COMMAND_ACK frames (MAVLink 1 and 2) in a byte stream. The caller matches
 * [CommandAckInfo.commandId] to what it sent. Payload decoding is done by mavlink-kotlin.
 * Not thread-safe.
 */
class CommandAckScanner {
    private var buf = ByteArray(8192)
    private var size = 0

    private fun b(i: Int) = buf[i].toInt() and 0xFF

    fun feed(chunk: ByteArray, len: Int = chunk.size): List<CommandAckInfo> {
        if (size + len > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + len))
        System.arraycopy(chunk, 0, buf, size, len)
        size += len

        val found = ArrayList<CommandAckInfo>()
        var i = 0
        while (i < size) {
            val magic = b(i)
            if (magic != 0xFD && magic != 0xFE) { i++; continue }
            val v2 = magic == 0xFD
            val headerSize = if (v2) 10 else 6
            if (size - i < headerSize) break
            val length = b(i + 1)
            val msgid = if (v2) b(i + 7) or (b(i + 8) shl 8) or (b(i + 9) shl 16) else b(i + 5)
            if (msgid != MSG_COMMAND_ACK || length < 1) { i++; continue } // v2 truncates trailing zeros: cmd 176 + ACCEPTED is 1 byte
            if (v2 && (b(i + 2) and 0x01.inv()) != 0) { i++; continue }

            val signed = v2 && (b(i + 2) and 0x01) != 0
            val frameSize = headerSize + length + 2 + if (signed) 13 else 0
            if (size - i < frameSize) break

            val crcOffset = i + headerSize + length
            val received = b(crcOffset) or (b(crcOffset + 1) shl 8)
            val computed = crcX25(buf, i + 1, crcOffset, CommandAck.crcExtra.toInt() and 0xFF)
            if (received != computed) { i++; continue }

            // Zero-pad: v2 truncates trailing zero bytes of the payload.
            val payload = ByteArray(maxOf(length, 10))
            System.arraycopy(buf, i + headerSize, payload, 0, length)
            val ack = CommandAck.deserialize(payload)
            val result = ack.result
            found += CommandAckInfo(
                commandId = ack.command.value.toInt(),
                accepted = result.entry == MavResult.ACCEPTED,
                inProgress = result.entry == MavResult.IN_PROGRESS,
                description = result.entry?.name ?: "result ${result.value}",
            )
            i += frameSize
        }
        System.arraycopy(buf, i, buf, 0, size - i)
        size -= i
        return found
    }
}
