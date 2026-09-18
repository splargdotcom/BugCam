package com.splarg.bugcam

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Published byte arrays are owned by this store and MUST NOT be mutated. */
data class JpegFrame(
    val sequence: Long,
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val monotonicNanos: Long,
    val unixMillis: Long,
    val encodeMillis: Double,
)

data class FrameMetrics(
    val frame: JpegFrame?, val frameCount: Long, val captureCount: Long,
    val skippedCount: Long, val fps: Double, val ageMillis: Long?,
)

/** Latest-value mailbox: independent of the number or speed of HTTP readers. */
class FrameStore(private val clock: () -> Long = System::nanoTime) : AutoCloseable {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var latest: JpegFrame? = null
    private var sequence = 0L
    private var closed = false
    private val times = ArrayDeque<Long>()
    private var firstNanos: Long? = null
    val captured = AtomicLong()
    val skipped = AtomicLong()

    fun publish(bytes: ByteArray, width: Int, height: Int, encodeMillis: Double) = lock.withLock {
        if (closed) return
        val now = clock()
        if (firstNanos == null) firstNanos = now
        latest = JpegFrame(++sequence, bytes, width, height, now,
            System.currentTimeMillis(), encodeMillis)
        times.addLast(now)
        prune(now)
        changed.signalAll()
    }

    fun snapshot(): JpegFrame? = lock.withLock { latest }

    fun isFresh(frame: JpegFrame, maxAgeMillis: Long = 5000): Boolean = lock.withLock {
        !closed && latest != null && clock() - frame.monotonicNanos <= maxAgeMillis * 1_000_000L
    }

    fun awaitAfter(sequence: Long, timeoutMillis: Long): JpegFrame? = lock.withLock {
        var remaining = timeoutMillis * 1_000_000L
        while (!closed && (latest?.sequence ?: 0L) <= sequence && remaining > 0) {
            remaining = changed.awaitNanos(remaining)
        }
        latest?.takeIf { !closed && it.sequence > sequence }
    }

    fun metrics(): FrameMetrics = lock.withLock {
        val now = clock()
        prune(now)
        val age = latest?.let { ((now - it.monotonicNanos) / 1_000_000).coerceAtLeast(0) }
        val seconds = ((now - (firstNanos ?: now)) / 1e9).coerceIn(1.0, 5.0)
        val fps = if (age == null || age > 3000) 0.0 else times.size / seconds
        FrameMetrics(latest, sequence, captured.get(), skipped.get(), fps, age)
    }

    fun invalidate() = lock.withLock {
        latest = null
        times.clear()
        firstNanos = null
        changed.signalAll()
    }

    private fun prune(now: Long) {
        while (times.isNotEmpty() && now - times.first > 5_000_000_000L) times.removeFirst()
    }

    override fun close() = lock.withLock {
        closed = true
        latest = null
        times.clear()
        changed.signalAll()
    }
}
