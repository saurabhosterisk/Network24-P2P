package com.network24.player.core.p2p

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
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
    private val forceRelayWhenTurnAvailable: Boolean = true,
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
    @Volatile private var activeIceServers: List<Network24IceServer> = iceServers.toList()
    @Volatile private var relayOnly = Network24IceTransportPolicy.relayOnly(forceRelayWhenTurnAvailable, activeIceServers)
    private val peers = ConcurrentHashMap<String, PeerConnection>()
    private val channels = ConcurrentHashMap<String, DataChannel>()
    private val remoteDescriptionSet = ConcurrentHashMap<String, Boolean>()
    private val remoteAnswerStarted = ConcurrentHashMap<String, Boolean>()
    private val pendingIceCandidates = ConcurrentHashMap<String, ConcurrentLinkedQueue<IceCandidate>>()
    private val remoteIceEnded = ConcurrentHashMap<String, Boolean>()
    private val cancelledUploads = ConcurrentHashMap<String, Boolean>()
    private val uploadPeers = ConcurrentHashMap<String, String>()
    private val disconnectRecoveryTasks = ConcurrentHashMap<String, Runnable>()
    private val closed = AtomicBoolean(false)
    private val signalingHandler = Handler(Looper.getMainLooper())
    private val uploadExecutor = ThreadPoolExecutor(
        // Do not queue behind the active transfer. Media3 can open several
        // adjacent HLS segments concurrently; a queued transfer starts after
        // the receiver has already timed out and then only wastes DataChannel
        // capacity. Rejected requests use the normal HTTP fallback instead.
        1, 1, 0L, TimeUnit.MILLISECONDS, SynchronousQueue(),
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
        try {
            signalingHandler.post { connectOnSignalingThread(peerId, initiator) }
        } catch (_: RejectedExecutionException) {
            listener.onPeerError(peerId, "signaling_executor_rejected")
        }
    }

    private fun connectOnSignalingThread(peerId: String, initiator: Boolean) {
        synchronized(connectionLock) {
            if (closed.get() || peers.containsKey(peerId)) return
            try {
                createConnection(peerId, initiator)
            } catch (error: Throwable) {
                Log.e(TAG, "event=peer_connection_exception peer=${shortPeer(peerId)} initiator=$initiator", error)
                listener.onPeerError(peerId, "peer_connection_exception")
            }
        }
    }

    private fun createConnection(peerId: String, initiator: Boolean) {
        val rtcServers = activeIceServers.map { server ->
            PeerConnection.IceServer.builder(server.urls).apply {
                server.username?.takeIf { it.isNotBlank() }?.let { setUsername(it) }
                server.password?.takeIf { it.isNotBlank() }?.let { setPassword(it) }
            }.createIceServer()
        }
        val icePolicy = if (relayOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
        Log.i(TAG, "event=peer_connection_create peer=${shortPeer(peerId)} initiator=$initiator " +
            "iceServers=${rtcServers.size} ice_policy=$icePolicy")
        val rtcConfiguration = PeerConnection.RTCConfiguration(rtcServers).apply {
            iceTransportsType = icePolicy
        }
        val connection = factory.createPeerConnection(rtcConfiguration, observer(peerId))
        if (connection == null) {
            Log.e(TAG, "event=peer_connection_create_failed peer=${shortPeer(peerId)}")
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
            if (channel != null) {
                Log.i(TAG, "event=datachannel_created peer=${shortPeer(peerId)}")
                attachChannel(peerId, channel)
            } else {
                Log.e(TAG, "event=datachannel_create_failed peer=${shortPeer(peerId)}")
            }
            connection.createOffer(SdpCallback(
                onSuccess = { description ->
                    connection.setLocalDescription(SdpCallback(
                        onSuccess = {},
                        onFailure = { listener.onPeerError(peerId, "set_local_description_failed") },
                        afterSetSuccess = {
                            Log.i(TAG, "event=offer_sent peer=${shortPeer(peerId)}")
                            signaling.sendOffer(peerId, description.description)
                        }
                    ), description)
                },
                onFailure = { error ->
                    Log.e(TAG, "event=offer_failed peer=${shortPeer(peerId)} error=$error")
                    listener.onPeerError(peerId, "offer_failed")
                }
            ), MediaConstraints())
        }
    }

    /** Applies token-broker ICE servers and recreates peers when transport policy changes. */
    fun updateIceServers(servers: List<Network24IceServer>) {
        if (closed.get()) return
        val nextServers = servers.distinctBy { Triple(it.urls, it.username, it.password) }
        val nextRelayOnly = Network24IceTransportPolicy.relayOnly(forceRelayWhenTurnAvailable, nextServers)
        val policyChanged = relayOnly != nextRelayOnly
        activeIceServers = nextServers
        relayOnly = nextRelayOnly
        val turnCount = activeIceServers.count { it.urls.startsWith("turn:") || it.urls.startsWith("turns:") }
        Log.i(TAG, "event=ice_servers_updated count=${activeIceServers.size} " +
            "turn=$turnCount ice_policy=${if (relayOnly) "RELAY" else "ALL"}")
        if (policyChanged && peers.isNotEmpty()) {
            Log.i(TAG, "event=ice_policy_changed action=drop_existing " +
                "ice_policy=${if (relayOnly) "RELAY" else "ALL"}")
            dropAll()
        }
    }

    fun handleSignal(type: String, payload: JsonObject) {
        try {
            signalingHandler.post {
                try {
                    handleSignalOnSignalingThread(type, payload)
                } catch (error: Throwable) {
                    val peerId = payload.stringField("from_peer_id") ?: payload.stringField("peer_id") ?: "unknown"
                    Log.e(TAG, "event=signal_processing_exception type=$type peer=${shortPeer(peerId)}", error)
                    listener.onPeerError(peerId, "signal_processing_exception")
                    if (peerId != "unknown") dropOnSignalingThread(peerId)
                }
            }
        } catch (_: RejectedExecutionException) {
            val peerId = payload.stringField("from_peer_id") ?: payload.stringField("peer_id") ?: "unknown"
            listener.onPeerError(peerId, "signaling_executor_rejected")
        }
    }

    private fun handleSignalOnSignalingThread(type: String, payload: JsonObject) {
        val peerId = payload.stringField("from_peer_id") ?: payload.stringField("peer_id") ?: run {
            rejectSignal("unknown", "missing_signal_sender")
            return
        }
        val connection = peers[peerId] ?: run {
            Log.i(TAG, "event=peer_connection_from_signal peer=${shortPeer(peerId)} type=$type")
            connectOnSignalingThread(peerId, initiator = false)
            peers[peerId]
        } ?: run {
            listener.onPeerError(peerId, "peer_connection_missing_after_signal")
            return
        }
        when (type) {
            "offer" -> {
                val sdp = payload.stringField("sdp")?.takeIf { it.isNotBlank() } ?: run {
                    listener.onPeerError(peerId, "invalid_sdp")
                    return
                }
                val normalizedSdp = normalizeSdp(sdp)
                Log.i(
                    TAG,
                        "event=offer_received peer=${shortPeer(peerId)} sdp_chars=${normalizedSdp.length} " +
                        "lines=${normalizedSdp.lineSequence().count()} escaped=${sdp.contains("\\\\n")} " +
                        "shape=${sdpShape(normalizedSdp)} " +
                        "has_application=${normalizedSdp.contains("m=application")} has_ice=${normalizedSdp.contains("a=ice-ufrag:")}"
                )
                Log.i(TAG, "event=set_remote_offer_begin peer=${shortPeer(peerId)}")
                connection.setRemoteDescription(SdpCallback(
                    onSuccess = {
                        continueAfterRemoteOffer(peerId, connection, "callback")
                    },
                    onFailure = { error ->
                        Log.e(TAG, "event=set_remote_offer_failed peer=${shortPeer(peerId)} error=$error")
                        listener.onPeerError(peerId, "set_remote_description_failed")
                    }
                ), SessionDescription(SessionDescription.Type.OFFER, normalizedSdp))
                signalingHandler.postDelayed({
                    if (remoteDescriptionSet[peerId] == true || remoteAnswerStarted[peerId] == true) return@postDelayed
                    val nativeRemote = runCatching { connection.remoteDescription }.getOrNull()
                    Log.w(
                        TAG,
                        "event=remote_offer_poll peer=${shortPeer(peerId)} " +
                            "native_remote_present=${nativeRemote != null}"
                    )
                    if (nativeRemote != null) {
                        continueAfterRemoteOffer(peerId, connection, "poll")
                    } else {
                        listener.onPeerError(peerId, "set_remote_description_timeout")
                        dropOnSignalingThread(peerId)
                    }
                }, REMOTE_DESCRIPTION_POLL_MS)
            }
            "answer" -> payload.stringField("sdp")?.let { sdp ->
                if (sdp.isBlank()) {
                    listener.onPeerError(peerId, "invalid_sdp")
                    return
                }
                Log.i(TAG, "event=set_remote_answer_begin peer=${shortPeer(peerId)}")
                connection.setRemoteDescription(SdpCallback(
                    onSuccess = {
                        continueAfterRemoteAnswer(peerId, connection, "callback")
                    },
                    onFailure = { error ->
                        Log.e(TAG, "event=set_remote_answer_failed peer=${shortPeer(peerId)} error=$error")
                        listener.onPeerError(peerId, "set_remote_description_failed")
                    }
                ), SessionDescription(SessionDescription.Type.ANSWER, normalizeSdp(sdp)))
                signalingHandler.postDelayed({
                    if (remoteDescriptionSet[peerId] == true) return@postDelayed
                    val nativeRemote = runCatching { connection.remoteDescription }.getOrNull()
                    Log.w(
                        TAG,
                        "event=remote_answer_poll peer=${shortPeer(peerId)} " +
                            "native_remote_present=${nativeRemote != null}"
                    )
                    if (nativeRemote != null) {
                        continueAfterRemoteAnswer(peerId, connection, "poll")
                    } else {
                        listener.onPeerError(peerId, "set_remote_description_timeout")
                        dropOnSignalingThread(peerId)
                    }
                }, REMOTE_DESCRIPTION_POLL_MS)
            } ?: listener.onPeerError(peerId, "invalid_sdp")
            "ice_candidate" -> {
                if (payload.booleanField("endOfCandidates") == true) {
                    remoteIceEnded[peerId] = true
                    return
                }
                // Signaling transports may deliver the end marker before the final
                // candidate callbacks. Keep accepting validated late candidates.
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
                Log.i(TAG, "event=ice_candidate peer=${shortPeer(peerId)} direction=remote type=${candidateType(candidate)}")
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
        uploadPeers[requestId] = peerId
        return try {
            uploadExecutor.execute {
                var success = false
                try {
                    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(uploadDeadlineMs)
                    Log.i(TAG, "event=upload_start peer=${shortPeer(peerId)} request=${requestId.take(8)} bytes=${bytes.size}")
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
                        if (success && (index == 0 || index == chunks - 1 || index % 8 == 0)) {
                            Log.i(TAG, "event=upload_chunk peer=${shortPeer(peerId)} request=${requestId.take(8)} " +
                                "index=${index + 1}/$chunks bytes=${end - start} buffered=${channel.bufferedAmount()}")
                        }
                    }
                    if (success && !cancelledUploads.containsKey(requestId)) {
                        while (channel.bufferedAmount() > maxBufferedAmount && System.nanoTime() < deadline &&
                            !cancelledUploads.containsKey(requestId) && channel.state() == DataChannel.State.OPEN
                        ) Thread.sleep(BACKPRESSURE_POLL_MS)
                        success = System.nanoTime() < deadline && channel.bufferedAmount() <= maxBufferedAmount &&
                            !cancelledUploads.containsKey(requestId) && sendControl("complete")
                        if (success) Log.i(TAG, "event=upload_complete_queued peer=${shortPeer(peerId)} request=${requestId.take(8)}")
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    success = false
                } finally {
                    if (!success) {
                        Log.w(
                            TAG,
                            "event=upload_failed_detail peer=${shortPeer(peerId)} " +
                                "request=${requestId.take(8)} bytes=${bytes.size} " +
                                "channel_state=${channel.state()} buffered=${channel.bufferedAmount()} " +
                                "cancelled=${cancelledUploads.containsKey(requestId)}"
                        )
                    }
                    cancelledUploads.remove(requestId)
                    uploadPeers.remove(requestId)
                    onQueued(success)
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            uploadPeers.remove(requestId)
            Log.w(TAG, "event=upload_rejected_queue_full peer=${shortPeer(peerId)} request=${requestId.take(8)}")
            false
        }
    }

    fun cancelUpload(requestId: String) { if (requestId.isNotBlank()) cancelledUploads[requestId] = true }
    fun openPeerIds(): List<String> = channels.entries.filter { it.value.state() == DataChannel.State.OPEN }.map { it.key }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        signalingHandler.removeCallbacksAndMessages(null)
        channels.values.forEach { it.close() }
        peers.values.forEach { it.close() }
        channels.clear()
        pendingIceCandidates.clear()
        remoteDescriptionSet.clear()
        remoteAnswerStarted.clear()
        remoteIceEnded.clear()
        peers.clear()
        cancelledUploads.clear()
        uploadPeers.clear()
        disconnectRecoveryTasks.values.forEach(signalingHandler::removeCallbacks)
        disconnectRecoveryTasks.clear()
        uploadExecutor.shutdownNow()
        factory.dispose()
    }

    /** Remove a dead connection so the session can create a fresh ICE/SDP attempt. */
    fun drop(peerId: String) {
        if (closed.get()) return
        try {
            signalingHandler.post { dropOnSignalingThread(peerId) }
        } catch (_: RejectedExecutionException) {
            // The manager is already shutting down.
        }
    }

    private fun dropOnSignalingThread(peerId: String) {
        disconnectRecoveryTasks.remove(peerId)?.let(signalingHandler::removeCallbacks)
        uploadPeers.filterValues { it == peerId }.keys.forEach(::cancelUpload)
        channels.remove(peerId)?.close()
        pendingIceCandidates.remove(peerId)
        remoteDescriptionSet.remove(peerId)
        remoteAnswerStarted.remove(peerId)
        remoteIceEnded.remove(peerId)
        peers.remove(peerId)?.close()
    }

    fun dropAll() {
        if (closed.get()) return
        try {
            signalingHandler.post { peers.keys.toList().forEach(::dropOnSignalingThread) }
        } catch (_: RejectedExecutionException) {
            // The manager is already shutting down.
        }
    }

    private fun attachChannel(peerId: String, channel: DataChannel) {
        val previous = channels.put(peerId, channel)
        if (previous != null && previous !== channel) previous.close()
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                Log.i(TAG, "event=datachannel peer=${shortPeer(peerId)} state=${channel.state()}")
                if (channel.state() == DataChannel.State.OPEN) {
                    cancelDisconnectedRecovery(peerId)
                    listener.onPeerState(peerId, "connected")
                    listener.onPeerReady(peerId, channel)
                    inspectSelectedCandidate(peerId)
                }
                if ((channel.state() == DataChannel.State.CLOSING || channel.state() == DataChannel.State.CLOSED) && channels[peerId] === channel) {
                    channels.remove(peerId, channel)
                    uploadPeers.filterValues { it == peerId }.keys.forEach(::cancelUpload)
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
            Log.i(TAG, "event=ice_candidate peer=${shortPeer(peerId)} direction=local type=${candidateType(candidate.sdp)}")
            signaling.sendIceCandidate(peerId, candidate.sdp, mid, candidate.sdpMLineIndex)
        }
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            Log.i(TAG, "event=ice_gathering peer=${shortPeer(peerId)} state=$state")
            if (state == PeerConnection.IceGatheringState.COMPLETE) signaling.sendEndOfCandidates(peerId)
        }
        override fun onDataChannel(dataChannel: DataChannel) = attachChannel(peerId, dataChannel)
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.i(TAG, "event=ice_connection peer=${shortPeer(peerId)} state=$state")
            listener.onPeerState(peerId, when (state) {
                PeerConnection.IceConnectionState.CHECKING -> "connecting"
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> "connected"
                PeerConnection.IceConnectionState.DISCONNECTED -> "disconnected"
                PeerConnection.IceConnectionState.FAILED -> "failed"
                else -> "new"
            })
            if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                cancelDisconnectedRecovery(peerId)
                inspectSelectedCandidate(peerId)
            }
            if (state == PeerConnection.IceConnectionState.DISCONNECTED) scheduleDisconnectedRecovery(peerId)
            if (state == PeerConnection.IceConnectionState.FAILED) {
                cancelDisconnectedRecovery(peerId)
                listener.onPeerError(peerId, "ice_failed")
            }
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver, mediaStreams: Array<out org.webrtc.MediaStream>) = Unit
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "event=peer_connection_state peer=${shortPeer(peerId)} state=$newState")
            listener.onPeerState(peerId, when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> "connected"
                PeerConnection.PeerConnectionState.CONNECTING -> "connecting"
                PeerConnection.PeerConnectionState.DISCONNECTED -> "disconnected"
                PeerConnection.PeerConnectionState.FAILED -> "failed"
                else -> "new"
            })
            if (newState == PeerConnection.PeerConnectionState.DISCONNECTED) scheduleDisconnectedRecovery(peerId)
            if (newState == PeerConnection.PeerConnectionState.CONNECTED) cancelDisconnectedRecovery(peerId)
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

    private fun scheduleDisconnectedRecovery(peerId: String) {
        if (closed.get() || disconnectRecoveryTasks.containsKey(peerId)) return
        val task = Runnable {
            disconnectRecoveryTasks.remove(peerId)
            val connection = peers[peerId]
            if (connection?.iceConnectionState() == PeerConnection.IceConnectionState.DISCONNECTED ||
                connection?.connectionState() == PeerConnection.PeerConnectionState.DISCONNECTED
            ) {
                Log.w(TAG, "event=ice_disconnected_timeout peer=${shortPeer(peerId)} action=fresh_connection")
                listener.onPeerError(peerId, "ice_disconnected_timeout")
            }
        }
        disconnectRecoveryTasks[peerId] = task
        signalingHandler.postDelayed(task, DISCONNECTED_RECOVERY_DELAY_MS)
    }

    private fun cancelDisconnectedRecovery(peerId: String) {
        disconnectRecoveryTasks.remove(peerId)?.let(signalingHandler::removeCallbacks)
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
            Log.i(TAG, "event=ice_selected_pair peer=${shortPeer(peerId)} local_type=${types.getOrNull(0) ?: "unknown"} remote_type=${types.getOrNull(1) ?: "unknown"} transport=$selected")
            listener.onPeerTransport(peerId, selected)
        }
    }

    private fun rejectSignal(peerId: String, code: String) {
        // Do not log raw signaling payloads: they contain SDP and ICE addresses.
        Log.w(TAG, "Ignoring malformed signaling message peer=$peerId code=$code")
        listener.onPeerError(peerId, code)
    }

    private fun continueAfterRemoteOffer(peerId: String, connection: PeerConnection, source: String) {
        if (remoteDescriptionSet.putIfAbsent(peerId, true) != null) return
        Log.i(TAG, "event=remote_offer_set peer=${shortPeer(peerId)} source=$source")
        flushPendingIceCandidates(peerId, connection)
        if (remoteAnswerStarted.putIfAbsent(peerId, true) != null) return
        connection.createAnswer(SdpCallback(
            onSuccess = { answer ->
                connection.setLocalDescription(SdpCallback(
                    onSuccess = {},
                    onFailure = { error ->
                        Log.e(TAG, "event=set_answer_failed peer=${shortPeer(peerId)} error=$error")
                        listener.onPeerError(peerId, "set_local_description_failed")
                    },
                    afterSetSuccess = {
                        Log.i(TAG, "event=answer_sent peer=${shortPeer(peerId)}")
                        signaling.sendAnswer(peerId, answer.description)
                    }
                ), answer)
            },
            onFailure = { error ->
                Log.e(TAG, "event=answer_failed peer=${shortPeer(peerId)} error=$error")
                listener.onPeerError(peerId, "answer_failed")
            }
        ), MediaConstraints())
    }

    private fun continueAfterRemoteAnswer(peerId: String, connection: PeerConnection, source: String) {
        if (remoteDescriptionSet.putIfAbsent(peerId, true) != null) return
        Log.i(TAG, "event=remote_answer_set peer=${shortPeer(peerId)} source=$source")
        flushPendingIceCandidates(peerId, connection)
    }

    private fun normalizeSdp(sdp: String): String {
        // Some signaling proxies double-encode SDP line endings inside the
        // JSON string. Decode those first, then emit the CRLF form expected by
        // the native WebRTC SDP parser.
        val decoded = sdp
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("\\r", "\n")

        return decoded
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .joinToString("\r\n")
            .trimEnd('\r', '\n') + "\r\n"
    }

    private fun sdpShape(sdp: String): String = sdp.lineSequence()
        .filter { line ->
            line.startsWith("m=") || line.startsWith("a=group:") ||
                line.startsWith("a=setup:") || line.startsWith("a=mid:") ||
                line.startsWith("a=sctp-") || line.startsWith("a=max-message-size:")
        }
        .joinToString(";")

    private fun candidateType(candidate: String): String = candidate
        .split(' ')
        .dropWhile { it != "typ" }
        .getOrNull(1)
        ?.takeIf { it in setOf("host", "srflx", "prflx", "relay") }
        ?: "unknown"

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

        private fun shortPeer(peerId: String): String = peerId.takeLast(12)
        private const val MAX_PENDING_ICE_CANDIDATES = 256
        private const val REMOTE_DESCRIPTION_POLL_MS = 2_000L
        private const val DISCONNECTED_RECOVERY_DELAY_MS = 8_000L
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
