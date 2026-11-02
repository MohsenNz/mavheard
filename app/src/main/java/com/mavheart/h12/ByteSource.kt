package com.mavheart.h12

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** A read-only stream of raw bytes from the H12 (serial port, Termux bridge, or a test fake). */
interface ByteSource {
    /** Human-readable origin, shown in the UI. */
    val description: String

    /** Blocks up to [timeoutMs]. Returns bytes read, 0 on timeout. Throws [IOException] on failure/EOF. */
    fun read(buf: ByteArray, timeoutMs: Int): Int

    fun close()
}

class SerialPort private constructor(
    private var handle: Long,
    private val path: String,
) : ByteSource {
    override val description get() = "$path @ 921600 8N1 (direct)"

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

        fun open(path: String = DEFAULT_PATH): SerialPort {
            val h = nativeOpen(path)
            if (h <= 0) throw IOException("open $path: ${errnoText((-h).toInt())}")
            return SerialPort(h, path)
        }

        private fun errnoText(errno: Int) = "${nativeStrerror(errno)} (errno $errno)"

        @JvmStatic private external fun nativeOpen(path: String): Long
        @JvmStatic private external fun nativeRead(handle: Long, buf: ByteArray, timeoutMs: Int): Int
        @JvmStatic private external fun nativeClose(handle: Long)
        @JvmStatic private external fun nativeStrerror(errno: Int): String
    }
}

/** Fallback: bytes forwarded by termux/h12_bridge.py to a localhost TCP port. */
class TcpByteSource private constructor(
    private val socket: Socket,
    override val description: String,
) : ByteSource {
    private val input = socket.getInputStream()

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
