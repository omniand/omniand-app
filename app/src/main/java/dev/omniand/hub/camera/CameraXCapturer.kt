package dev.omniand.hub.camera

import android.content.Context
import android.hardware.SensorManager
import android.os.Build
import android.os.Looper
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.webrtc.CapturerObserver
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame

/** CameraX ImageAnalysis source feeding bounded 720p30 I420 frames into WebRTC. */
class CameraXCapturer(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val observer: CapturerObserver,
    private val onState: (CameraHardwareState) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val closed = AtomicBoolean(false)
    private val observerLock = Any()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var selected = CameraFacing.BACK
    private var lastFrameTimestamp = 0L
    private var targetRotation = Surface.ROTATION_0
    private val zoomReliable = hasReliableCameraZoom(Build.HARDWARE)
    private val orientationListener =
        object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
            override fun onOrientationChanged(orientation: Int) {
                val rotation = targetRotationForOrientation(orientation) ?: return
                ContextCompat.getMainExecutor(context).execute {
                    if (closed.get() || targetRotation == rotation) return@execute
                    targetRotation = rotation
                    analysis?.targetRotation = rotation
                    imageCapture?.targetRotation = rotation
                }
            }
        }

    fun start() {
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                runCatching {
                        if (closed.get()) return@addListener
                        provider = future.get()
                        bind(selected)
                        synchronized(observerLock) {
                            if (!closed.get()) observer.onCapturerStarted(true)
                        }
                    }
                    .onFailure {
                        if (!closed.get()) {
                            synchronized(observerLock) { observer.onCapturerStarted(false) }
                            onFailure("camera-start-failed")
                        }
                    }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun switchCamera(facing: CameraFacing): String? {
        if (closed.get()) return "camera-closed"
        val available = facingAvailable(facing)
        if (!available) return "camera-unavailable"
        if (facing == selected) return null
        return runCatching {
                bind(facing)
                null
            }
            .getOrElse { "camera-switch-failed" }
    }

    fun setTorch(enabled: Boolean): String? {
        val current = camera ?: return "camera-not-ready"
        if (!current.cameraInfo.hasFlashUnit()) return "torch-unavailable"
        val operation = current.cameraControl.enableTorch(enabled)
        operation.addListener(
            { runCatching(operation::get).onFailure { onFailure("camera-control-failed") } },
            ContextCompat.getMainExecutor(context),
        )
        publishState(optimisticTorch = enabled)
        return null
    }

    fun setZoom(ratio: Double): String? {
        if (!ratio.isFinite()) return "zoom-out-of-range"
        if (!zoomReliable) return "zoom-out-of-range"
        val current = camera ?: return "camera-not-ready"
        val zoom = current.cameraInfo.zoomState.value ?: return "camera-not-ready"
        if (ratio < zoom.minZoomRatio || ratio > zoom.maxZoomRatio) return "zoom-out-of-range"
        val operation = current.cameraControl.setZoomRatio(ratio.toFloat())
        operation.addListener(
            {
                runCatching(operation::get).onFailure {
                    if (!isSupersededCameraControlFailure(it)) onFailure("camera-control-failed")
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        publishState(ratio.toFloat())
        return null
    }

    fun setFlashMode(mode: String): String? {
        val capture = imageCapture ?: return "camera-not-ready"
        if (camera?.cameraInfo?.hasFlashUnit() != true) return "flash-unavailable"
        capture.flashMode =
            when (mode) {
                "off" -> ImageCapture.FLASH_MODE_OFF
                "auto" -> ImageCapture.FLASH_MODE_AUTO
                "on" -> ImageCapture.FLASH_MODE_ON
                else -> return "invalid-control"
            }
        publishState()
        return null
    }

    /** Writes one maximum-quality, rotation-aware JPEG to bounded app cache. */
    fun capture(file: java.io.File, complete: (Result<java.io.File>) -> Unit): String? {
        val useCase = imageCapture ?: return "camera-not-ready"
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        useCase.takePicture(
            options,
            analysisExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(result: ImageCapture.OutputFileResults) =
                    complete(Result.success(file))

                override fun onError(error: ImageCaptureException) = complete(Result.failure(error))
            },
        )
        return null
    }

    fun state(): CameraHardwareState = hardwareState()

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        val release = {
            orientationListener.disable()
            analysis?.clearAnalyzer()
            analysis = null
            imageCapture = null
            provider?.unbindAll()
            provider = null
            camera = null
            analysisExecutor.shutdownNow()
            synchronized(observerLock) { observer.onCapturerStopped() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) release()
        else ContextCompat.getMainExecutor(context).execute(release)
    }

    /** Rebinds preview analysis and still capture to the same authoritative lens. */
    private fun bind(facing: CameraFacing) {
        val activeProvider = checkNotNull(provider)
        val selector = selector(facing)
        check(activeProvider.hasCamera(selector)) { "Requested camera is unavailable" }
        val useCase =
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setTargetRotation(targetRotation)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(MAX_WIDTH, MAX_HEIGHT),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            )
                        )
                        .build()
                )
                .build()
        useCase.setAnalyzer(analysisExecutor) { image ->
            try {
                if (closed.get()) return@setAnalyzer
                val timestamp = image.imageInfo.timestamp
                if (timestamp - lastFrameTimestamp < MIN_FRAME_INTERVAL_NS) return@setAnalyzer
                lastFrameTimestamp = timestamp
                val crop = image.cropRect
                val planes = image.planes
                if (planes.size != 3) error("Unexpected YUV plane count")
                val converted =
                    Yuv420Converter.convert(
                        image.width,
                        image.height,
                        crop.left,
                        crop.top,
                        crop.width() and -2,
                        crop.height() and -2,
                        image.imageInfo.rotationDegrees,
                        planes[0].toPlane(),
                        planes[1].toPlane(),
                        planes[2].toPlane(),
                    )
                val buffer = JavaI420Buffer.allocate(converted.width, converted.height)
                buffer.dataY.put(converted.y).rewind()
                buffer.dataU.put(converted.u).rewind()
                buffer.dataV.put(converted.v).rewind()
                val frame = VideoFrame(buffer, 0, timestamp)
                synchronized(observerLock) {
                    if (!closed.get()) observer.onFrameCaptured(frame)
                }
                frame.release()
            } catch (error: Exception) {
                if (!closed.get()) onFailure("camera-frame-failed")
            } finally {
                image.close()
            }
        }
        val stills =
            ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetRotation(targetRotation)
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .build()
        analysis?.clearAnalyzer()
        activeProvider.unbindAll()
        camera = activeProvider.bindToLifecycle(lifecycleOwner, selector, useCase, stills)
        analysis = useCase
        imageCapture = stills
        selected = facing
        publishState()
    }

    private fun androidx.camera.core.ImageProxy.PlaneProxy.toPlane() =
        YuvPlane(Yuv420Converter.bytes(buffer), rowStride, pixelStride)

    private fun publishState(
        optimisticZoom: Float? = null,
        optimisticTorch: Boolean? = null,
    ) = onState(hardwareState(optimisticZoom, optimisticTorch))

    private fun hardwareState(
        optimisticZoom: Float? = null,
        optimisticTorch: Boolean? = null,
    ): CameraHardwareState {
        val info = camera?.cameraInfo
        val zoom = info?.zoomState?.value
        val currentZoom = if (zoomReliable) zoom?.zoomRatio ?: 1f else 1f
        return CameraHardwareState(
            front = facingAvailable(CameraFacing.FRONT),
            back = facingAvailable(CameraFacing.BACK),
            torch = info?.hasFlashUnit() == true,
            flash = info?.hasFlashUnit() == true,
            flashMode =
                when (imageCapture?.flashMode) {
                    ImageCapture.FLASH_MODE_AUTO -> "auto"
                    ImageCapture.FLASH_MODE_ON -> "on"
                    else -> "off"
                },
            torchEnabled = optimisticTorch ?: (info?.torchState?.value == TorchState.ON),
            minZoom = if (zoomReliable) zoom?.minZoomRatio ?: 1f else 1f,
            maxZoom = if (zoomReliable) zoom?.maxZoomRatio ?: 1f else 1f,
            zoom = if (zoomReliable) optimisticZoom ?: currentZoom else 1f,
            camera = selected,
        )
    }

    private fun facingAvailable(facing: CameraFacing): Boolean =
        runCatching { provider?.hasCamera(selector(facing)) == true }.getOrDefault(false)

    private fun selector(facing: CameraFacing) =
        if (facing == CameraFacing.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA
        else CameraSelector.DEFAULT_BACK_CAMERA

    private companion object {
        const val MAX_WIDTH = 1280
        const val MAX_HEIGHT = 720
        const val MIN_FRAME_INTERVAL_NS = 1_000_000_000L / 30
    }
}

/** Emulator HALs advertise zoom ranges but reject their own zoom capture requests. */
internal fun hasReliableCameraZoom(hardware: String): Boolean =
    hardware != "goldfish" && hardware != "ranchu"

/** Maps the physical device angle to the surface rotation expected by CameraX. */
internal fun targetRotationForOrientation(orientation: Int): Int? {
    if (orientation !in 0..359) return null
    return when (orientation) {
        in 45 until 135 -> Surface.ROTATION_270
        in 135 until 225 -> Surface.ROTATION_180
        in 225 until 315 -> Surface.ROTATION_90
        else -> Surface.ROTATION_0
    }
}

/** CameraX cancels the previous zoom future when a newer slider value supersedes it. */
internal fun isSupersededCameraControlFailure(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        if (
            current is CameraControl.OperationCanceledException || current is CancellationException
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

enum class CameraFacing(val wireName: String) {
    FRONT("front"),
    BACK("back");

    companion object {
        fun fromWireName(value: String): CameraFacing? = entries.firstOrNull {
            it.wireName == value
        }
    }
}

data class CameraHardwareState(
    val front: Boolean,
    val back: Boolean,
    val torch: Boolean,
    val flash: Boolean,
    val flashMode: String,
    val torchEnabled: Boolean,
    val minZoom: Float,
    val maxZoom: Float,
    val zoom: Float,
    val camera: CameraFacing,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("version", 1)
            .put("type", "camera-state")
            .put("state", "streaming")
            .put("camera", camera.wireName)
            .put(
                "hardware",
                JSONObject()
                    .put("front", front)
                    .put("back", back)
                    .put("torch", torch)
                    .put("flash", flash)
                    .put("flashMode", flashMode)
                    .put("capture", true)
                    .put("torchEnabled", torchEnabled)
                    .put("minZoom", minZoom.toDouble())
                    .put("maxZoom", maxZoom.toDouble())
                    .put("zoom", zoom.toDouble()),
            )
}
