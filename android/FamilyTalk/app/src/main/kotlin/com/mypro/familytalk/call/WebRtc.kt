package com.mypro.familytalk.call

import android.content.Context
import android.util.Log
import com.mypro.familytalk.core.family.IceServer
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
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
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Thin wrapper over the WebRTC library. Media between family phones is DTLS-SRTP:
 * the DTLS certificate fingerprint travels inside the signed, end-to-end encrypted
 * SDP, so neither the relay nor a TURN server can sit in the middle.
 */
class WebRtc(private val context: Context) {
    val egl: EglBase by lazy { EglBase.create() }

    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions(),
        )
        val adm = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    var audioTrack: AudioTrack? = null; private set
    private var audioSource: org.webrtc.AudioSource? = null
    var videoTrack: VideoTrack? = null; private set
    private var videoSource: VideoSource? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    var frontCamera = true; private set

    fun startLocal(video: Boolean) {
        if (audioTrack == null) {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            }
            audioSource = factory.createAudioSource(constraints)
            audioTrack = factory.createAudioTrack("ft-audio", audioSource)
        }
        if (video && videoTrack == null) startCamera()
    }

    private fun startCamera() {
        val enumerator = Camera2Enumerator(context)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) == frontCamera }
            ?: enumerator.deviceNames.firstOrNull() ?: return
        val cap = enumerator.createCapturer(name, null) ?: return
        val helper = SurfaceTextureHelper.create("ft-capture", egl.eglBaseContext)
        val source = factory.createVideoSource(false)
        cap.initialize(helper, context, source.capturerObserver)
        cap.startCapture(1280, 720, 30)
        capturer = cap; textureHelper = helper; videoSource = source
        videoTrack = factory.createVideoTrack("ft-video", source)
    }

    fun setMic(on: Boolean) { audioTrack?.setEnabled(on) }
    fun setCamera(on: Boolean) {
        videoTrack?.setEnabled(on)
        if (on) runCatching { capturer?.startCapture(1280, 720, 30) } else runCatching { capturer?.stopCapture() }
    }
    fun switchCamera() { capturer?.switchCamera(null); frontCamera = !frontCamera }

    fun stopLocal() {
        runCatching { capturer?.stopCapture() }
        capturer?.dispose(); capturer = null
        videoTrack?.dispose(); videoTrack = null
        videoSource?.dispose(); videoSource = null
        textureHelper?.dispose(); textureHelper = null
        audioTrack?.dispose(); audioTrack = null
        audioSource?.dispose(); audioSource = null
    }

    fun createPeer(peerId: String, ice: List<IceServer>, relayOnly: Boolean, listener: Peer.Listener): Peer? {
        val servers = ice.map { s ->
            PeerConnection.IceServer.builder(s.urls).apply {
                s.username?.let { setUsername(it) }
                s.credential?.let { setPassword(it) }
            }.createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = if (relayOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
        }
        val peer = Peer(peerId, listener)
        val pc = factory.createPeerConnection(config, peer.observer) ?: return null
        peer.attach(pc)
        audioTrack?.let { pc.addTrack(it, listOf("ft")) }
        videoTrack?.let { pc.addTrack(it, listOf("ft")) }
        // Always offer to receive video so the other side can turn the camera on later.
        if (videoTrack == null) pc.addTransceiver(org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        return peer
    }
}

/** One WebRTC connection to one family member. */
class Peer(val peerId: String, private val listener: Listener) {
    interface Listener {
        fun onLocalSdp(peer: Peer, type: String, sdp: String)
        fun onLocalIce(peer: Peer, c: IceCandidate)
        fun onState(peer: Peer, state: PeerConnection.PeerConnectionState)
        fun onRemoteVideo(peer: Peer, track: VideoTrack)
    }

    private lateinit var pc: PeerConnection
    private val pendingIce = mutableListOf<IceCandidate>()
    private var remoteSet = false
    @Volatile var closed = false; private set
    var remoteVideo: VideoTrack? = null; private set

    internal fun attach(p: PeerConnection) { pc = p }

    fun offer(iceRestart: Boolean = false) {
        val c = MediaConstraints().apply { if (iceRestart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true")) }
        pc.createOffer(object : SimpleSdp() {
            override fun onCreateSuccess(sdp: SessionDescription) = setLocal(sdp)
        }, c)
    }

    fun onRemoteSdp(type: String, sdp: String) {
        if (closed) return
        val desc = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)
        pc.setRemoteDescription(object : SimpleSdp() {
            override fun onSetSuccess() {
                remoteSet = true
                synchronized(pendingIce) { pendingIce.forEach { pc.addIceCandidate(it) }; pendingIce.clear() }
                if (desc.type == SessionDescription.Type.OFFER) {
                    pc.createAnswer(object : SimpleSdp() {
                        override fun onCreateSuccess(sdp: SessionDescription) = setLocal(sdp)
                    }, MediaConstraints())
                }
            }
        }, desc)
    }

    fun addIce(c: IceCandidate) {
        if (closed) return
        synchronized(pendingIce) { if (!remoteSet) { pendingIce += c; return } }
        pc.addIceCandidate(c)
    }

    private fun setLocal(sdp: SessionDescription) {
        pc.setLocalDescription(object : SimpleSdp() {
            override fun onSetSuccess() = listener.onLocalSdp(this@Peer, sdp.type.canonicalForm(), sdp.description)
        }, sdp)
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { pc.close() }
        runCatching { pc.dispose() }
    }

    internal val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(c: IceCandidate) { if (!closed) listener.onLocalIce(this@Peer, c) }
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(d: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState) { if (!closed) listener.onState(this@Peer, s) }
        override fun onTrack(t: RtpTransceiver) {
            (t.receiver.track() as? VideoTrack)?.let { remoteVideo = it; listener.onRemoteVideo(this@Peer, it) }
        }
    }

    private open class SimpleSdp : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) { Log.w("Peer", "sdp create failed: $e") }
        override fun onSetFailure(e: String?) { Log.w("Peer", "sdp set failed: $e") }
    }
}
