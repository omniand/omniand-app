package dev.omniand.hub.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.omniand.hub.MainActivity
import dev.omniand.hub.R

/** Foreground privacy boundary for an approved stream; it is never restored from boot. */
class CameraStreamingService : Service() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL, "Camera streaming", NotificationManager.IMPORTANCE_LOW)
            )
        startForeground(ID, notification())
        runCatching {
                CameraSessionManager.instance(this)
                    .attach(CameraWebRtcPeer(this, CameraSessionManager.instance(this)))
            }
            .onFailure {
                CameraSessionManager.instance(this)
                    .emit(
                        org.json
                            .JSONObject()
                            .put("version", 1)
                            .put("type", "error")
                            .put("code", "camera-unavailable")
                    )
                stopSelf()
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            CameraSessionManager.instance(this).stop()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        CameraSessionManager.instance(this).stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, CameraStreamingService::class.java).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_hub_foreground)
            .setContentTitle("Camera is streaming")
            .setContentText("A paired computer can view your camera and microphone.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "camera-streaming"
        private const val ID = 7402
        private const val STOP = "dev.omniand.hub.STOP_CAMERA"
    }
}
