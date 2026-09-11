package dev.omniand.hub.background

import android.app.*
import android.content.Intent
import android.net.Uri
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.omniand.hub.R

/** Data-only FCM messages display a confirmation entry point and never start hosting. */
class WakeMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        WakeRegistration.enqueue(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (BackgroundHostingManager.mode(this) != "on-demand") return
        val id = message.data["requestId"]?.takeIf { it.matches(Regex("[0-9a-f]{32}")) } ?: return
        val prefs = getSharedPreferences("wake-notifications", MODE_PRIVATE)
        // A bounded durable duplicate set prevents repeated alerts after process recreation.
        val seen = prefs.getStringSet("seen", emptySet())!!.toMutableSet()
        if (!seen.add(id)) return
        prefs.edit().putStringSet("seen", seen.toList().takeLast(64).toSet()).apply()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                "remote-requests",
                "Remote connection requests",
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, WakeConfirmationActivity::class.java)
                    .setData(Uri.parse("omniand-wake:$id"))
                    .putExtra("requestId", id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        if (!manager.areNotificationsEnabled()) return
        manager.notify(
            id,
            7402,
            Notification.Builder(this, "remote-requests")
                .setSmallIcon(R.drawable.ic_hub_foreground)
                .setContentTitle("OmniAnd connection request")
                .setContentText(
                    "${message.data["browser"]?.take(100) ?: "A paired browser"} wants to connect"
                )
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setTimeoutAfter(120_000)
                .build(),
        )
    }
}
