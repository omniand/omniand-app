package dev.omniand.hub.camera

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dev.omniand.hub.R
import dev.omniand.hub.pairing.RemoteLinksClient
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Coordinates the one-viewer Idle → PendingApproval → Streaming → Idle lifecycle. */
class CameraSessionManager private constructor(private val context: Context) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val machine = CameraSessionStateMachine()
    private var viewer: Viewer? = null
    private var peer: CameraWebRtcPeer? = null
    private var expiration: Job? = null

    data class Viewer(
        val id: String,
        val publicLinkId: String,
        val events: kotlinx.coroutines.channels.Channel<String>,
    )

    fun openViewer(userAgent: String, publicLinkId: String): Viewer {
        val newViewer =
            Viewer(
                UUID.randomUUID().toString(),
                publicLinkId,
                kotlinx.coroutines.channels.Channel(EVENT_QUEUE_SIZE),
            )
        val request =
            synchronized(lock) {
                expireLocked()
                val pending =
                    machine.begin(
                        randomId(),
                        newViewer.id,
                        publicLinkId,
                        fallbackViewerName(userAgent),
                        APPROVAL_MILLIS,
                    )
                if (pending == null) {
                    newViewer.events.trySend(signal("busy"))
                    newViewer.events.close()
                    return newViewer
                }
                viewer = newViewer
                newViewer.events.trySend(signal("pending", "requestId" to pending.requestId))
                scheduleExpirationLocked(pending)
                pending
            }
        showApprovalNotification(request)
        resolveManagedViewerName(newViewer)
        Log.i(TAG, "camera request opened")
        return newViewer
    }

    fun pendingRequest(): JSONObject? =
        synchronized(lock) {
            expireLocked()
            (machine.state as? CameraSessionStateMachine.State.PendingApproval)?.let {
                JSONObject()
                    .put(
                        "request",
                        JSONObject()
                            .put("id", it.requestId)
                            .put("viewer", it.viewerName)
                            .put("expiresAt", it.expiresAt / 1000),
                    )
            }
        }

    /** Completes a current visible phone approval; stale IDs fail closed. */
    fun decide(id: String, approved: Boolean): Boolean {
        val shouldStart =
            synchronized(lock) {
                expireLocked()
                machine.decide(id, approved) ?: return false
                expiration?.cancel()
                expiration = null
                cancelApprovalNotification()
                if (!approved) {
                    viewer?.events?.trySend(signal("error", "code" to "denied"))
                    closeViewerLocked()
                    false
                } else {
                    viewer?.events?.trySend(signal("ready"))
                    true
                }
            }
        if (shouldStart)
            context.startForegroundService(Intent(context, CameraStreamingService::class.java))
        return true
    }

    fun disconnect(id: String) {
        val shouldStop =
            synchronized(lock) {
                if (!machine.disconnect(id)) return
                Log.i(TAG, "camera viewer disconnected")
                teardownLocked(sendStopped = false)
                true
            }
        if (shouldStop) context.stopService(Intent(context, CameraStreamingService::class.java))
    }

    fun stop() {
        val shouldStop =
            synchronized(lock) {
                val active = machine.stop()
                teardownLocked(sendStopped = active)
                active
            }
        if (shouldStop) context.stopService(Intent(context, CameraStreamingService::class.java))
    }

    fun serviceDestroyed() {
        synchronized(lock) {
            machine.stop()
            teardownLocked(sendStopped = true)
        }
    }

    /** A fatal capture/ICE error reports once and immediately tears down the whole session. */
    fun fatal(code: String) {
        synchronized(lock) {
            if (machine.state == CameraSessionStateMachine.State.Idle) return
            Log.w(TAG, "camera session fatal: ${code.take(MAX_ERROR_CODE)}")
            emitErrorLocked(code, fatal = true)
            machine.stop()
            teardownLocked(sendStopped = false)
        }
        context.stopService(Intent(context, CameraStreamingService::class.java))
    }

    /** Receives already validated version-one desktop signaling. */
    fun signal(viewerId: String, value: JSONObject) {
        Log.i(TAG, "browser signal received: ${value.optString("type").take(32)}")
        if (value.optString("type") == "stop") {
            disconnect(viewerId)
            return
        }
        val activePeer = synchronized(lock) { peer?.takeIf { viewer?.id == viewerId } } ?: return
        when (value.optString("type")) {
            "offer" -> activePeer.offer(value.getString("sdp"))
            "ice-candidate" -> activePeer.candidate(value.getJSONObject("candidate"))
            "control" -> activePeer.control(value)
        }
    }

    fun attach(newPeer: CameraWebRtcPeer, credentials: TurnCredentials) {
        synchronized(lock) {
            if (machine.state !is CameraSessionStateMachine.State.Streaming) {
                newPeer.close()
                return
            }
            peer?.close()
            peer = newPeer
            Log.i(TAG, "camera peer attached; emitting ice-config")
            emitIceConfigLocked(credentials, restart = false)
        }
    }

    fun renew(target: CameraWebRtcPeer, credentials: TurnCredentials): Boolean =
        synchronized(lock) {
            if (peer !== target || machine.state !is CameraSessionStateMachine.State.Streaming)
                return false
            if (!target.updateIceServers(credentials)) {
                emitErrorLocked("turn-renewal-failed", fatal = false)
                return false
            }
            emitIceConfigLocked(credentials, restart = true)
            true
        }

    fun activePublicLinkId(): String? =
        synchronized(lock) {
            when (val state = machine.state) {
                is CameraSessionStateMachine.State.Streaming -> state.publicLinkId
                else -> null
            }
        }

    fun emit(message: JSONObject) {
        synchronized(lock) { viewer?.events?.trySend(message.toString()) }
    }

    fun emitError(code: String) = synchronized(lock) { emitErrorLocked(code, fatal = false) }

    private fun emitErrorLocked(code: String, fatal: Boolean) {
        viewer
            ?.events
            ?.trySend(
                JSONObject()
                    .put("version", 1)
                    .put("type", "error")
                    .put("code", code.take(MAX_ERROR_CODE))
                    .put("fatal", fatal)
                    .toString()
            )
    }

    private fun emitIceConfigLocked(credentials: TurnCredentials, restart: Boolean) {
        emit(
            JSONObject()
                .put("version", 1)
                .put("type", "ice-config")
                .put("restart", restart)
                .put("expiresAt", credentials.expiresAtMillis / 1000)
                .put(
                    "iceServers",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("urls", JSONArray(credentials.urls))
                                .put("username", credentials.browserUsername)
                                .put("credential", credentials.browserCredential)
                        ),
                )
        )
    }

    private fun scheduleExpirationLocked(pending: CameraSessionStateMachine.State.PendingApproval) {
        expiration?.cancel()
        expiration = scope.launch {
            delay((pending.expiresAt - System.currentTimeMillis()).coerceAtLeast(1L))
            synchronized(lock) { expireLocked() }
        }
    }

    private fun expireLocked() {
        val expired = machine.expire() ?: return
        cancelApprovalNotification()
        viewer?.events?.trySend(signal("error", "code" to "expired"))
        teardownLocked(sendStopped = false)
    }

    private fun teardownLocked(sendStopped: Boolean) {
        expiration?.cancel()
        expiration = null
        cancelApprovalNotification()
        if (sendStopped) viewer?.events?.trySend(signal("stopped"))
        peer?.close()
        peer = null
        closeViewerLocked()
    }

    private fun closeViewerLocked() {
        viewer?.events?.close()
        viewer = null
    }

    /** Resolves Relay-managed link names without delaying or trusting signaling headers. */
    private fun resolveManagedViewerName(target: Viewer) {
        scope.launch(Dispatchers.IO) {
            val links =
                runCatching { RemoteLinksClient(context).list() }.getOrNull() ?: return@launch
            val link =
                (0 until links.length()).asSequence().map(links::getJSONObject).firstOrNull {
                    it.optString("publicLinkId") == target.publicLinkId
                } ?: return@launch
            val name =
                link.optString("name").takeIf { it.isNotBlank() }
                    ?: listOf(link.optString("browser"), link.optString("platform"))
                        .filter(String::isNotBlank)
                        .joinToString(" on ")
                        .takeIf(String::isNotBlank)
                    ?: return@launch
            val updated =
                synchronized(lock) {
                    if (!machine.renamePending(target.id, name.take(64))) null
                    else machine.state as? CameraSessionStateMachine.State.PendingApproval
                }
            updated?.let(::showApprovalNotification)
        }
    }

    private fun showApprovalNotification(pending: CameraSessionStateMachine.State.PendingApproval) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                REQUEST_CHANNEL,
                "Camera requests",
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
        val allowed =
            (Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED) && manager.areNotificationsEnabled()
        if (!allowed) return
        val open =
            PendingIntent.getActivity(
                context,
                pending.requestId.hashCode(),
                Intent(context, CameraApprovalActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        manager.notify(
            REQUEST_NOTIFICATION_ID,
            Notification.Builder(context, REQUEST_CHANNEL)
                .setSmallIcon(R.drawable.ic_hub_foreground)
                .setContentTitle("Camera request")
                .setContentText("${pending.viewerName} wants to use the camera and microphone.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setTimeoutAfter(APPROVAL_MILLIS)
                .build(),
        )
    }

    private fun cancelApprovalNotification() {
        context.getSystemService(NotificationManager::class.java).cancel(REQUEST_NOTIFICATION_ID)
    }

    private fun fallbackViewerName(userAgent: String): String =
        when {
            userAgent.contains("Firefox", true) -> "Firefox"
            userAgent.contains("Chrome", true) || userAgent.contains("Chromium", true) -> "Chromium"
            else -> "Paired browser"
        }

    private fun signal(type: String, vararg fields: Pair<String, String>) =
        JSONObject()
            .put("version", 1)
            .put("type", type)
            .also { value -> fields.forEach { value.put(it.first, it.second) } }
            .toString()

    private fun randomId(): String {
        val bytes = ByteArray(18).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private const val TAG = "OmniAndCamera"
        private const val APPROVAL_MILLIS = 60_000L
        private const val EVENT_QUEUE_SIZE = 64
        private const val MAX_ERROR_CODE = 80
        private const val REQUEST_CHANNEL = "camera-requests"
        // Must not collide with BackgroundHostingService's foreground notification (7401).
        private const val REQUEST_NOTIFICATION_ID = 7403
        @Volatile private var singleton: CameraSessionManager? = null

        fun instance(context: Context): CameraSessionManager =
            singleton
                ?: synchronized(this) {
                    singleton
                        ?: CameraSessionManager(context.applicationContext).also { singleton = it }
                }
    }
}
