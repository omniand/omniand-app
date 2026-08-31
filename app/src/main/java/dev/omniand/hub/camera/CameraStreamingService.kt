package dev.omniand.hub.camera

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dev.omniand.hub.MainActivity
import dev.omniand.hub.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Foreground owner of the complete CameraX/WebRTC lifecycle; never restored after boot. */
class CameraStreamingService : Service(), LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = registry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "camera streaming service created")
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL, "Camera streaming", NotificationManager.IMPORTANCE_LOW)
            )
        startForeground(ID, notification())
        scope.launch { startPeer() }
        scope.launch { monitorPermissions() }
    }

    private suspend fun monitorPermissions() {
        while (scope.isActive) {
            if (
                checkSelfPermission(Manifest.permission.CAMERA) !=
                    PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                        PackageManager.PERMISSION_GRANTED
            ) {
                CameraSessionManager.instance(this).fatal("permission-revoked")
                stopSelf()
                return
            }
            delay(PERMISSION_CHECK_MILLIS)
        }
    }

    private suspend fun startPeer() {
        val manager = CameraSessionManager.instance(this)
        try {
            val publicLinkId = checkNotNull(manager.activePublicLinkId())
            var issuedAt = System.currentTimeMillis()
            var credentials = TurnCredentialsClient(this).issue(publicLinkId)
            Log.i(TAG, "TURN credentials issued")
            val peer = CameraWebRtcPeer(this, this, manager, credentials)
            manager.attach(peer, credentials)
            while (scope.isActive) {
                delay(TurnRenewal.delayMillis(issuedAt, credentials.expiresAtMillis))
                credentials = renewUntilExpiry(manager, peer, publicLinkId, credentials)
                issuedAt = System.currentTimeMillis()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "camera peer startup failed", error)
            manager.fatal("turn-unavailable")
            stopSelf()
        }
    }

    /** Retries transient issuance failures while the existing allocation is still valid. */
    private suspend fun renewUntilExpiry(
        manager: CameraSessionManager,
        peer: CameraWebRtcPeer,
        publicLinkId: String,
        current: TurnCredentials,
    ): TurnCredentials {
        while (scope.isActive) {
            val renewed =
                runCatching { TurnCredentialsClient(this).issue(publicLinkId) }.getOrNull()
            if (renewed != null && manager.renew(peer, renewed)) return renewed
            if (System.currentTimeMillis() >= current.expiresAtMillis)
                error("TURN credentials expired")
            manager.emitError("turn-renewal-retrying")
            delay(
                minOf(
                    RENEW_RETRY_MILLIS,
                    (current.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(1L),
                )
            )
        }
        throw CancellationException("Camera service stopped")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            CameraSessionManager.instance(this).stop()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "camera streaming service destroyed")
        scope.cancel()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        CameraSessionManager.instance(this).serviceDestroyed()
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

    private companion object {
        private const val TAG = "OmniAndCamera"
        private const val CHANNEL = "camera-streaming"
        private const val ID = 7402
        private const val STOP = "dev.omniand.hub.STOP_CAMERA"
        private const val RENEW_RETRY_MILLIS = 30_000L
        private const val PERMISSION_CHECK_MILLIS = 1_000L
    }
}
