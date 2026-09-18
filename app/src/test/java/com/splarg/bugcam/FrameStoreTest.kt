package com.splarg.bugcam

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FrameStoreTest {
    @Test fun slowReaderSkipsStraightToNewestFrame() {
        val frames = FrameStore()
        repeat(100) { frames.publish(byteArrayOf(it.toByte()),2,2,1.0) }
        assertEquals(100L,frames.awaitAfter(1,10)?.sequence)
        assertEquals(100L,frames.metrics().frameCount)
        frames.close()
    }

    @Test fun ageAndFpsExposeStalledCamera() {
        var now = 1_000_000_000L
        val frames = FrameStore { now }
        frames.publish(byteArrayOf(1),2,2,1.0)
        now += 6_000_000_000L
        assertEquals(6000L,frames.metrics().ageMillis)
        assertEquals(0.0,frames.metrics().fps,0.0)
        assertFalse(frames.isFresh(frames.snapshot()!!))
        frames.invalidate()
        assertNull(frames.snapshot())
    }

    @Test fun stoppingWakesWaitingViewer() {
        val frames = FrameStore()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val waiting = worker.submit<JpegFrame?> { frames.awaitAfter(0,30_000) }
            frames.close()
            assertNull(waiting.get(1,TimeUnit.SECONDS))
        } finally { worker.shutdownNow() }
    }
}
