package com.mavheart.h12.proxy

import com.mavheart.h12.ByteSource
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/** A scriptable serial source: [push] makes bytes readable, [written] collects what was written. */
class FakeRwSource(private val writable: Boolean = true, override val description: String = "fake") : ByteSource {
    private val inbox = ConcurrentLinkedQueue<ByteArray>()
    val written = CopyOnWriteArrayList<ByteArray>()
    @Volatile var closed = false
    @Volatile var failReads = false
    override val canWrite get() = writable

    fun push(b: ByteArray) { inbox.add(b) }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        if (failReads) throw IOException("link lost")
        val c = inbox.poll() ?: run { Thread.sleep(5); return 0 }
        c.copyInto(buf); return c.size
    }

    override fun write(data: ByteArray) {
        if (!writable) throw IOException("read-only")
        written += data
    }

    override fun close() { closed = true }
}
