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
                "Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'\r\n")
            "/health" -> respond(client, output, 200, "application/json; charset=utf-8",
                healthJson().toByteArray(StandardCharsets.UTF_8), head)
            "/snapshot.jpg" -> {
                val frame = freshFrame()
                if (frame == null) unavailable(client, output, head)
                else respond(client, output, 200, "image/jpeg", frame.bytes, head,
                    "X-Frame-Sequence: ${frame.sequence}\r\nX-Frame-Time-Millis: ${frame.unixMillis}\r\n")
            }
            "/stream" -> stream(client, output, head)
            "/torch/on", "/torch/off", "/focus/lock", "/focus/auto" -> {
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
