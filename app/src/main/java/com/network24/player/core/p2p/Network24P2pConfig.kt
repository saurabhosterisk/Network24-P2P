package com.network24.player.core.p2p

/** Runtime controls for the opportunistic P2P layer. P2P is disabled by default. */
data class Network24P2pConfig(
    val enabled: Boolean = false,
    val websocketUrl: String = "wss://p2p.web24.live/ws",
    val protocolVersion: Int = 1,
    val heartbeatIntervalMs: Long = 15_000L,
    val reconnectInitialMs: Long = 1_000L,
    val reconnectMaxMs: Long = 30_000L,
    val maxMessageBytes: Int = 64 * 1024
)

fun interface Network24TokenProvider {
    /** Returns a short-lived signed Network24 client token. Never log or persist the token here. */
    fun getToken(): String?
}

data class Network24DeviceRegistration(
    val deviceId: String,
    val deviceType: String,
    val appVersion: String,
    val region: String? = null,
    val country: String? = null
)
