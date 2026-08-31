package dev.omniand.hub.camera

import android.content.Context
import android.os.Build
import android.util.Log
import dev.omniand.hub.BuildConfig
import dev.omniand.hub.pairing.DeviceIdentity
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Capturer
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
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
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/** Owns native WebRTC capture resources for exactly one foreground-approved viewing session. */
class CameraWebRtcPeer(
    private val context: Context,
    private val manager: CameraSessionManager,
    credentials: TurnCredentials,
) {
    private val factory: PeerConnectionFactory
    private val eglBase: EglBase
    private val videoSource: VideoSource
    private val audioSource: AudioSource
    private val capturer: Camera2Capturer
    private val helper: SurfaceTextureHelper
    private val connection: PeerConnection
    // Keep Java wrappers alive for the entire capture lifetime. The native peer connection does
    // not own those wrappers and recent WebRTC releases otherwise can receive a camera frame with
    // no attached video sink.
    private val videoTrack: VideoTrack
    private val audioTrack: AudioTrack

    @Volatile private var captureStarted = false
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        // The current WebRTC SDK does not supply video codec factories when the builder is
        // left at its defaults. Its worker thread then aborts as soon as a video sender is
        // created. Keep one EGL context shared by capture and the hardware/software factories.
        eglBase = EglBase.create()
        factory =
            PeerConnectionFactory.builder()
                .setVideoEncoderFactory(
                    DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
                )
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                .createPeerConnectionFactory()
        videoSource = factory.createVideoSource(false)
        audioSource = factory.createAudioSource(MediaConstraints())
        val enumerator = Camera2Enumerator(context)
        val camera =
            enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
                ?: error("No camera available")
        capturer =
            Camera2Capturer(
                context,
                camera,
                object : CameraVideoCapturer.CameraEventsHandler {
                    override fun onCameraError(errorDescription: String) = fail("camera-error")

                    override fun onCameraDisconnected() = fail("camera-disconnected")

                    override fun onCameraFreezed(errorDescription: String) = fail("camera-frozen")

                    override fun onCameraOpening(cameraName: String) = Unit

                    override fun onFirstFrameAvailable() {
                        Log.i(TAG, "first camera frame delivered to WebRTC")
                    }

                    override fun onCameraClosed() = Unit
                },
            )
        helper = SurfaceTextureHelper.create("OmniAndCamera", eglBase.eglBaseContext)
        val servers =
            androidTurnUrls(credentials.urls).map { url ->
                PeerConnection.IceServer.builder(url)
                    .setUsername(credentials.androidUsername)
                    .setPassword(credentials.androidCredential)
                    .createIceServer()
            }
        Log.i(TAG, "configured ${servers.size} ICE server URL(s); emulator=${isEmulator()}")
        val rtcConfiguration = PeerConnection.RTCConfiguration(servers)
        // The emulator's 10.0.2.x candidates are private to its virtual NAT,
        // so they cannot be reached by a desktop browser. Force its side onto
        // a TURN allocation; physical devices retain direct ICE first.
        if (isEmulator())
            rtcConfiguration.iceTransportsType = PeerConnection.IceTransportsType.RELAY
        connection =
            factory.createPeerConnection(rtcConfiguration, observer())
                ?: error("PeerConnection unavailable")
        videoTrack = factory.createVideoTrack("camera-video", videoSource)
        audioTrack = factory.createAudioTrack("camera-audio", audioSource)
        videoTrack.setEnabled(true)
        audioTrack.setEnabled(true)
        connection.setAudioPlayout(false)
        // Initializing does not open the camera. Wait for the browser's offer before delivering
        // a frame, so the sender is negotiated before native video processing begins.
        capturer.initialize(helper, context, videoSource.capturerObserver)
    }

    fun offer(sdp: String) {
        if (sdp.length !in 1..MAX_SDP) return
        Log.i(TAG, "setting browser offer")
        connection.setRemoteDescription(
            object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) = Unit

                override fun onSetSuccess() {
                    Log.i(TAG, "browser offer accepted")
                    val queued =
                        synchronized(this@CameraWebRtcPeer) {
                            remoteDescriptionSet = true
                            pendingCandidates.toList().also { pendingCandidates.clear() }
                        }
                    queued.forEach(connection::addIceCandidate)
                    configureSenders()
                    createAnswer()
                }

                override fun onCreateFailure(error: String) {
                    fail(error)
                }

                override fun onSetFailure(error: String) {
                    fail(error)
                }
            },
            SessionDescription(SessionDescription.Type.OFFER, sdp),
        )
    }

    private fun createAnswer() {
        Log.i(TAG, "creating answer")
        connection.createAnswer(
            object : SdpObserver {
                override fun onCreateSuccess(answer: SessionDescription) {
                    Log.i(TAG, "answer created")
                    connection.setLocalDescription(
                        object : SdpObserver {
                            override fun onCreateSuccess(description: SessionDescription) = Unit

                            override fun onSetSuccess() {
                                Log.i(TAG, "answer accepted locally")
                                manager.emit(
                                    JSONObject()
                                        .put("version", 1)
                                        .put("type", "answer")
                                        .put("sdp", answer.description)
                                )
                                startCapture()
                            }

                            override fun onCreateFailure(error: String) {
                                fail(error)
                            }

                            override fun onSetFailure(error: String) {
                                fail(error)
                            }
                        },
                        answer,
                    )
                }

                override fun onSetSuccess() = Unit

                override fun onCreateFailure(error: String) {
                    fail(error)
                }

                override fun onSetFailure(error: String) {
                    fail(error)
                }
            },
            MediaConstraints(),
        )
    }

    fun candidate(value: JSONObject) {
        val candidate = value.optString("candidate")
        if (candidate.length in 1..MAX_CANDIDATE) {
            Log.i(TAG, "browser ICE candidate: ${candidateSummary(candidate)}")
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
                        pendingCandidates += nativeCandidate
                        false
                    }
                }
            if (applyNow) connection.addIceCandidate(nativeCandidate)
        }
    }

    fun control(value: JSONObject) {
        if (value.has("microphone")) connection.setAudioRecording(value.optBoolean("microphone"))
        if (value.has("camera"))
            capturer.switchCamera(
                null,
                if (value.optString("camera") == "front")
                    Camera2Enumerator(context).deviceNames.firstOrNull {
                        Camera2Enumerator(context).isFrontFacing(it)
                    }
                else
                    Camera2Enumerator(context).deviceNames.firstOrNull {
                        Camera2Enumerator(context).isBackFacing(it)
                    },
            )
    }

    fun close() {
        if (captureStarted) runCatching { capturer.stopCapture() }
        capturer.dispose()
        helper.dispose()
        connection.dispose()
        videoTrack.dispose()
        audioTrack.dispose()
        videoSource.dispose()
        audioSource.dispose()
        factory.dispose()
        eglBase.release()
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

            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.i(TAG, "ICE connection: $state")
                if (state == PeerConnection.IceConnectionState.FAILED) fail("ice-failed")
            }

            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                Log.i(TAG, "peer connection: $state")
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit

            override fun onAddStream(stream: org.webrtc.MediaStream) = Unit

            override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit

            override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit

            override fun onRenegotiationNeeded() = Unit
        }

    private fun fail(detail: String) {
        Log.e(TAG, "WebRTC failure: ${detail.take(80)}")
        manager.emit(
            JSONObject().put("version", 1).put("type", "error").put("code", detail.take(80))
        )
    }

    private fun androidTurnUrls(urls: List<String>): List<String> {
        if (!isEmulator()) return urls
        val configuredHost =
            "turn.${DeviceIdentity(context).baseHost() ?: BuildConfig.PLATFORM_HOST}"
        // Android Emulator loopback points at the emulator itself. 10.0.2.2 is its host alias.
        // Use UDP relay transport. A pair of TCP relay candidates is passive on both sides and
        // cannot establish a media path, whereas UDP is NATed correctly to the host alias.
        return urls
            .filter { it.startsWith("turn:") && it.contains("transport=udp") }
            .map { it.replace(configuredHost, "10.0.2.2") }
    }

    private fun candidateType(sdp: String): String =
        Regex("\\btyp ([a-z]+)").find(sdp)?.groupValues?.getOrNull(1) ?: "unknown"

    /** Diagnostic only: deliberately excludes the candidate address, port and credentials. */
    private fun candidateSummary(sdp: String): String {
        val transport =
            Regex("^candidate:\\S+ \\d+ ([A-Za-z]+)").find(sdp)?.groupValues?.getOrNull(1)
        val tcpType = Regex("\\btcptype ([a-z]+)").find(sdp)?.groupValues?.getOrNull(1)
        return listOfNotNull(candidateType(sdp), transport?.lowercase(), tcpType).joinToString("/")
    }

    /** Attaches capture tracks to the transceivers created from the browser's remote offer. */
    private fun configureSenders() {
        val transceivers = connection.getTransceivers()
        val video =
            checkNotNull(
                transceivers.firstOrNull {
                    it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO && !it.isStopped
                }
            ) {
                "Remote offer has no video transceiver"
            }
        val audio =
            checkNotNull(
                transceivers.firstOrNull {
                    it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO && !it.isStopped
                }
            ) {
                "Remote offer has no audio transceiver"
            }

        check(video.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)) {
            "Video transceiver rejected send-only direction"
        }
        check(audio.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)) {
            "Audio transceiver rejected send-only direction"
        }
        check(video.sender.setTrack(videoTrack, false)) { "Video sender rejected track" }
        check(audio.sender.setTrack(audioTrack, false)) { "Audio sender rejected track" }
        video.sender.setStreams(listOf("omniand"))
        audio.sender.setStreams(listOf("omniand"))

        // VP8 is available in both Firefox and the Android software encoder, including AVDs.
        val vp8 =
            factory
                .getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
                .codecs
                .filter { it.name.equals("VP8", ignoreCase = true) }
        if (vp8.isNotEmpty()) video.setCodecPreferences(vp8)
    }

    private fun isEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("/emu") ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk_gphone", ignoreCase = true) ||
            Build.DEVICE.startsWith("emu") ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true)

    @Synchronized
    private fun startCapture() {
        if (captureStarted) return
        captureStarted = true
        runCatching { capturer.startCapture(1280, 720, 30) }
            .onFailure {
                captureStarted = false
                fail("camera-start-failed")
            }
    }

    private companion object {
        const val TAG = "OmniAndCamera"
        const val MAX_SDP = 200 * 1024
        const val MAX_CANDIDATE = 8 * 1024
    }
}
