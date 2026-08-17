package com.network24.player.core.p2p

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** A credential-free, stream-scoped identity for one exact Media3 byte request. */
data class Network24MediaRequest(
    val streamId: String,
    val segmentKey: String,
    val position: Long,
    val length: Long,
    val logLabel: String,
) {
    companion object {
        private val sensitiveQueryNames = setOf(
            "auth", "authorization", "credential", "expires", "hdnts", "key", "pass", "password",
            "policy", "sig", "signature", "token", "user", "username"
        )

        fun create(streamId: String, uri: String, position: Long, length: Long): Network24MediaRequest {
            require(streamId.isNotBlank() && streamId.length <= 256) { "invalid_stream_id" }
            require(position >= 0L && length >= -1L) { "invalid_media_range" }
            val parsed = runCatching { URI(uri) }.getOrNull()
            val parts = parsed?.rawPath.orEmpty().split('/').filter { it.isNotBlank() }
            val liveIndex = parts.indexOfLast { it.equals("live", ignoreCase = true) }
            val credentialFreeParts = if (liveIndex >= 0 && parts.size > liveIndex + 3) {
                parts.drop(liveIndex + 3)
            } else {
                parts.takeLast(3)
            }
            val pathIdentity = credentialFreeParts.joinToString("/").ifBlank {
                parts.lastOrNull().orEmpty().ifBlank { "segment" }
            }
            val queryIdentity = parsed?.rawQuery.orEmpty().split('&').mapNotNull { pair ->
                if (pair.isBlank()) return@mapNotNull null
                val name = pair.substringBefore('=').let(::decode).lowercase()
                if (name in sensitiveQueryNames) null else pair
            }.sorted().joinToString("&")
            val resourceIdentity = if (queryIdentity.isBlank()) pathIdentity else "$pathIdentity?$queryIdentity"
            val key = sha256("$streamId\n$resourceIdentity\n$position\n$length")
            val logLabel = parts.lastOrNull().orEmpty().replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(96).ifBlank { key.take(12) }
            return Network24MediaRequest(streamId, key, position, length, logLabel)
        }

        fun streamIdentity(streamId: String, streamUri: String): String {
            val normalizedId = streamId.trim().take(64)
            require(normalizedId.isNotBlank()) { "invalid_stream_id" }
            val parsed = runCatching { URI(streamUri) }.getOrNull()
            val origin = parsed?.host?.lowercase()?.let { host ->
                val port = parsed.port.takeIf { it >= 0 }
                if (port == null) host else "$host:$port"
            } ?: "unknown-origin"
            return "s-${sha256(origin).take(16)}-${sha256(normalizedId).take(16)}"
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        private fun sha256(value: String): String = sha256(value.toByteArray(Charsets.UTF_8))
        private fun decode(value: String): String = runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.getOrDefault(value)
    }
}
