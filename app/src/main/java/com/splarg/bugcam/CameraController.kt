package com.splarg.bugcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.SystemClock
import android.os.HandlerThread
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
    val torchStrength: Int? = null, // Requested level (null = off).
    val positioning: Boolean = false, // Full-frame 4:3 positioning preview active (/live).
    val torchFlashState: String? = null, // HAL FLASH_STATE from the latest preview result.
    val torchLit: Boolean = false, // Latest result: FLASH_MODE_TORCH and FLASH_STATE_FIRED.
    val torchCurrentStrength: Int? = null, // HAL FLASH_STRENGTH_LEVEL while lit (API 35+).
    val focusMode: String = "continuous",
    val focusDistanceDiopters: Float? = null,
    val minFocusDistanceDiopters: Float? = null,
    val physicalCameraId: String? = null,
    val stillWidth: Int = 0,
    val stillHeight: Int = 0,
    val snapshotBusy: Boolean = false,
    val lastStillError: String? = null,
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
    private var lifecycleCallbacksPending = 0
    private var teardownComplete = false
    private var retryPending = false
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var stillReader: ImageReader? = null
    private var sizes = emptyList<Size>()
    private var sizeIndex = 0
    /**
     * Positioning (live) view: while true the camera opens with a full-frame 4:3 preview instead of the
     * normal 16:9 one (which the HAL crops to the timelapse band), so /live can show what is trimmed.
     * Stills are unaffected: they always use the separate full-resolution JPEG stream.
     */
    @Volatile private var positioning = false
    @Volatile private var positioningUntil = 0L // SystemClock.elapsedRealtime() deadline.
    private var positioningSizes = emptyList<Size>()
    private var stillSizes = emptyList<Size>()
    private var stillSizeIndex = 0
    private var failures = 0
    private var requestFailures = 0
    private var attemptStarted = 0L
    private var stableSince = 0L
    private var requestBuilder: CaptureRequest.Builder? = null
    private var activeCharacteristics: CameraCharacteristics? = null
    private var physicalCharacteristics: CameraCharacteristics? = null
    private var physicalRequestKeys = emptySet<CaptureRequest.Key<*>>()
    private var activeCaptureCallback: CameraCaptureSession.CaptureCallback? = null
    private var previewAfMode = CaptureRequest.CONTROL_AF_MODE_OFF
    private var manualFocusDistance: Float? = null
    private var lastGoodFocusDistance: Float? = null
    /** Torch level requested for this camera-on episode (null = off); re-applied if the session is rebuilt. */
    private var requestedTorchStrength: Int? = null
    private var pendingStill: PendingStill? = null
    private var stillSequence = 0L
    @Volatile private var currentFocusDistance: Float? = null
    @Volatile private var activeSensorFps: Int = 0
    @Volatile private var lastJpeg = 0L
    private var lastAccepted = 0L
    // Reused only by the encoder worker.
    private var packed = ByteArray(0)
    private var rotated = ByteArray(0)
    private val jpegOutput = ByteArrayOutputStream(512 * 1024)

    fun start() {
        cameraHandler.post { startOnHandler() }
    }

    private fun startOnHandler() {
        if (running || closing.get()) return
        running = true

        cameraHandler.removeCallbacks(watchdog)
        cameraHandler.removeCallbacks(openFailsafe)

        openCamera()

        cameraHandler.postDelayed(watchdog, 2000)
        extendOpenFailsafe()
    }

    /**
     * /live/start: camera on with the full-frame positioning preview. From idle it opens directly; if the
     * camera is already running with the normal preview it is reopened (not during a still). Each
     * /live.jpg fetch keeps it open via positioningKeepalive(); otherwise the normal failsafe closes it.
     */
    fun startPositioning(callback: (ControlResult) -> Unit) {
        if (closing.get() || !cameraHandler.post {
                if (closing.get()) { callback(ControlResult(false, "Camera controller stopped")); return@post }
                if (pendingStill != null) {
                    callback(ControlResult(false, "Still capture in progress")); return@post
                }
                positioningUntil = SystemClock.elapsedRealtime() + POSITIONING_MAX_MILLIS
                val message = when {
                    !running -> {
                        setPositioning(true)
                        startOnHandler()
                        "Positioning view starting"
                    }
                    positioning -> {
                        extendOpenFailsafe()
                        "Positioning view active; time limit reset"
                    }
                    else -> {
                        setPositioning(true)
                        Log.i(TAG, "Reopening camera with the full-frame positioning preview")
                        closeSession("Switching to positioning preview")
                        frames.invalidate()
                        val reopenGeneration = generation
                        cameraHandler.postDelayed({
                            if (running && positioning && !closing.get() && generation == reopenGeneration) openCamera()
                        }, POSITIONING_REOPEN_DELAY_MILLIS)
                        extendOpenFailsafe()
                        "Switching to positioning view"
                    }
                }
                callback(ControlResult(true, "$message (limit ${POSITIONING_MAX_MILLIS / 60_000} min)"))
            }) callback(ControlResult(false, "Camera controller stopped"))
    }

    /** HTTP thread (/live.jpg): null = not active; <= 0 = time limit reached (no longer kept open). */
    fun positioningKeepalive(): Long? {
        val remaining = positioningRemainingMillis() ?: return null
        if (remaining > 0) cameraHandler.post { if (positioning && running) extendOpenFailsafe() }
        return remaining
    }

    fun positioningRemainingMillis(): Long? =
        if (!positioning) null else positioningUntil - SystemClock.elapsedRealtime()

    private fun setPositioning(enabled: Boolean) {
        positioning = enabled
        status = status.copy(positioning = enabled)
    }

    /**
     * (Re)arm the camera-open failsafe: the camera closes OPEN_FAILSAFE_MILLIS after the most recent
     * camera command (/camera/on, torch, still completion), not a fixed time after /camera/on, so an
     * actively driven night sequence (on -> torch -> settle -> AF still -> off) is not cut short.
     */
    private fun extendOpenFailsafe() {
        if (!running || closing.get()) return
        cameraHandler.removeCallbacks(openFailsafe)
        cameraHandler.postDelayed(openFailsafe, OPEN_FAILSAFE_MILLIS)
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
            if (pendingStill != null) {
                // A still has its own STILL_TIMEOUT_MILLIS deadline; re-check once it has finished.
                cameraHandler.postDelayed(this, 1000)
                return
            }

            Log.w(TAG, "Camera-open failsafe: no camera command for ${OPEN_FAILSAFE_MILLIS / 1000}s; closing camera and torch")
            requestedTorchStrength = null
            positioning = false

            running = false
            retryPending = false

            cameraHandler.removeCallbacks(watchdog)

            closeSession()

            status = status.copy(
                state = "idle",
                torchEnabled = false,
                torchStrength = null,
                positioning = false
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
            val physicalCameraId = PHYSICAL_CAMERA_ID
            check(Build.VERSION.SDK_INT >= 28 &&
                physicalCameraId in characteristics.physicalCameraIds) {
                "Rear logical camera $id does not expose physical camera $physicalCameraId"
            }
            val physicalCharacteristics =
                manager.getCameraCharacteristics(physicalCameraId)

            if (sizes.isEmpty()) {
                val map = physicalCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: error("Camera has no stream configuration map")
                val all = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
                    .filter { it.width % 2 == 0 && it.height % 2 == 0 }
                // Keep MJPEG/3A lightweight; the separate JPEG surface determines still resolution.
                val bounded = all.filter { it.width <= minOf(config.width, 1280) &&
                    it.height <= minOf(config.height, 720) }
                // Exact preference first, then smaller sizes close to the requested aspect ratio.
                sizes = bounded.sortedWith(compareBy<Size> {
                    abs(it.width.toDouble() / it.height - config.width.toDouble() / config.height) > 0.04
                }.thenByDescending { it.width.toLong() * it.height })
                    .ifEmpty { all.sortedBy { it.width.toLong() * it.height }.take(1) }
                check(sizes.isNotEmpty()) { "Rear camera exposes no YUV_420_888 sizes" }
            }
            if (positioning && positioningSizes.isEmpty()) {
                val map = physicalCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                positioningSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
                    .filter { it.width % 2 == 0 && it.height % 2 == 0 && it.width <= 1280 && it.height <= 960 &&
                        abs(it.width.toDouble() / it.height - 4.0 / 3.0) < 0.02 }
                    .sortedByDescending { it.width.toLong() * it.height }
                if (positioningSizes.isEmpty()) Log.w(TAG, "No 4:3 YUV preview <= 1280x960; positioning " +
                    "uses the normal preview and /live will warn that the overlay cannot be shown")
            }
            val size = if (positioning && positioningSizes.isNotEmpty())
                positioningSizes[sizeIndex.coerceAtMost(positioningSizes.lastIndex)]
            else sizes[sizeIndex.coerceAtMost(sizes.lastIndex)]
            if (stillSizes.isEmpty()) {
                val map = physicalCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: error("Physical camera has no stream configuration map")
                val native = physicalCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                stillSizes = (map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty() +
                    map.getHighResolutionOutputSizes(ImageFormat.JPEG)?.toList().orEmpty())
                    .distinct()
                    .filter { abs(it.width.toDouble() / it.height - 4.0 / 3.0) < 0.015 &&
                        (native == null || (it.width <= native.width && it.height <= native.height)) }
                    .sortedByDescending { it.width.toLong() * it.height }
                check(stillSizes.isNotEmpty()) { "Physical camera 3 exposes no native 4:3 JPEG size" }
            }
            val stillSize = stillSizes[stillSizeIndex.coerceAtMost(stillSizes.lastIndex)]
            status = status.copy(state = "opening", cameraId = id, width = size.width,
                height = size.height,
                physicalCameraId = physicalCameraId,
                stillWidth = stillSize.width, stillHeight = stillSize.height,
                sensorOrientation = physicalCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                torchAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                torchMaxStrength = if (Build.VERSION.SDK_INT >= 35)
                    characteristics.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL) ?: 1
                else 1,
                torchDefaultStrength = if (Build.VERSION.SDK_INT >= 35)
                    characteristics.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_DEFAULT_LEVEL) ?: 1
                else 1,
                minFocusDistanceDiopters = physicalCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE))
            activeCharacteristics = characteristics
            this.physicalCharacteristics = physicalCharacteristics
            physicalRequestKeys = characteristics.availablePhysicalCameraRequestKeys?.toSet().orEmpty()
            Log.i(TAG, "Opening logical=$id physical=$physicalCameraId preview=$size positioning=$positioning " +
                "JPEG=$stillSize quality=$STILL_JPEG_QUALITY zoom=1.0 generation=$token")
            Log.i(TAG, "Physical request keys: ${physicalRequestKeys.map { it.name }}")
            val newReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
            reader = newReader
            newReader.setOnImageAvailableListener({ source -> onImage(source, token) }, cameraHandler)
            val jpegReader = ImageReader.newInstance(stillSize.width, stillSize.height, ImageFormat.JPEG, 2)
            stillReader = jpegReader
            jpegReader.setOnImageAvailableListener({ source -> onStillImage(source, token) }, cameraHandler)
            val completeOpen = trackLifecycleCallback()
            try { manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    completeOpen()
                    if (!current(token)) { camera.close(); return }
                    device = camera
                    configure(camera, newReader, jpegReader, characteristics, physicalCharacteristics, token)
                }
                override fun onClosed(camera: CameraDevice) {
                    Log.i(TAG, "Camera device fully closed")
                }

                override fun onDisconnected(camera: CameraDevice) {
                    completeOpen()
                    camera.close()
                    if (current(token)) recover("Rear camera disconnected")
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    completeOpen()
                    camera.close()
                    if (current(token)) recover("Camera device error $error")
                }
            }, cameraHandler) } catch (e: Exception) { completeOpen(); throw e }
        } catch (e: Exception) {
            recover("Cannot open rear camera: ${e.message}", e)
        }
    }

    @Suppress("DEPRECATION") // The handler overload keeps all callbacks on one camera thread.
    private fun configure(
        camera: CameraDevice, output: ImageReader, jpegOutput: ImageReader, chars: CameraCharacteristics,
        physicalChars: CameraCharacteristics, token: Int,
    ) {
        val completeConfiguration = trackLifecycleCallback()
        try {
            status = status.copy(state = "configuring")
            val physicalOutput = OutputConfiguration(output.surface).apply {
                setPhysicalCameraId(PHYSICAL_CAMERA_ID)
            }
            val jpegConfiguration = OutputConfiguration(jpegOutput.surface).apply {
                setPhysicalCameraId(PHYSICAL_CAMERA_ID)
            }
            camera.createCaptureSessionByOutputConfigurations(
                listOf(physicalOutput, jpegConfiguration),
                object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(newSession: CameraCaptureSession) {
                    completeConfiguration()
                    if (!current(token)) { newSession.close(); return }
                    session = newSession
                    try {
                        val request = camera.createCaptureRequest(
                            CameraDevice.TEMPLATE_PREVIEW,
                            setOf(PHYSICAL_CAMERA_ID)
                        ).apply {
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
                            applyFullFrame(this, chars, physicalChars)
                            // Recovery rebuilds the session: keep the torch the controller asked for.
                            val torch = requestedTorchStrength
                                ?.takeIf { chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                            if (torch != null) {
                                applyTorch(this, torch)
                                Log.i(TAG, "Re-applying requested torch level $torch to the new session")
                            }
                            status = status.copy(torchEnabled = torch != null, torchStrength = torch)
                            val afModes = physicalChars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                                ?: intArrayOf(CaptureRequest.CONTROL_AF_MODE_OFF)
                            val minFocus = physicalChars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                            val savedFocus = FocusMemory.loadLockedDistance(appContext)?.takeIf {
                                minFocus > 0f && CaptureRequest.CONTROL_AF_MODE_OFF in afModes
                            }?.coerceIn(0f, minFocus)
                            val af = if (savedFocus != null) {
                                CaptureRequest.CONTROL_AF_MODE_OFF
                            } else {
                                listOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                                    CaptureRequest.CONTROL_AF_MODE_MACRO,
                                    CaptureRequest.CONTROL_AF_MODE_AUTO).firstOrNull { it in afModes }
                                    ?: CaptureRequest.CONTROL_AF_MODE_OFF
                            }
                            previewAfMode = af
                            manualFocusDistance = savedFocus
                            if (savedFocus != null) lastGoodFocusDistance = savedFocus
                            applyFocus(this, af, savedFocus)
                            val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                                ?.toList().orEmpty()
                            val range = chooseAeRange(ranges)
                            if (range != null) set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                            activeSensorFps = range?.upper ?: 0
                            status = status.copy(
                                autofocus = if (savedFocus != null) "locked" else when (af) {
                                    CaptureRequest.CONTROL_AF_MODE_OFF -> "fixed_focus"
                                    CaptureRequest.CONTROL_AF_MODE_AUTO, CaptureRequest.CONTROL_AF_MODE_MACRO -> "auto"
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
                                if (current(token)) {
                                    val physical = physicalResult(result)
                                    currentFocusDistance = physical?.get(CaptureResult.LENS_FOCUS_DISTANCE)
                                    val afState = physical?.get(CaptureResult.CONTROL_AF_STATE)
                                    if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                                        afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED) {
                                        lastGoodFocusDistance = currentFocusDistance
                                    }
                                    noteTorchResult(result)
                                    pendingStill?.let { pending ->
                                        if (r.tag === pending) pending.firstFrame = result.frameNumber
                                        advanceStill(pending, result, physical)
                                    }
                                }
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
                            setCameraKey(request, CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                            newSession.capture(request.build(), null, cameraHandler)
                            setCameraKey(request, CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                        }
                        status = status.copy(state = "warming_up")
                        Log.i(TAG, "Session ready; AE=${status.aeRange}, AF=${status.autofocus}")
                        pendingStill?.let(::beginStill)
                    } catch (e: Exception) {
                        recover("Starting repeating capture failed: ${e.message}", e)
                    }
                }
                override fun onConfigureFailed(failed: CameraCaptureSession) {
                    completeConfiguration()
                    failed.close()
                    if (current(token)) recover("Camera session configuration failed", smallerSize = true)
                }
            }, cameraHandler)
        } catch (e: Exception) {
            completeConfiguration()
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

    /** Global settings are inherited; only advertised keys may override physical camera 3. */
    private fun <T> setCameraKey(builder: CaptureRequest.Builder, key: CaptureRequest.Key<T>, value: T) {
        builder.set(key, value)
        if (key in physicalRequestKeys) builder.setPhysicalCameraKey(key, value, PHYSICAL_CAMERA_ID)
    }

    /**
     * Torch while Camera2 owns the device: FLASH_MODE_TORCH (+ FLASH_STRENGTH_LEVEL on API 35+) in the
     * capture request, with AE_MODE ON/OFF. CameraManager.setTorchMode()/turnOnTorchWithStrengthLevel()
     * are for when no app has the camera open. [strength] null = torch off.
     */
    private fun applyTorch(builder: CaptureRequest.Builder, strength: Int?) {
        builder.set(CaptureRequest.FLASH_MODE,
            if (strength == null) CaptureRequest.FLASH_MODE_OFF else CaptureRequest.FLASH_MODE_TORCH)
        if (strength != null && Build.VERSION.SDK_INT >= 35 && status.torchMaxStrength > 1) {
            builder.set(CaptureRequest.FLASH_STRENGTH_LEVEL, strength)
        }
    }

    private fun applyFocus(builder: CaptureRequest.Builder, mode: Int, distance: Float? = null) {
        setCameraKey(builder, CaptureRequest.CONTROL_AF_MODE, mode)
        setCameraKey(builder, CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        if (mode == CaptureRequest.CONTROL_AF_MODE_OFF && distance != null) {
            setCameraKey(builder, CaptureRequest.LENS_FOCUS_DISTANCE, distance)
        }
    }

    private fun applyFullFrame(builder: CaptureRequest.Builder, logical: CameraCharacteristics,
        physical: CameraCharacteristics) {
        if (Build.VERSION.SDK_INT >= 30 &&
            logical.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.contains(1.0f) == true) {
            setCameraKey(builder, CaptureRequest.CONTROL_ZOOM_RATIO, 1.0f)
        }
        val distortion = builder.get(CaptureRequest.DISTORTION_CORRECTION_MODE)
        val arrayKey = if (distortion == CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
            CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE
        else CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE
        (logical.get(arrayKey) ?: logical.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE))?.let {
            builder.set(CaptureRequest.SCALER_CROP_REGION, it)
        }
        if (CaptureRequest.SCALER_CROP_REGION in physicalRequestKeys) {
            (physical.get(arrayKey) ?: physical.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE))?.let {
                builder.setPhysicalCameraKey(CaptureRequest.SCALER_CROP_REGION, it, PHYSICAL_CAMERA_ID)
            }
        }
        if (Build.VERSION.SDK_INT >= 31 &&
            logical.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)
                ?.contains(CaptureRequest.SCALER_ROTATE_AND_CROP_NONE) == true) {
            setCameraKey(builder, CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)
        }
        setCameraKey(builder, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
    }

    /** HAL-reported torch state for /health; the flash belongs to the logical camera's result. */
    private fun noteTorchResult(result: CaptureResult) {
        val flashState = when (result.get(CaptureResult.FLASH_STATE)) {
            null -> null
            CaptureResult.FLASH_STATE_FIRED -> "fired"
            CaptureResult.FLASH_STATE_READY -> "ready"
            CaptureResult.FLASH_STATE_CHARGING -> "charging"
            CaptureResult.FLASH_STATE_PARTIAL -> "partial"
            CaptureResult.FLASH_STATE_UNAVAILABLE -> "unavailable"
            else -> "unknown"
        }
        val lit = result.get(CaptureResult.FLASH_MODE) == CaptureResult.FLASH_MODE_TORCH &&
            flashState == "fired"
        val strength = if (lit) {
            if (Build.VERSION.SDK_INT >= 35) result.get(CaptureResult.FLASH_STRENGTH_LEVEL) else null
        } else null
        if (flashState != status.torchFlashState || lit != status.torchLit || strength != status.torchCurrentStrength) {
            status = status.copy(torchFlashState = flashState, torchLit = lit, torchCurrentStrength = strength)
        }
    }

    @Suppress("DEPRECATION")
    private fun physicalResult(result: TotalCaptureResult): CaptureResult? =
        if (Build.VERSION.SDK_INT >= 31) result.physicalCameraTotalResults[PHYSICAL_CAMERA_ID]
        else result.physicalCameraResults[PHYSICAL_CAMERA_ID]

    /**
     * ARMING: repeating request switched to MACRO/AUTO; waiting for results to confirm the switch
     * before the one-shot START trigger is sent (a trigger in the mode-switch frame can be lost).
     * FOCUSING: START submitted; waiting for a terminal AF state on physical camera 3.
     */
    private enum class StillPhase { WAITING_FOR_SESSION, ARMING, FOCUSING, SETTLING, CAPTURING }

    /** Handler-owned state; images are copied and closed before any HTTP worker sees their bytes. */
    private class PendingStill(
        val future: CompletableFuture<SnapshotResult>,
        val manualExposure: ManualExposure? = null, // Target after clamping to camera 3; null = auto exposure.
        val manualExposureRequested: ManualExposure? = null, // As sent over HTTP.
        val fixedFocusRequested: Float? = null, // Per-still focus_diopters as sent (null = normal focus).
        val fixedFocusApplied: Float? = null, // Same, clamped to camera 3's lens range.
    ) {
        var sequence = 0L
        var phase = StillPhase.WAITING_FOR_SESSION
        var afMode = CaptureRequest.CONTROL_AF_MODE_OFF
        var manualDistance: Float? = null
        var firstFrame = Long.MAX_VALUE
        var settledFrames = 0
        var focusReady = false
        var focusReason = ""
        // Autofocus sequencing and diagnostics (camera handler only).
        var armedFrames = 0
        var triggerAttempts = 0
        var triggerResends = 0
        var notFocusedRetries = 0
        var inactiveFramesAfterTrigger = 0
        var lastAfState: Int? = null
        var lastAfSource = ""
        var lastResultAfMode: Int? = null
        var lastLensState: Int? = null
        var lastFocusDistance: Float? = null
        // Still result exposure (reported for auto and manual stills) and manual-mode audit.
        var resultExposureNs: Long? = null
        var resultIso: Int? = null
        var manualFrameDurationNs: Long? = null
        var manualHonoured: Boolean? = null
        var previewManualApplied = false // Repeating request switched to the manual exposure.
        var previewAeModeBefore: Int? = null // Restored on the repeating request after the JPEG.
        var manualSeenInPreview = false
        var focusMode = "" // fixed | autofocus | manual | autofocus-fallback | fixed-lens (response header).
        var resultFocusDistance: Float? = null
        var metadataReceived = false
        val timestamps = mutableSetOf<Long>()
        var imageTimestamp: Long? = null
        var jpeg: JpegFrame? = null
        var unixMillis = 0L
        var timeout: Runnable? = null
        var focusTimeout: Runnable? = null
        var settleTimeout: Runnable? = null
    }

    /**
     * Called only by an HTTP worker. All Camera2 work stays on cameraHandler.
     * [manual] null = the unchanged automatic still. Otherwise the values are clamped to camera 3's
     * ranges; focus (AF or fixed) is found first, then the repeating request switches to AE off with
     * those values, the settle waits until results show them, and the JPEG uses the same values.
     */
    fun captureStill(manual: ManualExposure? = null, focusDiopters: Float? = null): SnapshotResult {
        val manualTarget = try { manual?.let(::clampManualExposure) } catch (e: StillRejected) { return e.response }
        val extraWaitMillis = manualTarget?.extraWaitMillis() ?: 0L
        // Per-still fixed focus: validate and clamp to camera 3's lens range before touching the camera.
        val fixedFocus = focusDiopters?.let { requested ->
            val chars = try { manager.getCameraCharacteristics(PHYSICAL_CAMERA_ID) } catch (e: Exception) {
                return SnapshotResult(503, message = "Cannot read camera $PHYSICAL_CAMERA_ID characteristics: ${e.message}")
            }
            val maxDiopters = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            if (maxDiopters <= 0f || CaptureRequest.CONTROL_AF_MODE_OFF !in afModes) {
                return SnapshotResult(501, message = "Camera $PHYSICAL_CAMERA_ID does not support a manual " +
                    "focus distance (LENS_INFO_MINIMUM_FOCUS_DISTANCE=$maxDiopters)")
            }
            requested.coerceIn(0f, maxDiopters).also {
                if (it != requested) Log.w(TAG, "focus_diopters=$requested clamped to $it " +
                    "(camera $PHYSICAL_CAMERA_ID range 0..$maxDiopters D)")
            }
        }
        val pending = PendingStill(CompletableFuture(), manualTarget, manual, focusDiopters, fixedFocus)
        if (closing.get() || !cameraHandler.post {
                if (pending.future.isCancelled) return@post
                if (!running || closing.get()) {
                    pending.future.complete(SnapshotResult(503, message = "Camera is off; call /camera/on first"))
                } else if (pendingStill != null) {
                    pending.future.complete(SnapshotResult(409, message = "A still capture is already in progress"))
                } else {
                    pending.sequence = ++stillSequence
                    pendingStill = pending
                    status = status.copy(snapshotBusy = true, lastStillError = null)
                    pending.timeout = Runnable { abortStill(pending, "Still capture timed out") }.also {
                        cameraHandler.postDelayed(it, STILL_TIMEOUT_MILLIS + extraWaitMillis)
                    }
                    beginStill(pending) // May wait for asynchronous /camera/on configuration.
                }
            }) return SnapshotResult(503, message = "Camera controller stopped")
        return try {
            pending.future.get(STILL_TIMEOUT_MILLIS + extraWaitMillis + 2000, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            pending.future.cancel(false)
            cameraHandler.post { abortStill(pending, "HTTP still wait timed out") }
            SnapshotResult(503, message = "Still capture timed out")
        } catch (_: InterruptedException) {
            pending.future.cancel(false)
            cameraHandler.post { abortStill(pending, "HTTP still wait interrupted") }
            Thread.currentThread().interrupt()
            SnapshotResult(503, message = "Still capture interrupted")
        }
    }

    private class StillRejected(val response: SnapshotResult) : Exception(response.message)

    /**
     * HTTP worker thread, before any camera state is touched: clamp to camera 3's advertised exposure
     * and ISO ranges (exposure also to ManualExposure.MAX_EXPOSURE_NS). Unsupported -> 501.
     */
    private fun clampManualExposure(m: ManualExposure): ManualExposure {
        val chars = try { manager.getCameraCharacteristics(PHYSICAL_CAMERA_ID) } catch (e: Exception) {
            throw StillRejected(SnapshotResult(503, message = "Cannot read camera $PHYSICAL_CAMERA_ID characteristics: ${e.message}"))
        }
        val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val aeModes = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES) ?: intArrayOf()
        if (exposureRange == null || isoRange == null || CaptureRequest.CONTROL_AE_MODE_OFF !in aeModes) {
            throw StillRejected(SnapshotResult(501, message = "Camera $PHYSICAL_CAMERA_ID does not advertise manual " +
                "exposure (AE_MODE_OFF=${CaptureRequest.CONTROL_AE_MODE_OFF in aeModes}, " +
                "exposureRange=$exposureRange, isoRange=$isoRange)"))
        }
        val maxNs = minOf(exposureRange.upper, ManualExposure.MAX_EXPOSURE_NS,
            chars.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: Long.MAX_VALUE)
        val target = ManualExposure(m.exposureNs.coerceIn(minOf(exposureRange.lower, maxNs), maxNs),
            m.iso.coerceIn(isoRange.lower, isoRange.upper))
        if (target != m) Log.w(TAG, "Manual exposure ${m.exposureNs}ns ISO ${m.iso} clamped to " +
            "${target.exposureNs}ns ISO ${target.iso} (camera $PHYSICAL_CAMERA_ID: ${exposureRange.lower}..${maxNs}ns, " +
            "ISO ${isoRange.lower}..${isoRange.upper})")
        return target
    }

    /** AE off plus exposure, ISO and a frame duration long enough for both and for [output]'s stream. */
    private fun applyManualExposure(builder: CaptureRequest.Builder, m: ManualExposure, output: ImageReader): Long {
        val minFrameNs = try {
            physicalCharacteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputMinFrameDuration(output.imageFormat, Size(output.width, output.height))
        } catch (_: IllegalArgumentException) { null } ?: 0L
        val frameDurationNs = maxOf(m.exposureNs, minFrameNs)
        setCameraKey(builder, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        setCameraKey(builder, CaptureRequest.SENSOR_EXPOSURE_TIME, m.exposureNs)
        setCameraKey(builder, CaptureRequest.SENSOR_SENSITIVITY, m.iso)
        setCameraKey(builder, CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)
        return frameDurationNs
    }

    private fun manualValuesMatch(m: ManualExposure, ns: Long?, iso: Int?): Boolean =
        ns != null && iso != null &&
            abs(ns - m.exposureNs) <= maxOf(MANUAL_EXPOSURE_TOLERANCE_NS, m.exposureNs / 20) &&
            abs(iso - m.iso) <= maxOf(1, m.iso / 20)

    /** The HAL may clamp, quantise or ignore manual values: compare the (clamped) target with the result. */
    private fun auditManualExposure(pending: PendingStill, m: ManualExposure, physical: CaptureResult?,
                                    logical: CaptureResult) {
        val ns = pending.resultExposureNs
        val iso = pending.resultIso
        val aeMode = physical?.get(CaptureResult.CONTROL_AE_MODE) ?: logical.get(CaptureResult.CONTROL_AE_MODE)
        val honoured = aeMode == CaptureResult.CONTROL_AE_MODE_OFF && manualValuesMatch(m, ns, iso)
        pending.manualHonoured = honoured
        val message = "Still #${pending.sequence}: manual exposure target=${m.exposureNs}ns ISO ${m.iso}; " +
            "result=${ns}ns ISO $iso aeMode=$aeMode " +
            "postRawBoost=${physical?.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)} " +
            "frameDuration=${physical?.get(CaptureResult.SENSOR_FRAME_DURATION)} -> " +
            if (honoured) "honoured" else "NOT HONOURED by HAL (JPEG still returned; see X-Still-* headers)"
        if (honoured) Log.i(TAG, message) else Log.w(TAG, message)
    }

    /** Additive response headers; the automatic path reports what AE chose. */
    private fun stillHeaders(pending: PendingStill): String = buildString {
        val target = pending.manualExposure
        val requested = pending.manualExposureRequested
        append("X-Still-Exposure-Mode: ${if (target == null) "auto" else "manual"}\r\n")
        append("X-Still-Exposure-Ns: ${pending.resultExposureNs ?: "unknown"}\r\n")
        append("X-Still-ISO: ${pending.resultIso ?: "unknown"}\r\n")
        // Applied = what the JPEG's capture result reports; the target only if the metadata is missing.
        val fromResult = pending.resultExposureNs != null && pending.resultIso != null
        append("X-Exposure-Applied-Ns: ${pending.resultExposureNs ?: target?.exposureNs ?: "unknown"}\r\n")
        append("X-ISO-Applied: ${pending.resultIso ?: target?.iso ?: "unknown"}\r\n")
        append("X-Exposure-Applied-Source: ${when {
            fromResult -> "capture-result"; target != null -> "request"; else -> "unavailable" }}\r\n")
        if (target != null && requested != null) {
            append("X-Exposure-Requested-Ns: ${requested.exposureNs}\r\n")
            append("X-ISO-Requested: ${requested.iso}\r\n")
            append("X-Exposure-Target-Ns: ${target.exposureNs}\r\n")
            append("X-ISO-Target: ${target.iso}\r\n")
            append("X-Exposure-Clamped: ${target != requested}\r\n")
            append("X-Still-Requested-Exposure-Ns: ${requested.exposureNs}\r\n")
            append("X-Still-Requested-ISO: ${requested.iso}\r\n")
            append("X-Still-Manual-Honoured: ${pending.manualHonoured ?: false}\r\n")
        }
        append("X-Focus-Mode: ${pending.focusMode.ifEmpty { "unknown" }}\r\n")
        append("X-Focus-Result-Diopters: ${pending.resultFocusDistance ?: "unknown"}\r\n")
        if (pending.fixedFocusRequested != null) {
            append("X-Focus-Requested-Diopters: ${pending.fixedFocusRequested}\r\n")
            append("X-Focus-Applied-Diopters: ${pending.fixedFocusApplied}\r\n")
            append("X-Focus-Clamped: ${pending.fixedFocusApplied != pending.fixedFocusRequested}\r\n")
        }
    }

    private fun beginStill(pending: PendingStill) {
        if (pendingStill !== pending || pending.phase != StillPhase.WAITING_FOR_SESSION) return
        val s = session ?: return
        val builder = requestBuilder ?: return
        val physical = physicalCharacteristics ?: return
        try {
            val modes = physical.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            val manual = manualFocusDistance
            val af = listOf(CaptureRequest.CONTROL_AF_MODE_MACRO, CaptureRequest.CONTROL_AF_MODE_AUTO)
                .firstOrNull { it in modes }
            when {
                pending.fixedFocusApplied != null -> {
                    pending.focusMode = "fixed"
                    // Same settle as saved manual focus: AF off + LENS_FOCUS_DISTANCE on the repeating
                    // request; the JPEG is submitted once the lens is stationary at the target distance.
                    beginManualStill(pending, pending.fixedFocusApplied,
                        "per-request focus_diopters=${pending.fixedFocusRequested}")
                }
                manual != null -> {
                    pending.focusMode = "manual"
                    beginManualStill(pending, manual, "explicit saved/manual focus")
                }
                af != null -> {
                    pending.focusMode = "autofocus"
                    pending.afMode = af
                    pending.phase = StillPhase.ARMING
                    pending.armedFrames = 0
                    Log.i(TAG, "Still #${pending.sequence}: AF arming physical=$PHYSICAL_CAMERA_ID " +
                        "mode=${afModeName(af)} (preview was ${afModeName(previewAfMode)}); " +
                        "physical keys: AF_MODE=${CaptureRequest.CONTROL_AF_MODE in physicalRequestKeys} " +
                        "AF_TRIGGER=${CaptureRequest.CONTROL_AF_TRIGGER in physicalRequestKeys}; " +
                        "focusCalibration=${physical.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)}")
                    // Step 1: switch the repeating request to MACRO/AUTO (one-shot CANCEL clears any old
                    // scan/lock). START is NOT sent here: it is sent by advanceStill() only after results
                    // confirm the new mode is active, so the trigger cannot be lost in the mode switch.
                    applyFocus(builder, af)
                    setCameraKey(builder, CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                    submitFocusRequest(pending, builder, s) // Tagged CANCEL, then repeating af + IDLE.
                    pending.focusTimeout = Runnable { armTimedOut(pending) }.also {
                        cameraHandler.postDelayed(it, AF_ARM_TIMEOUT_MILLIS)
                    }
                }
                (status.minFocusDistanceDiopters ?: 0f) == 0f -> {
                    pending.focusMode = "fixed-lens"
                    beginManualStill(pending, null, "fixed-focus camera")
                }
                else -> fallbackFocus(pending, "Triggered AF unavailable")
            }
        } catch (e: Exception) { abortStill(pending, "Starting still focus failed: ${e.message}") }
    }

    private fun submitFocusRequest(pending: PendingStill, builder: CaptureRequest.Builder, s: CameraCaptureSession) {
        pending.firstFrame = Long.MAX_VALUE
        builder.setTag(pending)
        try { s.capture(builder.build(), activeCaptureCallback, cameraHandler) }
        finally {
            builder.setTag(null)
            setCameraKey(builder, CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        }
        s.setRepeatingRequest(builder.build(), activeCaptureCallback, cameraHandler)
    }

    private fun armTimedOut(pending: PendingStill) {
        if (pendingStill !== pending || pending.phase != StillPhase.ARMING) return
        Log.w(TAG, "Still #${pending.sequence}: AF mode switch not confirmed within ${AF_ARM_TIMEOUT_MILLIS}ms " +
            "(result mode=${afModeName(pending.lastResultAfMode)}, AF state=${afStateName(pending.lastAfState)}); " +
            "sending trigger anyway")
        triggerAutofocus(pending, "arm timeout", startAfTimer = true)
    }

    /**
     * Step 2: one-shot START on the already-repeating MACRO/AUTO request. The repeating request stays
     * af + IDLE, so a lock is held (never reverted to continuous AF) until the JPEG has been captured.
     * Resends/retries reuse the original AF deadline, so the total wait remains bounded.
     */
    private fun triggerAutofocus(pending: PendingStill, reason: String, startAfTimer: Boolean) {
        if (pendingStill !== pending) return
        val s = session
        val builder = requestBuilder
        if (s == null || builder == null) { abortStill(pending, "AF trigger: session unavailable"); return }
        try {
            pending.phase = StillPhase.FOCUSING
            pending.triggerAttempts++
            pending.inactiveFramesAfterTrigger = 0
            pending.lastAfState = null // Log the first post-trigger state, whatever it is.
            pending.firstFrame = Long.MAX_VALUE
            if (startAfTimer) {
                pending.focusTimeout?.let(cameraHandler::removeCallbacks)
                pending.focusTimeout = Runnable {
                    fallbackFocus(pending, "AF lock timeout after ${AF_TIMEOUT_MILLIS}ms " +
                        "(last AF state=${afStateName(pending.lastAfState)}, triggers=${pending.triggerAttempts})")
                }.also { cameraHandler.postDelayed(it, AF_TIMEOUT_MILLIS) }
            }
            setCameraKey(builder, CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            builder.setTag(pending)
            try { s.capture(builder.build(), activeCaptureCallback, cameraHandler) }
            finally {
                builder.setTag(null)
                setCameraKey(builder, CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            }
            Log.i(TAG, "Still #${pending.sequence}: AF trigger submitted (attempt ${pending.triggerAttempts}; $reason) " +
                "mode=${afModeName(pending.afMode)} physical=$PHYSICAL_CAMERA_ID")
        } catch (e: Exception) { abortStill(pending, "AF trigger failed: ${e.message}") }
    }

    private fun fallbackFocus(pending: PendingStill, reason: String) {
        if (pendingStill !== pending || pending.phase == StillPhase.CAPTURING) return
        val saved = FocusMemory.loadLockedDistance(appContext)
        val distance = saved ?: lastGoodFocusDistance
        if (distance == null) {
            abortStill(pending, "$reason; no saved or successfully focused manual fallback")
            return
        }
        pending.focusMode = "autofocus-fallback"
        Log.w(TAG, "Still #${pending.sequence}: AUTOFOCUS DID NOT LOCK ($reason). This still will use MANUAL " +
            "focus at ${distance}D from ${if (saved != null) "saved FocusMemory" else "last good focus distance"}, " +
            "not autofocus")
        try { beginManualStill(pending, distance, "AF fallback: $reason") }
        catch (e: Exception) { abortStill(pending, "Manual focus fallback failed: ${e.message}") }
    }

    private fun beginManualStill(pending: PendingStill, distance: Float?, reason: String) {
        val builder = checkNotNull(requestBuilder)
        val s = checkNotNull(session)
        val modes = physicalCharacteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        check(CaptureRequest.CONTROL_AF_MODE_OFF in modes) { "Manual focus unavailable" }
        pending.focusTimeout?.let(cameraHandler::removeCallbacks)
        pending.manualDistance = distance?.coerceIn(0f, status.minFocusDistanceDiopters ?: 0f)
        pending.afMode = CaptureRequest.CONTROL_AF_MODE_OFF
        pending.focusReason = "manual ${pending.manualDistance}D ($reason)"
        applyFocus(builder, pending.afMode, pending.manualDistance)
        beginSettling(pending)
        submitFocusRequest(pending, builder, s)
        Log.i(TAG, "Still #${pending.sequence}: manual=${pending.manualDistance}D ($reason)")
    }

    /**
     * Callers submit the repeating request afterwards (beginManualStill via submitFocusRequest; the AF
     * path explicitly), so a manual exposure set here reaches the preview before the settle is judged.
     */
    private fun beginSettling(pending: PendingStill) {
        pending.phase = StillPhase.SETTLING
        pending.settledFrames = 0
        pending.focusReady = false
        pending.focusTimeout?.let(cameraHandler::removeCallbacks)
        pending.settleTimeout?.let(cameraHandler::removeCallbacks)
        val extraSettleMillis = pending.manualExposure?.let { applyManualExposureToPreview(pending, it) } ?: 0L
        pending.settleTimeout = Runnable {
            if (pendingStill === pending && pending.phase == StillPhase.SETTLING) {
                if (pending.focusReady) {
                    Log.w(TAG, "Still #${pending.sequence}: " + if (pending.manualExposure == null)
                        "AE/AWB settling deadline; using current exposure"
                    else "manual-exposure settle deadline (manual values seen in preview=" +
                        "${pending.manualSeenInPreview}); capturing with the same manual settings")
                    submitStill(pending)
                } else abortStill(pending, "Lens/focus did not settle")
            }
        }.also { cameraHandler.postDelayed(it, SETTLE_TIMEOUT_MILLIS + extraSettleMillis) }
    }

    /** Sets (does not submit) AE off + target values on the repeating request; returns extra settle time. */
    private fun applyManualExposureToPreview(pending: PendingStill, m: ManualExposure): Long {
        val builder = requestBuilder ?: return 0L
        val preview = reader ?: return 0L
        if (!pending.previewManualApplied) {
            pending.previewAeModeBefore = builder.get(CaptureRequest.CONTROL_AE_MODE)
            pending.previewManualApplied = true
        }
        val frameNs = applyManualExposure(builder, m, preview)
        Log.i(TAG, "Still #${pending.sequence}: preview switched to manual exposure ${m.exposureNs}ns " +
            "ISO ${m.iso} frameDuration=${frameNs}ns; settling before JPEG")
        return MANUAL_SETTLE_FRAMES * frameNs / 1_000_000L
    }

    private fun advanceStill(pending: PendingStill, result: TotalCaptureResult, physical: CaptureResult?) {
        if (pendingStill !== pending || result.frameNumber < pending.firstFrame || physical == null) return
        val activeId = if (Build.VERSION.SDK_INT >= 29)
            result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) else null
        val physicalAf = physical.get(CaptureResult.CONTROL_AF_STATE)
        val logicalAf = result.get(CaptureResult.CONTROL_AF_STATE)
        // Camera 3's own metadata first. The logical state is used only while camera 3 is the
        // active physical camera and camera 3's own state is absent or INACTIVE.
        val useLogical = (physicalAf == null || physicalAf == CaptureResult.CONTROL_AF_STATE_INACTIVE) &&
            logicalAf != null && activeId == PHYSICAL_CAMERA_ID
        val af = if (useLogical) logicalAf else physicalAf
        val lensState = physical.get(CaptureResult.LENS_STATE)
        val moving = lensState == CaptureResult.LENS_STATE_MOVING
        val distance = physical.get(CaptureResult.LENS_FOCUS_DISTANCE)
        val resultAfMode = physical.get(CaptureResult.CONTROL_AF_MODE) ?: result.get(CaptureResult.CONTROL_AF_MODE)
        pending.lastAfSource = if (useLogical) "logical" else "physical"
        pending.lastResultAfMode = resultAfMode
        pending.lastLensState = lensState
        pending.lastFocusDistance = distance
        if (af != pending.lastAfState) {
            pending.lastAfState = af
            Log.i(TAG, "Still #${pending.sequence}: AF state: ${afStateName(af)} [${pending.phase}] " +
                "focusD=$distance lens=${lensStateName(lensState)} mode=${afModeName(resultAfMode)} " +
                "src=${pending.lastAfSource} (physical=${afStateName(physicalAf)} logical=${afStateName(logicalAf)} " +
                "active=$activeId) frame=${result.frameNumber}")
        }
        when (pending.phase) {
            StillPhase.ARMING -> {
                // Require the switch to MACRO/AUTO to be visible in results, with AF idle, before START.
                val modeApplied = resultAfMode == null || resultAfMode == pending.afMode
                val idle = af == null || af == CaptureResult.CONTROL_AF_STATE_INACTIVE
                if (modeApplied && idle) pending.armedFrames++ else pending.armedFrames = 0
                if (pending.armedFrames >= AF_ARM_FRAMES) {
                    triggerAutofocus(pending, "mode ${afModeName(pending.afMode)} confirmed", startAfTimer = true)
                }
                return
            }
            StillPhase.FOCUSING -> when (af) {
                CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> if (!moving) {
                    lastGoodFocusDistance = distance
                    pending.focusReason = "AF ${afModeName(pending.afMode)} FOCUSED_LOCKED " +
                        "after ${pending.triggerAttempts} trigger(s)"
                    Log.i(TAG, "Still #${pending.sequence}: AF locked: focusD=$distance " +
                        "(~${distance?.takeIf { it > 0f }?.let { "%.1f cm".format(100f / it) } ?: "infinity"}); " +
                        "waiting for AE/AWB")
                    beginSettling(pending) // Falls through to the settling check below for this frame.
                    if (pending.previewManualApplied) {
                        val s = session
                        val builder = requestBuilder
                        try {
                            checkNotNull(s).setRepeatingRequest(checkNotNull(builder).build(),
                                activeCaptureCallback, cameraHandler)
                        } catch (e: Exception) {
                            abortStill(pending, "Applying manual exposure to preview failed: ${e.message}")
                            return
                        }
                    }
                }
                CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> {
                    if (pending.notFocusedRetries < AF_MAX_NOT_FOCUSED_RETRIES) {
                        pending.notFocusedRetries++
                        Log.w(TAG, "Still #${pending.sequence}: AF NOT_FOCUSED_LOCKED at focusD=$distance; " +
                            "re-triggering (retry ${pending.notFocusedRetries}/$AF_MAX_NOT_FOCUSED_RETRIES)")
                        triggerAutofocus(pending, "retry after NOT_FOCUSED_LOCKED", startAfTimer = false)
                    } else {
                        fallbackFocus(pending, "AF reported NOT_FOCUSED_LOCKED after " +
                            "${pending.triggerAttempts} trigger(s), focusD=$distance")
                    }
                    return
                }
                null, CaptureResult.CONTROL_AF_STATE_INACTIVE -> {
                    // A START that the HAL acted on leaves INACTIVE; persistent INACTIVE means it was lost.
                    if (++pending.inactiveFramesAfterTrigger >= AF_TRIGGER_ACK_FRAMES &&
                        pending.triggerResends < AF_MAX_TRIGGER_RESENDS) {
                        pending.triggerResends++
                        Log.w(TAG, "Still #${pending.sequence}: AF still ${afStateName(af)} " +
                            "${pending.inactiveFramesAfterTrigger} frames after trigger; resending START " +
                            "(${pending.triggerResends}/$AF_MAX_TRIGGER_RESENDS)")
                        triggerAutofocus(pending, "resend: trigger not acknowledged", startAfTimer = false)
                    }
                    return
                }
                else -> pending.inactiveFramesAfterTrigger = 0 // ACTIVE_SCAN (or unexpected passive state).
            }
            else -> Unit
        }
        if (pending.phase != StillPhase.SETTLING) return
        pending.focusReady = !moving && if (pending.afMode == CaptureRequest.CONTROL_AF_MODE_OFF) {
            (physical.get(CaptureResult.CONTROL_AF_MODE) == null ||
                physical.get(CaptureResult.CONTROL_AF_MODE) == CaptureRequest.CONTROL_AF_MODE_OFF) &&
                (pending.manualDistance == null || (distance != null &&
                    abs(distance - pending.manualDistance!!) <= maxOf(0.15f, pending.manualDistance!! * 0.05f)))
        } else af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
        if (!pending.focusReady) { pending.settledFrames = 0; return }
        val ae = physical.get(CaptureResult.CONTROL_AE_STATE)
        val awb = physical.get(CaptureResult.CONTROL_AWB_STATE)
        val manual = pending.manualExposure
        val exposureReady = if (manual == null) {
            ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                ae == CaptureResult.CONTROL_AE_STATE_LOCKED || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
        } else {
            val aeMode = physical.get(CaptureResult.CONTROL_AE_MODE) ?: result.get(CaptureResult.CONTROL_AE_MODE)
            val ns = physical.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val iso = physical.get(CaptureResult.SENSOR_SENSITIVITY)
            (aeMode == CaptureResult.CONTROL_AE_MODE_OFF && manualValuesMatch(manual, ns, iso)).also { seen ->
                if (seen && !pending.manualSeenInPreview) {
                    pending.manualSeenInPreview = true
                    Log.i(TAG, "Still #${pending.sequence}: manual exposure active in preview: ${ns}ns ISO $iso " +
                        "frame=${result.frameNumber}")
                }
            }
        }
        val colourReady = awb == CaptureResult.CONTROL_AWB_STATE_CONVERGED ||
            awb == CaptureResult.CONTROL_AWB_STATE_LOCKED
        if (exposureReady && colourReady) pending.settledFrames++ else pending.settledFrames = 0
        if (pending.settledFrames >= 2) submitStill(pending)
    }

    private fun submitStill(pending: PendingStill) {
        if (pendingStill !== pending || pending.phase != StillPhase.SETTLING) return
        try {
            val camera = checkNotNull(device)
            val s = checkNotNull(session)
            val output = checkNotNull(stillReader)
            val preview = checkNotNull(requestBuilder)
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE,
                setOf(PHYSICAL_CAMERA_ID)).apply {
                addTarget(output.surface)
                setTag(pending)
                setCameraKey(this, CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                setCameraKey(this, CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                setCameraKey(this, CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                setCameraKey(this, CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    preview.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION) ?: 0)
                // EXPERIMENTAL: manual stills replace AE ON above for this one request only.
                pending.manualExposure?.let { pending.manualFrameDurationNs = applyManualExposure(this, it, output) }
                applyFullFrame(this, checkNotNull(activeCharacteristics), checkNotNull(physicalCharacteristics))
                applyFocus(this, pending.afMode, pending.manualDistance)
                if (activeCharacteristics?.availableCaptureRequestKeys?.contains(CaptureRequest.CONTROL_ENABLE_ZSL) == true)
                    setCameraKey(this, CaptureRequest.CONTROL_ENABLE_ZSL, false)
                setCameraKey(this, CaptureRequest.JPEG_QUALITY, STILL_JPEG_QUALITY.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, config.rotation)
                set(CaptureRequest.FLASH_MODE, preview.get(CaptureRequest.FLASH_MODE) ?: CaptureRequest.FLASH_MODE_OFF)
                if (Build.VERSION.SDK_INT >= 35) preview.get(CaptureRequest.FLASH_STRENGTH_LEVEL)?.let {
                    set(CaptureRequest.FLASH_STRENGTH_LEVEL, it)
                }
            }
            pending.phase = StillPhase.CAPTURING
            pending.settleTimeout?.let(cameraHandler::removeCallbacks)
            val token = generation
            Log.i(TAG, "Still #${pending.sequence}: submitting still capture: " +
                "AF mode=${afModeName(pending.afMode)} AF state=${afStateName(pending.lastAfState)} " +
                "(src=${pending.lastAfSource}) focusD=${pending.lastFocusDistance} " +
                "lens=${lensStateName(pending.lastLensState)} focusMode=${pending.focusMode} focus=${pending.focusReason} " +
                "exposure=${pending.manualExposure?.let { "MANUAL ${it.exposureNs}ns ISO ${it.iso} " +
                    "(requested ${pending.manualExposureRequested?.exposureNs}ns ISO " +
                    "${pending.manualExposureRequested?.iso}) frameDuration=${pending.manualFrameDurationNs}ns " +
                    "seenInPreview=${pending.manualSeenInPreview}" } ?: "auto"}")
            pending.manualExposure?.let {
                Log.i(TAG, "Still #${pending.sequence}: manual exposure physical keys: " +
                    "AE_MODE=${CaptureRequest.CONTROL_AE_MODE in physicalRequestKeys} " +
                    "EXPOSURE_TIME=${CaptureRequest.SENSOR_EXPOSURE_TIME in physicalRequestKeys} " +
                    "SENSITIVITY=${CaptureRequest.SENSOR_SENSITIVITY in physicalRequestKeys} " +
                    "FRAME_DURATION=${CaptureRequest.SENSOR_FRAME_DURATION in physicalRequestKeys}; " +
                    "syncMaxLatency=${physicalCharacteristics?.get(CameraCharacteristics.SYNC_MAX_LATENCY)}")
            }
            s.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(s: CameraCaptureSession, r: CaptureRequest, timestamp: Long, frameNumber: Long) {
                    if (current(token) && pendingStill === pending) {
                        pending.timestamps.add(timestamp)
                        pending.unixMillis = System.currentTimeMillis()
                    }
                }
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    if (!current(token) || pendingStill !== pending) return
                    val physical = physicalResult(result)
                    result.get(CaptureResult.SENSOR_TIMESTAMP)?.let(pending.timestamps::add)
                    physical?.get(CaptureResult.SENSOR_TIMESTAMP)?.let(pending.timestamps::add)
                    pending.metadataReceived = true
                    Log.i(TAG, "Still #${pending.sequence}: physical=$PHYSICAL_CAMERA_ID JPEG=${output.width}x${output.height} " +
                        "AF=${physical?.get(CaptureResult.CONTROL_AF_STATE)} " +
                        "focusD=${physical?.get(CaptureResult.LENS_FOCUS_DISTANCE)} " +
                        "focalMm=${physical?.get(CaptureResult.LENS_FOCAL_LENGTH)} " +
                        "exposureNs=${physical?.get(CaptureResult.SENSOR_EXPOSURE_TIME)} " +
                        "ISO=${physical?.get(CaptureResult.SENSOR_SENSITIVITY)} " +
                        "zoom=${if (Build.VERSION.SDK_INT >= 30) physical?.get(CaptureResult.CONTROL_ZOOM_RATIO) else null} " +
                        "crop=${physical?.get(CaptureResult.SCALER_CROP_REGION)} " +
                        "physicalMetadata=${physical != null}")
                    pending.resultExposureNs = physical?.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                    pending.resultIso = physical?.get(CaptureResult.SENSOR_SENSITIVITY)
                    pending.resultFocusDistance = physical?.get(CaptureResult.LENS_FOCUS_DISTANCE)
                    pending.manualExposure?.let { auditManualExposure(pending, it, physical, result) }
                    completeStillIfReady(pending)
                }
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                    if (current(token)) abortStill(pending, "JPEG capture failed: reason=${failure.reason}")
                }
                override fun onCaptureSequenceAborted(s: CameraCaptureSession, sequenceId: Int) {
                    if (current(token)) abortStill(pending, "JPEG capture sequence aborted")
                }
            }, cameraHandler)
        } catch (e: Exception) { abortStill(pending, "Submitting JPEG failed: ${e.message}") }
    }

    private fun onStillImage(source: ImageReader, token: Int) {
        try {
            while (true) {
                val image = source.acquireNextImage() ?: break
                val pending = pendingStill
                try {
                    if (!current(token) || pending == null || pending.phase != StillPhase.CAPTURING) continue
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    pending.imageTimestamp = image.timestamp
                    pending.jpeg = JpegFrame(pending.sequence, bytes, image.width, image.height,
                        System.nanoTime(), pending.unixMillis.takeIf { it != 0L } ?: System.currentTimeMillis(), 0.0)
                } finally { image.close() }
                completeStillIfReady(pending)
            }
        } catch (e: Exception) {
            if (current(token)) pendingStill?.let { abortStill(it, "Reading JPEG failed: ${e.message}") }
        }
    }

    private fun completeStillIfReady(pending: PendingStill) {
        if (pendingStill !== pending || !pending.metadataReceived) return
        val jpeg = pending.jpeg ?: return
        if (pending.imageTimestamp !in pending.timestamps) {
            Log.w(TAG, "Discarding unmatched JPEG timestamp=${pending.imageTimestamp}")
            pending.jpeg = null
            return
        }
        clearStillTimers(pending)
        pendingStill = null
        status = status.copy(snapshotBusy = false, lastStillError = null)
        try {
            requestBuilder?.let { builder ->
                applyFocus(builder, previewAfMode, manualFocusDistance)
                if (pending.previewManualApplied) setCameraKey(builder, CaptureRequest.CONTROL_AE_MODE,
                    pending.previewAeModeBefore ?: CaptureRequest.CONTROL_AE_MODE_ON)
                session?.setRepeatingRequest(builder.build(), activeCaptureCallback, cameraHandler)
            }
        } catch (e: Exception) { recover("Restoring preview after JPEG failed: ${e.message}", e) }
        Log.i(TAG, "Still #${pending.sequence}: returning ${jpeg.bytes.size} HAL JPEG bytes, timestamp=${pending.imageTimestamp}")
        extendOpenFailsafe() // Allow the controller time to send /torch/off and /camera/off.
        pending.future.complete(SnapshotResult(200, jpeg, headers = stillHeaders(pending)))
    }

    private fun clearStillTimers(pending: PendingStill) {
        pending.timeout?.let(cameraHandler::removeCallbacks)
        pending.focusTimeout?.let(cameraHandler::removeCallbacks)
        pending.settleTimeout?.let(cameraHandler::removeCallbacks)
    }

    private fun abortStill(pending: PendingStill, message: String) {
        if (pendingStill !== pending) return
        Log.w(TAG, "Still #${pending.sequence}: $message")
        running = false
        retryPending = false
        cameraHandler.removeCallbacks(watchdog)
        cameraHandler.removeCallbacks(openFailsafe)
        requestedTorchStrength = null
        positioning = false
        status = status.copy(state = "idle", lastStillError = message, torchEnabled = false, torchStrength = null,
            positioning = false)
        // Retire the entire session on failure: a late JPEG can never satisfy a subsequent request.
        closeSession(message)
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
            if (rejectDuringStill(callback)) return@post
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

                    applyTorch(builder, strength)

                    s.setRepeatingRequest(
                        builder.build(),
                        activeCaptureCallback,
                        cameraHandler
                    )
                    requestedTorchStrength = strength
                    extendOpenFailsafe()
                    Log.i(TAG, "Torch requested on at level $strength/$maxStrength (FLASH_MODE_TORCH on repeating request)")

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
                    applyTorch(builder, null)

                    s.setRepeatingRequest(
                        builder.build(),
                        activeCaptureCallback,
                        cameraHandler
                    )
                    requestedTorchStrength = null
                    extendOpenFailsafe()
                    Log.i(TAG, "Torch requested off")

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
            if (rejectDuringStill(callback)) return@post
            val chars = physicalCharacteristics; val builder = requestBuilder; val s = session
            if (!running || chars == null || builder == null || s == null) {
                callback(ControlResult(false, "Camera is not ready")); return@post
            }
            try {
                val modes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                val af = listOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                    CaptureRequest.CONTROL_AF_MODE_MACRO, CaptureRequest.CONTROL_AF_MODE_AUTO).firstOrNull { it in modes }
                    ?: CaptureRequest.CONTROL_AF_MODE_OFF
                applyFocus(builder, af)
                s.setRepeatingRequest(builder.build(), activeCaptureCallback, cameraHandler)
                previewAfMode = af
                manualFocusDistance = null
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
            if (rejectDuringStill(callback)) return@post
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

    fun restoreSavedFocus(callback: (ControlResult) -> Unit = {}) {
        val saved = FocusMemory.loadLockedDistance(appContext)
        if (saved == null) callback(ControlResult(false, "No saved manual focus"))
        else setManualFocus(saved, callback)
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
            if (rejectDuringStill(callback)) return@post
            val builder = requestBuilder
            val s = session
            val min = status.minFocusDistanceDiopters ?: 0f

            if (!running || builder == null || s == null) {
                callback(ControlResult(false, "Camera is not ready"))
                return@post
            }

            val modes = physicalCharacteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            if (min <= 0f || !distanceDiopters.isFinite() || CaptureRequest.CONTROL_AF_MODE_OFF !in modes) {
                callback(ControlResult(false, "Manual focus is not available"))
                return@post
            }

            try {
                val lockedDistance = distanceDiopters.coerceIn(0f, min)

                applyFocus(builder, CaptureRequest.CONTROL_AF_MODE_OFF, lockedDistance)

                s.setRepeatingRequest(
                    builder.build(),
                    activeCaptureCallback,
                    cameraHandler
                )

                previewAfMode = CaptureRequest.CONTROL_AF_MODE_OFF
                manualFocusDistance = lockedDistance
                lastGoodFocusDistance = lockedDistance

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
            if (rejectDuringStill(callback)) return@post
            val builder = requestBuilder; val s = session
            val min = status.minFocusDistanceDiopters ?: 0f
            val distance = currentFocusDistance
            if (!running || builder == null || s == null) { callback(ControlResult(false, "Camera is not ready")); return@post }
            if (min <= 0f || distance == null) { callback(ControlResult(false, "Manual focus lock is not available yet")); return@post }
            try {
                val lockedDistance = distance.coerceIn(0f, min)
                applyFocus(builder, CaptureRequest.CONTROL_AF_MODE_OFF, lockedDistance)
                s.setRepeatingRequest(builder.build(), activeCaptureCallback, cameraHandler)
                previewAfMode = CaptureRequest.CONTROL_AF_MODE_OFF
                manualFocusDistance = lockedDistance
                lastGoodFocusDistance = lockedDistance
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

    private fun rejectDuringStill(callback: (ControlResult) -> Unit): Boolean {
        if (pendingStill == null) return false
        callback(ControlResult(false, "Still capture in progress; retry control afterwards"))
        return true
    }

    private fun current(token: Int) = !closing.get() && token == generation

    /** Keep the handler alive for late onOpened/onConfigured callbacks so they can close resources. */
    private fun trackLifecycleCallback(): () -> Unit {
        lifecycleCallbacksPending++
        var completed = false
        return {
            if (!completed) {
                completed = true
                lifecycleCallbacksPending--
            }
            if (teardownComplete && lifecycleCallbacksPending == 0) thread.quitSafely()
        }
    }

    private fun recover(message: String, error: Exception? = null, smallerSize: Boolean = false) {
        if (!running || closing.get() || retryPending) return
        Log.e(TAG, message, error)
        failures++
        if (smallerSize || failures % 3 == 0) {
            if (sizeIndex < sizes.lastIndex) sizeIndex++
            else if (smallerSize && stillSizeIndex < stillSizes.lastIndex) {
                stillSizeIndex++
                sizeIndex = 0
            }
        }
        retryPending = true
        stableSince = 0
        status = status.copy(state = "retrying", lastError = message, recoveries = status.recoveries + 1)
        closeSession(message)
        frames.invalidate()
        val delay = (1000L shl (failures - 1).coerceAtMost(5)).coerceAtMost(30_000)
        Log.w(TAG, "Retrying in ${delay}ms; preview index=$sizeIndex JPEG index=$stillSizeIndex")
        val retryGeneration = generation
        cameraHandler.postDelayed({ if (running && !closing.get() && generation == retryGeneration) openCamera() }, delay)
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
            requestedTorchStrength = null
            positioning = false

            // Cancel only BugCam-owned scheduled work.
            // Do NOT erase Camera2 lifecycle callbacks: a late onOpened()
            // or onConfigured() must still run so it can close stale resources.
            cameraHandler.removeCallbacks(watchdog)
            cameraHandler.removeCallbacks(openFailsafe)

            closeSession()
            status = status.copy(
                state = "idle",
                torchEnabled = false,
                torchStrength = null,
                positioning = false
            )
        }
    }

    private fun closeSession(reason: String = "Camera closed") {
        generation++ // Reject callbacks and JPEG results belonging to the previous camera.
        pendingStill?.let {
            clearStillTimers(it)
            it.future.complete(SnapshotResult(503, message = reason))
            status = status.copy(lastStillError = reason)
        }
        pendingStill = null
        status = status.copy(snapshotBusy = false, torchFlashState = null, torchLit = false, torchCurrentStrength = null)
        try { session?.close() } catch (e: Exception) { Log.w(TAG, "Session close", e) }
        session = null
        requestBuilder = null
        activeCharacteristics = null
        physicalCharacteristics = null
        physicalRequestKeys = emptySet()
        activeCaptureCallback = null
        currentFocusDistance = null
        manualFocusDistance = null
        activeSensorFps = 0
        try { device?.close() } catch (e: Exception) { Log.w(TAG, "Device close", e) }
        device = null
        val retiredStill = stillReader
        stillReader = null
        try {
            retiredStill?.setOnImageAvailableListener(null, null)
            retiredStill?.close() // JPEG Images never outlive their handler callback.
        } catch (e: Exception) { Log.w(TAG, "JPEG reader close", e) }
        val retired = reader
        reader = null
        retired?.setOnImageAvailableListener(null, null)
        // Keep its native buffers valid until the outstanding encoder image is closed.
        if (retired != null) encoder.execute { retired.close() }
        frames.invalidate()
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        cameraHandler.post {
            running = false
            cameraHandler.removeCallbacks(watchdog)
            cameraHandler.removeCallbacks(openFailsafe)
            closeSession()
            encoder.shutdown() // Drains the image/reader teardown tasks in order.
            status = status.copy(state = "stopped")
            teardownComplete = true
            if (lifecycleCallbacksPending == 0) thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "BugCam-Camera"
        private const val PHYSICAL_CAMERA_ID = "3"
        const val STILL_JPEG_QUALITY = 95
        private const val STILL_TIMEOUT_MILLIS = 12_000L
        private const val OPEN_FAILSAFE_MILLIS = 15_000L // After the most recent camera command.
        private const val POSITIONING_MAX_MILLIS = 10 * 60_000L // /live must be resumed after this.
        private const val POSITIONING_REOPEN_DELAY_MILLIS = 500L
        private const val AF_TIMEOUT_MILLIS = 4_000L // From the first START trigger; retries do not extend it.
        private const val SETTLE_TIMEOUT_MILLIS = 2_000L
        // Worst case before capture: arm 1.5 s + AF 4 s + settle 2 s, inside STILL_TIMEOUT_MILLIS.
        private const val AF_ARM_TIMEOUT_MILLIS = 1_500L
        private const val AF_ARM_FRAMES = 2 // Consecutive results showing MACRO/AUTO and INACTIVE.
        private const val AF_TRIGGER_ACK_FRAMES = 8 // Still INACTIVE this long after START: resend it.
        private const val AF_MAX_TRIGGER_RESENDS = 2
        private const val AF_MAX_NOT_FOCUSED_RETRIES = 1
        // Manual stills: exposure cap is ManualExposure.MAX_EXPOSURE_NS; waits scale with frame length.
        private const val MANUAL_EXPOSURE_TOLERANCE_NS = 100_000L // Or 5%, whichever is larger.
        private const val MANUAL_SETTLE_FRAMES = 4L // Extra settle frames at the manual frame duration.

        private fun afStateName(state: Int?): String = when (state) {
            null -> "null"
            CaptureResult.CONTROL_AF_STATE_INACTIVE -> "INACTIVE"
            CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN -> "PASSIVE_SCAN"
            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> "PASSIVE_FOCUSED"
            CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> "ACTIVE_SCAN"
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> "FOCUSED_LOCKED"
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "NOT_FOCUSED_LOCKED"
            CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> "PASSIVE_UNFOCUSED"
            else -> "UNKNOWN($state)"
        }

        private fun afModeName(mode: Int?): String = when (mode) {
            null -> "null"
            CaptureRequest.CONTROL_AF_MODE_OFF -> "OFF"
            CaptureRequest.CONTROL_AF_MODE_AUTO -> "AUTO"
            CaptureRequest.CONTROL_AF_MODE_MACRO -> "MACRO"
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "CONTINUOUS_VIDEO"
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "CONTINUOUS_PICTURE"
            CaptureRequest.CONTROL_AF_MODE_EDOF -> "EDOF"
            else -> "UNKNOWN($mode)"
        }

        private fun lensStateName(state: Int?): String = when (state) {
            null -> "null"
            CaptureResult.LENS_STATE_STATIONARY -> "STATIONARY"
            CaptureResult.LENS_STATE_MOVING -> "MOVING"
            else -> "UNKNOWN($state)"
        }
    }
}
