package com.network24.player.core.p2p

/** Rejects incomplete ICE text before any value can reach native WebRTC. */
object Network24IceValidation {
    fun candidate(value: String?): Boolean {
        if (value == null || value.length !in 12..4096 || '\r' in value || '\n' in value || !value.startsWith("candidate:")) return false
        val fields = value.trim().split(Regex("\\s+"))
        val typeIndex = fields.indexOf("typ")
        return fields.size >= 8 && typeIndex >= 6 && fields.getOrNull(typeIndex + 1) in setOf("host", "srflx", "prflx", "relay")
    }

    fun sdpMid(value: String?): Boolean = value != null && value.isNotBlank() && value.length <= 256 && '\r' !in value && '\n' !in value
    fun sdpMLineIndex(value: Int?): Boolean = value != null && value >= 0
}
