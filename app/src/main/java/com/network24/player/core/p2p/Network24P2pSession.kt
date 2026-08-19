package com.network24.player.core.p2p

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.network24.player.BuildConfig
import java.util.UUID
import java.util.Collections
import java.util.HashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** One app-wide, stream-generation-scoped bridge between Media3 and WebRTC peers. */
class Network24P2pSession(
    context: Context,
    private val config: Network24P2pConfig = Network24P2pConfig(),
) : Network24MediaBridge {
    val enabled: Boolean get() = config.enabled
    private val appContext = context.applicationContext
    private val gson = Gson()
    private val cache = Network24SegmentCache(appContext)
    private val pending = ConcurrentHashMap<String, PendingRequest>()
    private val outbound = ConcurrentHashMap<String, OutboundTransfer>()
    private val advertisedSegments = ConcurrentHashMap<String, RecentSegmentSet>()
    private val deviceId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        ?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
    private val scheduler = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "network24-p2p-session").apply { isDaemon = true }
    }
    private val signaling: Network24SignalingClient by lazy {
        Network24SignalingClient(
            config = config,
            registration = Network24DeviceRegistration(deviceId, deviceType(), BuildConfig.VERSION_NAME),
            tokenProvider = Network24AccountTokenProvider(appContext, com.network24.player.core.preferences.PreferenceManager(appContext)),
            listener = signalingListener,
        )
    }
    private val webrtc: Network24WebRtcPeerManager by lazy {
        Network24WebRtcPeerManager(
            appContext, signaling, webRtcListener, config.iceServers,
            config.uploadDeadlineMs.coerceIn(500L, 120_000L),
            config.maxDataChannelBufferedBytes.coerceIn(64L * 1024L, 8L * 1024L * 1024L),
            config.forceRelayWhenTurnAvailable,
            config.disconnectedRecoveryDelayMs.coerceIn(5_000L, 120_000L),
        )
    }

    @Volatile private var streamId: String? = null
    @Volatile private var displayStreamId: String? = null
    @Volatile private var mediaSessionId = UUID.randomUUID().toString()
    @Volatile private var localPeerId: String? = null
    @Volatile private var runtimeIceServers: List<Network24IceServer> = emptyList()
    @Volatile private var discoveredPeers: List<Network24SignalingClient.Peer> = emptyList()
    private val generation = AtomicLong(0L)
    private val stats = AtomicReference(SessionStats())
    private val connectionStates = ConcurrentHashMap<String, String>()
    private val connectionRoles = ConcurrentHashMap<String, MutableSet<String>>()
    private val connectionTransports = ConcurrentHashMap<String, String>()
    private val peerCooldownUntilMs = ConcurrentHashMap<String, Long>()
    private val peerHealthScores = ConcurrentHashMap<String, AtomicLong>()
    private val peerRttMs = ConcurrentHashMap<String, Long>()

    fun start() {
        if (!config.enabled) return
        signaling.connect()
        scheduler.scheduleWithFixedDelay({
            if (streamId != null) {
                signaling.requestPeers()
                publishTelemetry()
                expireOutbound()
            }
        }, PEER_REFRESH_MS, PEER_REFRESH_MS, TimeUnit.MILLISECONDS)
    }

    fun joinStream(newStreamId: String?, streamUri: String? = null) {
        val raw = newStreamId?.trim()?.takeIf { it.isNotEmpty() && it.length <= 256 }
        val normalized = if (raw != null && !streamUri.isNullOrBlank()) {
            Network24MediaRequest.streamIdentity(raw, streamUri)
        } else raw
        if (normalized == streamId) return

        logSessionSummary("stream_switch")
        generation.incrementAndGet()
        cancelPending(Network24PeerMissReason.SWITCHED)
        outbound.keys.forEach(webrtc::cancelUpload)
        outbound.clear()
        webrtc.dropAll()
        discoveredPeers = emptyList()
        advertisedSegments.clear()
        connectionStates.clear()
        connectionRoles.clear()
        connectionTransports.clear()
        peerCooldownUntilMs.clear()
        peerHealthScores.clear()
        peerRttMs.clear()
        signaling.leaveStream()

        streamId = normalized
        displayStreamId = raw
        mediaSessionId = UUID.randomUUID().toString()
        stats.set(SessionStats())
        if (normalized != null) {
            Log.i(TAG, "event=session_start device=${shortDevice(deviceId)} stream=${safeLog(raw)} room=${normalized.take(24)} generation=${generation.get()}")
            signaling.joinStream(normalized)
            signaling.requestPeers()
        }
    }

    fun mediaCache(): Network24SegmentCache = cache
    fun mediaRequestTimeoutMs(): Long = config.segmentRequestTimeoutMs.coerceIn(500L, 120_000L)
    override fun currentStreamId(): String? = streamId

    fun close() {
        logSessionSummary("close")
        cancelPending(Network24PeerMissReason.NO_SESSION)
        scheduler.shutdownNow()
        webrtc.close()
        signaling.close()
    }

    override fun fetch(request: Network24MediaRequest, timeoutMs: Long): Network24PeerFetchOutcome {
        val room = streamId ?: return Network24PeerFetchOutcome.Miss(Network24PeerMissReason.NO_SESSION)
        val requestGeneration = generation.get()
        if (request.streamId != room) return Network24PeerFetchOutcome.Miss(Network24PeerMissReason.SWITCHED)
        val nowMs = System.currentTimeMillis()
        val openPeers = webrtc.openPeerIds()
            .filter { peer -> discoveredPeers.any { it.peerId == peer } }
            .filter { peer ->
                val cooldownUntil = peerCooldownUntilMs[peer] ?: 0L
                if (cooldownUntil > nowMs) {
                    Log.i(TAG, "event=peer_skipped peer=${shortPeer(peer)} reason=cooldown remaining_ms=${cooldownUntil - nowMs}")
                    false
                } else {
                    peerCooldownUntilMs.remove(peer, cooldownUntil)
                    true
                }
            }
        if (openPeers.isEmpty()) return Network24PeerFetchOutcome.Miss(Network24PeerMissReason.NO_PEER)

        val currentStats = stats.get()
        val advertised = openPeers.filter { advertisedSegments[it]?.contains(request.segmentKey) == true }
        // Do not blindly probe a peer that has not advertised this exact
        // segment. On a live stream that turns normal playback skew into a
        // large stream of guaranteed misses and can starve the real transfers.
        if (advertised.isEmpty()) return Network24PeerFetchOutcome.Miss(Network24PeerMissReason.NO_PEER)
        currentStats.p2pRequests.incrementAndGet()
        val orderedPeers = advertised.distinct().take(MAX_DOWNLOAD_PEERS)
        // A peer that explicitly advertised this segment gets enough time to
        // drain a multi-megabyte transfer; non-advertised peers are skipped.
        val requestTimeoutMs = if (advertised.isNotEmpty()) {
            timeoutMs.coerceIn(500L, 45_000L)
        } else {
            timeoutMs.coerceIn(500L, 1_500L)
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(requestTimeoutMs)
        var finalReason = Network24PeerMissReason.UNAVAILABLE

        for (peerId in orderedPeers) {
            if (generation.get() != requestGeneration || streamId != room) {
                finalReason = Network24PeerMissReason.SWITCHED
                break
            }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) {
                finalReason = Network24PeerMissReason.TIMEOUT
                break
            }
            val requestId = UUID.randomUUID().toString()
            val state = PendingRequest(request, requestGeneration, peerId, requestId)
            pending[requestId] = state
            val sent = sendControl(peerId, JsonObject().apply {
                addProperty("protocol", Network24PeerProtocol.VERSION)
                addProperty("type", "segment_request")
                addProperty("stream_id", room)
                addProperty("request_id", requestId)
                addProperty("segment_key", request.segmentKey)
            })
            if (!sent) {
                pending.remove(requestId)
                markPeerFailure(peerId, Network24PeerMissReason.SEND_FAILED)
                finalReason = Network24PeerMissReason.SEND_FAILED
                continue
            }
            try {
                state.latch.await(remaining, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                pending.remove(requestId)
                sendCancel(peerId, room, requestId, request.segmentKey)
                finalReason = Network24PeerMissReason.TIMEOUT
                break
            }
            pending.remove(requestId)
            val bytes = state.bytes
            if (bytes != null) {
                val transport = connectionTransports[peerId] ?: "unknown"
                return Network24PeerFetchOutcome.Hit(request, requestId, peerId, bytes, transport, requestGeneration)
            }
            if (!state.completed) {
                sendCancel(peerId, room, requestId, request.segmentKey)
                markPeerFailure(peerId, Network24PeerMissReason.TIMEOUT)
                finalReason = Network24PeerMissReason.TIMEOUT
                break
            }
            finalReason = state.failure ?: Network24PeerMissReason.UNAVAILABLE
            if (finalReason == Network24PeerMissReason.UNAVAILABLE ||
                finalReason == Network24PeerMissReason.TIMEOUT ||
                finalReason == Network24PeerMissReason.INTEGRITY ||
                finalReason == Network24PeerMissReason.SEND_FAILED
            ) {
                // A have message is only a hint. Remove it as soon as the
                // peer cannot serve the segment so an expired/evicted cache
                // entry cannot cause repeated long waits.
                advertisedSegments[peerId]?.remove(request.segmentKey)
            }
            if (finalReason == Network24PeerMissReason.TIMEOUT || finalReason == Network24PeerMissReason.INTEGRITY ||
                finalReason == Network24PeerMissReason.SEND_FAILED
            ) {
                markPeerFailure(peerId, finalReason)
            }
        }

        if (finalReason == Network24PeerMissReason.TIMEOUT) currentStats.p2pTimeouts.incrementAndGet()
        currentStats.p2pMisses.incrementAndGet()
        publishTelemetry()
        return Network24PeerFetchOutcome.Miss(finalReason)
    }

    override fun consumed(hit: Network24PeerFetchOutcome.Hit, bytes: Long, durationMs: Long) {
        if (bytes != hit.bytes.size.toLong() || generation.get() != hit.generation || streamId != hit.request.streamId) {
            cancel(hit, "stale_or_partial_media3_read")
            return
        }
        val currentStats = stats.get()
        currentStats.bytesFromP2p.addAndGet(bytes)
        currentStats.segmentsFromP2p.incrementAndGet()
        currentStats.p2pHits.incrementAndGet()
        adjustPeerHealth(hit.peerId, PEER_HEALTH_TRANSFER_SUCCESS)
        if (hit.transport == "relay") currentStats.turnBytes.addAndGet(bytes)
        peerCooldownUntilMs.remove(hit.peerId)
        markRole(hit.peerId, "downloader")
        sendControl(hit.peerId, JsonObject().apply {
            addProperty("protocol", Network24PeerProtocol.VERSION)
            addProperty("type", "segment_ack")
            addProperty("stream_id", hit.request.streamId)
            addProperty("request_id", hit.requestId)
            addProperty("segment_key", hit.request.segmentKey)
            addProperty("bytes", bytes)
        })
        Log.i(TAG, "event=media stream=${safeLog(displayStreamId)} segment=${hit.request.logLabel} source=P2P peer=${shortPeer(hit.peerId)} transport=${hit.transport} bytes=$bytes duration_ms=$durationMs")
        publishTelemetry()
    }

    override fun reject(hit: Network24PeerFetchOutcome.Hit, reason: String) = cancel(hit, reason)

    override fun cancel(hit: Network24PeerFetchOutcome.Hit, reason: String) {
        sendCancel(hit.peerId, hit.request.streamId, hit.requestId, hit.request.segmentKey)
        Log.w(TAG, "event=p2p_discard stream=${safeLog(displayStreamId)} segment=${hit.request.logLabel} reason=$reason")
    }

    override fun recordHttpBytes(bytes: Long) {
        if (bytes > 0L) stats.get().bytesFromHttp.addAndGet(bytes)
    }

    override fun httpSegmentComplete(
        request: Network24MediaRequest,
        bytes: Int,
        reason: Network24PeerMissReason,
        durationMs: Long,
    ) {
        if (streamId != request.streamId || bytes <= 0) return
        stats.get().segmentsFromHttp.incrementAndGet()
        val cached = cache.get(request.segmentKey) != null
        Log.i(
            TAG,
            "event=cache_ready stream=${safeLog(displayStreamId)} segment=${request.logLabel} " +
                "key=${request.segmentKey.take(12)} cached=$cached reason=$reason"
        )
        if (cached) announceSegment(request.segmentKey)
        Log.i(TAG, "event=media stream=${safeLog(displayStreamId)} segment=${request.logLabel} source=HTTP reason=$reason bytes=$bytes duration_ms=$durationMs")
    }

    private val signalingListener = object : Network24SignalingClient.Listener {
        override fun onIceServers(iceServers: List<Network24IceServer>) {
            // Keep the public STUN fallback and add only the short-lived TURN
            // servers returned by the authenticated token broker.
            runtimeIceServers = iceServers
            val merged = (config.iceServers + runtimeIceServers)
                .distinctBy { Triple(it.urls, it.username, it.password) }
            Log.i(TAG, "event=ice_servers count=${merged.size} turn=${iceServers.size} source=token")
            webrtc.updateIceServers(merged)
        }

        override fun onLocalPeerId(peerId: String) {
            if (localPeerId != null && localPeerId != peerId) {
                // A signaling reconnect can assign a new server peer id while
                // an already-established relay DataChannel is still healthy.
                // Keep that direct path alive; the next peer list reconciles
                // peers that are no longer present.
                Log.i(TAG, "event=local_peer_id_changed action=keep_existing_connections")
            }
            localPeerId = peerId
            webrtc.updateIceServers(config.iceServers + runtimeIceServers)
            Log.i(TAG, "event=registered peer=${shortPeer(peerId)}")
            if (streamId != null) signaling.requestPeers()
            connectToDiscoveredPeers()
        }

        override fun onState(state: Network24SignalingClient.State) {
            Log.i(TAG, "event=signaling state=$state stream=${safeLog(displayStreamId)}")
            if (state == Network24SignalingClient.State.AUTHENTICATED) {
                streamId?.let {
                    signaling.joinStream(it)
                    signaling.requestPeers()
                }
            }
            publishTelemetry()
        }

        override fun onPeerList(peers: List<Network24SignalingClient.Peer>) {
            val old = discoveredPeers.map { it.peerId }.toSet()
            discoveredPeers = selectBestPeers(peers)
            val current = discoveredPeers.map { it.peerId }.toSet()
            (old - current).forEach { peer ->
                webrtc.drop(peer)
                connectionStates.remove(peer)
                connectionRoles.remove(peer)
                connectionTransports.remove(peer)
                advertisedSegments.remove(peer)
                peerCooldownUntilMs.remove(peer)
                peerRttMs.remove(peer)
            }
            (current - old).forEach { Log.i(TAG, "event=peer_discovered stream=${safeLog(displayStreamId)} peer=${shortPeer(it)}") }
            current.forEach { connectionStates.putIfAbsent(it, "connecting") }
            publishTelemetry()
            connectToDiscoveredPeers()
        }

        override fun onSignal(type: String, payload: JsonObject) {
            val from = payload.get("from_peer_id")?.takeIf { it.isJsonPrimitive }?.asString
                ?: payload.get("peer_id")?.takeIf { it.isJsonPrimitive }?.asString
                ?: "unknown"
            Log.i(TAG, "event=signal_received type=$type peer=${shortPeer(from)}")
            runCatching { webrtc.handleSignal(type, payload) }
                .onFailure { error ->
                    Log.e(TAG, "event=signal_exception type=$type peer=${shortPeer(from)}", error)
                    webRtcListener.onPeerError(from, "signal_exception")
                }
        }
        override fun onError(code: String) { Log.w(TAG, "event=signaling_error code=$code") }
    }

    private fun connectToDiscoveredPeers() {
        val ownId = localPeerId ?: run {
            Log.w(TAG, "event=connect_skipped reason=local_peer_id_missing")
            return
        }
        discoveredPeers.forEach { peer ->
            val initiator = ownId < peer.peerId
            Log.i(TAG, "event=connect_attempt peer=${shortPeer(peer.peerId)} initiator=$initiator")
            if (initiator) {
                runCatching { webrtc.connect(peer.peerId, initiator = true) }
                    .onFailure { error ->
                        Log.e(TAG, "event=connect_exception peer=${shortPeer(peer.peerId)}", error)
                        webRtcListener.onPeerError(peer.peerId, "connect_exception")
                    }
            } else {
                Log.i(TAG, "event=connect_waiting_for_offer peer=${shortPeer(peer.peerId)}")
            }
        }
    }

    private fun selectBestPeers(peers: List<Network24SignalingClient.Peer>): List<Network24SignalingClient.Peer> {
        val ownId = localPeerId ?: return emptyList()
        val eligible = peers
            .asSequence()
            .filter { it.peerId != ownId }
            .distinctBy { it.peerId }
            .toList()
        val retained = eligible.filter { peer ->
            connectionStates[peer.peerId] == "connected" || connectionStates[peer.peerId] == "connecting"
        }
        val ranked = eligible.sortedByDescending { peerSelectionScore(ownId, it.peerId) }
        val selected = (retained + ranked).distinctBy { it.peerId }.take(MAX_CONNECTED_PEERS)
        Log.i(
            TAG,
            "event=peer_selection candidates=${eligible.size} selected=${selected.size} " +
                "active_limit=$MAX_CONNECTED_PEERS"
        )
        return selected
    }

    private fun peerSelectionScore(ownId: String, peerId: String): Long {
        val stateScore = when (connectionStates[peerId]) {
            "connected" -> 1_000_000L
            "connecting" -> 500_000L
            "failed", "disconnected" -> -1_000_000L
            else -> 0L
        }
        val healthScore = peerHealthScores[peerId]?.get() ?: 0L
        val rttScore = peerRttMs[peerId]
            ?.let { (100_000L - it.coerceAtMost(100_000L)).coerceAtLeast(0L) }
            ?: 0L
        // Stable per-device affinity spreads otherwise equal unknown peers
        // across the room instead of making every client choose list index 0.
        val affinityScore = ("$ownId:$peerId".hashCode().toLong() and 0x7fffffffL) % 10_000L
        return stateScore + healthScore * 1_000L + rttScore + affinityScore
    }

    private fun adjustPeerHealth(peerId: String, delta: Long) {
        peerHealthScores.computeIfAbsent(peerId) { AtomicLong() }.addAndGet(delta)
    }

    private val webRtcListener = object : Network24WebRtcPeerManager.Listener {
        override fun onPeerReady(peerId: String, channel: org.webrtc.DataChannel) {
            adjustPeerHealth(peerId, PEER_HEALTH_SUCCESS)
            connectionStates[peerId] = "connected"
            stats.get().peerConnectionsSuccessful.incrementAndGet()
            Log.i(TAG, "event=datachannel state=OPEN peer=${shortPeer(peerId)}")
            cache.recentKeys().forEach { announceSegmentToPeer(peerId, it) }
            publishTelemetry()
        }

        override fun onPeerClosed(peerId: String) {
            adjustPeerHealth(peerId, PEER_HEALTH_CLOSED)
            connectionStates[peerId] = "disconnected"
            cancelPendingForPeer(peerId)
            // A closed DataChannel cannot be reused. Remove its PeerConnection
            // so the next peer-list refresh creates a fresh offer/ICE attempt.
            webrtc.drop(peerId)
            publishTelemetry()
            signaling.requestPeers()
        }

        override fun onPeerState(peerId: String, state: String) {
            connectionStates[peerId] = state
            Log.i(TAG, "event=webrtc peer=${shortPeer(peerId)} state=$state")
            publishTelemetry()
        }

        override fun onPeerTransport(peerId: String, candidateType: String) {
            connectionTransports[peerId] = candidateType
            Log.i(TAG, "event=ice_selected peer=${shortPeer(peerId)} candidate_type=$candidateType direct=${candidateType != "relay"}")
            publishTelemetry()
        }

        override fun onPeerRtt(peerId: String, rttMs: Long) {
            peerRttMs[peerId] = rttMs
            Log.i(TAG, "event=peer_quality peer=${shortPeer(peerId)} rtt_ms=$rttMs")
        }

        override fun onPeerError(peerId: String, code: String) {
            adjustPeerHealth(peerId, PEER_HEALTH_FAILURE)
            Log.w(TAG, "event=webrtc_error peer=${shortPeer(peerId)} code=$code")
            if (code.startsWith("invalid_") || code == "ice_candidate_after_end" || code == "too_many_pending_ice_candidates") return
            connectionStates[peerId] = "failed"
            stats.get().peerConnectionsFailed.incrementAndGet()
            cancelPendingForPeer(peerId)
            webrtc.drop(peerId)
            publishTelemetry()
            signaling.requestPeers()
        }

        override fun onPeerMessage(peerId: String, bytes: ByteArray) {
            if (bytes.isEmpty() || bytes.size > config.maxMessageBytes) return
            val chunk = Network24PeerProtocol.decodeChunk(bytes)
            if (chunk != null) {
                pending[chunk.requestId]?.acceptChunk(peerId, chunk)
                return
            }
            handleControl(peerId, bytes)
        }
    }

    private fun handleControl(peerId: String, bytes: ByteArray) {
        try {
            val message = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            if (message.int("protocol") != Network24PeerProtocol.VERSION) return
            when (message.string("type")) {
                "segment_have" -> {
                    if (message.string("stream_id") == streamId) message.segmentKey()?.let {
                        val created = RecentSegmentSet()
                        (advertisedSegments.putIfAbsent(peerId, created) ?: created).add(it)
                        Log.i(TAG, "event=segment_have peer=${shortPeer(peerId)} key=${it.take(12)}")
                    }
                }
                "segment_request" -> serveSegmentRequest(peerId, message)
                "segment_meta" -> {
                    val id = message.requestId() ?: return
                    pending[id]?.acceptMeta(peerId, message)
                }
                "segment_complete" -> {
                    val id = message.requestId() ?: return
                    pending[id]?.complete(peerId, message)
                }
                "segment_unavailable" -> {
                    val id = message.requestId() ?: return
                    pending[id]?.unavailable(peerId)
                }
                "segment_cancel" -> message.requestId()?.let {
                    webrtc.cancelUpload(it)
                    outbound.remove(it)
                }
                "segment_ack" -> acceptUploadAck(peerId, message)
            }
        } catch (_: Exception) {
            Log.w(TAG, "event=peer_message_rejected peer=${shortPeer(peerId)}")
        }
    }

    private fun serveSegmentRequest(peerId: String, message: JsonObject) {
        val room = streamId ?: return
        if (message.string("stream_id") != room || discoveredPeers.none { it.peerId == peerId }) return
        val requestId = message.requestId() ?: return
        val segmentKey = message.segmentKey() ?: return
        val bytes = cache.get(segmentKey)
        Log.i(
            TAG,
            "event=segment_request peer=${shortPeer(peerId)} key=${segmentKey.take(12)} " +
                "cache=${if (bytes != null) "hit" else "miss"}"
        )
        if (bytes == null) {
            sendControl(peerId, responseControl("segment_unavailable", room, requestId, segmentKey))
            return
        }
        val hash = Network24MediaRequest.sha256(bytes)
        val chunks = (bytes.size + Network24PeerProtocol.CHUNK_BYTES - 1) / Network24PeerProtocol.CHUNK_BYTES
        val transfer = OutboundTransfer(peerId, room, segmentKey, bytes.size, hash, System.currentTimeMillis())
        outbound[requestId] = transfer
        val accepted = webrtc.sendSegment(
            peerId, requestId, segmentKey, bytes,
            sendControl = { stage ->
                val control = responseControl(if (stage == "meta") "segment_meta" else "segment_complete", room, requestId, segmentKey).apply {
                    if (stage == "meta") {
                        addProperty("total_size", bytes.size)
                        addProperty("chunk_count", chunks)
                        addProperty("sha256", hash)
                    }
                }
                sendControl(peerId, control)
            },
            onQueued = { success ->
                if (!success) {
                    outbound.remove(requestId)
                    Log.w(TAG, "event=upload_failed peer=${shortPeer(peerId)} segment=${segmentKey.take(12)}")
                }
            },
        )
        if (!accepted) {
            outbound.remove(requestId)
            sendControl(peerId, responseControl("segment_unavailable", room, requestId, segmentKey))
        }
    }

    private fun acceptUploadAck(peerId: String, message: JsonObject) {
        val requestId = message.requestId() ?: return
        val transfer = outbound[requestId] ?: return
        if (transfer.peerId != peerId || transfer.streamId != streamId || message.segmentKey() != transfer.segmentKey ||
            message.long("bytes") != transfer.bytes.toLong()
        ) return
        if (!outbound.remove(requestId, transfer)) return
        val currentStats = stats.get()
        currentStats.bytesUploadedToPeers.addAndGet(transfer.bytes.toLong())
        currentStats.segmentsUploaded.incrementAndGet()
        adjustPeerHealth(peerId, PEER_HEALTH_TRANSFER_SUCCESS)
        markRole(peerId, "uploader")
        Log.i(TAG, "event=upload_ack stream=${safeLog(displayStreamId)} request=${requestId.take(8)} segment=${transfer.segmentKey.take(12)} peer=${shortPeer(peerId)} transport=${connectionTransports[peerId] ?: "unknown"} bytes=${transfer.bytes}")
        publishTelemetry()
    }

    private fun announceSegment(segmentKey: String) {
        webrtc.openPeerIds().take(MAX_UPLOAD_PEERS).forEach { announceSegmentToPeer(it, segmentKey) }
    }

    private fun announceSegmentToPeer(peerId: String, segmentKey: String) {
        val room = streamId ?: return
        sendControl(peerId, JsonObject().apply {
            addProperty("protocol", Network24PeerProtocol.VERSION)
            addProperty("type", "segment_have")
            addProperty("stream_id", room)
            addProperty("segment_key", segmentKey)
        })
    }

    private fun sendCancel(peerId: String, room: String, requestId: String, segmentKey: String) {
        sendControl(peerId, responseControl("segment_cancel", room, requestId, segmentKey))
    }

    private fun sendControl(peerId: String, message: JsonObject): Boolean =
        webrtc.sendControl(peerId, gson.toJson(message).toByteArray(Charsets.UTF_8))

    private fun responseControl(type: String, room: String, requestId: String, segmentKey: String) = JsonObject().apply {
        addProperty("protocol", Network24PeerProtocol.VERSION)
        addProperty("type", type)
        addProperty("stream_id", room)
        addProperty("request_id", requestId)
        addProperty("segment_key", segmentKey)
    }

    private fun cancelPending(reason: Network24PeerMissReason) {
        pending.values.forEach { it.fail(reason) }
        pending.clear()
    }

    private fun cancelPendingForPeer(peerId: String) {
        pending.values.filter { it.peerId == peerId }.forEach { it.fail(Network24PeerMissReason.NO_PEER) }
    }

    private fun expireOutbound() {
        val cutoff = System.currentTimeMillis() - OUTBOUND_TTL_MS
        outbound.entries.toList().forEach { entry ->
            if (entry.value.createdAtMs < cutoff) {
                webrtc.cancelUpload(entry.key)
                outbound.remove(entry.key)
            }
        }
    }

    private fun markRole(peerId: String, role: String) {
        val created = Collections.synchronizedSet(HashSet<String>())
        (connectionRoles.putIfAbsent(peerId, created) ?: created).add(role)
    }

    private fun markPeerFailure(peerId: String, reason: Network24PeerMissReason) {
        if (reason != Network24PeerMissReason.TIMEOUT && reason != Network24PeerMissReason.INTEGRITY &&
            reason != Network24PeerMissReason.SEND_FAILED
        ) return
        val until = System.currentTimeMillis() + PEER_FAILURE_COOLDOWN_MS
        peerCooldownUntilMs[peerId] = until
        Log.w(TAG, "event=peer_cooldown peer=${shortPeer(peerId)} reason=$reason duration_ms=$PEER_FAILURE_COOLDOWN_MS")
    }

    private fun publishTelemetry() {
        val room = streamId ?: return
        val values = stats.get()
        val payload = JsonObject().apply {
            addProperty("stream_id", room)
            addProperty("media_session_id", mediaSessionId)
            addProperty("webrtc_state", when {
                connectionStates.values.any { it == "connected" } -> "connected"
                connectionStates.values.any { it == "connecting" } -> "connecting"
                connectionStates.values.any { it == "failed" } -> "failed"
                else -> "idle"
            })
            addProperty("p2p_bytes_uploaded", values.bytesUploadedToPeers.get())
            addProperty("p2p_bytes_downloaded", values.bytesFromP2p.get())
            addProperty("cdn_bytes_downloaded", values.bytesFromHttp.get())
            addProperty("successful_transfers", values.p2pHits.get())
            addProperty("failed_transfers", values.p2pMisses.get())
            addProperty("p2p_requests", values.p2pRequests.get())
            addProperty("p2p_hits", values.p2pHits.get())
            addProperty("p2p_misses", values.p2pMisses.get())
            addProperty("p2p_timeouts", values.p2pTimeouts.get())
            addProperty("segments_from_http", values.segmentsFromHttp.get())
            addProperty("segments_from_p2p", values.segmentsFromP2p.get())
            addProperty("segments_uploaded", values.segmentsUploaded.get())
            addProperty("peer_connections_successful", values.peerConnectionsSuccessful.get())
            addProperty("peer_connections_failed", values.peerConnectionsFailed.get())
            addProperty("turn_bytes", values.turnBytes.get())
            val connections = JsonArray()
            connectionStates.forEach { (peerId, state) ->
                connections.add(JsonObject().apply {
                    addProperty("peer_id", peerId)
                    addProperty("state", state)
                    val roles = rolesFor(peerId)
                    if (roles.isNotEmpty()) addProperty("role", if (roles.size > 1) "both" else roles.first())
                    connectionTransports[peerId]?.let { addProperty("candidate_type", it) }
                })
            }
            add("connections", connections)
        }
        signaling.sendTelemetry(payload)
    }

    private fun logSessionSummary(reason: String) {
        val room = displayStreamId ?: return
        val value = stats.get()
        Log.i(TAG, "event=session_stats reason=$reason stream=${safeLog(room)} bytesFromP2p=${value.bytesFromP2p.get()} bytesFromHttp=${value.bytesFromHttp.get()} bytesUploadedToPeers=${value.bytesUploadedToPeers.get()} segmentsFromP2p=${value.segmentsFromP2p.get()} segmentsFromHttp=${value.segmentsFromHttp.get()} p2pRequests=${value.p2pRequests.get()} p2pHits=${value.p2pHits.get()} p2pMisses=${value.p2pMisses.get()} p2pTimeouts=${value.p2pTimeouts.get()} turnBytes=${value.turnBytes.get()}")
    }

    private fun rolesFor(peerId: String): Set<String> {
        val roles = connectionRoles[peerId] ?: return emptySet()
        return synchronized(roles) { roles.toSet() }
    }

    private fun deviceType(): String = if (appContext.packageManager.hasSystemFeature("android.software.leanback")) "ANDROID_TV" else "ANDROID"
    private fun safeLog(value: String?): String = value?.replace(Regex("[^A-Za-z0-9._:-]"), "_")?.take(80) ?: "none"
    private fun shortPeer(peerId: String): String = peerId.takeLast(12)
    private fun shortDevice(value: String): String = value.takeLast(8)

    private class PendingRequest(
        val request: Network24MediaRequest,
        val generation: Long,
        val peerId: String,
        private val requestId: String,
    ) {
        val latch = CountDownLatch(1)
        @Volatile var bytes: ByteArray? = null
        @Volatile var completed = false
        @Volatile var failure: Network24PeerMissReason? = null
        private val assembler = Network24SegmentAssembler(request.segmentKey, request.length)

        @Synchronized
        fun acceptMeta(sender: String, message: JsonObject) {
            if (sender != peerId || completed || message.string("stream_id") != request.streamId || message.segmentKey() != request.segmentKey) return
            val size = message.int("total_size") ?: return fail(Network24PeerMissReason.INTEGRITY)
            val count = message.int("chunk_count") ?: return fail(Network24PeerMissReason.INTEGRITY)
            val hash = message.string("sha256")?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return fail(Network24PeerMissReason.INTEGRITY)
            if (!assembler.acceptMeta(request.segmentKey, size, count, hash)) {
                Log.w(TAG, "event=segment_meta_rejected peer=${peerId.takeLast(12)} request=${requestIdForLog()} " +
                    "segment=${request.segmentKey.take(12)} size=$size chunks=$count")
                fail(Network24PeerMissReason.INTEGRITY)
            } else {
                Log.i(TAG, "event=segment_meta_received peer=${peerId.takeLast(12)} request=${requestIdForLog()} " +
                    "segment=${request.segmentKey.take(12)} size=$size chunks=$count")
            }
        }

        @Synchronized
        fun acceptChunk(sender: String, chunk: Network24PeerProtocol.Chunk) {
            if (sender != peerId || completed || chunk.requestId != requestId || chunk.segmentKey != request.segmentKey) {
                Log.w(TAG, "event=segment_chunk_rejected peer=${peerId.takeLast(12)} request=${requestIdForLog()} " +
                    "segment=${request.segmentKey.take(12)} reason=request_or_segment_mismatch")
                return fail(Network24PeerMissReason.INTEGRITY)
            }
            if (!assembler.acceptChunk(chunk)) {
                Log.w(TAG, "event=segment_chunk_rejected peer=${peerId.takeLast(12)} request=${chunk.requestId.take(8)} " +
                    "segment=${chunk.segmentKey.take(12)} index=${chunk.index}")
                fail(Network24PeerMissReason.INTEGRITY)
            } else if (chunk.index == 0 || chunk.index == assembler.chunkCount() - 1 || chunk.index % 8 == 0) {
                Log.i(TAG, "event=segment_chunk_received peer=${peerId.takeLast(12)} request=${requestIdForLog()} " +
                    "segment=${request.segmentKey.take(12)} index=${chunk.index + 1}/${assembler.chunkCount()} bytes=${chunk.bytes.size}")
            }
        }

        @Synchronized
        fun complete(sender: String, message: JsonObject) {
            if (sender != peerId || completed || message.string("stream_id") != request.streamId || message.segmentKey() != request.segmentKey) return
            val result = assembler.complete() ?: run {
                Log.w(TAG, "event=segment_complete_rejected peer=${peerId.takeLast(12)} request=${requestIdForLog()} " +
                    "segment=${request.segmentKey.take(12)} reason=missing_chunk_or_checksum")
                return fail(Network24PeerMissReason.INTEGRITY)
            }
            bytes = result
            completed = true
            latch.countDown()
            Log.i(TAG, "event=segment_received peer=${peerId.takeLast(12)} request=${requestIdForLog()} " +
                "segment=${request.segmentKey.take(12)} bytes=${result.size}")
        }

        @Synchronized fun unavailable(sender: String) { if (sender == peerId) fail(Network24PeerMissReason.UNAVAILABLE) }
        @Synchronized fun fail(reason: Network24PeerMissReason) {
            if (completed) return
            failure = reason
            completed = true
            latch.countDown()
        }

        private fun requestIdForLog(): String = requestId.take(8)
    }

    private data class OutboundTransfer(
        val peerId: String,
        val streamId: String,
        val segmentKey: String,
        val bytes: Int,
        val hash: String,
        val createdAtMs: Long,
    )

    private class RecentSegmentSet {
        private val values = LinkedHashSet<String>()
        @Synchronized fun add(value: String) {
            values.add(value)
            while (values.size > MAX_ADVERTISED_SEGMENTS) values.remove(values.first())
        }
        @Synchronized fun contains(value: String): Boolean = values.contains(value)
        @Synchronized fun remove(value: String): Boolean = values.remove(value)
    }

    private class SessionStats {
        val bytesFromP2p = AtomicLong()
        val bytesFromHttp = AtomicLong()
        val bytesUploadedToPeers = AtomicLong()
        val segmentsFromP2p = AtomicLong()
        val segmentsFromHttp = AtomicLong()
        val segmentsUploaded = AtomicLong()
        val p2pRequests = AtomicLong()
        val p2pHits = AtomicLong()
        val p2pMisses = AtomicLong()
        val p2pTimeouts = AtomicLong()
        val peerConnectionsSuccessful = AtomicLong()
        val peerConnectionsFailed = AtomicLong()
        val turnBytes = AtomicLong()
    }

    companion object {
        private const val TAG = "N24-P2P"
        private const val MAX_CONNECTED_PEERS = 4
        private const val MAX_DOWNLOAD_PEERS = 4
        private const val MAX_UPLOAD_PEERS = 3
        private const val MAX_ADVERTISED_SEGMENTS = 64
        private const val PEER_HEALTH_SUCCESS = 100L
        private const val PEER_HEALTH_FAILURE = -150L
        private const val PEER_HEALTH_CLOSED = -50L
        private const val PEER_HEALTH_TRANSFER_SUCCESS = 25L
        private const val PEER_REFRESH_MS = 3_000L
        // Keep the transfer record until the receiver has consumed and verified
        // the segment. The old 5-second TTL discarded valid late ACKs.
        private const val OUTBOUND_TTL_MS = 60_000L
        // A failed segment advertisement should not suppress an otherwise
        // healthy peer for an entire live segment window.
        private const val PEER_FAILURE_COOLDOWN_MS = 5_000L

        private fun JsonObject.string(name: String): String? = get(name)?.takeUnless { it.isJsonNull }
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        private fun JsonObject.int(name: String): Int? = get(name)?.takeUnless { it.isJsonNull }
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
        private fun JsonObject.long(name: String): Long? = get(name)?.takeUnless { it.isJsonNull }
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
        private fun JsonObject.requestId(): String? = string("request_id")?.takeIf { it.length in 1..128 }
        private fun JsonObject.segmentKey(): String? = string("segment_key")?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    }
}
