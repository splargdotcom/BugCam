package com.splarg.bugcam

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean

data class HttpStatus(
    val state: String = "waiting_for_wifi", val url: String? = null, val lastError: String? = null,
)

/** Explicit Wi-Fi IPv4 binding, including LANs without internet connectivity. */
class WifiHttpHost(
    context: Context, private val config: AppConfig, private val frames: FrameStore,
    private val health: () -> String, private val page: ByteArray,
    private val control: (String) -> HttpControlResult = { HttpControlResult(501, "{\"error\":\"Camera control unavailable\"}") },
) : AutoCloseable {
    private data class Binding(val network: Network, val address: Inet4Address)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val thread = HandlerThread("BugCam-Network").apply { start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean()
    private val bindings = linkedMapOf<Network, Inet4Address>()
    private var registered = false
    private var binding: Binding? = null
    @Volatile private var server: BugCamHttpServer? = null
    @Volatile var status = HttpStatus()
        private set
    val clientCount: Int get() = server?.clientCount ?: 0
    val streamCount: Int get() = server?.streamCount ?: 0

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            if (closed.get()) return
            val address = properties.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
            if (address == null) bindings.remove(network) else bindings[network] = address
            reconcile()
        }
        override fun onLost(network: Network) {
            bindings.remove(network)
            reconcile()
        }
    }

    fun start() {
        handler.post {
            if (closed.get()) return@post
            handler.post(tick)
        }
    }

    private fun ensureRegistered() {
        if (registered) return
        try {
            connectivity.registerNetworkCallback(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), callback, handler)
            registered = true
        } catch (e: Exception) {
            status = HttpStatus("retrying", lastError = "Wi-Fi monitoring: ${e.message}")
            Log.e(TAG, "Network callback registration failed; will retry", e)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (closed.get()) return
            ensureRegistered()
            reconcile()
            handler.postDelayed(this, 3000)
        }
    }

    private fun reconcile() {
        if (closed.get()) return
        val desired = bindings.entries.firstOrNull()?.let { Binding(it.key, it.value) }
        if (desired == binding && server?.isRunning == true) return
        server?.close()
        server = null
        binding = null
        if (desired == null) {
            if (registered) status = HttpStatus()
            return
        }
        try {
            val next = BugCamHttpServer(desired.address, config.port, frames, health, page, control = control,
                log = { message, error -> Log.w(TAG, message, error) })
            next.start()
            server = next
            binding = desired
            status = HttpStatus("listening", "http://${desired.address.hostAddress}:${config.port}")
            Log.i(TAG, "Listening on ${status.url} (Wi-Fi network ${desired.network})")
        } catch (e: Exception) {
            status = HttpStatus("retrying", lastError = "HTTP bind: ${e.message}")
            Log.e(TAG, "HTTP bind failed; retry in 3 seconds", e)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.post {
            handler.removeCallbacksAndMessages(null)
            if (registered) try { connectivity.unregisterNetworkCallback(callback) }
                catch (e: Exception) { Log.w(TAG, "Unregister network callback", e) }
            server?.close()
            server = null
            status = HttpStatus("stopped")
            thread.quitSafely()
        }
    }

    companion object { private const val TAG = "BugCam-HTTP" }
}
