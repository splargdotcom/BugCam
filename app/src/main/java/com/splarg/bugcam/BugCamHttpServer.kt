package com.splarg.bugcam

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class HttpControlResult(val status: Int, val json: String)
/** [headers]: extra CRLF-terminated response headers for a successful still (e.g. X-Still-*). */
data class SnapshotResult(val status: Int, val frame: JpegFrame? = null, val message: String = "",
    val headers: String = "")

/**
 * Manual still exposure: `/snapshot.jpg?exposure_ns=<ns>&iso=<iso>`, for that one still only.
 * Without either parameter the still is the unchanged automatic one; one without the other is
 * rejected (HTTP 400), as are other keys beginning "exposure"/"iso", so a mistyped name cannot
 * silently give an automatic photo. Other query keys (e.g. cache busters) are ignored.
 * CameraController clamps both values to physical camera 3's advertised ranges.
 */
data class ManualExposure(val exposureNs: Long, val iso: Int) {
    /** Extra still/HTTP wait: long manual frames slow the whole preview pipeline, not just the JPEG. */
    fun extraWaitMillis(): Long =
        EXTRA_WAIT_FRAMES * maxOf(minOf(exposureNs, MAX_EXPOSURE_NS) / 1_000_000L, MIN_FRAME_BUDGET_MILLIS)

    companion object {
        const val EXPOSURE_PARAM = "exposure_ns"
        const val ISO_PARAM = "iso"
        /** BugCam's own ceiling (keeps the still inside its timeouts); applied on top of camera 3's range. */
        const val MAX_EXPOSURE_NS = 1_000_000_000L
        private const val EXTRA_WAIT_FRAMES = 10L // Pipeline depth + settle frames + JPEG behind in-flight frames.
        private const val MIN_FRAME_BUDGET_MILLIS = 100L

        /** null = automatic exposure; IllegalArgumentException = malformed/partial request (HTTP 400). */
        fun fromQuery(target: String): ManualExposure? {
            val params = queryPairs(target)
            params.map { it.first }.firstOrNull { key ->
                key !in listOf(EXPOSURE_PARAM, ISO_PARAM) &&
                    (key.startsWith("exposure", ignoreCase = true) || key.startsWith("iso", ignoreCase = true))
            }?.let { throw IllegalArgumentException("Unknown exposure parameter '$it'; use $EXPOSURE_PARAM and $ISO_PARAM") }
            val rawNs = params.lastOrNull { it.first == EXPOSURE_PARAM }?.second
            val rawIso = params.lastOrNull { it.first == ISO_PARAM }?.second
            if (rawNs == null && rawIso == null) return null
            val ns = rawNs?.toLongOrNull()
            val iso = rawIso?.toIntOrNull()
            require(ns != null && iso != null && ns > 0 && iso > 0) {
                "Manual exposure needs both $EXPOSURE_PARAM=<positive integer nanoseconds> " +
                    "and $ISO_PARAM=<positive integer>"
            }
            return ManualExposure(ns, iso)
        }
    }
}

/**
 * Optional per-still settings parsed from `/snapshot.jpg?...`. Defaults = the unchanged automatic still.
 * [focusDiopters]: `focus_diopters=N` (0 = infinity) for this still only: AF off and a fixed lens
 * distance on the preview and the JPEG request. Clamping to the lens range happens in CameraController.
 * Any other key beginning "focus" is rejected, so a mistyped name cannot silently autofocus.
 */
data class StillOptions(val exposure: ManualExposure? = null, val focusDiopters: Float? = null) {
    companion object {
        const val FOCUS_PARAM = "focus_diopters"

        /** IllegalArgumentException = malformed request (HTTP 400). */
        fun fromQuery(target: String): StillOptions {
            val exposure = ManualExposure.fromQuery(target)
            val params = queryPairs(target)
            params.map { it.first }.firstOrNull { it != FOCUS_PARAM && it.startsWith("focus", ignoreCase = true) }
                ?.let { throw IllegalArgumentException("Unknown focus parameter '$it'; use $FOCUS_PARAM") }
            val focus = params.lastOrNull { it.first == FOCUS_PARAM }?.second?.let { raw ->
                raw.toFloatOrNull()?.takeIf { it.isFinite() }
                    ?: throw IllegalArgumentException("$FOCUS_PARAM must be a finite number of diopters (0 = infinity)")
            }
            return StillOptions(exposure, focus)
        }
    }
}

/** How long /live.jpg waits for a frame newer than `after` before returning the latest one. */
private const val LIVE_FRAME_WAIT_MILLIS = 2_000L

private fun queryPairs(target: String): List<Pair<String, String>> =
    target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
        .map { it.substringBefore('=') to it.substringAfter('=', "") }

/** Small HTTP server with bounded resources. No Android classes: loopback-testable on the JVM. */
class BugCamHttpServer(
    private val address: InetAddress,
    private val port: Int,
    private val frames: FrameStore,
    private val healthJson: () -> String,
    private val page: ByteArray,
    private val control: (String) -> HttpControlResult = { HttpControlResult(501, "{\"error\":\"Camera control unavailable\"}") },
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
    private val writeTimeoutMillis: Long = 10_000,
    private val headerTimeoutMillis: Long = 5_000,
    private val snapshot: (StillOptions) -> SnapshotResult = { SnapshotResult(503, message = "Still capture unavailable") },
    private val snapshotTimeoutMillis: Long = 16_000,
    /** Positioning page (/live): browser-side crop overlay over /live.jpg; never drawn into frames. */
    private val livePage: ByteArray = "Positioning view unavailable\n".toByteArray(),
    /** Keeps the positioning camera open: null = not active, <= 0 = time limit reached, else ms left. */
    private val liveKeepalive: () -> Long? = { null },
) : AutoCloseable {
    private data class Client(val socket: Socket, @Volatile var deadlineNanos: Long)
    private val clients = ConcurrentHashMap<Socket, Client>()
    private val workers = ThreadPoolExecutor(8, 8, 0L, TimeUnit.MILLISECONDS,
        SynchronousQueue(), { r -> Thread(r, "BugCam-HTTP-client") }, ThreadPoolExecutor.AbortPolicy())
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "BugCam-HTTP-deadlines") }
    private val streamSlots = Semaphore(3)
    private val running = AtomicBoolean(false)
    private var listener: ServerSocket? = null
    val isRunning: Boolean get() = running.get()
    val clientCount: Int get() = clients.size
    val streamCount: Int get() = 3 - streamSlots.availablePermits()
    val boundPort: Int get() = listener?.localPort ?: port

    fun start() {
        check(running.compareAndSet(false, true)) { "Server already started" }
        try {
            listener = ServerSocket()
            listener!!.apply {
                reuseAddress = true
                bind(InetSocketAddress(address, port), 8)
            }
            watchdog.scheduleAtFixedRate({
                val now = System.nanoTime()
                clients.values.forEach { if (now > it.deadlineNanos) closeSocket(it.socket) }
            }, 250, 250, TimeUnit.MILLISECONDS)
            Thread({ acceptLoop() }, "BugCam-HTTP-accept").start()
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun acceptLoop() {
        try {
            while (running.get()) {
                val socket = listener!!.accept()
                if (!running.get()) { closeSocket(socket); break }
                socket.soTimeout = headerTimeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                socket.tcpNoDelay = true
                socket.sendBufferSize = 64 * 1024
                val client = Client(socket, deadline(headerTimeoutMillis))
                clients[socket] = client
                try {
                    workers.execute {
                        try { serve(client) }
                        catch (_: IOException) { /* Ordinary disconnect/timeout, never a camera error. */ }
                        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                        catch (e: Exception) { log("HTTP client failed", e) }
                        finally { clients.remove(socket); closeSocket(socket) }
                    }
                } catch (_: RejectedExecutionException) {
                    // The listener never blocks trying to send an overload response.
                    clients.remove(socket)
                    closeSocket(socket)
                }
            }
        } catch (e: Exception) {
            if (running.get()) log("HTTP listener failed; host will rebind", e)
        } finally { close() }
    }

    private fun serve(client: Client) {
        val input = BufferedInputStream(client.socket.getInputStream(), 4096)
        val output = BufferedOutputStream(client.socket.getOutputStream(), 64 * 1024)
        val raw = try { readHeaders(input) } catch (e: BadRequest) {
            respond(client, output, e.status, "text/plain; charset=utf-8", e.message!!.toByteArray())
            return
        }
        val lines = raw.split("\r\n")
        val request = lines.first().split(' ')
        if (request.size != 3 || request[2] !in listOf("HTTP/1.0", "HTTP/1.1") ||
            !request[1].startsWith("/") || request[1].length > 2048) {
            respond(client, output, 400, "text/plain", "Bad request\n".toByteArray()); return
        }
        val method = request[0]
        val head = method == "HEAD"
        val target = request[1]
        val path = target.substringBefore('?')
        if (method !in listOf("GET", "HEAD")) {
            respond(client, output, 405, "text/plain", "GET and HEAD only\n".toByteArray(),
                extra = "Allow: GET, HEAD\r\n"); return
        }
        // No request bodies, transfer coding or pipeline. Close after exactly one request.
        for (line in lines.drop(1).filter { it.isNotEmpty() }) {
            val colon = line.indexOf(':')
            if (colon <= 0 || line.first().isWhitespace()) {
                respond(client, output, 400, "text/plain", "Malformed header\n".toByteArray(), head); return
            }
            val name = line.substring(0, colon).lowercase()
            val value = line.substring(colon + 1).trim()
            if (name == "transfer-encoding" || (name == "content-length" && value != "0")) {
                respond(client, output, 400, "text/plain", "Request bodies are unsupported\n".toByteArray(), head); return
            }
        }
        when (path) {
            "/" -> respond(client, output, 200, "text/html; charset=utf-8", page, head,
                "Content-Security-Policy: default-src 'self'; img-src 'self' blob:; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'\r\n")
            "/health" -> respond(client, output, 200, "application/json; charset=utf-8",
                healthJson().toByteArray(StandardCharsets.UTF_8), head)
            "/snapshot.jpg" -> {
                val options = try { StillOptions.fromQuery(target) } catch (e: IllegalArgumentException) {
                    respond(client, output, 400, "text/plain; charset=utf-8",
                        "${e.message}\n".toByteArray(StandardCharsets.UTF_8), head)
                    return
                }
                // Capture has its own bounded wait; do not reuse the short header/write deadline.
                // Manual exposures extend it by the same budget CameraController adds to its own wait.
                client.deadlineNanos = deadline(snapshotTimeoutMillis + (options.exposure?.extraWaitMillis() ?: 0L))
                val result = snapshot(options)
                val frame = result.frame
                if (result.status != 200 || frame == null)
                    respond(client, output, result.status.takeIf { it != 200 } ?: 503,
                        "text/plain; charset=utf-8", (result.message + "\n").toByteArray(StandardCharsets.UTF_8), head,
                        "Retry-After: 2\r\n")
                else respond(client, output, 200, "image/jpeg", frame.bytes, head,
                    "X-Frame-Sequence: ${frame.sequence}\r\nX-Frame-Time-Millis: ${frame.unixMillis}\r\n" +
                        "X-Image-Width: ${frame.width}\r\nX-Image-Height: ${frame.height}\r\n" + result.headers)
            }
            "/stream" -> stream(client, output, head)
            "/live" -> respond(client, output, 200, "text/html; charset=utf-8", livePage, head,
                "Content-Security-Policy: default-src 'self'; img-src 'self' blob:; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'\r\n")
            "/live.jpg" -> livePreview(client, output, target, head)
            "/live/start", "/live/stop", "/camera/on", "/camera/off", "/torch/on", "/torch/off", "/focus/lock", "/focus/auto", "/focus/restore", "/focus/set", "/focus/get", "/exposure/set", "/exposure/get", "/exposure/0", "/exposure/p2", "/exposure/p3", "/exposure/p4" -> {
                val result = control(target)
                respond(client, output, result.status, "application/json; charset=utf-8",
                    result.json.toByteArray(StandardCharsets.UTF_8), head)
            }
            else -> respond(client, output, 404, "text/plain", "Not found\n".toByteArray(), head)
        }
    }

    private fun stream(client: Client, output: BufferedOutputStream, head: Boolean) {
        if (freshFrame() == null) { unavailable(client, output, head); return }
        if (!streamSlots.tryAcquire()) {
            respond(client, output, 503, "text/plain", "Three viewers already connected\n".toByteArray(), head,
                "Retry-After: 3\r\n"); return
        }
        try {
            client.deadlineNanos = deadline(writeTimeoutMillis)
            output.write(("HTTP/1.1 200 OK\r\n" + commonHeaders() +
                "Content-Type: multipart/x-mixed-replace; boundary=bugcamframe\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            if (head) return
            var sequence = 0L
            while (running.get() && !client.socket.isClosed) {
                client.deadlineNanos = deadline(7_000)
                val frame = frames.awaitAfter(sequence, 5_000) ?: return
                if (!frames.isFresh(frame)) return
                client.deadlineNanos = deadline(writeTimeoutMillis)
                output.write(("--bugcamframe\r\nContent-Type: image/jpeg\r\n" +
                    "Content-Length: ${frame.bytes.size}\r\nX-Frame-Sequence: ${frame.sequence}\r\n" +
                    "X-Frame-Time-Millis: ${frame.unixMillis}\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
                output.write(frame.bytes)
                output.write(CRLF)
                output.flush()
                sequence = frame.sequence
            }
        } finally { streamSlots.release() }
    }

    private fun freshFrame() = frames.snapshot()?.takeIf { frames.isFresh(it) }

    /**
     * One positioning frame per request (`?after=<last X-Frame-Sequence>` waits briefly for a newer one).
     * Each request extends the camera-open failsafe, so closing the page lets the camera shut itself.
     */
    private fun livePreview(client: Client, output: BufferedOutputStream, target: String, head: Boolean) {
        val remaining = liveKeepalive()
        if (remaining == null || remaining <= 0) {
            val state = if (remaining == null) "inactive" else "expired"
            respond(client, output, 409, "text/plain; charset=utf-8",
                (if (remaining == null) "Positioning view is not active; start it from /live\n"
                else "Positioning time limit reached; press Resume on /live\n").toByteArray(StandardCharsets.UTF_8),
                head, "X-Positioning: $state\r\n")
            return
        }
        val after = queryPairs(target).lastOrNull { it.first == "after" }?.second?.toLongOrNull() ?: 0L
        client.deadlineNanos = deadline(LIVE_FRAME_WAIT_MILLIS + writeTimeoutMillis)
        val frame = (frames.awaitAfter(after, LIVE_FRAME_WAIT_MILLIS) ?: frames.snapshot())?.takeIf { frames.isFresh(it) }
        if (frame == null) {
            respond(client, output, 503, "text/plain; charset=utf-8", "No positioning frame yet\n".toByteArray(),
                head, "Retry-After: 1\r\nX-Positioning: active\r\n")
            return
        }
        client.deadlineNanos = deadline(writeTimeoutMillis)
        respond(client, output, 200, "image/jpeg", frame.bytes, head,
            "X-Positioning: active\r\nX-Positioning-Remaining-Seconds: ${remaining / 1000}\r\n" +
                "X-Frame-Sequence: ${frame.sequence}\r\nX-Frame-Width: ${frame.width}\r\n" +
                "X-Frame-Height: ${frame.height}\r\n")
    }

    private fun unavailable(client: Client, output: BufferedOutputStream, head: Boolean) =
        respond(client, output, 503, "application/json", "{\"error\":\"No fresh camera frame\"}".toByteArray(), head,
            "Retry-After: 2\r\n")

    private fun respond(
        client: Client, output: BufferedOutputStream, status: Int, type: String,
        body: ByteArray, head: Boolean = false, extra: String = "",
    ) {
        client.deadlineNanos = deadline(writeTimeoutMillis)
        val reason = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            409 -> "Conflict"; 431 -> "Request Header Fields Too Large"; 501 -> "Not Implemented"; else -> "Service Unavailable"
        }
        output.write(("HTTP/1.1 $status $reason\r\n" + commonHeaders() +
            "Content-Type: $type\r\nContent-Length: ${body.size}\r\n$extra\r\n").toByteArray(StandardCharsets.US_ASCII))
        if (!head) output.write(body)
        output.flush()
    }

    private fun commonHeaders() = "Connection: close\r\nCache-Control: no-store, no-cache, must-revalidate\r\n" +
        "Pragma: no-cache\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\n"

    private class BadRequest(val status: Int, message: String) : IOException(message)

    private fun readHeaders(input: BufferedInputStream): String {
        val data = ByteArrayOutputStream(1024)
        var tail = 0
        while (data.size() < 8192) {
            val next = input.read()
            if (next < 0) throw EOFException()
            if ((next < 32 && next !in listOf(9, 10, 13)) || next == 127) throw BadRequest(400, "Invalid header\n")
            data.write(next)
            tail = (tail shl 8) or next
            if (tail == 0x0D0A0D0A) return data.toString(StandardCharsets.ISO_8859_1.name())
        }
        throw BadRequest(431, "Headers exceed 8192 bytes\n")
    }

    private fun deadline(millis: Long) = System.nanoTime() + millis * 1_000_000

    override fun close() {
        if (!running.getAndSet(false)) return
        try { listener?.close() } catch (_: IOException) { }
        clients.keys.forEach(::closeSocket)
        workers.shutdownNow()
        watchdog.shutdownNow()
    }

    private fun closeSocket(socket: Socket) { try { socket.close() } catch (_: IOException) { } }
    companion object { private val CRLF = byteArrayOf(13, 10) }
}
