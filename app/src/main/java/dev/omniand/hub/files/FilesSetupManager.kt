package dev.omniand.hub.files

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import dev.omniand.hub.BuildConfig
import dev.omniand.hub.services.FilesService
import org.json.JSONObject

/** Owns the phone-only Android special-access flow independently from Web capabilities. */
object FilesSetupManager {
    private const val PREFS = "files-setup"
    private const val PENDING = "pending"

    fun request(context: Context) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PENDING, true)
            .apply()
        context.startActivity(
            Intent(context, FilesSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openPendingSetup(activity: Activity): Boolean {
        if (!activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PENDING, false))
            return false
        activity.startActivity(Intent(activity, FilesSetupActivity::class.java))
        return true
    }

    fun granted(): Boolean = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    fun state(context: Context, phoneClient: Boolean): JSONObject =
        JSONObject()
            .put("granted", granted())
            .put("available", Build.VERSION.SDK_INT >= 30)
            .put("phoneClient", phoneClient)
            .put(
                "pending",
                context
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(PENDING, false),
            )
            .put("maxUploadSize", FilesService.MAX_UPLOAD_SIZE)

    fun complete(context: Context) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PENDING, false)
            .apply()
    }

    internal fun settingsIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${BuildConfig.APPLICATION_ID}"),
        )
}

/** Explains and opens Android's app-scoped all-files access settings on the phone. */
class FilesSetupActivity : Activity() {
    private var awaitingSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 30) {
            FilesSetupManager.complete(this)
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Set up Files")
            .setMessage(
                "Files needs Android all-files access to browse and manage shared internal, SD-card, and USB storage. App-private storage and protected Android app directories remain unavailable."
            )
            .setPositiveButton("Open settings") { _, _ ->
                awaitingSettings = true
                startActivity(FilesSetupManager.settingsIntent())
            }
            .setNegativeButton("Not now") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (awaitingSettings) {
            if (FilesSetupManager.granted()) FilesSetupManager.complete(this)
            finish()
        }
    }
}
