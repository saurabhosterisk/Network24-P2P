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

/** Owns one app-wide signaling/WebRTC session. It is inert while disabled. */
class Network24P2pSession(
    context: Context,
    private val config: Network24P2pConfig = Network24P2pConfig()
) : Network24PeerSegmentFetcher {
    val enabled: Boolean get() = config.enabled
    private val appContext = context.applicationContext
    private val gson = Gson()
    private val cache = Network24SegmentCache(appContext)
    private val pending = ConcurrentHashMap<String, PendingRequest>()
    private val deviceId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        ?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
    private val signaling: Network24SignalingClient by lazy {
        Network24SignalingClient(
            config = config,
            registration = Network24DeviceRegistration(deviceId, deviceType(), BuildConfig.VERSION_NAME),
            tokenProvider = Network24FirebaseTokenProvider(
                deviceId = deviceId,
                deviceType = deviceType(),
                appVersion = BuildConfig.VERSION_NAME
            ),
            listener = signalingListener
        )
    }
    private val webrtc: Network24WebRtcPeerManager by lazy {
        Network24WebRtcPeerManager(appContext, signaling, webRtcListener, config.iceServers)
    }
    @Volatile private var streamId: String? = null
    @Volatile private var peerIds: List<String> = emptyList()

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

    override fun fetch(segmentUri: String): ByteArray? {
        val room = streamId ?: return null
        val peers = peerIds
        if (peers.isEmpty()) return null
        val segmentId = sha256(segmentUri)
        for (peerId in peers.take(4)) {
            val requestId = UUID.randomUUID().toString()
            val request = PendingRequest(CountDownLatch(1))
            pending[requestId] = request
            if (webrtc.requestSegment(peerId, room, segmentId, requestId)) {
                try {
                    if (request.latch.await(100, TimeUnit.MILLISECONDS)) return request.bytes
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
        override fun onState(state: Network24SignalingClient.State) {
            if (state == Network24SignalingClient.State.AUTHENTICATED) streamId?.let { signaling.joinStream(it) }
        }
        override fun onPeerList(peers: List<Network24SignalingClient.Peer>) {
            peerIds = peers.map { it.peerId }.distinct().take(4)
            peerIds.forEach { webrtc.connect(it, initiator = true) }
        }
        override fun onSignal(type: String, payload: JsonObject) = webrtc.handleSignal(type, payload)
        override fun onError(code: String) { Log.w(TAG, "P2P signaling error: $code") }
    }

    private val webRtcListener = object : Network24WebRtcPeerManager.Listener {
        override fun onPeerClosed(peerId: String) { peerIds = peerIds.filterNot { it == peerId } }
        override fun onPeerMessage(peerId: String, bytes: ByteArray) {
            if (bytes.size > config.maxMessageBytes) return
            try {
                val message = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
                if (message.get("type")?.asString != "segment_response") return
                val requestId = message.get("request_id")?.asString ?: return
                val encoded = message.get("data_base64")?.asString ?: return
                val result = Base64.decode(encoded, Base64.DEFAULT)
                if (result.size > MAX_SEGMENT_BYTES) return
                pending[requestId]?.let { it.bytes = result; it.latch.countDown() }
            } catch (_: Exception) {
                Log.w(TAG, "Ignoring invalid peer data")
            }
        }
    }

    private fun deviceType(): String = if (appContext.packageManager.hasSystemFeature("android.software.leanback")) "ANDROID_TV" else "ANDROID"
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private data class PendingRequest(val latch: CountDownLatch, @Volatile var bytes: ByteArray? = null)

    companion object {
        private const val TAG = "Network24P2PSession"
        private const val MAX_SEGMENT_BYTES = 8 * 1024 * 1024
    }
}
