package dev.omniand.hub.camera

import android.content.Context
import android.util.Log
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
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/** Owns native WebRTC capture resources for exactly one foreground-approved viewing session. */
class CameraWebRtcPeer(private val context: Context, private val manager: CameraSessionManager) {
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

                    override fun onFirstFrameAvailable() = Unit

                    override fun onCameraClosed() = Unit
                },
            )
        helper = SurfaceTextureHelper.create("OmniAndCamera", eglBase.eglBaseContext)
        val servers =
            listOf(
                PeerConnection.IceServer.builder(
                        "stun:turn.${dev.omniand.hub.pairing.DeviceIdentity(context).baseHost() ?: dev.omniand.hub.BuildConfig.PLATFORM_HOST}:3478"
                    )
                    .createIceServer()
            )
        connection =
            factory.createPeerConnection(PeerConnection.RTCConfiguration(servers), observer())
                ?: error("PeerConnection unavailable")
        videoTrack = factory.createVideoTrack("camera-video", videoSource)
        audioTrack = factory.createAudioTrack("camera-audio", audioSource)
        val sendOnly =
            RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
                listOf("omniand"),
            )
        // addTrack creates a send/receive transceiver. Firefox then advertises an incoming
        // stream even for a receiver-only browser offer, which trips a native WebRTC assertion.
        // This peer never receives media, so declare that boundary explicitly.
        connection.addTransceiver(videoTrack, sendOnly)
        connection.addTransceiver(audioTrack, sendOnly)
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
                if (state == PeerConnection.IceConnectionState.FAILED) fail("ice-failed")
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
