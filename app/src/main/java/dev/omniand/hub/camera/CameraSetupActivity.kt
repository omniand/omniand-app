package dev.omniand.hub.camera

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import dev.omniand.hub.wrappers.WrapperNotificationPermission

/** Visible phone-local setup flow for the Camera runtime permission. */
class CameraSetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            requestWrapperNotificationPermission()
        else requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST) requestWrapperNotificationPermission()
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: android.content.Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == WRAPPER_NOTIFICATION_REQUEST) finish()
    }

    private fun requestWrapperNotificationPermission() {
        if (
            !WrapperNotificationPermission.request(
                this,
                "camera",
                WRAPPER_NOTIFICATION_REQUEST,
            )
        )
            finish()
    }

    private companion object {
        const val CAMERA_REQUEST = 308
        const val WRAPPER_NOTIFICATION_REQUEST = 309
    }
}
