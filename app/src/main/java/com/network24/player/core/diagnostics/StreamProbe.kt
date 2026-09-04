package com.network24.player.core.diagnostics

import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.math.min

data class StreamProbeResult(
    val responseCode: Int?,
    val timeToFirstByteMs: Long?,
    val elapsedMs: Long?,
    val bytesRead: Long,
    val error: String?,
    val finalHost: String? = null,
    val packetLossPercent: Int? = null,
    val downloadMbps: Double? = null,
    val successfulRequests: Int = 0,
    val attemptedRequests: Int = 0,
    val segmentResponseCode: Int? = null,
    val segmentBytesRead: Long = 0L
)

object StreamProbe {
    // A single diagnostic probe can afford a longer timeout; the 5-attempt
    // speed test uses a shorter one so a slow/unreachable server does not
    // stretch the whole test out to tens of seconds.
    private const val DIAGNOSTIC_TIMEOUT_MS = 6_000
    private const val SPEED_TEST_TIMEOUT_MS = 4_000

    fun run(url: String): StreamProbeResult {
        if (url.isBlank()) return StreamProbeResult(null, null, null, 0L, "Stream URL unavailable")

        val attempt = request(URL(url), 65_536L, 65_535L, includeBody = false)
        return StreamProbeResult(
            responseCode = attempt.responseCode,
            timeToFirstByteMs = attempt.timeToFirstByteMs,
            elapsedMs = attempt.elapsedMs,
            bytesRead = attempt.bytesRead,
            error = attempt.error,
            finalHost = attempt.finalHost
        )
    }

    /**
     * Runs a small application-level test against the current stream path.
     * It deliberately does not touch ExoPlayer: playback can continue while
     * the playlist and one media segment are sampled.
     */
    fun runSpeedTest(url: String): StreamProbeResult {
        if (url.isBlank()) return StreamProbeResult(null, null, null, 0L, "Stream URL unavailable")

        val playlistUrl = runCatching { URL(url) }.getOrElse {
            return StreamProbeResult(null, null, null, 0L, it.javaClass.simpleName)
        }
        val attempts = mutableListOf<HttpAttempt>()
        var segmentUrl: URL? = null

        repeat(5) {
            val attempt = request(playlistUrl, 65_536L, 65_535L, includeBody = true, timeoutMs = SPEED_TEST_TIMEOUT_MS)
            attempts += attempt
            if (segmentUrl == null && isSuccessful(attempt) && !attempt.body.isNullOrBlank()) {
                segmentUrl = findFirstMediaUri(attempt.finalUrl ?: playlistUrl, attempt.body)
            }
        }

        val successful = attempts.count(::isSuccessful)
        val firstSuccessful = attempts.firstOrNull(::isSuccessful) ?: attempts.firstOrNull()
        val lastSuccessful = attempts.lastOrNull(::isSuccessful)
        val segment = segmentUrl?.let {
            request(it, 512 * 1024L, 512 * 1024L - 1L, includeBody = false, timeoutMs = SPEED_TEST_TIMEOUT_MS)
        }
        val finalHost = segment?.finalHost ?: lastSuccessful?.finalHost ?: firstSuccessful?.finalHost
        val loss = ((attempts.size - successful).toDouble() / attempts.size * 100.0).roundToInt()

        return StreamProbeResult(
            responseCode = firstSuccessful?.responseCode,
            timeToFirstByteMs = firstSuccessful?.timeToFirstByteMs,
            elapsedMs = firstSuccessful?.elapsedMs,
            bytesRead = firstSuccessful?.bytesRead ?: 0L,
            error = if (successful > 0) null else attempts.firstOrNull { it.error != null }?.error ?: "No successful response",
            finalHost = finalHost,
            packetLossPercent = loss,
            downloadMbps = segment?.downloadMbps,
            successfulRequests = successful,
            attemptedRequests = attempts.size,
            segmentResponseCode = segment?.responseCode,
            segmentBytesRead = segment?.bytesRead ?: 0L
        )
    }

    private data class HttpAttempt(
        val responseCode: Int?,
        val timeToFirstByteMs: Long?,
        val elapsedMs: Long?,
        val bytesRead: Long,
        val error: String?,
        val finalHost: String?,
        val finalUrl: URL?,
        val body: String?,
        val downloadMbps: Double?
    )

    private fun request(
        url: URL,
        maxBytes: Long,
        rangeEnd: Long,
        includeBody: Boolean,
        timeoutMs: Int = DIAGNOSTIC_TIMEOUT_MS
    ): HttpAttempt {
        val startedAt = System.nanoTime()
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("Range", "bytes=0-$rangeEnd")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "N24Player-Diagnostics")
                // This probe hits the same username/password stream path as
                // real playback, so on IPTV panels that count a connection
                // per open socket to that path, a diagnostic run can eat one
                // of the account's limited slots. "Connection: close" stops
                // the JVM's HTTP keep-alive pool from holding this socket
                // open (and counted) beyond this single request, instead of
                // silently reusing it across the whole probe/speed-test run.
                setRequestProperty("Connection", "close")
            }

            val responseCode = connection.responseCode
            val firstByteMs = elapsedMs(startedAt)
            val input = if (responseCode in 200..399) connection.inputStream else connection.errorStream
            val output = ByteArrayOutputStream()
            var bytes = 0L
            input?.use { stream ->
                val buffer = ByteArray(16 * 1024)
                while (bytes < maxBytes) {
                    val read = stream.read(buffer, 0, min(buffer.size.toLong(), maxBytes - bytes).toInt())
                    if (read <= 0) break
                    output.write(buffer, 0, read)
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
                finalHost = connection.url?.host,
                finalUrl = connection.url,
                body = if (includeBody) output.toByteArray().toString(Charsets.UTF_8) else null,
                downloadMbps = if (bytes > 0L && elapsed > 0L) bytes * 8.0 / (elapsed / 1000.0) / 1_000_000.0 else null
            )
        } catch (error: Exception) {
            HttpAttempt(null, null, elapsedMs(startedAt), 0L, error.javaClass.simpleName, null, null, null, null)
        } finally {
            connection?.disconnect()
        }
    }

    private fun isSuccessful(attempt: HttpAttempt): Boolean =
        attempt.responseCode in 200..399

    /**
     * Resolves the first real media segment referenced by a fetched
     * playlist. A master playlist's first non-comment line is a variant
     * (sub-)playlist, not a segment, so that case is followed one level
     * deeper before picking a URI.
     */
    private fun findFirstMediaUri(baseUrl: URL, body: String): URL? {
        val lines = body.lineSequence().map { it.trim() }.toList()
        val firstUri = lines.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?.let { runCatching { URL(baseUrl, it) }.getOrNull() } ?: return null

        val isMasterPlaylist = lines.any { it.startsWith("#EXT-X-STREAM-INF") }
        if (!isMasterPlaylist) return firstUri

        val variantAttempt = request(firstUri, 65_536L, 65_535L, includeBody = true, timeoutMs = SPEED_TEST_TIMEOUT_MS)
        val variantBody = variantAttempt.body
        if (!isSuccessful(variantAttempt) || variantBody.isNullOrBlank()) return null
        return variantBody.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?.let { runCatching { URL(variantAttempt.finalUrl ?: firstUri, it) }.getOrNull() }
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L
}
