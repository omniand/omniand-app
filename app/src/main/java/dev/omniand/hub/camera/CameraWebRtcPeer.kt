package dev.omniand.hub.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import dev.omniand.hub.BuildConfig
import dev.omniand.hub.services.MediaService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/** Owns CameraX still/preview capture and one reusable native PeerConnection. */
class CameraWebRtcPeer(
    private val context: Context,
    lifecycleOwner: LifecycleOwner,
    private val manager: CameraSessionManager,
    credentials: TurnCredentials,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val factory: PeerConnectionFactory
    private val eglBase: EglBase
    private val videoSource: VideoSource
    private val connection: PeerConnection
    private val videoTrack: VideoTrack
    private val capturer: CameraXCapturer
    private var rtcConfiguration = configuration(credentials)
    private val photoInFlight = AtomicBoolean(false)
    private var captureStarted = false
    private var closed = false
    private var remoteDescriptionSet = false
    private val pendingCandidates = ArrayDeque<IceCandidate>()
    private var disconnectedTimeout: Runnable? = null
    private var negotiationTimeout: Runnable? = null
    private var initialFailureTimeout: Runnable? = null
    private var everConnected = false

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        eglBase = EglBase.create()
        factory =
            PeerConnectionFactory.builder()
                .setVideoEncoderFactory(
                    DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
                )
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                .createPeerConnectionFactory()
        videoSource = factory.createVideoSource(false)
        videoSource.adaptOutputFormat(MAX_WIDTH, MAX_HEIGHT, MAX_FPS)
        connection =
            factory.createPeerConnection(rtcConfiguration, observer())
                ?: error("PeerConnection unavailable")
        videoTrack = factory.createVideoTrack("camera-video", videoSource)
        videoTrack.setEnabled(true)
        connection.setAudioPlayout(false)
        capturer =
            CameraXCapturer(
                context,
                lifecycleOwner,
                videoSource.capturerObserver,
                onState = { manager.emit(it.toJson()) },
                onFailure = manager::fatal,
            )
    }

    /** Accepts initial and ICE-restart offers without replacing the native connection. */
    fun offer(sdp: String) {
        if (closed || sdp.length !in 1..CameraSignalValidator.MAX_SDP) return
        Log.i(TAG, "remote offer received")
        scheduleNegotiationTimeout()
        synchronized(this) { remoteDescriptionSet = false }
        connection.setRemoteDescription(
            object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    Log.i(TAG, "remote offer accepted")
                    val queued =
                        synchronized(this@CameraWebRtcPeer) {
                            remoteDescriptionSet = true
                            pendingCandidates.toList().also { pendingCandidates.clear() }
                        }
                    queued.forEach(connection::addIceCandidate)
                    runCatching { configureSenders() }
                        .onSuccess { createAnswer() }
                        .onFailure { manager.fatal("invalid-offer") }
                }

                override fun onSetFailure(error: String) {
                    Log.w(TAG, "remote offer rejected: ${error.take(160)}")
                    manager.fatal("offer-rejected")
                }
            },
            SessionDescription(SessionDescription.Type.OFFER, sdp),
        )
    }

    fun candidate(value: JSONObject) {
        if (closed) return
        val candidate = value.optString("candidate")
        if (candidate.length !in 1..CameraSignalValidator.MAX_CANDIDATE) return
        val nativeCandidate =
            IceCandidate(
                value.optString("sdpMid"),
                value.optInt("sdpMLineIndex", 0),
                candidate,
            )
        val applyNow =
            synchronized(this) {
                if (remoteDescriptionSet) true
                else {
                    if (pendingCandidates.size >= CameraSignalValidator.MAX_PENDING_CANDIDATES) {
                        manager.fatal("ice-queue-overflow")
                        return
                    }
                    pendingCandidates.addLast(nativeCandidate)
                    false
                }
            }
        if (applyNow) connection.addIceCandidate(nativeCandidate)
    }

    /** Applies only controls supported by the current CameraX binding. */
    fun control(value: JSONObject) {
        if (closed) return
        mainHandler.post {
            var error: String? = null
            if (value.has("camera")) {
                val facing = CameraFacing.fromWireName(value.optString("camera"))
                error = facing?.let(capturer::switchCamera) ?: "camera-unavailable"
            }
            if (error == null && value.has("torch"))
                error = capturer.setTorch(value.getBoolean("torch"))
            if (error == null && value.has("zoom"))
                error = capturer.setZoom(value.getDouble("zoom"))
            if (error == null && value.has("flashMode"))
                error = capturer.setFlashMode(value.getString("flashMode"))
            if (error != null) manager.emitError(error)
        }
    }

    /** Captures one correlated JPEG and publishes it into Pictures/OmniAnd. */
    fun capture(requestId: String) {
        if (closed || !photoInFlight.compareAndSet(false, true)) {
            manager.emitCaptureError(requestId, "capture-busy")
            return
        }
        manager.emitCaptureStarted(requestId)
        val name = SimpleDateFormat("'IMG_'yyyyMMdd_HHmmss_SSS'.jpg'", Locale.US).format(Date())
        val file = File(context.cacheDir, "camera-$requestId.jpg")
        file.delete()
        val startError =
            capturer.capture(file) { result ->
                try {
                    result
                        .onSuccess {
                            if (it.length() !in 1..MAX_CAPTURE_BYTES) error("Invalid camera output")
                            manager.emitCaptureComplete(
                                requestId,
                                MediaService(context).publish(it, name, "image/jpeg"),
                            )
                        }
                        .onFailure { manager.emitCaptureError(requestId, "capture-failed") }
                } catch (_: Exception) {
                    manager.emitCaptureError(requestId, "capture-failed")
                } finally {
                    file.delete()
                    photoInFlight.set(false)
                }
            }
        if (startError != null) {
            file.delete()
            photoInFlight.set(false)
            manager.emitCaptureError(requestId, startError)
        }
    }

    /** Installs renewed credentials in place before the browser creates its restart offer. */
    fun updateIceServers(credentials: TurnCredentials): Boolean {
        if (closed) return false
        rtcConfiguration = configuration(credentials)
        return connection.setConfiguration(rtcConfiguration)
    }

    fun close() {
        val shouldDispose =
            synchronized(this) {
                if (closed) false
                else {
                    closed = true
                    true
                }
            }
        if (!shouldDispose) return
        if (Looper.myLooper() == Looper.getMainLooper()) dispose() else mainHandler.post(::dispose)
    }

    /** Native WebRTC and CameraX teardown must not run inside a WebRTC callback thread. */
    private fun dispose() {
        cancelDisconnectedTimeout()
        cancelNegotiationTimeout()
        cancelInitialFailureTimeout()
        synchronized(this) { pendingCandidates.clear() }
        if (captureStarted) capturer.close()
        connection.close()
        connection.dispose()
        videoTrack.dispose()
        videoSource.dispose()
        factory.dispose()
        eglBase.release()
    }

    private fun createAnswer() {
        connection.createAnswer(
            object : SdpObserverAdapter() {
                override fun onCreateSuccess(description: SessionDescription) {
                    Log.i(TAG, "local answer created")
                    connection.setLocalDescription(
                        object : SdpObserverAdapter() {
                            override fun onSetSuccess() {
                                Log.i(TAG, "local answer set; starting capture")
                                manager.emit(
                                    JSONObject()
                                        .put("version", 1)
                                        .put("type", "answer")
                                        .put("sdp", description.description)
                                )
                                startCapture()
                            }

                            override fun onSetFailure(error: String) =
                                manager.fatal("answer-rejected")
                        },
                        description,
                    )
                }

                override fun onCreateFailure(error: String) = manager.fatal("answer-failed")
            },
            MediaConstraints(),
        )
    }

    /** Reuses the remote offer's transceivers and bounds outgoing video adaptation. */
    private fun configureSenders() {
        val transceivers = connection.transceivers
        val video =
            checkNotNull(
                transceivers.firstOrNull {
                    it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO && !it.isStopped
                }
            )
        check(video.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
        check(video.sender.setTrack(videoTrack, false))
        video.sender.setStreams(listOf("omniand"))
        val parameters = video.sender.parameters
        parameters.encodings.forEach {
            it.maxFramerate = MAX_FPS
            it.maxBitrateBps = MAX_VIDEO_BITRATE
        }
        video.sender.setParameters(parameters)
    }

    @Synchronized
    private fun startCapture() {
        if (captureStarted || closed) return
        captureStarted = true
        capturer.start()
    }

    private fun observer() =
        object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.i(TAG, "local ICE candidate: ${candidateSummary(candidate.sdp)}")
                manager.emit(
                    JSONObject()
                        .put("version", 1)
                        .put("type", "ice-candidate")
                        .put(
                            "candidate",
                            JSONObject()
                                .put("sdpMid", candidate.sdpMid)
                                .put("sdpMLineIndex", candidate.sdpMLineIndex)
                                .put("candidate", candidate.sdp),
                        )
                )
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.i(TAG, "ICE connection: $state")
                when (state) {
                    PeerConnection.IceConnectionState.DISCONNECTED -> scheduleDisconnectedTimeout()
                    PeerConnection.IceConnectionState.FAILED -> {
                        if (everConnected) manager.fatal("ice-failed")
                        else scheduleInitialFailureTimeout()
                    }
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        everConnected = true
                        cancelDisconnectedTimeout()
                        cancelNegotiationTimeout()
                        cancelInitialFailureTimeout()
                    }
                    else -> Unit
                }
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                if (state == PeerConnection.PeerConnectionState.FAILED) {
                    if (everConnected) manager.fatal("peer-failed")
                    else scheduleInitialFailureTimeout()
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit

            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit

            override fun onAddStream(stream: org.webrtc.MediaStream) = Unit

            override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit

            override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit

            override fun onRenegotiationNeeded() = Unit
        }

    private fun scheduleDisconnectedTimeout() {
        cancelDisconnectedTimeout()
        val timeout = Runnable { manager.fatal("ice-disconnected") }
        disconnectedTimeout = timeout
        mainHandler.postDelayed(timeout, DISCONNECTED_GRACE_MILLIS)
    }

    private fun cancelDisconnectedTimeout() {
        disconnectedTimeout?.let(mainHandler::removeCallbacks)
        disconnectedTimeout = null
    }

    /** Prevents a TURN outage from leaving capture active forever in NEW/CHECKING. */
    private fun scheduleNegotiationTimeout() {
        cancelNegotiationTimeout()
        val timeout = Runnable { manager.fatal("ice-timeout") }
        negotiationTimeout = timeout
        mainHandler.postDelayed(timeout, NEGOTIATION_TIMEOUT_MILLIS)
    }

    private fun cancelNegotiationTimeout() {
        negotiationTimeout?.let(mainHandler::removeCallbacks)
        negotiationTimeout = null
    }

    /** Allows relay candidates that are still trickling when native ICE first reports FAILED. */
    private fun scheduleInitialFailureTimeout() {
        cancelInitialFailureTimeout()
        val timeout = Runnable { manager.fatal("ice-failed") }
        initialFailureTimeout = timeout
        mainHandler.postDelayed(timeout, INITIAL_TRICKLE_GRACE_MILLIS)
    }

    private fun cancelInitialFailureTimeout() {
        initialFailureTimeout?.let(mainHandler::removeCallbacks)
        initialFailureTimeout = null
    }

    private fun configuration(credentials: TurnCredentials): PeerConnection.RTCConfiguration {
        val urls =
            androidTurnUrls(credentials.urls).let { configured ->
                if (BuildConfig.DEBUG_ICE_RELAY_ONLY)
                    configured.filter { it.startsWith("turn:") && "transport=udp" in it }
                else configured
            }
        if (credentials.urls.isNotEmpty())
            check(urls.isNotEmpty()) { "No compatible ICE server URL" }
        val servers = urls.map { url ->
            val builder = PeerConnection.IceServer.builder(url)
            if (url.startsWith("turn:") || url.startsWith("turns:")) {
                builder
                    .setUsername(credentials.androidUsername)
                    .setPassword(credentials.androidCredential)
            }
            builder.createIceServer()
        }
        return PeerConnection.RTCConfiguration(servers).also {
            if (BuildConfig.DEBUG_ICE_RELAY_ONLY && credentials.urls.isNotEmpty())
                it.iceTransportsType = PeerConnection.IceTransportsType.RELAY
        }
    }

    private fun androidTurnUrls(urls: List<String>): List<String> {
        val alias = BuildConfig.DEBUG_TURN_HOST_ALIAS
        if (!BuildConfig.DEBUG || alias.isBlank()) return urls
        return urls.map { url ->
            if (url.startsWith("turn:") || url.startsWith("turns:")) replaceTurnHost(url, alias)
            else url
        }
    }

    /** Diagnostic only: excludes candidate addresses, ports, and credentials. */
    private fun candidateSummary(sdp: String): String {
        val type = Regex("\\btyp ([a-z]+)").find(sdp)?.groupValues?.getOrNull(1) ?: "unknown"
        val transport =
            Regex("^candidate:\\S+ \\d+ ([A-Za-z]+)")
                .find(sdp)
                ?.groupValues
                ?.getOrNull(1)
                ?.lowercase()
        return listOfNotNull(type, transport).joinToString("/")
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit

        override fun onSetSuccess() = Unit

        override fun onCreateFailure(error: String) = Unit

        override fun onSetFailure(error: String) = Unit
    }

    private companion object {
        const val TAG = "OmniAndCamera"
        const val MAX_WIDTH = 1280
        const val MAX_HEIGHT = 720
        const val MAX_FPS = 30
        const val MAX_VIDEO_BITRATE = 4_000_000
        const val MAX_CAPTURE_BYTES = 50L * 1024 * 1024
        const val DISCONNECTED_GRACE_MILLIS = 15_000L
        const val NEGOTIATION_TIMEOUT_MILLIS = 30_000L
        const val INITIAL_TRICKLE_GRACE_MILLIS = 5_000L
    }
}

internal fun replaceTurnHost(url: String, host: String): String {
    val hostStart = url.indexOf(':') + 1
    if (hostStart <= 0 || host.isBlank()) return url
    val hostEnd =
        url.indexOfAny(charArrayOf(':', '/', '?'), startIndex = hostStart).let {
            if (it == -1) url.length else it
        }
    return url.replaceRange(hostStart, hostEnd, host)
}
