package dev.omniand.hub.pairing

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.omniand.hub.BuildConfig
import dev.omniand.hub.background.BackgroundHostingManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

/** CameraX/ML Kit scanner for a relay-owned connect portal QR. */
class ConnectComputerActivity : ComponentActivity() {
    private val claimed = AtomicBoolean(false)
    private lateinit var preview: PreviewView
    private lateinit var message: TextView
    private val permission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else fail("Camera permission is required")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PairingState.started()
        preview = PreviewView(this)
        message =
            TextView(this).apply {
                text = "Scan the QR code shown on your computer"
                setPadding(32, 32, 32, 32)
            }
        setContentView(
            FrameLayout(this).apply {
                addView(preview)
                addView(message)
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
                val cameraPreview =
                    Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                val scanner =
                    BarcodeScanning.getClient(
                        BarcodeScannerOptions.Builder()
                            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                            .build()
                    )
                val analysis =
                    ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { proxy ->
                    val image = proxy.image
                    if (image == null || claimed.get()) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    scanner
                        .process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { codes ->
                            val secret =
                                codes
                                    .asSequence()
                                    .mapNotNull { it.rawValue }
                                    .mapNotNull {
                                        PairingPayload.secret(it, BuildConfig.PLATFORM_HOST)
                                    }
                                    .firstOrNull()
                            if (secret != null && claimed.compareAndSet(false, true)) {
                                analysis.clearAnalyzer()
                                message.text = "Connecting…"
                                lifecycleScope.launch {
                                    runCatching {
                                            PairingClient(DeviceIdentity(applicationContext))
                                                .claim(secret)
                                        }
                                        .onSuccess {
                                            if (
                                                BackgroundHostingManager.isEnabled(
                                                    this@ConnectComputerActivity
                                                )
                                            )
                                                BackgroundHostingManager.reconnect(
                                                    this@ConnectComputerActivity
                                                )
                                            finish()
                                        }
                                        .onFailure { fail(it.message ?: "Unable to connect") }
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
}
