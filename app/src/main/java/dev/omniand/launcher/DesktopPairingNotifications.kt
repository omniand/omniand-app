package dev.omniand.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.omniand.launcher.server.DesktopPairing

/** Surfaces pending desktop requests without making notification delivery an approval boundary. */
object DesktopPairingNotifications {
    private const val CHANNEL = "desktop-pairing"
    private const val NOTIFICATION_ID = 0x50414952
    private var visibleRequest: String? = null

    fun publish(context: Context, request: DesktopPairing.Request) {
        MainActivity.activeInstance?.let { activity ->
            activity.runOnUiThread { showPending(activity) }
        }
        ensureChannel(context)
        if (
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            return
        val intent =
            Intent(context, DesktopPairingApprovalActivity::class.java)
                .putExtra(DesktopPairingApprovalActivity.EXTRA_REQUEST_ID, request.id)
        val pending =
            PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("Desktop access requested")
                .setContentText("Tap to review ${request.peerAddress}")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    fun showPending(activity: Activity) {
        val request = DesktopPairing.pending().firstOrNull() ?: return
        if (visibleRequest == request.id) return
        visibleRequest = request.id
        approvalDialog(activity, request) { visibleRequest = null }.show()
    }

    fun approvalDialog(
        activity: Activity,
        request: DesktopPairing.Request,
        finished: () -> Unit,
    ): AlertDialog =
        AlertDialog.Builder(activity)
            .setTitle("Allow desktop access?")
            .setMessage(
                "Requester: ${request.peerAddress}\n\n${request.userAgent.ifBlank { "Unknown browser" }}\n\n" +
                    "Access lasts until OmniAnd stops."
            )
            .setNegativeButton("Deny") { _, _ -> DesktopPairing.decide(request.id, false) }
            .setPositiveButton("Allow") { _, _ -> DesktopPairing.decide(request.id, true) }
            .setOnDismissListener { finished() }
            .create()

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26)
            context
                .getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        "Desktop access",
                        NotificationManager.IMPORTANCE_HIGH,
                    )
                )
    }
}
