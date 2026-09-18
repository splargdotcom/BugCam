package com.splarg.bugcam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class BatteryState(val percent: Int? = null, val temperatureC: Double? = null, val plugged: Boolean = false)

/** Started service: never bound to Activity lifetime and never launched at boot. */
class BugCamService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val frames = FrameStore(SystemClock::elapsedRealtimeNanos)
    private var camera: CameraController? = null
    private var http: WifiHttpHost? = null
    private var startedAt = 0L
    private var started = false
    private var receiverRegistered = false
    private lateinit var config: AppConfig
    private lateinit var notifications: NotificationManager
    private var lastNotification = ""
    @Volatile private var battery = BatteryState()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            battery = BatteryState(
                if (level >= 0 && scale > 0) level * 100 / scale else null,
                if (intent.hasExtra(BatteryManager.EXTRA_TEMPERATURE))
                    intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0 else null,
                intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "BugCam camera",
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "Persistent status while the rear camera is streaming"
            setSound(null, null)
            enableVibration(false)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        try {
            check(Permissions.missing(this).isEmpty()) { "Grant camera, notifications and local network access in BugCam" }
            check(notifications.areNotificationsEnabled()) { "Enable BugCam notifications in Android settings" }
            check(notifications.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE) {
                "Enable the BugCam camera notification channel in Android settings"
            }
            val initial = notification("Starting rear camera…")
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            else startForeground(NOTIFICATION_ID, initial)
            config = AppConfig.load(this)
            startedAt = SystemClock.elapsedRealtime()
            started = true
            BugCamRuntime.running = true
            BugCamRuntime.lastError = null
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(batteryReceiver, filter, RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(batteryReceiver, filter)
            receiverRegistered = true
            camera = CameraController(this, config, frames).also { it.start() }
            http = WifiHttpHost(this, config, frames, ::healthJson,
                assets.open("status.html").use { it.readBytes() }, ::handleControl).also { it.start() }
            handler.post(tick)
            Log.i(TAG, "Started; target ${config.width}x${config.height} @ ${config.fps} JPEG fps. No wake lock.")
        } catch (e: Exception) {
            BugCamRuntime.lastError = "Start failed: ${e.message}"
            Log.e(TAG, "Service start failed; launch the Activity while visible", e)
            stopSelf()
        }
        // An external supervisor owns reboot/process-death relaunch; camera session retries stay in-process.
        return START_NOT_STICKY
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!started) return
            val c = camera?.status ?: CameraStatus()
            val h = http?.status ?: HttpStatus()
            val f = frames.metrics()
            val b = battery
            val thermal = thermalStatus()
            BugCamRuntime.summary = buildString {
                append(h.url ?: "Waiting for Wi-Fi IPv4 address")
                append("\n\nCamera: ${c.state.replace('_', ' ')}")
                append("\n${f.frame?.width ?: c.width} × ${f.frame?.height ?: c.height}")
                append(String.format(Locale.UK, " · %.1f fps · %,d frames", f.fps, f.frameCount))
                append("\nBattery: ${b.percent?.toString() ?: "?"}% · ${b.temperatureC ?: "?"} °C")
                append(if (b.plugged) " · plugged in" else " · unplugged")
                append("\nThermal: ${thermalName(thermal)}")
                append("\nViewers: ${http?.streamCount ?: 0} · uptime ${(SystemClock.elapsedRealtime() - startedAt) / 1000}s")
                if (c.state == "retrying") append("\n${c.lastError}")
                if (h.lastError != null) append("\n${h.lastError}")
            }
            val text = if (h.url != null) "${c.state.replace('_', ' ')} · ${h.url}"
                else "${c.state.replace('_', ' ')} · waiting for Wi-Fi"
            if (text != lastNotification) {
                notifications.notify(NOTIFICATION_ID, notification(text))
                lastNotification = text
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun healthJson(): String {
        val c = camera?.status ?: CameraStatus()
        val h = http?.status ?: HttpStatus()
        val f = frames.metrics()
        val b = battery
        val thermal = thermalStatus()
        val active = c.state == "streaming" && f.ageMillis != null && f.ageMillis <= 5000
        return JSONObject().apply {
            put("app", "BugCam"); put("version", "1.0.0"); put("api_level", Build.VERSION.SDK_INT)
            put("service_uptime_seconds", (SystemClock.elapsedRealtime() - startedAt) / 1000.0)
            put("status", if (active) "ok" else "degraded")
            put("camera_active", active); put("camera_state", c.state); put("camera_id", c.cameraId ?: JSONObject.NULL)
            put("lens_facing", "back"); put("frame_count", f.frameCount); put("capture_frame_count", f.captureCount)
            put("skipped_frame_count", f.skippedCount); put("fps", f.fps); put("configured_fps", config.fps)
            put("frame_age_ms", f.ageMillis ?: JSONObject.NULL)
            put("last_frame_unix_ms", f.frame?.unixMillis ?: JSONObject.NULL)
            put("last_jpeg_bytes", f.frame?.bytes?.size ?: JSONObject.NULL)
            put("last_encode_ms", f.frame?.encodeMillis ?: JSONObject.NULL)
            put("resolution", JSONObject().put("width", f.frame?.width ?: c.width)
                .put("height", f.frame?.height ?: c.height))
            put("capture_resolution", JSONObject().put("width", c.width).put("height", c.height))
            put("requested_resolution", JSONObject().put("width", config.width).put("height", config.height))
            put("rotation_degrees", config.rotation); put("sensor_orientation_degrees", c.sensorOrientation)
            put("jpeg_quality", config.jpegQuality); put("sensor_ae_fps_range", c.aeRange ?: JSONObject.NULL)
            put("autofocus", c.autofocus); put("camera_recoveries", c.recoveries)
            put("battery_percent", b.percent ?: JSONObject.NULL)
            put("battery_temperature_c", b.temperatureC ?: JSONObject.NULL); put("plugged_in", b.plugged)
            put("thermal_status", thermal); put("thermal_status_name", thermalName(thermal))
            put("http_state", h.state); put("lan_url", h.url ?: JSONObject.NULL)
            put("http_clients", http?.clientCount ?: 0); put("stream_clients", http?.streamCount ?: 0)
            put("camera_last_error", c.lastError ?: JSONObject.NULL)
            put("http_last_error", h.lastError ?: JSONObject.NULL)
            put("torch_capability", if (c.torchAvailable) "available" else "unavailable")
            put("torch_enabled", c.torchEnabled)
            put("torch_max_strength", c.torchMaxStrength)
            put("torch_default_strength", c.torchDefaultStrength)
            put("torch_strength", c.torchStrength ?: JSONObject.NULL)
            put("focus_mode", c.focusMode)
            put("focus_distance_diopters", c.focusDistanceDiopters ?: JSONObject.NULL)
            put("min_focus_distance_diopters", c.minFocusDistanceDiopters ?: JSONObject.NULL)
            put("wake_lock_held", false)
        }.toString()
    }


    private fun handleControl(target: String): HttpControlResult {
        val cam = camera ?: return HttpControlResult(503, "{\"ok\":false,\"error\":\"Camera unavailable\"}")

        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "")

        var torchLevel: Int? = null

        if (path == "/torch/on" && query.isNotEmpty()) {
            val rawLevel = query
                .split('&')
                .firstOrNull { it.startsWith("level=") }
                ?.substringAfter('=')

            if (rawLevel != null) {
                torchLevel = rawLevel.toIntOrNull()
                    ?: return HttpControlResult(
                        400,
                        "{\"ok\":false,\"error\":\"Invalid torch level\"}"
                    )
            }
        }

        val latch = CountDownLatch(1)
        val ref = AtomicReference(CameraController.ControlResult(false, "No response"))
        val callback: (CameraController.ControlResult) -> Unit = {
            result -> ref.set(result); latch.countDown()
        }

        when (path) {
            "/torch/on" -> cam.setTorchEnabled(true, torchLevel, callback)
            "/torch/off" -> cam.setTorchEnabled(false, null, callback)
            "/focus/lock" -> cam.lockCurrentFocus(callback)
            "/focus/auto" -> cam.setContinuousFocus(callback)
            else -> return HttpControlResult(404, "{\"ok\":false,\"error\":\"Unknown control\"}")
        }
        if (!latch.await(2500, TimeUnit.MILLISECONDS))
            return HttpControlResult(503, "{\"ok\":false,\"error\":\"Camera control timed out\"}")
        val result = ref.get()
        val json = JSONObject().put("ok", result.ok).put(if (result.ok) "message" else "error", result.message).toString()
        return HttpControlResult(if (result.ok) 200 else 409, json)
    }

    private fun thermalStatus() = if (Build.VERSION.SDK_INT >= 29)
        getSystemService(PowerManager::class.java).currentThermalStatus else -1

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, BugCamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_bug)
            .setContentTitle("🐛 BugCam is running").setContentText(text).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        started = false
        handler.removeCallbacksAndMessages(null)
        camera?.close()
        http?.close()
        frames.close()
        if (receiverRegistered) unregisterReceiver(batteryReceiver)
        BugCamRuntime.running = false
        BugCamRuntime.summary = "Stopped"
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "Stopped; camera, HTTP clients and network monitor released")
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.splarg.bugcam.STOP"
        private const val TAG = "BugCam-Service"
        private const val CHANNEL = "bugcam_camera"
        private const val NOTIFICATION_ID = 1001
        fun thermalName(status: Int) = when (status) {
            0 -> "none"; 1 -> "light"; 2 -> "moderate"; 3 -> "severe"; 4 -> "critical"
            5 -> "emergency"; 6 -> "shutdown"; else -> "unknown"
        }
    }
}
