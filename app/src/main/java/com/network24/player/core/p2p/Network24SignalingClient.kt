package com.network24.player.core.p2p

import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Authenticated Network24 v1 signaling transport.
 *
 * This class only carries control/signaling metadata. Media bytes must never be
 * sent through this socket. The client is deliberately inert when config.enabled
 * is false, preserving the existing CDN playback path.
 */
class Network24SignalingClient(
    private val config: Network24P2pConfig,
    private val registration: Network24DeviceRegistration,
    private val tokenProvider: Network24TokenProvider,
    private val listener: Listener,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
) {
    interface Listener {
        fun onState(state: State)
        fun onPeerList(peers: List<Peer>) {}
        fun onSignal(type: String, payload: JsonObject) {}
        fun onError(code: String) {}
    }

    enum class State { DISABLED, IDLE, CONNECTING, AUTHENTICATED, CLOSED }

    data class Peer(
        val peerId: String,
        val deviceType: String?,
        val appVersion: String?,
        val region: String?,
        val country: String?
    )

    private data class Envelope(
        val version: Int,
        val type: String,
        val request_id: String,
        val sent_at: String,
        val payload: JsonObject = JsonObject()
    )

    private val gson = Gson()
    private val scheduler = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "network24-p2p").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)
    private var socket: WebSocket? = null
    private var heartbeat: ScheduledFuture<*>? = null
    private var reconnect: ScheduledFuture<*>? = null
    private var reconnectDelayMs = config.reconnectInitialMs
    private var currentStreamId: String? = null
    private var authenticated = false

    fun connect() {
        if (!config.enabled) {
            listener.onState(State.DISABLED)
            return
        }
        if (closed.get()) return
        listener.onState(State.CONNECTING)
        val request = Request.Builder().url(config.websocketUrl).build()
        socket = httpClient.newWebSocket(request, socketListener)
    }

    fun joinStream(streamId: String) {
        require(streamId.isNotBlank() && streamId.length <= 256) { "invalid_stream_id" }
        currentStreamId = streamId
        if (authenticated) send("join_stream", JsonObject().apply { addProperty("stream_id", streamId) })
    }

    fun leaveStream() {
        if (authenticated) send("leave_stream", JsonObject())
        currentStreamId = null
    }

    fun requestPeers() {
        if (authenticated) send("request_peers", JsonObject())
    }

    fun sendOffer(targetPeerId: String, sdp: String) = sendSignal("offer", targetPeerId, sdp, null)
    fun sendAnswer(targetPeerId: String, sdp: String) = sendSignal("answer", targetPeerId, sdp, null)
    fun sendIceCandidate(targetPeerId: String, candidate: String) = sendSignal("ice_candidate", targetPeerId, null, candidate)

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        heartbeat?.cancel(false)
        reconnect?.cancel(false)
        socket?.close(1000, "client_shutdown")
        scheduler.shutdownNow()
        listener.onState(State.CLOSED)
    }

    private fun sendSignal(type: String, targetPeerId: String, sdp: String?, candidate: String?) {
        val payload = JsonObject().apply {
            addProperty("target_peer_id", targetPeerId)
            if (sdp != null) addProperty("sdp", sdp)
            if (candidate != null) addProperty("candidate", candidate)
        }
        send(type, payload)
    }

    private fun send(type: String, payload: JsonObject) {
        if (closed.get()) return
        val envelope = Envelope(config.protocolVersion, type, UUID.randomUUID().toString(), nowIso(), payload)
        val encoded = gson.toJson(envelope)
        if (encoded.toByteArray(Charsets.UTF_8).size > config.maxMessageBytes) {
            listener.onError("message_too_large")
            return
        }
        if (socket?.send(encoded) != true) listener.onError("signaling_not_connected")
    }

    private fun authenticateAndRegister() {
        val token = tokenProvider.getToken()
        if (token.isNullOrBlank()) {
            listener.onError("client_token_unavailable")
            socket?.close(1008, "authentication_required")
            return
        }
        send("authenticate", JsonObject().apply { addProperty("token", token) })
        send("register", JsonObject().apply {
            addProperty("device_id", registration.deviceId)
            addProperty("device_type", registration.deviceType)
            addProperty("app_version", registration.appVersion)
            addProperty("protocol_version", config.protocolVersion)
            registration.region?.let { addProperty("region", it) }
            registration.country?.let { addProperty("country", it) }
        })
        currentStreamId?.let { send("join_stream", JsonObject().apply { addProperty("stream_id", it) }) }
        send("request_peers", JsonObject())
    }

    private fun startHeartbeat() {
        heartbeat?.cancel(false)
        heartbeat = scheduler.scheduleAtFixedRate({
            if (authenticated) send("heartbeat", JsonObject())
        }, config.heartbeatIntervalMs, config.heartbeatIntervalMs, TimeUnit.MILLISECONDS)
    }

    private fun scheduleReconnect() {
        if (closed.get() || reconnect?.isDone == false) return
        val delay = reconnectDelayMs
        reconnectDelayMs = min(config.reconnectMaxMs, reconnectDelayMs * 2)
        reconnect = scheduler.schedule({ connect() }, delay, TimeUnit.MILLISECONDS)
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectDelayMs = config.reconnectInitialMs
            authenticateAndRegister()
            startHeartbeat()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.toByteArray(Charsets.UTF_8).size > config.maxMessageBytes) {
                listener.onError("message_too_large")
                webSocket.close(1009, "message_too_large")
                return
            }
            try {
                val root = gson.fromJson(text, JsonObject::class.java)
                if (root.get("version")?.asInt != config.protocolVersion) throw IllegalArgumentException("unsupported_protocol_version")
                val type = root.get("type")?.asString ?: throw IllegalArgumentException("message_type_required")
                val payload = root.getAsJsonObject("payload") ?: JsonObject()
                when (type) {
                    "peer_connected" -> {
                        authenticated = true
                        listener.onState(State.AUTHENTICATED)
                        listener.onPeerList(emptyList())
                    }
                    "peer_list" -> listener.onPeerList(payload.getAsJsonArray("peers")?.mapNotNull { item ->
                        val peer = item.asJsonObject
                        peer.get("peer_id")?.asString?.let { Peer(it, peer.stringOrNull("device_type"), peer.stringOrNull("app_version"), peer.stringOrNull("region"), peer.stringOrNull("country")) }
                    } ?: emptyList())
                    "client_error" -> listener.onError(payload.stringOrNull("code") ?: "client_error")
                    "offer", "answer", "ice_candidate" -> listener.onSignal(type, payload)
                    "heartbeat" -> Unit
                }
            } catch (error: Exception) {
                Log.w(TAG, "Ignoring malformed signaling message", error)
                listener.onError("invalid_message")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            authenticated = false
            heartbeat?.cancel(false)
            listener.onState(State.IDLE)
            listener.onError("signaling_connection_failed")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            authenticated = false
            heartbeat?.cancel(false)
            if (!closed.get()) {
                listener.onState(State.IDLE)
                scheduleReconnect()
            }
        }
    }

    private fun nowIso(): String = java.time.Instant.now().toString()

    companion object {
        private const val TAG = "Network24P2P"
        private fun JsonObject.stringOrNull(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
    }
}
