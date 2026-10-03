package com.splarg.bugcam

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class HttpServerTest {
    private lateinit var frames: FrameStore
    private lateinit var server: BugCamHttpServer
    private val opened = mutableListOf<Socket>()
    private val snapshotCalls = AtomicInteger()
    @Volatile private var snapshotHandler: () -> SnapshotResult = { SnapshotResult(503, message = "Camera off") }
    @Volatile private var lastManual: ManualExposure? = null
    @Volatile private var lastFocus: Float? = null

    @Before fun setup() {
        frames = FrameStore()
        server = BugCamHttpServer(InetAddress.getLoopbackAddress(),0,frames,{"{\"camera_active\":true}"},
            "BugCam".toByteArray(),writeTimeoutMillis=500,headerTimeoutMillis=500,
            snapshot = { lastManual = it.exposure; lastFocus = it.focusDiopters
                snapshotCalls.incrementAndGet(); snapshotHandler() })
        server.start()
    }
    @After fun cleanup() { opened.forEach { it.close() }; server.close(); frames.close() }
    private fun socket() = Socket(InetAddress.getLoopbackAddress(),server.boundPort).apply {
        soTimeout=4000; opened.add(this)
    }
    private fun request(raw: String): ByteArray = socket().use {
        it.getOutputStream().write(raw.toByteArray(StandardCharsets.ISO_8859_1))
        it.getInputStream().readBytes()
    }
    private fun get(path: String) = request("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n")
    private fun publish(bytes: ByteArray = byteArrayOf(-1,-40,1,2,-1,-39)) = frames.publish(bytes,1920,1080,2.0)
    private fun still(bytes: ByteArray) = SnapshotResult(200,
        JpegFrame(snapshotCalls.get().toLong(),bytes,4000,3000,System.nanoTime(),System.currentTimeMillis(),0.0))

    @Test fun routesSnapshotAndHeadUseTheSameBytes() {
        val jpeg = byteArrayOf(-1,-40,1,2,-1,-39)
        publish(byteArrayOf(5,6,7)) // Preview must never satisfy a still request.
        snapshotHandler = { still(jpeg) }
        assertTrue(String(get("/health")).contains("\"camera_active\":true"))
        val response = get("/snapshot.jpg")
        assertArrayEquals(jpeg,response.takeLast(jpeg.size).toByteArray())
        assertTrue(String(response,StandardCharsets.ISO_8859_1).contains("Content-Length: 6"))
        assertTrue(String(response,StandardCharsets.ISO_8859_1).contains("X-Image-Width: 4000"))
        val nextJpeg = byteArrayOf(-1,-40,9,8,7,-1,-39)
        snapshotHandler = { still(nextJpeg) }
        assertArrayEquals(nextJpeg,get("/snapshot.jpg").takeLast(nextJpeg.size).toByteArray())
        val head = String(request("HEAD /snapshot.jpg HTTP/1.1\r\nHost: localhost\r\n\r\n"))
        assertTrue(head.endsWith("\r\n\r\n"))
        assertTrue(head.contains("Content-Length: 7"))
        assertEquals(3,snapshotCalls.get())
        assertEquals(1L,frames.metrics().frameCount)
    }

    @Test fun manualExposureQueryReachesStillCallbackAndAutoStaysDefault() {
        snapshotHandler = { still(byteArrayOf(-1,-40,4,-1,-39)) }
        assertTrue(String(get("/snapshot.jpg")).startsWith("HTTP/1.1 200"))
        assertNull(lastManual)
        assertTrue(String(get("/snapshot.jpg?t=12345")).startsWith("HTTP/1.1 200")) // Cache buster: still auto.
        assertNull(lastManual)
        assertTrue(String(get("/snapshot.jpg?exposure_ns=33333333&iso=100")).startsWith("HTTP/1.1 200"))
        assertEquals(ManualExposure(33_333_333L, 100), lastManual)
        assertEquals(3, snapshotCalls.get())
    }

    @Test fun malformedManualExposureIsRejectedBeforeCapture() {
        snapshotHandler = { still(byteArrayOf(-1,-40,-1,-39)) }
        for (query in listOf("iso=100", "exposure_ns=1000000", "exposure_ns=abc&iso=100",
                "exposure_ns=1000000&iso=0", "exposure_ns=-5&iso=100", "exposure_ns=&iso=100",
                "exposure=33ms&iso=100", "exposure_ms=33&iso=100", "ISO=100&exposure_ns=1000000")) {
            val response = String(get("/snapshot.jpg?$query"))
            assertTrue(query, response.startsWith("HTTP/1.1 400"))
            assertFalse(query, response.contains("image/jpeg"))
        }
        assertEquals(0, snapshotCalls.get())
    }

    @Test fun focusDioptersQueryReachesStillCallbackAndAutofocusStaysDefault() {
        snapshotHandler = { still(byteArrayOf(-1,-40,6,-1,-39)) }
        assertTrue(String(get("/snapshot.jpg?t=1")).startsWith("HTTP/1.1 200"))
        assertNull(lastFocus) // No focus parameter: unchanged autofocus path.
        assertTrue(String(get("/snapshot.jpg?focus_diopters=20.5")).startsWith("HTTP/1.1 200"))
        assertEquals(20.5f, lastFocus)
        assertNull(lastManual)
        assertTrue(String(get("/snapshot.jpg?focus_diopters=0&exposure_ns=33333333&iso=100")).startsWith("HTTP/1.1 200"))
        assertEquals(0f, lastFocus)
        assertEquals(ManualExposure(33_333_333L, 100), lastManual)
        // Out-of-range but finite values pass through; CameraController clamps them to the lens range.
        assertTrue(String(get("/snapshot.jpg?focus_diopters=99")).startsWith("HTTP/1.1 200"))
        assertEquals(99f, lastFocus)
        assertEquals(4, snapshotCalls.get())
    }

    @Test fun malformedFocusIsRejectedBeforeCapture() {
        snapshotHandler = { still(byteArrayOf(-1,-40,-1,-39)) }
        for (query in listOf("focus_diopters=", "focus_diopters=abc", "focus_diopters=NaN",
                "focus_diopters=Infinity", "focus=20", "focus_distance=20", "FOCUS_DIOPTERS=20")) {
            val response = String(get("/snapshot.jpg?$query"))
            assertTrue(query, response.startsWith("HTTP/1.1 400"))
            assertFalse(query, response.contains("image/jpeg"))
        }
        assertEquals(0, snapshotCalls.get())
    }

    @Test fun focusAndExposureOptionsWorkIndependentlyAndTogether() {
        snapshotHandler = { still(byteArrayOf(-1,-40,7,-1,-39)) }
        val cases = listOf(
            "/snapshot.jpg" to StillOptions(),
            "/snapshot.jpg?focus_diopters=18" to StillOptions(focusDiopters = 18f),
            "/snapshot.jpg?exposure_ns=20000000&iso=80" to StillOptions(ManualExposure(20_000_000L, 80)),
            "/snapshot.jpg?focus_diopters=18&exposure_ns=20000000&iso=80" to
                StillOptions(ManualExposure(20_000_000L, 80), 18f))
        for ((path, expected) in cases) {
            assertTrue(path, String(get(path)).startsWith("HTTP/1.1 200"))
            assertEquals(path, expected, StillOptions(lastManual, lastFocus))
        }
        // An incomplete exposure pair is never silently ignored, with or without focus.
        for (query in listOf("focus_diopters=18&iso=80", "focus_diopters=18&exposure_ns=20000000")) {
            val response = String(get("/snapshot.jpg?$query"))
            assertTrue(query, response.startsWith("HTTP/1.1 400"))
            assertTrue(query, response.contains("needs both"))
        }
        assertEquals(cases.size, snapshotCalls.get())
    }

    @Test fun manualExposureWaitBudgetScalesWithFrameLength() {
        assertEquals(1_000L, ManualExposure(33_333_333L, 100).extraWaitMillis()) // 100 ms floor per frame.
        assertEquals(10_000L, ManualExposure(1_000_000_000L, 100).extraWaitMillis())
        assertEquals(10_000L, ManualExposure(5_000_000_000L, 100).extraWaitMillis()) // Capped like the camera.
    }

    @Test fun manualExposureExtendsSnapshotSocketDeadline() {
        val quick = BugCamHttpServer(InetAddress.getLoopbackAddress(),0,frames,{"{}"},"BugCam".toByteArray(),
            writeTimeoutMillis=500,headerTimeoutMillis=500,snapshotTimeoutMillis=500,
            snapshot = { Thread.sleep(900); still(byteArrayOf(-1,-40,8,-1,-39)) })
        quick.start()
        try {
            fun fetch(path: String) = Socket(InetAddress.getLoopbackAddress(), quick.boundPort).use {
                it.soTimeout = 4000
                it.getOutputStream().write("GET $path HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                String(it.getInputStream().readBytes())
            }
            // 100 ms manual frames add 1 s to the 0.5 s deadline, so a 0.9 s capture completes.
            assertTrue(fetch("/snapshot.jpg?exposure_ns=100000000&iso=100").startsWith("HTTP/1.1 200"))
            // The automatic deadline is unchanged: the same 0.9 s capture outlives it.
            assertFalse(fetch("/snapshot.jpg").startsWith("HTTP/1.1 200"))
        } finally { quick.close() }
    }

    private fun liveServer(keepalive: () -> Long?, controlCalls: MutableList<String> = mutableListOf()) =
        BugCamHttpServer(InetAddress.getLoopbackAddress(), 0, frames, { "{}" }, "BugCam".toByteArray(),
            control = { controlCalls += it; HttpControlResult(200, "{\"ok\":true}") },
            livePage = "<p>positioning</p>".toByteArray(), liveKeepalive = keepalive).also { it.start() }

    private fun fetch(server: BugCamHttpServer, path: String): ByteArray =
        Socket(InetAddress.getLoopbackAddress(), server.boundPort).use {
            it.soTimeout = 5000
            it.getOutputStream().write("GET $path HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            it.getInputStream().readBytes()
        }

    @Test fun livePreviewRequiresActivePositioningAndKeepsItAlive() {
        var calls = 0
        var remaining: Long? = null
        val server = liveServer({ calls++; remaining })
        try {
            val inactive = String(fetch(server, "/live.jpg"))
            assertTrue(inactive.startsWith("HTTP/1.1 409"))
            assertTrue(inactive.contains("X-Positioning: inactive"))
            remaining = 0L
            assertTrue(String(fetch(server, "/live.jpg")).contains("X-Positioning: expired"))
            remaining = 125_000L
            val jpeg = byteArrayOf(-1,-40,9,-1,-39)
            frames.publish(jpeg, 1280, 960, 1.0)
            val response = fetch(server, "/live.jpg?after=0")
            val text = String(response, StandardCharsets.ISO_8859_1)
            assertTrue(text.startsWith("HTTP/1.1 200"))
            assertTrue(text.contains("Content-Type: image/jpeg"))
            assertTrue(text.contains("X-Frame-Width: 1280\r\nX-Frame-Height: 960"))
            assertTrue(text.contains("X-Positioning-Remaining-Seconds: 125"))
            assertArrayEquals(jpeg, response.takeLast(jpeg.size).toByteArray())
            assertEquals(3, calls) // Every /live.jpg request is a keepalive.
        } finally { server.close() }
    }

    @Test fun livePreviewWaitsForANewerFrameThanTheOneShown() {
        val server = liveServer({ 60_000L })
        try {
            frames.publish(byteArrayOf(-1,-40,1,-1,-39), 1280, 960, 1.0)
            val shown = frames.snapshot()!!.sequence
            Thread { Thread.sleep(300); frames.publish(byteArrayOf(-1,-40,2,-1,-39), 1280, 960, 1.0) }.start()
            val text = String(fetch(server, "/live.jpg?after=$shown"), StandardCharsets.ISO_8859_1)
            assertTrue(text.contains("X-Frame-Sequence: ${shown + 1}"))
        } finally { server.close() }
    }

    @Test fun livePageAndLiveControlsAreRouted() {
        val calls = mutableListOf<String>()
        val server = liveServer({ null }, calls)
        try {
            val page = String(fetch(server, "/live"))
            assertTrue(page.startsWith("HTTP/1.1 200"))
            assertTrue(page.contains("Content-Type: text/html"))
            assertTrue(page.contains("Content-Security-Policy:"))
            assertTrue(page.endsWith("<p>positioning</p>"))
            assertTrue(String(fetch(server, "/live/start")).startsWith("HTTP/1.1 200"))
            assertTrue(String(fetch(server, "/live/stop")).startsWith("HTTP/1.1 200"))
            assertEquals(listOf("/live/start", "/live/stop"), calls)
        } finally { server.close() }
    }

    @Test fun stillHeadersAreAppendedToSuccessfulResponse() {
        val jpeg = byteArrayOf(-1,-40,5,-1,-39)
        snapshotHandler = { still(jpeg).copy(headers = "X-Still-Exposure-Mode: manual\r\nX-Still-ISO: 100\r\n") }
        val response = get("/snapshot.jpg?exposure_ns=10000000&iso=100")
        val text = String(response, StandardCharsets.ISO_8859_1)
        assertTrue(text.contains("X-Image-Height: 3000\r\nX-Still-Exposure-Mode: manual\r\nX-Still-ISO: 100\r\n\r\n"))
        assertArrayEquals(jpeg, response.takeLast(jpeg.size).toByteArray())
    }

    @Test fun stillFailuresNeverFallBackToCachedPreview() {
        publish()
        snapshotHandler = { SnapshotResult(409, message = "Still busy") }
        assertTrue(String(get("/snapshot.jpg")).startsWith("HTTP/1.1 409"))
        snapshotHandler = { SnapshotResult(503, message = "Focus timed out") }
        val response = String(get("/snapshot.jpg"))
        assertTrue(response.startsWith("HTTP/1.1 503"))
        assertTrue(response.contains("Focus timed out"))
        assertFalse(response.contains("Content-Type: image/jpeg"))
        assertEquals(1L,frames.metrics().frameCount)
    }

    @Test fun stillWaitUsesCaptureDeadlineRatherThanHeaderDeadline() {
        val jpeg = byteArrayOf(-1,-40,3,-1,-39)
        snapshotHandler = { Thread.sleep(1100); still(jpeg) }
        val response = get("/snapshot.jpg")
        assertTrue(String(response).startsWith("HTTP/1.1 200"))
        assertArrayEquals(jpeg,response.takeLast(jpeg.size).toByteArray())
        assertTrue(String(get("/health")).startsWith("HTTP/1.1 200"))
    }

    @Test fun serverShutdownInterruptsPendingStillWait() {
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        snapshotHandler = {
            entered.countDown()
            try { CountDownLatch(1).await(10,TimeUnit.SECONDS) }
            catch (_: InterruptedException) { interrupted.countDown(); Thread.currentThread().interrupt() }
            SnapshotResult(503, message = "Interrupted")
        }
        val s = socket()
        s.getOutputStream().write("GET /snapshot.jpg HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        assertTrue(entered.await(2,TimeUnit.SECONDS))
        server.close()
        assertTrue(interrupted.await(2,TimeUnit.SECONDS))
        assertEquals(-1,s.getInputStream().read())
    }

    @Test fun missingFrameAndUnsupportedRoutesHaveExplicitStatus() {
        assertTrue(String(get("/snapshot.jpg")).startsWith("HTTP/1.1 503"))
        assertTrue(String(get("/stream")).startsWith("HTTP/1.1 503"))
        assertTrue(String(get("/torch/on")).startsWith("HTTP/1.1 501"))
        assertTrue(String(get("/absent")).startsWith("HTTP/1.1 404"))
        assertTrue(String(request("POST / HTTP/1.1\r\n\r\n")).startsWith("HTTP/1.1 405"))
    }

    @Test fun malformedAndOversizedHeadersAreRejected() {
        assertTrue(String(request("GET / HTTP/1.1\r\nBroken\r\n\r\n")).startsWith("HTTP/1.1 400"))
        assertTrue(String(request("GET / HTTP/1.1\r\nX: "+"a".repeat(8200)+"\r\n\r\n")).startsWith("HTTP/1.1 431"))
        assertTrue(String(get("/health")).startsWith("HTTP/1.1 200"))
    }

    private fun header(input: BufferedInputStream): String {
        val out=StringBuilder()
        while (!out.endsWith("\r\n\r\n")) {
            val b=input.read(); check(b>=0); out.append(b.toChar())
        }
        return out.toString()
    }

    @Test fun multipartFramingAndViewerLimitLeaveHealthAvailable() {
        publish()
        repeat(3) {
            val s=socket()
            s.getOutputStream().write("GET /stream HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val input=BufferedInputStream(s.getInputStream())
            assertTrue(header(input).contains("boundary=bugcamframe"))
            val part=header(input)
            assertTrue(part.startsWith("--bugcamframe\r\n"))
            assertTrue(part.contains("Content-Length: 6"))
            assertArrayEquals(byteArrayOf(-1,-40,1,2,-1,-39,13,10),input.readNBytes(8))
        }
        assertTrue(String(get("/stream")).startsWith("HTTP/1.1 503"))
        assertTrue(String(get("/health")).startsWith("HTTP/1.1 200"))
    }

    @Test fun slowHeaderCannotOccupyWorkerForever() {
        val slow=socket()
        slow.getOutputStream().write("G".toByteArray())
        assertEquals(-1,slow.getInputStream().read())
        assertTrue(String(get("/health")).startsWith("HTTP/1.1 200"))
    }

    @Test fun blockedWriterIsClosedAndCaptureMailboxKeepsAdvancing() {
        // Larger than TCP buffers: the server must interrupt a blocking output write.
        publish()
        val largeJpeg = ByteArray(8*1024*1024) { 7 }
        snapshotHandler = { still(largeJpeg) }
        val slow=socket()
        slow.receiveBufferSize=1024
        slow.getOutputStream().write("GET /snapshot.jpg HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4)
        var sawClient=false
        while (System.nanoTime()<end) {
            if(server.clientCount>0)sawClient=true
            if(sawClient && server.clientCount==0)break
            Thread.sleep(20)
        }
        assertTrue(sawClient)
        assertEquals(0,server.clientCount)
        repeat(10) { publish() }
        assertEquals(11L,frames.metrics().frameCount)
        assertTrue(String(get("/health")).startsWith("HTTP/1.1 200"))
    }
}
