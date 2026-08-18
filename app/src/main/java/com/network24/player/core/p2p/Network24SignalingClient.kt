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
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
        fun onLocalPeerId(peerId: String) {}
        fun onPeerList(peers: List<Peer>) {}
        fun onSignal(type: String, payload: JsonObject) {}
        fun onError(code: String) {}
        fun onIceServers(iceServers: List<Network24IceServer>) {}
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

    fun sendTelemetry(payload: JsonObject) {
        if (authenticated) send("telemetry", payload)
    }

    fun sendOffer(targetPeerId: String, sdp: String) = sendSignal("offer", targetPeerId, sdp, null)
    fun sendAnswer(targetPeerId: String, sdp: String) = sendSignal("answer", targetPeerId, sdp, null)
    fun sendIceCandidate(
        targetPeerId: String,
        candidate: String,
        sdpMid: String,
        sdpMLineIndex: Int
    ) {
        if (!Network24IceValidation.candidate(candidate) || !Network24IceValidation.sdpMid(sdpMid) || !Network24IceValidation.sdpMLineIndex(sdpMLineIndex)) {
            listener.onError("invalid_local_ice_candidate")
            return
        }
        sendSignal("ice_candidate", targetPeerId, null, candidate, sdpMid, sdpMLineIndex)
    }

    fun sendEndOfCandidates(targetPeerId: String) {
        sendSignal("ice_candidate", targetPeerId, null, null, endOfCandidates = true)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        heartbeat?.cancel(false)
        reconnect?.cancel(false)
        socket?.close(1000, "client_shutdown")
        scheduler.shutdownNow()
        listener.onState(State.CLOSED)
    }

    private fun sendSignal(
        type: String,
        targetPeerId: String,
        sdp: String?,
        candidate: String?,
        sdpMid: String? = null,
        sdpMLineIndex: Int? = null,
        endOfCandidates: Boolean? = null
    ) {
        val payload = JsonObject().apply {
            addProperty("target_peer_id", targetPeerId)
            if (sdp != null) addProperty("sdp", sdp)
            if (candidate != null) {
                addProperty("candidate", candidate)
                if (sdpMid != null) addProperty("sdpMid", sdpMid)
                if (sdpMLineIndex != null) addProperty("sdpMLineIndex", sdpMLineIndex)
            }
            if (endOfCandidates == true) addProperty("endOfCandidates", true)
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
        tokenProvider.getToken { result ->
            if (result == null || result.token.isBlank()) {
                listener.onError("client_token_unavailable")
                socket?.close(1008, "authentication_required")
                return@getToken
            }
            if (result.iceServers.isNotEmpty()) listener.onIceServers(result.iceServers)
            send("authenticate", JsonObject().apply { addProperty("token", result.token) })
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
    }

    private fun startHeartbeat() {
        heartbeat?.cancel(false)
        heartbeat = scheduler.scheduleWithFixedDelay({
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
                        // The server also uses peer_connected as the auth acknowledgement.
                        // Only the registration acknowledgement contains our server-owned ID.
                        payload.stringOrNull("peer_id")?.let {
                            listener.onLocalPeerId(it)
                            listener.onState(State.AUTHENTICATED)
                            listener.onPeerList(emptyList())
                        }
                    }
                    "peer_list" -> listener.onPeerList(payload.getAsJsonArray("peers")?.mapNotNull { item ->
                        val peer = item.asJsonObject
                        peer.get("peer_id")?.asString?.let { Peer(it, peer.stringOrNull("device_type"), peer.stringOrNull("app_version"), peer.stringOrNull("region"), peer.stringOrNull("country")) }
                    } ?: emptyList())
                "client_error" -> listener.onError(payload.stringOrNull("code") ?: "client_error")
                    "offer", "answer", "ice_candidate" -> {
                        validateSignal(type, payload)
                        listener.onSignal(type, payload)
                    }
                    "heartbeat" -> Unit
                }
            } catch (error: Exception) {
                Log.w(TAG, "Ignoring malformed signaling message code=${error.message ?: "invalid_message"}")
                listener.onError(error.message ?: "invalid_message")
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

    private fun nowIso(): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    companion object {
        private const val TAG = "N24-P2P"
        private fun JsonObject.stringOrNull(name: String): String? = get(name)?.takeUnless { it.isJsonNull }
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

        private fun validateSignal(type: String, payload: JsonObject) {
            require(!payload.stringOrNull("from_peer_id").isNullOrBlank()) { "signal_sender_required" }
            when (type) {
                "offer", "answer" -> require(!payload.stringOrNull("sdp").isNullOrBlank()) { "invalid_sdp" }
                "ice_candidate" -> if (payload.get("endOfCandidates")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true) {
                    require(payload.get("candidate") == null && payload.get("sdpMid") == null && payload.get("sdpMLineIndex") == null) { "invalid_end_of_candidates" }
                } else {
                    require(payload.get("endOfCandidates") == null) { "invalid_ice_candidate" }
                    require(Network24IceValidation.candidate(payload.stringOrNull("candidate"))) { "invalid_ice_candidate" }
                    require(Network24IceValidation.sdpMid(payload.stringOrNull("sdpMid"))) { "invalid_ice_candidate_sdp_mid" }
                    require(payload.get("sdpMLineIndex")?.takeUnless { it.isJsonNull }
                        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                        ?.asJsonPrimitive?.asNumber?.toDouble()
                        ?.let { it.isFinite() && it % 1.0 == 0.0 && it >= 0.0 } == true) { "invalid_ice_candidate_sdp_m_line_index" }
                }
            }
        }
    }
}
