package com.network24.player.core.p2p

import java.net.URI
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
            // Signed URLs, server directory prefixes and Media3 range metadata
            // can differ between devices for the same HLS segment. The final
            // segment filename is the shared content identity; the stream id
            // scopes it to one channel/origin.
            val segmentIdentity = parts.lastOrNull().orEmpty().ifBlank { pathIdentity }
            val key = sha256("$streamId\n$segmentIdentity\n$position\n$length")
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
    }
}
