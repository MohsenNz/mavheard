package com.mavheart.h12

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A stream of raw bytes from the H12 (serial port, Termux bridge, or a test fake).
 * Receive-only unless [canWrite] is true.
 */
interface ByteSource {
    /** Human-readable origin, shown in the UI. */
    val description: String

    /** Blocks up to [timeoutMs]. Returns bytes read, 0 on timeout. Throws [IOException] on failure/EOF. */
    fun read(buf: ByteArray, timeoutMs: Int): Int

    /** True only if this source can send bytes to the flight controller. */
    val canWrite: Boolean get() = false

    /** Writes all of [data]. Throws [IOException] if the source is read-only or the write fails. */
    fun write(data: ByteArray) { throw IOException("$description is read-only") }

    fun close()
}

class SerialPort private constructor(
    private var handle: Long,
    private val path: String,
    override val canWrite: Boolean,
) : ByteSource {
    override val description get() = "$path @ 921600 8N1 (direct${if (canWrite) "" else ", read-only"})"

    override fun write(data: ByteArray) {
        check(handle != 0L) { "closed" }
        if (!canWrite) throw IOException("$path was opened read-only")
        val n = nativeWrite(handle, data, data.size)
        if (n < 0) throw IOException("write $path: ${errnoText(-n)}")
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        check(handle != 0L) { "closed" }
        val n = nativeRead(handle, buf, timeoutMs)
        if (n < 0) throw IOException("read $path: ${errnoText(-n)}")
        return n
    }

    override fun close() {
        if (handle != 0L) nativeClose(handle)
        handle = 0L
    }

    companion object {
        init { System.loadLibrary("h12serial") }

        const val DEFAULT_PATH = "/dev/ttyHS1"
        private const val EPERM = 1
        private const val EACCES = 13
        private const val EROFS = 30

        fun open(path: String = DEFAULT_PATH): SerialPort {
            // Prefer read-write (needed for arm/disarm). If the node is not writable for us,
            // fall back to read-only so heartbeat monitoring still works.
            val rw = nativeOpen(path, true)
            if (rw > 0) return SerialPort(rw, path, canWrite = true)
            val denied = setOf(EACCES, EPERM, EROFS)
            if (-rw.toInt() !in denied) throw IOException("open $path: ${errnoText((-rw).toInt())}")
            val ro = nativeOpen(path, false)
            if (ro <= 0) throw IOException("open $path: ${errnoText((-ro).toInt())}")
            return SerialPort(ro, path, canWrite = false)
        }

        private fun errnoText(errno: Int) = "${nativeStrerror(errno)} (errno $errno)"

        @JvmStatic private external fun nativeOpen(path: String, writable: Boolean): Long
        @JvmStatic private external fun nativeRead(handle: Long, buf: ByteArray, timeoutMs: Int): Int
        @JvmStatic private external fun nativeWrite(handle: Long, buf: ByteArray, len: Int): Int
        @JvmStatic private external fun nativeClose(handle: Long)
        @JvmStatic private external fun nativeStrerror(errno: Int): String
    }
}

/**
 * Fallback: a localhost TCP port, normally fed by termux/h12_bridge.py (which is receive-only and
 * drops anything we write) or by a simulator such as ArduPilot SITL (which accepts commands).
 */
class TcpByteSource private constructor(
    private val socket: Socket,
    override val description: String,
) : ByteSource {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override val canWrite: Boolean get() = true

    override fun write(data: ByteArray) {
        try { output.write(data); output.flush() } catch (e: IOException) { throw IOException("write $description: ${e.message}") }
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        socket.soTimeout = timeoutMs
        return try {
            val n = input.read(buf)
            if (n < 0) throw IOException("bridge closed the connection")
            n
        } catch (_: java.net.SocketTimeoutException) {
            0
        }
    }

    override fun close() = try { socket.close() } catch (_: IOException) {}

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 5762

        fun open(host: String = DEFAULT_HOST, port: Int = DEFAULT_PORT): TcpByteSource {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(host, port), 1000)
            } catch (e: IOException) {
                try { s.close() } catch (_: IOException) {}
                throw IOException("connect $host:$port: ${e.message}")
            }
            return TcpByteSource(s, "$host:$port (Termux bridge)")
        }
    }
}
