package dev.omniand.hub.camera

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.omniand.hub.pairing.DeviceIdentity
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject

/**
 * Owns the one-viewer camera state machine. Signaling peers receive only bounded JSON control
 * events; Android capture is deliberately owned by CameraStreamingService rather than hosting.
 */
class CameraSessionManager private constructor(private val context: Context) {
    private val lock = Any()
    private var pending: Pending? = null
    private var viewer: Viewer? = null
    private var peer: CameraWebRtcPeer? = null

    data class Viewer(val id: String, val events: Channel<String>)

    private data class Pending(
        val id: String,
        val viewerId: String,
        val name: String,
        val expiresAt: Long,
    )

    fun openViewer(userAgent: String): Viewer {
        synchronized(lock) {
            expireLocked()
            val events = Channel<String>(16)
            if (viewer != null || pending != null) {
                events.trySend(signal("busy"))
                events.close()
                return Viewer(UUID.randomUUID().toString(), events)
            }
            val newViewer = Viewer(UUID.randomUUID().toString(), events)
            viewer = newViewer
            val request =
                Pending(
                    randomId(),
                    newViewer.id,
                    viewerName(userAgent),
                    System.currentTimeMillis() + APPROVAL_MILLIS,
                )
            pending = request
            events.trySend(signal("pending", "requestId" to request.id))
            Log.i(TAG, "camera request opened")
            context.startActivity(
                Intent(context, CameraApprovalActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return newViewer
        }
    }

    fun pendingRequest(): JSONObject? =
        synchronized(lock) {
            expireLocked()
            pending?.let {
                JSONObject()
                    .put(
                        "request",
                        JSONObject()
                            .put("id", it.id)
                            .put("viewer", it.name)
                            .put("expiresAt", it.expiresAt / 1000),
                    )
            }
        }

    /** Completes a current phone-local approval; stale IDs fail closed. */
    fun decide(id: String, approved: Boolean): Boolean =
        synchronized(lock) {
            expireLocked()
            val request = pending?.takeIf { it.id == id } ?: return false
            pending = null
            val active = viewer?.takeIf { it.id == request.viewerId } ?: return false
            if (!approved) {
                active.events.trySend(signal("error", "code" to "denied"))
                active.events.close()
                viewer = null
                return true
            }
            Log.i(TAG, "camera request approved")
            active.events.trySend(signal("ready"))
            context.startForegroundService(Intent(context, CameraStreamingService::class.java))
            return true
        }

    fun disconnect(id: String) =
        synchronized(lock) {
            if (viewer?.id == id) {
                pending = null
                viewer?.events?.close()
                viewer = null
                peer?.close()
                peer = null
                context.stopService(Intent(context, CameraStreamingService::class.java))
            }
        }

    fun stop() =
        synchronized(lock) {
            pending = null
            viewer?.events?.trySend(signal("stopped"))
            viewer?.events?.close()
            viewer = null
            peer?.close()
            peer = null
        }

    /** Receives bounded, versioned desktop signaling after WebSocket authorization. */
    fun signal(viewerId: String, signal: JSONObject) {
        val type = signal.optString("type")
        if (type == "stop") {
            disconnect(viewerId)
            return
        }
        // Native peer calls may synchronously wait for WebRTC's signaling thread. Never hold the
        // manager lock while making them: that same thread emits answer/candidate events back here.
        val activePeer = synchronized(lock) { peer?.takeIf { viewer?.id == viewerId } } ?: return
        when (type) {
            "offer" -> {
                Log.i(TAG, "browser offer received")
                activePeer.offer(signal.optString("sdp"))
            }
            "ice-candidate" -> {
                Log.i(TAG, "browser ICE candidate received")
                signal.optJSONObject("candidate")?.let(activePeer::candidate)
            }
            "control" -> activePeer.control(signal)
        }
    }

    /** Attaches the foreground-service owned peer and starts browser offer creation. */
    fun attach(peer: CameraWebRtcPeer) =
        synchronized(lock) {
            if (viewer == null) {
                peer.close()
                return
            }
            this.peer?.close()
            this.peer = peer
            Log.i(TAG, "camera peer attached; sending ICE configuration")
            val turnHost =
                "turn.${DeviceIdentity(context).baseHost() ?: dev.omniand.hub.BuildConfig.PLATFORM_HOST}"
            emit(
                JSONObject()
                    .put("version", 1)
                    .put("type", "ice-config")
                    .put(
                        "iceServers",
                        org.json
                            .JSONArray()
                            .put(
                                JSONObject()
                                    .put("urls", org.json.JSONArray().put("stun:$turnHost:3478"))
                            ),
                    )
            )
        }

    fun emit(message: JSONObject) {
        synchronized(lock) {
            Log.i(TAG, "camera signal sent: ${message.optString("type")}")
            viewer?.events?.trySend(message.toString())
        }
    }

    private fun expireLocked() {
        if (pending != null && pending!!.expiresAt <= System.currentTimeMillis()) {
            viewer?.events?.trySend(signal("error", "code" to "expired"))
            viewer?.events?.close()
            viewer = null
            peer?.close()
            peer = null
            pending = null
        }
    }

    private fun viewerName(userAgent: String): String =
        when {
            userAgent.contains("Firefox", true) -> "Firefox"
            userAgent.contains("Chrome", true) || userAgent.contains("Chromium", true) -> "Chromium"
            else -> "Paired browser"
        }

    private fun signal(type: String, vararg fields: Pair<String, String>) = buildString {
        append("{\"version\":1,\"type\":\"").append(type).append('\"')
        fields.forEach { (key, value) ->
            append(",\"").append(key).append("\":\"").append(escape(value)).append('\"')
        }
        append('}')
    }

    private fun randomId(): String {
        val bytes = ByteArray(18).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        private const val TAG = "OmniAndCamera"
        private const val APPROVAL_MILLIS = 60_000L
        @Volatile private var singleton: CameraSessionManager? = null

        fun instance(context: Context): CameraSessionManager =
            singleton
                ?: synchronized(this) {
                    singleton
                        ?: CameraSessionManager(context.applicationContext).also { singleton = it }
                }
    }
}
