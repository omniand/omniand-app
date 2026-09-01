package dev.omniand.hub.wrappers

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Opens a trusted wrapper's notification prompt as part of a visible, feature-specific setup. */
object WrapperNotificationPermission {
    private const val ACTIVITY = "dev.omniand.wrapper.runtime.NotificationPermissionActivity"

    fun request(activity: Activity, appId: String, requestCode: Int): Boolean {
        if (Build.VERSION.SDK_INT < 33 || !WrapperInstaller.isTrustedWrapper(activity, appId))
            return false
        val packageName = WrapperInstaller.packageName(appId)
        if (
            activity.packageManager.checkPermission(
                Manifest.permission.POST_NOTIFICATIONS,
                packageName,
            ) == PackageManager.PERMISSION_GRANTED
        )
            return false
        return runCatching {
                activity.startActivityForResult(
                    Intent().setComponent(ComponentName(packageName, ACTIVITY)),
                    requestCode,
                )
                true
            }
            .getOrDefault(false)
    }
}
