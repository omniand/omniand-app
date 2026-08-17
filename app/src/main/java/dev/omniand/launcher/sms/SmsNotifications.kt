package dev.omniand.launcher.sms

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.omniand.launcher.MainActivity
import dev.omniand.launcher.WebAppActivity

object SmsNotifications {
    private const val CHANNEL = "incoming-messages"

    fun publish(
        context: Context,
        threadId: String,
        title: String,
        preview: String,
        timestamp: Long,
    ) {
        if (
            WrapperNotificationRelay.publish(
                context,
                threadId,
                notificationId(threadId),
                title,
                preview,
                timestamp,
            )
        )
            return
        ensureChannel(context)
        if (
            Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        )
            return
        val messagesInstalled =
            runCatching {
                    dev.omniand.launcher.webapps.WebAppRegistry.apps(context).any {
                        it.id == "messages"
                    }
                }
                .getOrDefault(false)
        val click =
            if (messagesInstalled)
                Intent(context, WebAppActivity::class.java)
                    .putExtra(WebAppActivity.EXTRA_APP_ID, "messages")
                    .putExtra(WebAppActivity.EXTRA_ROUTE, "#/thread?id=$threadId")
                    .putExtra(WebAppActivity.EXTRA_THREAD_ID, threadId)
            else Intent(context, MainActivity::class.java)
        val pending =
            PendingIntent.getActivity(
                context,
                notificationId(threadId),
                click,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle(title)
                .setContentText(preview)
                .setWhen(timestamp)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, CHANNEL)
                        .setSmallIcon(android.R.drawable.sym_action_chat)
                        .setContentTitle("Messages")
                        .setContentText("New message")
                        .build()
                )
                .setContentIntent(pending)
                .build()
        NotificationManagerCompat.from(context).notify(notificationId(threadId), notification)
    }

    fun publishMmsUnsupported(context: Context, timestamp: Long) =
        publish(
            context,
            "mms-$timestamp",
            "Messages",
            "MMS received — display not supported",
            timestamp,
        )

    fun cancelThread(context: Context, threadId: String) {
        WrapperNotificationRelay.cancelThread(context, threadId)
        NotificationManagerCompat.from(context).cancel(notificationId(threadId))
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26)
            context
                .getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH)
                )
    }

    fun notificationId(threadId: String): Int = threadId.hashCode() and 0x7fffffff
}
