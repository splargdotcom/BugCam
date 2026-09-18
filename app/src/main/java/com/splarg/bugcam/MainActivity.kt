package com.splarg.bugcam

import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.graphics.Color
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var message: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var size: Spinner
    private lateinit var fps: Spinner
    private lateinit var rotation: Spinner
    private var pendingStart = false
    private var permissionsInFlight = false
    private var resumed = false
    private var startRequestedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = AppConfig.load(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(body) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)
        fun text(value: String, sp: Float = 16f) = TextView(this).apply {
            text = value; textSize = sp; setTextColor(Color.rgb(29, 53, 37))
            setPadding(0, dp(10), 0, dp(10)); body.addView(this)
        }
        text("🐛 BugCam", 34f)
        text("Rear camera · Wi-Fi · screen-off streaming")
        status = text("Stopped").apply { setTextIsSelectable(true) }
        message = text("")
        start = Button(this).apply {
            text = "Start BugCam"
            setOnClickListener { pendingStart = true; requestStart() }
            body.addView(this)
        }
        stop = Button(this).apply {
            text = "Stop"
            setOnClickListener {
                pendingStart = false
                startRequestedAt = 0
                stopService(Intent(this@MainActivity, BugCamService::class.java))
                message.text = ""
            }
            body.addView(this)
        }
        text("Capture resolution")
        size = spinner(body, AppConfig.sizes.map { "${it.first} × ${it.second}" },
            AppConfig.sizes.indexOf(config.width to config.height).coerceAtLeast(0))
        text("Stream FPS limit")
        fps = spinner(body, AppConfig.rates.map { "$it fps" }, AppConfig.rates.indexOf(config.fps).coerceAtLeast(0))
        text("Clockwise image rotation")
        rotation = spinner(body, AppConfig.rotations.map { "$it°" }, AppConfig.rotations.indexOf(config.rotation))
        text("Stop before changing settings. JPEG quality: 85. The actual camera size may be smaller if the preferred size is unsupported.", 14f)
        text("Grant permissions once, start BugCam, then turn the screen off or close this screen. The notification shows when the camera service is running.", 14f)
        body.addView(Button(this).apply {
            text = "App permissions / battery settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            }
        })
        pendingStart = savedInstanceState?.getBoolean("pending_start") ?: consumeAutoStart(intent)
    }

    private fun spinner(parent: LinearLayout, values: List<String>, selected: Int) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, values)
        setSelection(selected.coerceAtLeast(0)); minimumHeight = dp(48); parent.addView(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (consumeAutoStart(intent)) pendingStart = true
        if (resumed) requestStart()
    }

    private fun consumeAutoStart(intent: Intent): Boolean {
        val requested = intent.getBooleanExtra("auto_start", false)
        intent.removeExtra("auto_start")
        return requested
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending_start", pendingStart)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        handler.post(refresh)
        // A posted start executes after Activity resume, including the ADB entry path.
        handler.post { if (pendingStart && !permissionsInFlight) requestStart() }
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun requestStart() {
        if (!pendingStart || !resumed || permissionsInFlight) return
        if (BugCamRuntime.running) { pendingStart = false; return }
        val missing = Permissions.missing(this)
        if (missing.isNotEmpty()) {
            permissionsInFlight = true
            requestPermissions(missing.toTypedArray(), 1)
            return
        }
        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            pendingStart = false
            message.text = "Enable BugCam notifications in App settings so the camera has a visible running notification."
            return
        }
        val (w, h) = AppConfig.sizes[size.selectedItemPosition]
        AppConfig(width = w, height = h, fps = AppConfig.rates[fps.selectedItemPosition],
            rotation = AppConfig.rotations[rotation.selectedItemPosition]).save(this)
        try {
            BugCamRuntime.lastError = null
            startForegroundService(Intent(this, BugCamService::class.java))
            startRequestedAt = android.os.SystemClock.elapsedRealtime()
            message.text = "Starting…"
        } catch (e: Exception) {
            message.text = "Could not start: ${e.message}. Keep this screen visible and try again."
            android.util.Log.e("BugCam-UI", "Foreground service start rejected", e)
        }
        pendingStart = false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        permissionsInFlight = false
        if (Permissions.missing(this).isNotEmpty()) {
            pendingStart = false
            message.text = "Camera, notifications and local-network access are needed. Grant them in App settings, then Start."
        } else if (resumed) handler.post { requestStart() }
    }

    private val refresh = object : Runnable {
        override fun run() {
            val running = BugCamRuntime.running
            val waiting = !running && startRequestedAt != 0L &&
                android.os.SystemClock.elapsedRealtime() - startRequestedAt < 5000
            status.text = BugCamRuntime.summary
            start.isEnabled = !running && !waiting
            stop.isEnabled = running || waiting
            listOf<View>(size, fps, rotation).forEach { it.isEnabled = !running && !waiting }
            if (running) { message.text = ""; startRequestedAt = 0 }
            BugCamRuntime.lastError?.let { message.text = it }
            handler.postDelayed(this, 1000)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
