package dev.omniand.hub.camera

import android.Manifest
import android.app.Activity
import android.os.Bundle

/** Visible phone-local setup flow for Camera and microphone runtime permissions. */
class CameraSetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            REQUEST_CODE,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
    }

    private companion object {
        const val REQUEST_CODE = 308
    }
}
