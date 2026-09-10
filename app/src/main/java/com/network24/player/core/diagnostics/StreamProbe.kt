package com.network24.player.core.diagnostics

import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

data class StreamProbeResult(
    val responseCode: Int?,
    val timeToFirstByteMs: Long?,
    val elapsedMs: Long?,
    val bytesRead: Long,
    val error: String?,
    val finalHost: String? = null
)

object StreamProbe {
    private const val DIAGNOSTIC_TIMEOUT_MS = 6_000

    fun run(url: String): StreamProbeResult {
        if (url.isBlank()) return StreamProbeResult(null, null, null, 0L, "Stream URL unavailable")

        val attempt = request(URL(url), 65_536L)
        return StreamProbeResult(
            responseCode = attempt.responseCode,
            timeToFirstByteMs = attempt.timeToFirstByteMs,
            elapsedMs = attempt.elapsedMs,
            bytesRead = attempt.bytesRead,
            error = attempt.error,
            finalHost = attempt.finalHost
        )
    }

    private data class HttpAttempt(
        val responseCode: Int?,
        val timeToFirstByteMs: Long?,
        val elapsedMs: Long?,
        val bytesRead: Long,
        val error: String?,
        val finalHost: String?
    )

    private fun request(url: URL, maxBytes: Long): HttpAttempt {
        val startedAt = System.nanoTime()
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = DIAGNOSTIC_TIMEOUT_MS
                readTimeout = DIAGNOSTIC_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("Range", "bytes=0-${maxBytes - 1}")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "N24Player-Diagnostics")
                // This probe hits the same username/password stream path as
                // real playback, so on IPTV panels that count a connection
                // per open socket to that path, a diagnostic run can eat one
                // of the account's limited slots. "Connection: close" stops
                // the JVM's HTTP keep-alive pool from holding this socket
                // open (and counted) beyond this single request, instead of
                // silently reusing it across the whole probe run.
                setRequestProperty("Connection", "close")
            }

            val responseCode = connection.responseCode
            val firstByteMs = elapsedMs(startedAt)
            val input = if (responseCode in 200..399) connection.inputStream else connection.errorStream
            var bytes = 0L
            input?.use { stream ->
                val buffer = ByteArray(16 * 1024)
                while (bytes < maxBytes) {
                    val read = stream.read(buffer, 0, min(buffer.size.toLong(), maxBytes - bytes).toInt())
                    if (read <= 0) break
                    bytes += read
                }
            }
            val elapsed = elapsedMs(startedAt)
            HttpAttempt(
                responseCode = responseCode,
                timeToFirstByteMs = firstByteMs,
                elapsedMs = elapsed,
                bytesRead = bytes,
                error = null,
                finalHost = connection.url?.host
            )
        } catch (error: Exception) {
            HttpAttempt(null, null, elapsedMs(startedAt), 0L, error.javaClass.simpleName, null)
        } finally {
            connection?.disconnect()
        }
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L
}
