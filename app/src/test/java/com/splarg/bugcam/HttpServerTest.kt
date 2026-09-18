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

class HttpServerTest {
    private lateinit var frames: FrameStore
    private lateinit var server: BugCamHttpServer
    private val opened = mutableListOf<Socket>()

    @Before fun setup() {
        frames = FrameStore()
        server = BugCamHttpServer(InetAddress.getLoopbackAddress(),0,frames,{"{\"camera_active\":true}"},
            "BugCam".toByteArray(),writeTimeoutMillis=500,headerTimeoutMillis=500)
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

    @Test fun routesSnapshotAndHeadUseTheSameBytes() {
        val jpeg = byteArrayOf(-1,-40,1,2,-1,-39)
        publish(jpeg)
        assertTrue(String(get("/health")).contains("\"camera_active\":true"))
        val response = get("/snapshot.jpg")
        assertArrayEquals(jpeg,response.takeLast(jpeg.size).toByteArray())
        assertTrue(String(response,StandardCharsets.ISO_8859_1).contains("Content-Length: 6"))
        val head = String(request("HEAD /snapshot.jpg HTTP/1.1\r\nHost: localhost\r\n\r\n"))
        assertTrue(head.endsWith("\r\n\r\n"))
        assertEquals(1L,frames.metrics().frameCount)
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
        publish(ByteArray(8*1024*1024) { 7 })
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
