package com.network24.player.core.p2p

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small, transport-only WebRTC manager. It does not fetch media itself.
 * Segment cache/request policy belongs above this class so a slow peer can
 * always be abandoned in favour of the normal HTTP DataSource.
 */
class Network24WebRtcPeerManager(
    context: Context,
    private val signaling: Network24SignalingClient,
    private val listener: Listener,
    private val iceServers: List<String> = emptyList()
) {
    interface Listener {
        fun onPeerReady(peerId: String, channel: DataChannel) {}
        fun onPeerMessage(peerId: String, bytes: ByteArray) {}
        fun onPeerClosed(peerId: String) {}
        fun onPeerError(peerId: String, code: String) {}
    }

    private val factory: PeerConnectionFactory
    private val peers = ConcurrentHashMap<String, PeerConnection>()
    private val channels = ConcurrentHashMap<String, DataChannel>()
    private val closed = AtomicBoolean(false)
    private val gson = Gson()

    init {
        initializeFactory(context.applicationContext)
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    fun connect(peerId: String, initiator: Boolean) {
        if (closed.get() || peerId.isBlank() || peers.containsKey(peerId)) return
        val rtcServers = iceServers.map { PeerConnection.IceServer.builder(it).createIceServer() }
        val connection = factory.createPeerConnection(rtcServers, observer(peerId))
        if (connection == null) {
            listener.onPeerError(peerId, "peer_connection_create_failed")
            return
        }
        peers[peerId] = connection
        if (initiator) {
            val channel = connection.createDataChannel(DATA_CHANNEL_LABEL, DataChannel.Init())
            if (channel != null) attachChannel(peerId, channel)
            connection.createOffer(SdpCallback(
                onSuccess = { description ->
                    connection.setLocalDescription(SdpCallback(onSuccess = {}, onFailure = { listener.onPeerError(peerId, "set_local_description_failed") }), description)
                    signaling.sendOffer(peerId, description.description)
                },
                onFailure = { listener.onPeerError(peerId, "offer_failed") }
            ), MediaConstraints())
        }
    }

    fun handleSignal(type: String, payload: JsonObject) {
        val peerId = payload.get("from_peer_id")?.asString ?: payload.get("peer_id")?.asString ?: return
        val connection = peers[peerId] ?: run { connect(peerId, initiator = false); peers[peerId] } ?: return
        when (type) {
            "offer" -> {
                val sdp = payload.get("sdp")?.asString ?: return
                connection.setRemoteDescription(SdpCallback(
                    onSuccess = {
                        connection.createAnswer(SdpCallback(
                            onSuccess = { answer ->
                                connection.setLocalDescription(SdpCallback(onSuccess = {}, onFailure = { listener.onPeerError(peerId, "set_local_description_failed") }), answer)
                                signaling.sendAnswer(peerId, answer.description)
                            },
                            onFailure = { listener.onPeerError(peerId, "answer_failed") }
                        ), MediaConstraints())
                    },
                    onFailure = { listener.onPeerError(peerId, "set_remote_description_failed") }
                ), SessionDescription(SessionDescription.Type.OFFER, sdp))
            }
            "answer" -> payload.get("sdp")?.asString?.let { sdp ->
                connection.setRemoteDescription(SdpCallback(onSuccess = {}, onFailure = { listener.onPeerError(peerId, "set_remote_description_failed") }), SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }
            "ice_candidate" -> {
                val candidate = payload.get("candidate")?.asString ?: return
                val mid = payload.get("sdp_mid")?.asString
                val line = payload.get("sdp_m_line_index")?.asInt ?: 0
                connection.addIceCandidate(IceCandidate(mid, line, candidate))
            }
        }
    }

    fun requestSegment(peerId: String, streamId: String, segmentId: String, requestId: String): Boolean {
        val channel = channels[peerId] ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        val request = JsonObject().apply {
            addProperty("type", "segment_request")
            addProperty("stream_id", streamId)
            addProperty("segment_id", segmentId)
            addProperty("request_id", requestId)
        }
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(gson.toJson(request).toByteArray(Charsets.UTF_8)), true))
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        channels.values.forEach { it.close() }
        peers.values.forEach { it.close() }
        channels.clear()
        peers.clear()
        factory.dispose()
    }

    private fun attachChannel(peerId: String, channel: DataChannel) {
        channels[peerId] = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) listener.onPeerReady(peerId, channel)
                if (channel.state() == DataChannel.State.CLOSING || channel.state() == DataChannel.State.CLOSED) listener.onPeerClosed(peerId)
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                listener.onPeerMessage(peerId, bytes)
            }
        })
    }

    private fun observer(peerId: String): PeerConnection.Observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            signaling.sendIceCandidate(peerId, candidate.sdp)
        }
        override fun onDataChannel(dataChannel: DataChannel) = attachChannel(peerId, dataChannel)
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.DISCONNECTED) listener.onPeerError(peerId, "ice_${state.name.lowercase()}")
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver, mediaStreams: Array<out org.webrtc.MediaStream>) = Unit
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) = Unit
        override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit
    }

    private class SdpCallback(
        private val onSuccess: (SessionDescription) -> Unit,
        private val onFailure: (String) -> Unit
    ) : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = onSuccess(description)
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = onFailure(error)
        override fun onSetFailure(error: String) = onFailure(error)
    }

    companion object {
        private const val TAG = "Network24WebRTC"
        private const val DATA_CHANNEL_LABEL = "network24-segments-v1"
        private val initialized = AtomicBoolean(false)

        private fun initializeFactory(context: Context) {
            if (initialized.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
            }
        }
    }
}
