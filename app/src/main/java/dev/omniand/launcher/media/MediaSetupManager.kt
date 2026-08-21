package dev.omniand.launcher.media

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import org.json.JSONObject

/** Owns phone-local runtime and special-access setup for MediaStore capabilities. */
object MediaSetupManager {
    private const val PREFS = "media-setup"
    private const val PENDING = "pending"
    private const val CAPABILITIES = "capabilities"

    fun recordPending(context: Context, capabilities: Set<String>) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PENDING, true)
            .putStringSet(CAPABILITIES, capabilities)
            .apply()
    }

    fun request(context: Context, capabilities: Set<String>) {
        recordPending(context, capabilities)
        context.startActivity(
            Intent(context, MediaSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openPendingSetup(activity: Activity): Boolean {
        if (!activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PENDING, false))
            return false
        activity.startActivity(Intent(activity, MediaSetupActivity::class.java))
        return true
    }

    fun capabilities(context: Context): Set<String> =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(CAPABILITIES, emptySet())
            .orEmpty()

    /** Reports Android authorization independently from the Web capability decision. */
    fun state(context: Context, localTransport: Boolean = false): JSONObject {
        val images = granted(context, imagePermission())
        val videos = granted(context, videoPermission())
        val selected =
            Build.VERSION.SDK_INT >= 34 &&
                granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        return JSONObject()
            .put("images", images)
            .put("videos", videos)
            .put("selectedAccess", selected && !(images && videos))
            .put("partialAccess", selected && !(images && videos))
            .put("manageMedia", canManage(context))
            .put("manageMediaAvailable", Build.VERSION.SDK_INT >= 31)
            .put(
                "pending",
                context
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(PENDING, false),
            )
            .put("uploadTransport", if (localTransport) "base64-header" else "binary-body")
            .put("chunkSize", if (localTransport) 24 * 1024 else 256 * 1024)
            .put("maxFiles", 20)
            .put("maxFileSize", 500L * 1024 * 1024)
    }

    fun canManage(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(context)

    fun complete(context: Context) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PENDING, false)
            .apply()
    }

    private fun imagePermission() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun videoPermission() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

/** Presents Android-controlled media permission and management screens only on the phone. */
class MediaSetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertDialog.Builder(this)
            .setTitle("Set up Gallery")
            .setMessage(
                "OmniAnd needs Android photo and video access. Media management access gives phone and desktop clients equal deletion rights. You can finish setup later."
            )
            .setPositiveButton("Continue") { _, _ -> requestReadAccess() }
            .setNegativeButton("Not now") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun requestReadAccess() {
        val permissions =
            if (Build.VERSION.SDK_INT >= 33) {
                buildList {
                    add(Manifest.permission.READ_MEDIA_IMAGES)
                    add(Manifest.permission.READ_MEDIA_VIDEO)
                    if (Build.VERSION.SDK_INT >= 34)
                        add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                }
            } else {
                buildList {
                    add(Manifest.permission.READ_EXTERNAL_STORAGE)
                    if (
                        Build.VERSION.SDK_INT <= 28 &&
                            "media.write" in MediaSetupManager.capabilities(this@MediaSetupActivity)
                    ) {
                        add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }
            }
        val missing = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) requestManagement()
        else requestPermissions(missing.toTypedArray(), 83)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        results: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 83) requestManagement()
    }

    private fun requestManagement() {
        if (
            "media.write" in MediaSetupManager.capabilities(this) &&
                Build.VERSION.SDK_INT >= 31 &&
                !MediaSetupManager.canManage(this)
        ) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
        }
        MediaSetupManager.complete(this)
        finish()
    }
}
