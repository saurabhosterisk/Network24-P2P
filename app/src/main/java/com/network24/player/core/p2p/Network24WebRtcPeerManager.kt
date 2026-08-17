package com.network24.player.core.p2p

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
    private val iceServers: List<Network24IceServer> = emptyList(),
    private val uploadDeadlineMs: Long = 1_200L,
    private val maxBufferedAmount: Long = 256L * 1024L,
) {
    interface Listener {
        fun onPeerReady(peerId: String, channel: DataChannel) {}
        fun onPeerMessage(peerId: String, bytes: ByteArray) {}
        fun onPeerClosed(peerId: String) {}
        fun onPeerState(peerId: String, state: String) {}
        fun onPeerTransport(peerId: String, candidateType: String) {}
        fun onPeerError(peerId: String, code: String) {}
    }

    private val factory: PeerConnectionFactory
    private val peers = ConcurrentHashMap<String, PeerConnection>()
    private val channels = ConcurrentHashMap<String, DataChannel>()
    private val remoteDescriptionSet = ConcurrentHashMap<String, Boolean>()
    private val pendingIceCandidates = ConcurrentHashMap<String, ConcurrentLinkedQueue<IceCandidate>>()
    private val remoteIceEnded = ConcurrentHashMap<String, Boolean>()
    private val cancelledUploads = ConcurrentHashMap<String, Boolean>()
    private val closed = AtomicBoolean(false)
    private val uploadExecutor = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16),
        { runnable -> Thread(runnable, "network24-p2p-upload").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val connectionLock = Any()

    init {
        initializeFactory(context.applicationContext)
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    fun connect(peerId: String, initiator: Boolean) {
        if (closed.get() || peerId.isBlank()) return
        synchronized(connectionLock) {
            if (peers.containsKey(peerId)) return
            createConnection(peerId, initiator)
        }
    }

    private fun createConnection(peerId: String, initiator: Boolean) {
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
            val channel = connection.createDataChannel(DATA_CHANNEL_LABEL, DataChannel.Init().apply {
                ordered = true
                maxRetransmits = -1
                protocol = "n24-media-v2"
            })
            if (channel != null) attachChannel(peerId, channel)
            connection.createOffer(SdpCallback(
                onSuccess = { description ->
                    connection.setLocalDescription(SdpCallback(
                        onSuccess = {},
                        onFailure = { listener.onPeerError(peerId, "set_local_description_failed") },
                        afterSetSuccess = { signaling.sendOffer(peerId, description.description) }
                    ), description)
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
                        remoteDescriptionSet[peerId] = true
                        flushPendingIceCandidates(peerId, connection)
                        connection.createAnswer(SdpCallback(
                            onSuccess = { answer ->
                                connection.setLocalDescription(SdpCallback(
                                    onSuccess = {},
                                    onFailure = { listener.onPeerError(peerId, "set_local_description_failed") },
                                    afterSetSuccess = { signaling.sendAnswer(peerId, answer.description) }
                                ), answer)
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
                        remoteDescriptionSet[peerId] = true
                        flushPendingIceCandidates(peerId, connection)
                    },
                    onFailure = { listener.onPeerError(peerId, "set_remote_description_failed") }
                ), SessionDescription(SessionDescription.Type.ANSWER, sdp))
            } ?: listener.onPeerError(peerId, "invalid_sdp")
            "ice_candidate" -> {
                if (payload.booleanField("endOfCandidates") == true) {
                    remoteIceEnded[peerId] = true
                    return
                }
                if (remoteIceEnded.containsKey(peerId)) {
                    rejectSignal(peerId, "ice_candidate_after_end")
                    return
                }
                val candidate = payload.stringField("candidate")?.takeIf(Network24IceValidation::candidate) ?: run {
                    listener.onPeerError(peerId, "invalid_ice_candidate")
                    return
                }
                val mid = payload.stringField("sdpMid")?.takeIf(Network24IceValidation::sdpMid) ?: run {
                    listener.onPeerError(peerId, "invalid_ice_candidate_missing_sdp_mid")
                    return
                }
                val line = payload.intField("sdpMLineIndex")?.takeIf(Network24IceValidation::sdpMLineIndex) ?: run {
                    listener.onPeerError(peerId, "invalid_ice_candidate_missing_m_line_index")
                    return
                }
                val iceCandidate = IceCandidate(mid, line, candidate)
                if (remoteDescriptionSet.containsKey(peerId)) addIceCandidate(peerId, connection, iceCandidate)
                else {
                    val candidateQueue = ConcurrentLinkedQueue<IceCandidate>()
                    val queue = pendingIceCandidates.putIfAbsent(peerId, candidateQueue) ?: candidateQueue
                    if (queue.size >= MAX_PENDING_ICE_CANDIDATES) rejectSignal(peerId, "too_many_pending_ice_candidates")
                    else queue.add(iceCandidate)
                }
            }
        }
    }

    fun sendControl(peerId: String, payload: ByteArray): Boolean {
        val channel = channels[peerId] ?: return false
        if (channel.state() != DataChannel.State.OPEN || payload.isEmpty() || payload.size > MAX_MESSAGE_BYTES) return false
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(payload), true))
    }

    fun sendSegment(
        peerId: String,
        requestId: String,
        segmentKey: String,
        bytes: ByteArray,
        sendControl: (String) -> Boolean,
        onQueued: (Boolean) -> Unit,
    ): Boolean {
        val channel = channels[peerId] ?: return false
        if (channel.state() != DataChannel.State.OPEN || bytes.isEmpty() || bytes.size > Network24PeerProtocol.MAX_SEGMENT_BYTES) return false
        cancelledUploads.remove(requestId)
        return try {
            uploadExecutor.execute {
                var success = false
                try {
                    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(uploadDeadlineMs)
                    success = sendControl("meta")
                    val chunks = (bytes.size + Network24PeerProtocol.CHUNK_BYTES - 1) / Network24PeerProtocol.CHUNK_BYTES
                    for (index in 0 until chunks) {
                        if (!success || cancelledUploads.containsKey(requestId) || channel.state() != DataChannel.State.OPEN) {
                            success = false
                            break
                        }
                        while (channel.bufferedAmount() > maxBufferedAmount && System.nanoTime() < deadline &&
                            !cancelledUploads.containsKey(requestId) && channel.state() == DataChannel.State.OPEN
                        ) Thread.sleep(BACKPRESSURE_POLL_MS)
                        if (System.nanoTime() >= deadline || channel.bufferedAmount() > maxBufferedAmount) {
                            success = false
                            break
                        }
                        val start = index * Network24PeerProtocol.CHUNK_BYTES
                        val end = minOf(bytes.size, start + Network24PeerProtocol.CHUNK_BYTES)
                        val frame = Network24PeerProtocol.encodeChunk(requestId, segmentKey, index, bytes.copyOfRange(start, end))
                        success = channel.send(DataChannel.Buffer(ByteBuffer.wrap(frame), true))
                    }
                    if (success && !cancelledUploads.containsKey(requestId)) success = sendControl("complete")
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    success = false
                } finally {
                    cancelledUploads.remove(requestId)
                    onQueued(success)
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    fun cancelUpload(requestId: String) { if (requestId.isNotBlank()) cancelledUploads[requestId] = true }
    fun openPeerIds(): List<String> = channels.entries.filter { it.value.state() == DataChannel.State.OPEN }.map { it.key }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        channels.values.forEach { it.close() }
        peers.values.forEach { it.close() }
        channels.clear()
        pendingIceCandidates.clear()
        remoteDescriptionSet.clear()
        remoteIceEnded.clear()
        peers.clear()
        cancelledUploads.clear()
        uploadExecutor.shutdownNow()
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

    fun dropAll() { peers.keys.toList().forEach(::drop) }

    private fun attachChannel(peerId: String, channel: DataChannel) {
        val previous = channels.put(peerId, channel)
        if (previous != null && previous !== channel) previous.close()
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) {
                    listener.onPeerState(peerId, "connected")
                    listener.onPeerReady(peerId, channel)
                    inspectSelectedCandidate(peerId)
                }
                if ((channel.state() == DataChannel.State.CLOSING || channel.state() == DataChannel.State.CLOSED) && channels[peerId] === channel) {
                    channels.remove(peerId, channel)
                    listener.onPeerClosed(peerId)
                }
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
            if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) inspectSelectedCandidate(peerId)
            if (state == PeerConnection.IceConnectionState.FAILED) listener.onPeerError(peerId, "ice_failed")
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
        if (!Network24IceValidation.candidate(candidate.sdp) || !Network24IceValidation.sdpMid(candidate.sdpMid) || !Network24IceValidation.sdpMLineIndex(candidate.sdpMLineIndex)) {
            listener.onPeerError(peerId, "invalid_ice_candidate")
            return
        }
        try {
            if (!connection.addIceCandidate(candidate)) listener.onPeerError(peerId, "add_ice_candidate_failed")
        } catch (_: RuntimeException) {
            listener.onPeerError(peerId, "add_ice_candidate_failed")
        }
    }

    private fun inspectSelectedCandidate(peerId: String) {
        val connection = peers[peerId] ?: return
        connection.getStats { report ->
            val stats = report.statsMap
            val pair = stats.values.firstOrNull { stat ->
                stat.type == "candidate-pair" && (stat.members["nominated"] == true || stat.members["selected"] == true) &&
                    stat.members["state"] == "succeeded"
            } ?: return@getStats
            val localId = pair.members["localCandidateId"] as? String
            val remoteId = pair.members["remoteCandidateId"] as? String
            val types = listOfNotNull(localId?.let(stats::get), remoteId?.let(stats::get)).mapNotNull { candidate ->
                candidate.members["candidateType"] as? String
            }
            val selected = if (types.any { it == "relay" }) "relay" else types.firstOrNull { it in setOf("host", "srflx", "prflx") } ?: "unknown"
            listener.onPeerTransport(peerId, selected)
        }
    }

    private fun rejectSignal(peerId: String, code: String) {
        // Do not log raw signaling payloads: they contain SDP and ICE addresses.
        Log.w(TAG, "Ignoring malformed signaling message peer=$peerId code=$code")
        listener.onPeerError(peerId, code)
    }

    private class SdpCallback(
        private val onSuccess: (SessionDescription) -> Unit,
        private val onFailure: (String) -> Unit,
        private val afterSetSuccess: () -> Unit = {}
    ) : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = onSuccess(description)
        override fun onSetSuccess() = afterSetSuccess()
        override fun onCreateFailure(error: String) = onFailure(error)
        override fun onSetFailure(error: String) = onFailure(error)
    }

    companion object {
        private const val TAG = "N24-P2P"
        private const val DATA_CHANNEL_LABEL = "network24-segments-v2"
        private const val MAX_MESSAGE_BYTES = 64 * 1024
        private const val MAX_PENDING_ICE_CANDIDATES = 256
        private const val BACKPRESSURE_POLL_MS = 5L
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
