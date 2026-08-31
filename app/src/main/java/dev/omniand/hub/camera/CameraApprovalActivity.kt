package dev.omniand.hub.camera

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle

/** Visible, user-initiated permission gate for a pending remote camera request. */
class CameraApprovalActivity : Activity() {
    private var requestId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request =
            CameraSessionManager.instance(this).pendingRequest()
                ?: run {
                    finish()
                    return
                }
        requestId = request.getJSONObject("request").getString("id")
        AlertDialog.Builder(this)
            .setTitle("Share camera?")
            .setMessage("A paired computer is requesting your camera and microphone.")
            .setNegativeButton("Deny") { _, _ -> decide(false) }
            .setPositiveButton("Approve") { _, _ -> requestPermissionsIfNeeded() }
            .setOnCancelListener { decide(false) }
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grants: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grants)
        if (requestCode == REQUEST_PERMISSIONS)
            decide(grants.all { it == PackageManager.PERMISSION_GRANTED })
    }

    private fun requestPermissionsIfNeeded() {
        if (
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        )
            decide(true)
        else
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                REQUEST_PERMISSIONS,
            )
    }

    private fun decide(approved: Boolean) {
        requestId?.let { CameraSessionManager.instance(this).decide(it, approved) }
        requestId = null
        finish()
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 307
    }
}
