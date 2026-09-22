package com.splarg.bugcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

data class CameraStatus(
    val state: String = "stopped",
    val cameraId: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val sensorOrientation: Int = 0,
    val autofocus: String = "unknown",
    val aeRange: String? = null,
    val recoveries: Int = 0,
    val lastError: String? = null,
    val torchAvailable: Boolean = false,
    val torchEnabled: Boolean = false,
    val torchMaxStrength: Int = 1,
    val torchDefaultStrength: Int = 1,
    val torchStrength: Int? = null,
    val focusMode: String = "continuous",
    val focusDistanceDiopters: Float? = null,
    val minFocusDistanceDiopters: Float? = null,
)

/** All camera mutations occur on cameraHandler; JPEG encoding has one worker. */
class CameraController(
    context: Context, private val config: AppConfig, private val frames: FrameStore,
) : AutoCloseable {
    private val appContext = context.applicationContext.createDeviceProtectedStorageContext()
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("BugCam-Camera").apply { start() }
    private val cameraHandler = Handler(thread.looper)
    private val encoder = Executors.newSingleThreadExecutor { r -> Thread(r, "BugCam-JPEG") }
    private val encoding = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    @Volatile private var generation = 0
    @Volatile var status = CameraStatus()
        private set
    private var running = false
    private var retryPending = false
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var sizes = emptyList<Size>()
    private var sizeIndex = 0
    private var failures = 0
    private var requestFailures = 0
    private var attemptStarted = 0L
    private var stableSince = 0L
    private var requestBuilder: CaptureRequest.Builder? = null
    private var activeCharacteristics: CameraCharacteristics? = null
    private var activeCaptureCallback: CameraCaptureSession.CaptureCallback? = null
    @Volatile private var currentFocusDistance: Float? = null
    @Volatile private var activeSensorFps: Int = 0
    @Volatile private var lastJpeg = 0L
    private var lastAccepted = 0L
    // Reused only by the encoder worker.
    private var packed = ByteArray(0)
    private var rotated = ByteArray(0)
    private val jpegOutput = ByteArrayOutputStream(512 * 1024)

    fun start() {
        cameraHandler.post {
            if (running || closing.get()) return@post
            running = true

            cameraHandler.removeCallbacks(watchdog)
            cameraHandler.removeCallbacks(openFailsafe)

            openCamera()

            cameraHandler.postDelayed(watchdog, 2000)
            cameraHandler.postDelayed(openFailsafe, 15_000)
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!running || closing.get()) return
            val now = System.nanoTime()
            if (!retryPending && now - maxOf(lastJpeg, attemptStarted) > 15_000_000_000L) {
                recover("No JPEG received for 15 seconds (camera/session/encoder stalled)")
            }
            if (stableSince != 0L && now - stableSince > 10_000_000_000L) failures = 0
            cameraHandler.postDelayed(this, 2000)
        }
    }

    /**
     * Last-resort protection against a lost ThinkCentre/Wi-Fi request.
     * The physical camera must never remain open indefinitely.
     */
    private val openFailsafe = object : Runnable {
        override fun run() {
            if (!running || closing.get()) return

            Log.w(TAG, "15 second camera-open failsafe triggered")

            running = false
            retryPending = false

            cameraHandler.removeCallbacks(watchdog)

            closeSession()

            status = status.copy(
                state = "idle",
                torchEnabled = false,
                torchStrength = null
            )
        }
    }

    @SuppressLint("MissingPermission") // The visible Activity and service validate permission first.
    private fun openCamera() {
        if (!running || closing.get()) return
        retryPending = false
        attemptStarted = System.nanoTime()
        lastJpeg = 0
        lastAccepted = 0
        stableSince = 0
        val token = ++generation
        try {
            val id = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: error("No rear-facing camera found")
            val characteristics = manager.getCameraCharacteristics(id)
            if (sizes.isEmpty()) {
                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: error("Camera has no stream configuration map")
                val all = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
                    .filter { it.width % 2 == 0 && it.height % 2 == 0 }
                val bounded = all.filter { it.width <= config.width && it.height <= config.height }
                // Exact preference first, then smaller sizes close to the requested aspect ratio.
                sizes = bounded.sortedWith(compareBy<Size> {
                    abs(it.width.toDouble() / it.height - config.width.toDouble() / config.height) > 0.04
                }.thenByDescending { it.width.toLong() * it.height })
                    .ifEmpty { all.sortedBy { it.width.toLong() * it.height }.take(1) }
                check(sizes.isNotEmpty()) { "Rear camera exposes no YUV_420_888 sizes" }
            }
            val size = sizes[sizeIndex.coerceAtMost(sizes.lastIndex)]
            status = status.copy(state = "opening", cameraId = id, width = size.width,
                height = size.height,
                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                torchAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                torchMaxStrength = if (Build.VERSION.SDK_INT >= 35)
                    characteristics.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL) ?: 1
                else 1,
                torchDefaultStrength = if (Build.VERSION.SDK_INT >= 35)
                    characteristics.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_DEFAULT_LEVEL) ?: 1
                else 1,
                minFocusDistanceDiopters = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE))
            activeCharacteristics = characteristics
            Log.i(TAG, "Opening rear camera $id at $size, publish ${config.fps} fps, generation $token")
            val newReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
            reader = newReader
            newReader.setOnImageAvailableListener({ source -> onImage(source, token) }, cameraHandler)
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!current(token)) { camera.close(); return }
                    device = camera
                    configure(camera, newReader, characteristics, token)
                }
                override fun onClosed(camera: CameraDevice) {
                    Log.i(TAG, "Camera device fully closed")
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (current(token)) recover("Rear camera disconnected")
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (current(token)) recover("Camera device error $error")
                }
            }, cameraHandler)
        } catch (e: Exception) {
            recover("Cannot open rear camera: ${e.message}", e)
        }
    }

    @Suppress("DEPRECATION") // The handler overload keeps all callbacks on one camera thread.
    private fun configure(
        camera: CameraDevice, output: ImageReader, chars: CameraCharacteristics, token: Int,
    ) {
        try {
            status = status.copy(state = "configuring")
            camera.createCaptureSession(listOf(output.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(newSession: CameraCaptureSession) {
                    if (!current(token)) { newSession.close(); return }
                    session = newSession
                    try {
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(output.surface)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)

                            // Apply the user's persisted exposure compensation
                            // every time the physical camera wakes.
                            val savedExposureSteps =
                                ExposureMemory.load(appContext)

                            set(
                                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                                savedExposureSteps
                            )

                            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                            if (Build.VERSION.SDK_INT >= 30 &&
                                chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.contains(1.0f) == true) {
                                set(CaptureRequest.CONTROL_ZOOM_RATIO, 1.0f)
                            }
                            val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                                ?: intArrayOf(CaptureRequest.CONTROL_AF_MODE_OFF)
                            val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                            val savedFocus = FocusMemory.loadLockedDistance(appContext)
                                ?.takeIf { minFocus > 0f && it <= minFocus &&
                                    CaptureRequest.CONTROL_AF_MODE_OFF in afModes }
                            val af = if (savedFocus != null) {
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                                set(CaptureRequest.LENS_FOCUS_DISTANCE, savedFocus)
                                CaptureRequest.CONTROL_AF_MODE_OFF
                            } else {
                                listOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                                    CaptureRequest.CONTROL_AF_MODE_AUTO).firstOrNull { it in afModes }
                                    ?: CaptureRequest.CONTROL_AF_MODE_OFF
                            }
                            if (savedFocus == null) set(CaptureRequest.CONTROL_AF_MODE, af)
                            val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                                ?.toList().orEmpty()
                            val range = chooseAeRange(ranges)
                            if (range != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                            activeSensorFps = range?.upper ?: 0
                            status = status.copy(
                                autofocus = if (savedFocus != null) "locked" else when (af) {
                                    CaptureRequest.CONTROL_AF_MODE_OFF -> "fixed_focus"
                                    CaptureRequest.CONTROL_AF_MODE_AUTO -> "auto"
                                    else -> "continuous"
                                },
                                focusMode = if (savedFocus != null) "locked" else "continuous",
                                focusDistanceDiopters = savedFocus,
                                aeRange = range?.let { "${it.lower}-${it.upper}" }
                            )
                        }
                        requestBuilder = request
                        val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                                if (current(token)) currentFocusDistance = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                            }
                            override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                                if (current(token) && ++requestFailures >= 10) {
                                    requestFailures = 0
                                    recover("10 capture failures; latest reason=${f.reason}")
                                }
                            }
                        }
                        activeCaptureCallback = captureCallback
                        newSession.setRepeatingRequest(request.build(), captureCallback, cameraHandler)
                        if (status.autofocus == "auto") {
                            request.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                            newSession.capture(request.build(), null, cameraHandler)
                        }
                        status = status.copy(state = "warming_up")
                        Log.i(TAG, "Session ready; AE=${status.aeRange}, AF=${status.autofocus}")
                    } catch (e: Exception) {
                        recover("Starting repeating capture failed: ${e.message}", e)
                    }
                }
                override fun onConfigureFailed(failed: CameraCaptureSession) {
                    failed.close()
                    if (current(token)) recover("Camera session configuration failed", smallerSize = true)
                }
            }, cameraHandler)
        } catch (e: Exception) {
            recover("Configuring camera failed: ${e.message}", e, smallerSize = true)
        }
    }

    private fun chooseAeRange(ranges: List<Range<Int>>): Range<Int>? {
        // Keep the sensor close to the configured publish rate.
        // Lower sensor FPS materially reduces thermals on an always-on camera.
        val target = config.fps
        return ranges.minWithOrNull(compareBy<Range<Int>> {
            if (it.lower <= target && it.upper >= target) 0 else 1
        }.thenBy { abs(it.upper - target) }.thenBy { abs(it.lower - target) })
    }

    private fun onImage(source: ImageReader, token: Int) {
        val image = try { source.acquireLatestImage() } catch (e: Exception) {
            if (current(token)) recover("ImageReader failed: ${e.message}", e)
            null
        } ?: return
        frames.captured.incrementAndGet()
        val now = System.nanoTime()
        val shouldThrottle = activeSensorFps <= 0 || config.fps < activeSensorFps
        val tooSoon = shouldThrottle && now - lastAccepted < 1_000_000_000L / config.fps
        if (!current(token) || tooSoon || !encoding.compareAndSet(false, true)) {
            frames.skipped.incrementAndGet()
            image.close()
            return
        }
        lastAccepted = now
        // This executor is shut down only after the camera handler has stopped producing work.
        encoder.execute { encode(image, token) }
    }

    private fun encode(image: Image, token: Int) {
        try {
            if (!current(token)) return
            val started = System.nanoTime()
            val crop = image.cropRect
            val w = crop.width()
            val h = crop.height()
            val bytes = w * h * 3 / 2
            if (packed.size != bytes) { packed = ByteArray(bytes); rotated = ByteArray(bytes) }
            YuvPacking.toNv21(image.planes.map {
                YuvPacking.Plane(it.buffer, it.rowStride, it.pixelStride)
            }, crop.left, crop.top, w, h, packed)
            val data = if (config.rotation == 0) packed else {
                YuvPacking.rotateNv21(packed, w, h, config.rotation, rotated)
                rotated
            }
            val portrait = config.rotation == 90 || config.rotation == 270
            val outWidth = if (portrait) h else w
            val outHeight = if (portrait) w else h
            jpegOutput.reset()
            check(YuvImage(data, ImageFormat.NV21, outWidth, outHeight, null)
                .compressToJpeg(Rect(0, 0, outWidth, outHeight), config.jpegQuality, jpegOutput)) {
                "YuvImage.compressToJpeg returned false"
            }
            if (current(token)) {
                val jpeg = jpegOutput.toByteArray()
                val encodeMillis = (System.nanoTime() - started) / 1e6
                cameraHandler.post {
                    if (current(token)) {
                        // Publish on the camera thread so teardown cannot interleave with this check.
                        frames.publish(jpeg, outWidth, outHeight, encodeMillis)
                        lastJpeg = System.nanoTime()
                        requestFailures = 0
                        if (stableSince == 0L) stableSince = System.nanoTime()
                        if (status.state != "streaming") status = status.copy(state = "streaming")
                    }
                }
            }
        } catch (e: Exception) {
            cameraHandler.post { if (current(token)) recover("JPEG encoding failed: ${e.message}", e) }
        } finally {
            image.close()
            encoding.set(false)
        }
    }


    data class ControlResult(val ok: Boolean, val message: String)

    fun setTorchEnabled(
        enabled: Boolean,
        requestedStrength: Int? = null,
        callback: (ControlResult) -> Unit = {}
    ) {
        cameraHandler.post {
            val chars = activeCharacteristics
            val builder = requestBuilder
            val s = session

            if (!running || chars == null || builder == null || s == null) {
                callback(ControlResult(false, "Camera is not ready"))
                return@post
            }

            if (chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) {
                callback(ControlResult(false, "Rear camera reports no flash/torch"))
                return@post
            }

            try {
                if (enabled) {
                    val maxStrength = status.torchMaxStrength.coerceAtLeast(1)
                    val defaultStrength =
                        status.torchDefaultStrength.coerceIn(1, maxStrength)

                    val strength = requestedStrength ?: defaultStrength

                    if (strength !in 1..maxStrength) {
                        callback(
                            ControlResult(
                                false,
                                "Torch strength must be between 1 and $maxStrength"
                            )
                        )
                        return@post
                    }

                    builder.set(
                        CaptureRequest.FLASH_MODE,
                        CaptureRequest.FLASH_MODE_TORCH
                    )

                    if (Build.VERSION.SDK_INT >= 35 && maxStrength > 1) {
                        builder.set(
                            CaptureRequest.FLASH_STRENGTH_LEVEL,
                            strength
                        )
                    }

                    s.setRepeatingRequest(
                        builder.build(),
                        activeCaptureCallback,
                        cameraHandler
                    )

                    status = status.copy(
                        torchEnabled = true,
                        torchStrength = strength
                    )

                    callback(
                        ControlResult(
                            true,
                            "Torch on at level $strength/$maxStrength"
                        )
                    )
                } else {
                    builder.set(
                        CaptureRequest.FLASH_MODE,
                        CaptureRequest.FLASH_MODE_OFF
                    )

                    s.setRepeatingRequest(
                        builder.build(),
                        activeCaptureCallback,
                        cameraHandler
                    )

                    status = status.copy(
                        torchEnabled = false,
                        torchStrength = null
                    )

                    callback(ControlResult(true, "Torch off"))
                }
            } catch (e: Exception) {
                callback(
                    ControlResult(
                        false,
                        "Torch change failed: ${e.message}"
                    )
                )
            }
        }
    }

    fun setContinuousFocus(callback: (ControlResult) -> Unit = {}) {
        cameraHandler.post {
            val chars = activeCharacteristics; val builder = requestBuilder; val s = session
            if (!running || chars == null || builder == null || s == null) {
                callback(ControlResult(false, "Camera is not ready")); return@post
            }
            try {
                val modes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                val af = listOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE, CaptureRequest.CONTROL_AF_MODE_AUTO).firstOrNull { it in modes }
                    ?: CaptureRequest.CONTROL_AF_MODE_OFF
                builder.set(CaptureRequest.CONTROL_AF_MODE, af)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                s.setRepeatingRequest(builder.build(), activeCaptureCallback, cameraHandler)
                FocusMemory.clear(appContext)
                status = status.copy(
                    focusMode = "continuous",
                    autofocus = if (af == CaptureRequest.CONTROL_AF_MODE_OFF) "fixed_focus" else "continuous",
                    focusDistanceDiopters = null
                )
                callback(ControlResult(true, "Continuous autofocus enabled; saved focus lock cleared"))
            } catch (e: Exception) { callback(ControlResult(false, "Focus change failed: ${e.message}")) }
        }
    }

    fun setExposureCompensation(steps: Int, callback: (ControlResult) -> Unit = {}) {
        cameraHandler.post {
            val chars = activeCharacteristics
            val builder = requestBuilder
            val s = session

            if (!running || chars == null || builder == null || s == null) {
                callback(ControlResult(false, "Camera is not ready"))
                return@post
            }

            val range = chars.get(
                CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE
            )

            if (range == null) {
                callback(ControlResult(false, "AE compensation unavailable"))
                return@post
            }

            try {
                val value = steps.coerceIn(range.lower, range.upper)

                ExposureMemory.save(appContext, value)

                builder.set(
                    CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    value
                )

                s.setRepeatingRequest(
                    builder.build(),
                    activeCaptureCallback,
                    cameraHandler
                )

                callback(
                    ControlResult(
                        true,
                        "AE compensation set to $value steps"
                    )
                )
            } catch (e: Exception) {
                callback(
                    ControlResult(
                        false,
                        "Exposure change failed: ${e.message}"
                    )
                )
            }
        }
    }

    fun getSavedFocus(callback: (ControlResult) -> Unit = {}) {
        cameraHandler.post {
            val value = FocusMemory.loadLockedDistance(appContext)
            callback(
                ControlResult(
                    true,
                    value?.toString() ?: ""
                )
            )
        }
    }

    fun getSavedExposure(callback: (ControlResult) -> Unit = {}) {
        cameraHandler.post {
            callback(
                ControlResult(
                    true,
                    ExposureMemory.load(appContext).toString()
                )
            )
        }
    }

    fun setManualFocus(distanceDiopters: Float, callback: (ControlResult) -> Unit = {}) {
        cameraHandler.post {
            val builder = requestBuilder
            val s = session
            val min = status.minFocusDistanceDiopters ?: 0f

            if (!running || builder == null || s == null) {
                callback(ControlResult(false, "Camera is not ready"))
                return@post
            }

            if (min <= 0f) {
                callback(ControlResult(false, "Manual focus is not available"))
                return@post
            }

            try {
                val lockedDistance = distanceDiopters.coerceIn(0f, min)

                builder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF
                )
                builder.set(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    lockedDistance
                )

                s.setRepeatingRequest(
                    builder.build(),
                    activeCaptureCallback,
                    cameraHandler
                )

                FocusMemory.saveLocked(
                    appContext,
                    lockedDistance
                )

                status = status.copy(
                    focusMode = "locked",
                    autofocus = "locked",
                    focusDistanceDiopters = lockedDistance
                )

                callback(
                    ControlResult(
                        true,
                        "Manual focus set and saved at %.6f diopters".format(
                            lockedDistance
                        )
                    )
                )
            } catch (e: Exception) {
                callback(
                    ControlResult(
                        false,
                        "Manual focus failed: ${e.message}"
                    )
                )
            }
        }
    }

    fun lockCurrentFocus(callback: (ControlResult) -> Unit = {}) {
        cameraHandler.post {
            val builder = requestBuilder; val s = session
            val min = status.minFocusDistanceDiopters ?: 0f
            val distance = currentFocusDistance
            if (!running || builder == null || s == null) { callback(ControlResult(false, "Camera is not ready")); return@post }
            if (min <= 0f || distance == null) { callback(ControlResult(false, "Manual focus lock is not available yet")); return@post }
            try {
                val lockedDistance = distance.coerceIn(0f, min)
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, lockedDistance)
                s.setRepeatingRequest(builder.build(), activeCaptureCallback, cameraHandler)
                FocusMemory.saveLocked(appContext, lockedDistance)
                status = status.copy(
                    focusMode = "locked",
                    autofocus = "locked",
                    focusDistanceDiopters = lockedDistance
                )
                callback(ControlResult(true, "Focus locked and saved at %.3f diopters".format(lockedDistance)))
            } catch (e: Exception) { callback(ControlResult(false, "Focus lock failed: ${e.message}")) }
        }
    }

    private fun current(token: Int) = !closing.get() && token == generation

    private fun recover(message: String, error: Exception? = null, smallerSize: Boolean = false) {
        if (!running || closing.get() || retryPending) return
        Log.e(TAG, message, error)
        failures++
        if ((smallerSize || failures % 3 == 0) && sizeIndex < sizes.lastIndex) sizeIndex++
        retryPending = true
        stableSince = 0
        status = status.copy(state = "retrying", lastError = message, recoveries = status.recoveries + 1)
        closeSession()
        frames.invalidate()
        val delay = (1000L shl (failures - 1).coerceAtMost(5)).coerceAtMost(30_000)
        Log.w(TAG, "Retrying in ${delay}ms; size index=$sizeIndex")
        cameraHandler.postDelayed({ if (running && !closing.get()) openCamera() }, delay)
    }

    /**
     * Close the physical camera while keeping this controller reusable.
     * A later start() opens a fresh camera session.
     */
    fun pause() {
        cameraHandler.post {
            if (closing.get()) return@post
            running = false
            retryPending = false

            // Cancel only BugCam-owned scheduled work.
            // Do NOT erase Camera2 lifecycle callbacks: a late onOpened()
            // or onConfigured() must still run so it can close stale resources.
            cameraHandler.removeCallbacks(watchdog)
            cameraHandler.removeCallbacks(openFailsafe)

            closeSession()
            status = status.copy(
                state = "idle",
                torchEnabled = false,
                torchStrength = null
            )
        }
    }

    private fun closeSession() {
        generation++ // Reject callbacks and JPEG results belonging to the previous camera.
        try { session?.close() } catch (e: Exception) { Log.w(TAG, "Session close", e) }
        session = null
        requestBuilder = null
        activeCharacteristics = null
        activeCaptureCallback = null
        activeSensorFps = 0
        try { device?.close() } catch (e: Exception) { Log.w(TAG, "Device close", e) }
        device = null
        val retired = reader
        reader = null
        retired?.setOnImageAvailableListener(null, null)
        // Keep its native buffers valid until the outstanding encoder image is closed.
        if (retired != null) encoder.execute { retired.close() }
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        cameraHandler.post {
            running = false
            cameraHandler.removeCallbacksAndMessages(null)
            closeSession()
            encoder.shutdown() // Drains the image/reader teardown tasks in order.
            status = status.copy(state = "stopped")
            thread.quitSafely()
        }
    }

    companion object { private const val TAG = "BugCam-Camera" }
}
