package com.network24.player.core.p2p

import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.network24.player.BuildConfig
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Owns one app-wide signaling/WebRTC session. It is inert while disabled. */
class Network24P2pSession(
    context: Context,
    private val config: Network24P2pConfig = Network24P2pConfig()
) : Network24PeerSegmentFetcher, Network24TransferTelemetry {
    val enabled: Boolean get() = config.enabled
    private val appContext = context.applicationContext
    private val gson = Gson()
    private val cache = Network24SegmentCache(appContext)
    private val pending = ConcurrentHashMap<String, PendingRequest>()
    private val incoming = ConcurrentHashMap<String, IncomingSegment>()
    private val deviceId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        ?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
    private val signaling: Network24SignalingClient by lazy {
        Network24SignalingClient(
            config = config,
            registration = Network24DeviceRegistration(deviceId, deviceType(), BuildConfig.VERSION_NAME),
            tokenProvider = Network24AccountTokenProvider(appContext, com.network24.player.core.preferences.PreferenceManager(appContext)),
            listener = signalingListener
        )
    }
    private val webrtc: Network24WebRtcPeerManager by lazy {
        Network24WebRtcPeerManager(appContext, signaling, webRtcListener, config.iceServers)
    }
    @Volatile private var streamId: String? = null
    @Volatile private var localPeerId: String? = null
    @Volatile private var peerIds: List<String> = emptyList()
    @Volatile private var discoveredPeers: List<Network24SignalingClient.Peer> = emptyList()
    private val p2pBytesUploaded = AtomicLong(0)
    private val p2pBytesDownloaded = AtomicLong(0)
    private val cdnBytesDownloaded = AtomicLong(0)
    private val successfulTransfers = AtomicLong(0)
    private val failedTransfers = AtomicLong(0)
    private val lastSegmentId = AtomicReference<String?>(null)
    private val lastTransferResult = AtomicReference<String?>(null)
    private val connectionStates = ConcurrentHashMap<String, String>()
    private val connectionRoles = ConcurrentHashMap<String, MutableSet<String>>()

    fun start() {
        if (config.enabled) signaling.connect()
    }

    fun joinStream(newStreamId: String?) {
        val normalized = newStreamId?.trim()?.takeIf { it.isNotEmpty() && it.length <= 256 }
        if (normalized == streamId) return
        signaling.leaveStream()
        streamId = normalized
        normalized?.let {
            signaling.joinStream(it)
            signaling.requestPeers()
        }
    }

    fun dataSourceFetcher(): Network24PeerSegmentFetcher = this

    fun close() {
        pending.values.forEach { it.latch.countDown() }
        pending.clear()
        webrtc.close()
        signaling.close()
    }

    override fun recordCdnBytes(bytes: Long) { if (bytes > 0) cdnBytesDownloaded.addAndGet(bytes); publishTelemetry() }
    override fun recordP2pMiss(segmentUri: String) {
        failedTransfers.incrementAndGet(); lastSegmentId.set(sha256(segmentUri)); lastTransferResult.set("p2p_miss"); publishTelemetry()
    }

    override fun fetch(segmentUri: String): ByteArray? {
        val room = streamId ?: return null
        val peers = peerIds
        if (peers.isEmpty()) return null
        val segmentId = sha256(segmentUri)
        for (peerId in peers.take(4)) {
            val requestId = UUID.randomUUID().toString()
            val request = PendingRequest(CountDownLatch(1))
            pending[requestId] = request
            if (webrtc.requestSegment(peerId, room, segmentId, requestId, segmentUri)) {
                try {
                    if (request.latch.await(1_200, TimeUnit.MILLISECONDS)) {
                        request.bytes?.let {
                            p2pBytesDownloaded.addAndGet(it.size.toLong())
                            successfulTransfers.incrementAndGet()
                            lastSegmentId.set(segmentId)
                            lastTransferResult.set("p2p_hit")
                            markRole(peerId, "downloader")
                            publishTelemetry()
                            return it
                        }
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            pending.remove(requestId)
        }
        return null
    }

    private val signalingListener = object : Network24SignalingClient.Listener {
        override fun onLocalPeerId(peerId: String) {
            localPeerId = peerId
            if (streamId != null) signaling.requestPeers()
            connectToDiscoveredPeers()
        }
        override fun onState(state: Network24SignalingClient.State) {
            if (state == Network24SignalingClient.State.AUTHENTICATED) {
                streamId?.let {
                    signaling.joinStream(it)
                    // A join acknowledgement is not a peer list. Refresh
                    // explicitly so a client that joined before auth cannot
                    // remain registered with candidate_count=0.
                    signaling.requestPeers()
                }
            }
            publishTelemetry()
        }
        override fun onPeerList(peers: List<Network24SignalingClient.Peer>) {
            discoveredPeers = peers
            peerIds = peers.map { it.peerId }.distinct().take(4)
            val currentPeerIds = peerIds.toSet()
            connectionStates.keys.toList().filterNot { it in currentPeerIds }.forEach { connectionStates.remove(it) }
            connectionRoles.keys.toList().filterNot { it in currentPeerIds }.forEach { connectionRoles.remove(it) }
            peerIds.forEach { connectionStates.putIfAbsent(it, "connecting") }
            publishTelemetry()
            connectToDiscoveredPeers()
        }
        override fun onSignal(type: String, payload: JsonObject) = webrtc.handleSignal(type, payload)
        override fun onError(code: String) { Log.w(TAG, "P2P signaling error: $code") }
    }

    private fun connectToDiscoveredPeers() {
        val ownId = localPeerId ?: return
        discoveredPeers.map { it.peerId }.distinct().take(4).forEach { peerId ->
            // Deterministic offerer selection prevents simultaneous-offer glare.
            webrtc.connect(peerId, initiator = ownId < peerId)
        }
    }

    private val webRtcListener = object : Network24WebRtcPeerManager.Listener {
        override fun onPeerReady(peerId: String, channel: org.webrtc.DataChannel) {
            connectionStates[peerId] = "connected"
            publishTelemetry()
        }
        override fun onPeerClosed(peerId: String) {
            connectionStates[peerId] = "disconnected"
            webrtc.drop(peerId)
            publishTelemetry()
            signaling.requestPeers()
        }
        override fun onPeerState(peerId: String, state: String) {
            connectionStates[peerId] = state
            publishTelemetry()
        }
        override fun onPeerError(peerId: String, code: String) {
            connectionStates[peerId] = "failed"
            // A PeerConnection cannot be reused after ICE failure. Remove it so
            // the next candidate refresh can start a clean offer/answer cycle.
            webrtc.drop(peerId)
            publishTelemetry()
            signaling.requestPeers()
        }
        override fun onPeerMessage(peerId: String, bytes: ByteArray) {
            if (bytes.size > config.maxMessageBytes) return
            try {
                val message = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
                val type = message.get("type")?.asString ?: return
                if (type == "segment_request") {
                    serveSegmentRequest(peerId, message)
                    return
                }
                if (type != "segment_response") return
                val requestId = message.get("request_id")?.asString ?: return
                val segmentId = message.get("segment_id")?.asString ?: return
                val chunkIndex = message.get("chunk_index")?.asInt ?: return
                val chunkCount = message.get("chunk_count")?.asInt ?: return
                val encoded = message.get("data_base64")?.asString ?: return
                if (chunkCount !in 1..MAX_SEGMENT_CHUNKS || chunkIndex !in 0 until chunkCount) return
                val result = Base64.decode(encoded, Base64.DEFAULT)
                if (result.isEmpty() || result.size > MAX_CHUNK_BYTES) return
                val request = pending[requestId] ?: return
                val state = incoming.computeIfAbsent(requestId) { IncomingSegment(segmentId, chunkCount) }
                if (state.segmentId != segmentId || state.chunkCount != chunkCount || state.chunks.containsKey(chunkIndex)) return
                state.chunks[chunkIndex] = result
                if (state.chunks.size == chunkCount) {
                    val combined = ByteArray(state.chunks.values.sumOf { it.size })
                    var offset = 0
                    for (index in 0 until chunkCount) { val chunk = state.chunks[index] ?: return; chunk.copyInto(combined, offset); offset += chunk.size }
                    if (combined.size <= MAX_SEGMENT_BYTES) request.bytes = combined
                    incoming.remove(requestId)
                    request.latch.countDown()
                }
            } catch (_: Exception) {
                Log.w(TAG, "Ignoring invalid peer data")
            }
        }

        private fun serveSegmentRequest(peerId: String, message: JsonObject) {
            val room = streamId ?: return
            if (message.get("stream_id")?.asString != room) return
            val requestId = message.get("request_id")?.asString ?: return
            val segmentId = message.get("segment_id")?.asString ?: return
            val segmentUri = message.get("segment_uri")?.asString ?: return
            if (segmentUri.length > 2048 || sha256(segmentUri) != segmentId) return
            val bytes = cache.get(segmentUri) ?: return
            if (bytes.size > MAX_SEGMENT_BYTES) return
            if (webrtc.sendSegmentResponse(peerId, room, segmentId, requestId, bytes)) {
                p2pBytesUploaded.addAndGet(bytes.size.toLong())
                successfulTransfers.incrementAndGet()
                lastSegmentId.set(segmentId)
                lastTransferResult.set("p2p_upload")
                markRole(peerId, "uploader")
                publishTelemetry()
            }
        }
    }

    private fun markRole(peerId: String, role: String) {
        connectionRoles.computeIfAbsent(peerId) { ConcurrentHashMap.newKeySet() }.add(role)
    }

    private fun publishTelemetry() {
        val room = streamId ?: return
        val payload = JsonObject().apply {
            addProperty("stream_id", room)
            addProperty("webrtc_state", when {
                connectionStates.values.any { it == "connected" } -> "connected"
                connectionStates.values.any { it == "connecting" } -> "connecting"
                connectionStates.values.any { it == "failed" } -> "failed"
                else -> "idle"
            })
            addProperty("p2p_bytes_uploaded", p2pBytesUploaded.get())
            addProperty("p2p_bytes_downloaded", p2pBytesDownloaded.get())
            addProperty("cdn_bytes_downloaded", cdnBytesDownloaded.get())
            addProperty("successful_transfers", successfulTransfers.get())
            addProperty("failed_transfers", failedTransfers.get())
            lastSegmentId.get()?.let { addProperty("last_segment_id", it) }
            lastTransferResult.get()?.let { addProperty("last_transfer_result", it) }
            val connections = com.google.gson.JsonArray()
            connectionStates.forEach { (peerId, state) ->
                val connection = JsonObject().apply {
                    addProperty("peer_id", peerId); addProperty("state", state)
                    val roles = connectionRoles[peerId].orEmpty()
                    if (roles.isNotEmpty()) addProperty("role", if (roles.size > 1) "both" else roles.first())
                }
                connections.add(connection)
            }
            add("connections", connections)
        }
        signaling.sendTelemetry(payload)
    }

    private fun deviceType(): String = if (appContext.packageManager.hasSystemFeature("android.software.leanback")) "ANDROID_TV" else "ANDROID"
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private data class PendingRequest(val latch: CountDownLatch, @Volatile var bytes: ByteArray? = null)
    private data class IncomingSegment(val segmentId: String, val chunkCount: Int, val chunks: MutableMap<Int, ByteArray> = ConcurrentHashMap())

    companion object {
        private const val TAG = "Network24P2PSession"
        private const val MAX_SEGMENT_BYTES = 8 * 1024 * 1024
        private const val MAX_CHUNK_BYTES = 32 * 1024
        private const val MAX_SEGMENT_CHUNKS = 256
    }
}

interface Network24TransferTelemetry {
    fun recordCdnBytes(bytes: Long)
    fun recordP2pMiss(segmentUri: String)
}
