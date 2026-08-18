package com.network24.player.core.p2p

/** Runtime controls for the opportunistic P2P layer. P2P is disabled by default. */
data class Network24P2pConfig(
    val enabled: Boolean = false,
    val websocketUrl: String = "wss://p2p.web24.live/ws",
    val protocolVersion: Int = 1,
    val heartbeatIntervalMs: Long = 15_000L,
    val reconnectInitialMs: Long = 1_000L,
    val reconnectMaxMs: Long = 30_000L,
    val segmentRequestTimeoutMs: Long = 15_000L,
    val uploadDeadlineMs: Long = 20_000L,
    val maxDataChannelBufferedBytes: Long = 8L * 1024L * 1024L,
    // Binary frames include the request ID, segment key and framing header in
    // addition to the 64 KiB media chunk.
    val maxMessageBytes: Int = 128 * 1024,
    val iceServers: List<Network24IceServer> = listOf(Network24IceServer("stun:stun.l.google.com:19302"))
)

data class Network24IceServer(
    val urls: String,
    val username: String? = null,
    val password: String? = null,
)

/** Short-lived authentication result returned by the P2P token broker. */
data class Network24TokenResult(
    val token: String,
    val iceServers: List<Network24IceServer> = emptyList(),
)

fun interface Network24TokenProvider {
    /** Asynchronously returns a short-lived token and its runtime ICE servers. */
    fun getToken(callback: (Network24TokenResult?) -> Unit)
}

data class Network24DeviceRegistration(
    val deviceId: String,
    val deviceType: String,
    val appVersion: String,
    val region: String? = null,
    val country: String? = null
)
