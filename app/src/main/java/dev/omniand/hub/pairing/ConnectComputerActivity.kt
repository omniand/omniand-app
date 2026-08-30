package dev.omniand.hub.pairing

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.omniand.hub.R
import dev.omniand.hub.background.BackgroundHostingManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** CameraX/ML Kit scanner for a relay-owned connect portal QR. */
class ConnectComputerActivity : ComponentActivity() {
    private val claimed = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private lateinit var preview: PreviewView
    private lateinit var message: TextView
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var scanner: BarcodeScanner? = null
    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else fail("Camera permission is required")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PairingState.started()
        onBackPressedDispatcher.addCallback(this) {
            stopScanning()
            finish()
        }
        preview = PreviewView(this)
        message =
            TextView(this).apply {
                text = getString(R.string.pairing_scanner_instruction)
                gravity = Gravity.CENTER
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.argb(180, 0, 0, 0))
                val padding = (16 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
            }
        setContentView(
            FrameLayout(this).apply {
                addView(preview)
                addView(QrScannerOverlay(this@ConnectComputerActivity))
                addView(
                    message,
                    FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM,
                        )
                        .apply {
                            val margin = (24 * resources.displayMetrics.density).toInt()
                            setMargins(margin, margin, margin, margin)
                        },
                )
            }
        )
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                val provider = providerFuture.get()
                if (closed.get()) {
                    provider.unbindAll()
                    return@addListener
                }
                cameraProvider = provider
                val cameraPreview =
                    Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                val analysis =
                    ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(1280, 720),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                    )
                                )
                                .build()
                        )
                        .build()
                val barcodeScanner =
                    scanner
                        ?: BarcodeScanning.getClient(
                                BarcodeScannerOptions.Builder()
                                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                    .build()
                            )
                            .also { scanner = it }
                imageAnalysis = analysis
                analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { proxy ->
                    val image = proxy.image
                    if (image == null || claimed.get() || closed.get()) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    barcodeScanner
                        .process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes ->
                            val target =
                                codes
                                    .asSequence()
                                    .mapNotNull { it.rawValue }
                                    .mapNotNull(PairingTarget::parse)
                                    .firstOrNull()
                            if (codes.isNotEmpty() && target == null && !closed.get()) {
                                message.text = getString(R.string.pairing_scanner_invalid)
                            }
                            if (
                                target != null &&
                                    !closed.get() &&
                                    claimed.compareAndSet(false, true)
                            ) {
                                analysis.clearAnalyzer()
                                message.text = getString(R.string.pairing_scanner_connecting)
                                lifecycleScope.launch {
                                    try {
                                        PairingClient(DeviceIdentity(applicationContext))
                                            .claim(target)
                                        if (closed.get()) return@launch
                                        if (
                                            BackgroundHostingManager.isEnabled(
                                                this@ConnectComputerActivity
                                            )
                                        )
                                            BackgroundHostingManager.reconnect(
                                                this@ConnectComputerActivity
                                            )
                                        finish()
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        if (!closed.get())
                                            fail(
                                                error.message
                                                    ?: getString(R.string.pairing_scanner_failure)
                                            )
                                    }
                                }
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    cameraPreview,
                    analysis,
                )
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun fail(value: String) {
        PairingState.failed(value)
        message.text = value
        claimed.set(false)
    }

    private fun stopScanning() {
        if (!closed.compareAndSet(false, true)) return
        imageAnalysis?.clearAnalyzer()
        imageAnalysis = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        scanner?.close()
        scanner = null
        PairingState.stopped()
    }

    override fun onDestroy() {
        stopScanning()
        super.onDestroy()
    }
}
