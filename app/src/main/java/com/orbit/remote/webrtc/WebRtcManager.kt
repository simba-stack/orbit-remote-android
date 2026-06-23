package com.orbit.remote.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import com.orbit.remote.data.signaling.IceServerConfig
import com.orbit.remote.domain.model.AgentEvent
import com.orbit.remote.domain.model.ControlMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Owns the WebRTC peer connection for a single remote session.
 *
 * Screen frames are captured with [ScreenCapturerAndroid] (MediaProjection) and
 * encoded by the hardware H.264 encoder selected by [DefaultVideoEncoderFactory] —
 * this is real hardware MediaCodec encoding, not a screenshot stream. A data
 * channel carries control commands (taps/swipes/text) and clipboard sync.
 */
class WebRtcManager(
    private val context: Context,
    private val eglBase: EglBase,
    private val iceServers: List<IceServerConfig>,
    private val json: Json,
    private val onLocalSignal: (JsonObject) -> Unit,
    private val onControlMessage: (ControlMessage) -> Unit,
    private val onClipboardRequest: () -> Unit
) {
    // Process-shared factory: PeerConnectionFactory.initialize() and the factory
    // itself must be created exactly ONCE per process. Re-initializing per session
    // crashes the native WebRTC layer (the "crashes every other reconnect" bug).
    private val factory: PeerConnectionFactory = obtainFactory(context)
    private var peerConnection: PeerConnection? = null

    private var videoCapturer: ScreenCapturerAndroid? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var dataChannel: DataChannel? = null

    companion object {
        @Volatile private var initialized = false
        @Volatile private var sharedEgl: EglBase? = null
        @Volatile private var sharedFactory: PeerConnectionFactory? = null

        /** One EglBase for the whole process; used by both the factory and capture. */
        @Synchronized
        fun sharedEglBase(): EglBase =
            sharedEgl ?: EglBase.create().also { sharedEgl = it }

        /** One PeerConnectionFactory for the whole process; never disposed on the fly. */
        @Synchronized
        private fun obtainFactory(context: Context): PeerConnectionFactory {
            sharedFactory?.let { return it }
            if (!initialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions
                        .builder(context.applicationContext)
                        .createInitializationOptions()
                )
                initialized = true
            }
            val egl = sharedEglBase()
            // Constrained Baseline (High Profile off): lower encode/decode latency.
            val encoderFactory = DefaultVideoEncoderFactory(
                egl.eglBaseContext,
                /* enableIntelVp8Encoder = */ true,
                /* enableH264HighProfile = */ false
            )
            val decoderFactory = DefaultVideoDecoderFactory(egl.eglBaseContext)
            return PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()
                .also { sharedFactory = it }
        }
    }

    /**
     * Start capturing the screen and create the peer connection. Must be called
     * from a context where the MediaProjection permission [projectionData] is valid
     * (i.e. a started foreground service with type mediaProjection).
     */
    fun start(projectionData: Intent, width: Int, height: Int, fps: Int) {
        createPeerConnection()
        startScreenCapture(projectionData, width, height, fps)
    }

    private fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(
            iceServers.map { server ->
                val builder = PeerConnection.IceServer.builder(server.urls)
                server.username?.let { builder.setUsername(it) }
                server.credential?.let { builder.setPassword(it) }
                builder.createIceServer()
            }
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        }

        peerConnection = factory.createPeerConnection(rtcConfig, pcObserver)
    }

    private fun startScreenCapture(projectionData: Intent, width: Int, height: Int, fps: Int) {
        val helper = SurfaceTextureHelper.create("OrbitCapture", eglBase.eglBaseContext)
        surfaceTextureHelper = helper

        val capturer = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
            override fun onStop() {
                // System revoked the projection (e.g. user stopped sharing).
                close()
            }
        })
        videoCapturer = capturer

        val source = factory.createVideoSource(/* isScreencast = */ true)
        videoSource = source
        capturer.initialize(helper, context, source.capturerObserver)
        capturer.startCapture(width, height, fps)

        val track = factory.createVideoTrack("orbit_video", source)
        track.setEnabled(true)
        videoTrack = track
        peerConnection?.addTrack(track, listOf("orbit_stream"))
    }

    /** Apply an offer or ICE candidate relayed from the controller. */
    fun handleRemoteSignal(data: JsonObject) {
        when (data["kind"]?.jsonPrimitive?.contentOrNull) {
            "offer" -> {
                val sdp = data["sdp"]?.jsonPrimitive?.contentOrNull ?: return
                val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
                peerConnection?.setRemoteDescription(
                    SetSdpObserver(onSet = { createAnswer() }),
                    offer
                )
            }
            "candidate" -> {
                val candidate = data["candidate"]?.jsonPrimitive?.contentOrNull ?: return
                val sdpMid = data["sdpMid"]?.jsonPrimitive?.contentOrNull
                val sdpMLineIndex = data["sdpMLineIndex"]?.jsonPrimitive?.int ?: 0
                peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
            }
        }
    }

    private fun createAnswer() {
        val constraints = MediaConstraints()
        peerConnection?.createAnswer(
            CreateSdpObserver(onCreate = { desc ->
                peerConnection?.setLocalDescription(SetSdpObserver(), desc)
                onLocalSignal(buildJsonObject {
                    put("kind", JsonPrimitive("answer"))
                    put("sdp", JsonPrimitive(desc.description))
                })
            }),
            constraints
        )
    }

    /** Send an event (e.g. clipboard contents) back to the controller. */
    fun sendAgentEvent(event: AgentEvent) {
        val channel = dataChannel ?: return
        val text = json.encodeToString(AgentEvent.serializer(), event)
        val buffer = ByteBuffer.wrap(text.toByteArray(StandardCharsets.UTF_8))
        channel.send(DataChannel.Buffer(buffer, false))
    }

    fun close() {
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        runCatching { surfaceTextureHelper?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { dataChannel?.dispose() }
        runCatching { peerConnection?.dispose() }
        videoCapturer = null
        surfaceTextureHelper = null
        videoSource = null
        videoTrack = null
        dataChannel = null
        peerConnection = null
    }

    fun release() {
        // The factory and EglBase are process-shared and reused across sessions;
        // never dispose them mid-process. Per-session natives are freed in close().
        close()
    }

    // ---- Observers --------------------------------------------------------

    private val pcObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            onLocalSignal(buildJsonObject {
                put("kind", JsonPrimitive("candidate"))
                put("candidate", JsonPrimitive(candidate.sdp))
                put("sdpMid", JsonPrimitive(candidate.sdpMid))
                put("sdpMLineIndex", JsonPrimitive(candidate.sdpMLineIndex))
            })
        }

        override fun onDataChannel(channel: DataChannel) {
            dataChannel = channel
            channel.registerObserver(dataChannelObserver(channel))
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {}
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
    }

    private fun dataChannelObserver(channel: DataChannel) = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) {}
        override fun onStateChange() {}
        override fun onMessage(buffer: DataChannel.Buffer) {
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            val text = String(bytes, StandardCharsets.UTF_8)
            val msg = runCatching { json.decodeFromString(ControlMessage.serializer(), text) }.getOrNull()
                ?: return
            if (msg.type == ControlMessage.CLIPBOARD_GET) {
                onClipboardRequest()
            } else {
                onControlMessage(msg)
            }
        }
    }
}
