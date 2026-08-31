package com.network24.player.core.diagnostics

import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

data class StreamProbeResult(
    val responseCode: Int?,
    val timeToFirstByteMs: Long?,
    val elapsedMs: Long?,
    val bytesRead: Long,
    val error: String?
)

object StreamProbe {
    fun run(url: String): StreamProbeResult {
        if (url.isBlank()) return StreamProbeResult(null, null, null, 0L, "Stream URL unavailable")

        val startedAt = System.nanoTime()
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 6_000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("Range", "bytes=0-65535")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "N24Player-Diagnostics")
            }

            val responseCode = connection.responseCode
            val firstByteMs = elapsedMs(startedAt)
            val input = if (responseCode in 200..399) connection.inputStream else connection.errorStream
            var bytes = 0L
            input?.use { stream ->
                val buffer = ByteArray(16 * 1024)
                while (bytes < 65_536) {
                    val read = stream.read(buffer, 0, min(buffer.size.toLong(), 65_536L - bytes).toInt())
                    if (read <= 0) break
                    bytes += read
                }
            }
            StreamProbeResult(responseCode, firstByteMs, elapsedMs(startedAt), bytes, null)
        } catch (error: Exception) {
            StreamProbeResult(null, null, elapsedMs(startedAt), 0L, error.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L
}
