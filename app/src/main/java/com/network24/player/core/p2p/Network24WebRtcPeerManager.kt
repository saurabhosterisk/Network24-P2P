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
import android.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
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
    private val iceServers: List<Network24IceServer> = emptyList()
) {
    interface Listener {
        fun onPeerReady(peerId: String, channel: DataChannel) {}
        fun onPeerMessage(peerId: String, bytes: ByteArray) {}
        fun onPeerClosed(peerId: String) {}
        fun onPeerState(peerId: String, state: String) {}
        fun onPeerError(peerId: String, code: String) {}
    }

    private val factory: PeerConnectionFactory
    private val peers = ConcurrentHashMap<String, PeerConnection>()
    private val channels = ConcurrentHashMap<String, DataChannel>()
    private val remoteDescriptionSet = ConcurrentHashMap.newKeySet<String>()
    private val pendingIceCandidates = ConcurrentHashMap<String, ConcurrentLinkedQueue<IceCandidate>>()
    private val remoteIceEnded = ConcurrentHashMap.newKeySet<String>()
    private val closed = AtomicBoolean(false)
    private val gson = Gson()

    init {
        initializeFactory(context.applicationContext)
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    fun connect(peerId: String, initiator: Boolean) {
        if (closed.get() || peerId.isBlank() || peers.containsKey(peerId)) return
        val rtcServers = iceServers.map { server ->
            PeerConnection.IceServer.builder(server.urls).apply {
                server.username?.takeIf { it.isNotBlank() }?.let { setUsername(it) }
                server.password?.takeIf { it.isNotBlank() }?.let { setPassword(it) }
            }.createIceServer()
        }
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
        val peerId = payload.stringField("from_peer_id") ?: payload.stringField("peer_id") ?: run {
            rejectSignal("unknown", "missing_signal_sender")
            return
        }
        val connection = peers[peerId] ?: run { connect(peerId, initiator = false); peers[peerId] } ?: return
        when (type) {
            "offer" -> {
                val sdp = payload.stringField("sdp")?.takeIf { it.isNotBlank() } ?: run {
                    listener.onPeerError(peerId, "invalid_sdp")
                    return
                }
                connection.setRemoteDescription(SdpCallback(
                    onSuccess = {
                        remoteDescriptionSet.add(peerId)
                        flushPendingIceCandidates(peerId, connection)
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
            "answer" -> payload.stringField("sdp")?.let { sdp ->
                if (sdp.isBlank()) {
                    listener.onPeerError(peerId, "invalid_sdp")
                    return
                }
                connection.setRemoteDescription(SdpCallback(
                    onSuccess = {
                        remoteDescriptionSet.add(peerId)
                        flushPendingIceCandidates(peerId, connection)
                    },
                    onFailure = { listener.onPeerError(peerId, "set_remote_description_failed") }
                ), SessionDescription(SessionDescription.Type.ANSWER, sdp))
            } ?: listener.onPeerError(peerId, "invalid_sdp")
            "ice_candidate" -> {
                if (payload.booleanField("endOfCandidates") == true) {
                    remoteIceEnded.add(peerId)
                    return
                }
                if (remoteIceEnded.contains(peerId)) {
                    rejectSignal(peerId, "ice_candidate_after_end")
                    return
                }
                val candidate = payload.stringField("candidate")?.takeIf { it.isNotBlank() } ?: run {
                    listener.onPeerError(peerId, "invalid_ice_candidate")
                    return
                }
                val mid = payload.stringField("sdpMid")?.takeIf { it.isNotBlank() } ?: run {
                    listener.onPeerError(peerId, "invalid_ice_candidate_missing_sdp_mid")
                    return
                }
                val line = payload.intField("sdpMLineIndex")?.takeIf { it >= 0 } ?: run {
                    listener.onPeerError(peerId, "invalid_ice_candidate_missing_m_line_index")
                    return
                }
                val iceCandidate = IceCandidate(mid, line, candidate)
                if (remoteDescriptionSet.contains(peerId)) addIceCandidate(peerId, connection, iceCandidate)
                else {
                    val queue = pendingIceCandidates.computeIfAbsent(peerId) { ConcurrentLinkedQueue() }
                    if (queue.size >= MAX_PENDING_ICE_CANDIDATES) rejectSignal(peerId, "too_many_pending_ice_candidates")
                    else queue.add(iceCandidate)
                }
            }
        }
    }

    fun requestSegment(peerId: String, streamId: String, segmentId: String, requestId: String): Boolean {
        return requestSegment(peerId, streamId, segmentId, requestId, null)
    }

    fun requestSegment(peerId: String, streamId: String, segmentId: String, requestId: String, segmentUri: String?): Boolean {
        val channel = channels[peerId] ?: return false
        if (channel.state() != DataChannel.State.OPEN) return false
        val request = JsonObject().apply {
            addProperty("type", "segment_request")
            addProperty("stream_id", streamId)
            addProperty("segment_id", segmentId)
            addProperty("request_id", requestId)
            segmentUri?.takeIf { it.length <= 2048 }?.let { addProperty("segment_uri", it) }
        }
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(gson.toJson(request).toByteArray(Charsets.UTF_8)), true))
    }

    fun sendSegmentResponse(peerId: String, streamId: String, segmentId: String, requestId: String, bytes: ByteArray): Boolean {
        val channel = channels[peerId] ?: return false
        if (channel.state() != DataChannel.State.OPEN || bytes.isEmpty()) return false
        val chunkSize = 32 * 1024
        val chunkCount = (bytes.size + chunkSize - 1) / chunkSize
        for (index in 0 until chunkCount) {
            val start = index * chunkSize
            val end = minOf(bytes.size, start + chunkSize)
            val payload = JsonObject().apply {
                addProperty("type", "segment_response")
                addProperty("stream_id", streamId)
                addProperty("segment_id", segmentId)
                addProperty("request_id", requestId)
                addProperty("chunk_index", index)
                addProperty("chunk_count", chunkCount)
                addProperty("data_base64", Base64.encodeToString(bytes.copyOfRange(start, end), Base64.NO_WRAP))
            }
            val encoded = gson.toJson(payload).toByteArray(Charsets.UTF_8)
            if (encoded.size > MAX_MESSAGE_BYTES || !channel.send(DataChannel.Buffer(ByteBuffer.wrap(encoded), true))) return false
        }
        return true
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        channels.values.forEach { it.close() }
        peers.values.forEach { it.close() }
        channels.clear()
        pendingIceCandidates.clear()
        remoteDescriptionSet.clear()
        remoteIceEnded.clear()
        peers.clear()
        factory.dispose()
    }

    /** Remove a dead connection so the session can create a fresh ICE/SDP attempt. */
    fun drop(peerId: String) {
        channels.remove(peerId)?.close()
        pendingIceCandidates.remove(peerId)
        remoteDescriptionSet.remove(peerId)
        remoteIceEnded.remove(peerId)
        peers.remove(peerId)?.close()
    }

    private fun attachChannel(peerId: String, channel: DataChannel) {
        channels[peerId] = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) {
                    listener.onPeerState(peerId, "connected")
                    listener.onPeerReady(peerId, channel)
                }
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
            val mid = candidate.sdpMid?.takeIf { it.isNotBlank() }
            if (mid == null || candidate.sdp.isBlank() || candidate.sdpMLineIndex < 0) {
                listener.onPeerError(peerId, "invalid_local_ice_candidate")
                return
            }
            signaling.sendIceCandidate(peerId, candidate.sdp, mid, candidate.sdpMLineIndex)
        }
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) signaling.sendEndOfCandidates(peerId)
        }
        override fun onDataChannel(dataChannel: DataChannel) = attachChannel(peerId, dataChannel)
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            listener.onPeerState(peerId, when (state) {
                PeerConnection.IceConnectionState.CHECKING -> "connecting"
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> "connected"
                PeerConnection.IceConnectionState.DISCONNECTED -> "disconnected"
                PeerConnection.IceConnectionState.FAILED -> "failed"
                else -> "new"
            })
            if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.DISCONNECTED) listener.onPeerError(peerId, "ice_${state.name.lowercase()}")
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver, mediaStreams: Array<out org.webrtc.MediaStream>) = Unit
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            listener.onPeerState(peerId, when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> "connected"
                PeerConnection.PeerConnectionState.CONNECTING -> "connecting"
                PeerConnection.PeerConnectionState.DISCONNECTED -> "disconnected"
                PeerConnection.PeerConnectionState.FAILED -> "failed"
                else -> "new"
            })
        }
        override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit
    }

    private fun flushPendingIceCandidates(peerId: String, connection: PeerConnection) {
        val candidates = pendingIceCandidates.remove(peerId) ?: return
        candidates.forEach { addIceCandidate(peerId, connection, it) }
    }

    private fun addIceCandidate(peerId: String, connection: PeerConnection, candidate: IceCandidate) {
        if (candidate.sdp.isBlank() || candidate.sdpMid.isNullOrBlank() || candidate.sdpMLineIndex < 0) {
            listener.onPeerError(peerId, "invalid_ice_candidate")
            return
        }
        try {
            if (!connection.addIceCandidate(candidate)) listener.onPeerError(peerId, "add_ice_candidate_failed")
        } catch (_: RuntimeException) {
            listener.onPeerError(peerId, "add_ice_candidate_failed")
        }
    }

    private fun rejectSignal(peerId: String, code: String) {
        // Do not log raw signaling payloads: they contain SDP and ICE addresses.
        Log.w(TAG, "Ignoring malformed signaling message peer=$peerId code=$code")
        listener.onPeerError(peerId, code)
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
        private const val MAX_MESSAGE_BYTES = 64 * 1024
        private const val MAX_PENDING_ICE_CANDIDATES = 256
        private val initialized = AtomicBoolean(false)

        private fun JsonObject.stringField(name: String): String? =
            get(name)?.takeUnless { it.isJsonNull }
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

        private fun JsonObject.intField(name: String): Int? =
            get(name)?.takeUnless { it.isJsonNull }
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asJsonPrimitive?.asNumber?.toDouble()
                ?.takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toInt()

        private fun JsonObject.booleanField(name: String): Boolean? =
            get(name)?.takeUnless { it.isJsonNull }
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

        private fun initializeFactory(context: Context) {
            if (initialized.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
            }
        }
    }
}
