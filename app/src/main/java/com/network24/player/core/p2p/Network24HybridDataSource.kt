package com.network24.player.core.p2p

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Media3 source that tries cache, then a bounded P2P attempt, then HTTP.
 * P2P can only improve delivery; it cannot block playback indefinitely.
 */
class Network24HybridDataSource(
    private val httpDataSource: DataSource,
    private val cache: Network24SegmentCache,
    private val peerFetcher: Network24PeerSegmentFetcher?,
    private val p2pTimeoutMs: Long = 1_500L
) : DataSource {
    private val peerExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "network24-p2p-fetch").apply { isDaemon = true }
    }
    private var cachedInput: ByteArrayInputStream? = null
    private var uri: String? = null
    private var cdnCapture: ByteArrayOutputStream? = null
    private var cacheCandidate = false
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) {
        httpDataSource.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        closeCurrent()
        uri = dataSpec.uri.toString()
        val cached = if (dataSpec.position == 0L) cache.get(uri!!) else null
        if (cached != null) {
            cachedInput = ByteArrayInputStream(cached)
            opened = true
            return cached.size.toLong()
        }

        val peerBytes = if (isPeerEligible(dataSpec.uri)) boundedPeerFetch(uri!!) else null
        if (peerBytes != null && peerBytes.isNotEmpty()) {
            cache.put(uri!!, peerBytes)
            cachedInput = ByteArrayInputStream(peerBytes)
            opened = true
            return peerBytes.size.toLong()
        }

        val length = httpDataSource.open(dataSpec)
        cacheCandidate = dataSpec.position == 0L && isPeerEligible(dataSpec.uri) && length in 1..MAX_CACHE_BYTES
        if (cacheCandidate) cdnCapture = ByteArrayOutputStream(minOf(length.coerceAtLeast(0L).toInt(), MAX_CACHE_BYTES.toInt()))
        opened = true
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        check(opened) { "data_source_not_open" }
        val input = cachedInput
        if (input != null) return input.read(buffer, offset, length)
        val read = httpDataSource.read(buffer, offset, length)
        if (read > 0) (peerFetcher as? Network24TransferTelemetry)?.recordCdnBytes(read.toLong())
        if (read > 0 && cacheCandidate) {
            val capture = cdnCapture
            if (capture != null && capture.size() + read <= MAX_CACHE_BYTES) capture.write(buffer, offset, read)
        }
        return read
    }

    override fun getUri(): Uri? = cachedInput?.let { uri?.let(Uri::parse) } ?: httpDataSource.uri

    @Throws(IOException::class)
    override fun close() {
        try {
            val value = uri
            val capture = cdnCapture
            if (value != null && cacheCandidate && capture != null && capture.size() > 0) cache.put(value, capture.toByteArray())
            closeCurrent()
        } finally {
            peerExecutor.shutdownNow()
        }
    }

    private fun boundedPeerFetch(segmentUri: String): ByteArray? {
        val fetcher = peerFetcher ?: return null
        return try {
            val result = peerExecutor.submit(Callable { fetcher.fetch(segmentUri) }).get(p2pTimeoutMs, TimeUnit.MILLISECONDS)
            if (result == null) (fetcher as? Network24TransferTelemetry)?.recordP2pMiss(segmentUri)
            result
        } catch (_: TimeoutException) {
            (fetcher as? Network24TransferTelemetry)?.recordP2pMiss(segmentUri)
            null
        } catch (_: Exception) {
            (fetcher as? Network24TransferTelemetry)?.recordP2pMiss(segmentUri)
            null
        }
    }

    private fun closeCurrent() {
        cachedInput = null
        if (opened) httpDataSource.close()
        opened = false
        uri = null
        cdnCapture = null
        cacheCandidate = false
    }

    private fun isLikelyMediaSegment(uri: Uri): Boolean {
        val path = uri.path?.lowercase() ?: return false
        return path.endsWith(".ts") || path.endsWith(".m4s") || path.endsWith(".mp4") || path.endsWith(".aac") || path.endsWith(".webvtt")
    }

    private fun isPeerEligible(uri: Uri): Boolean {
        val path = uri.path?.lowercase() ?: return false
        // Playlists/manifests are control metadata, not transferable media segments.
        if (path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".json")) return false
        return isLikelyMediaSegment(uri) || path.substringAfterLast('/').contains('.') || path.isNotBlank()
    }

    companion object {
        private const val MAX_CACHE_BYTES = 8L * 1024L * 1024L
    }
}

fun interface Network24PeerSegmentFetcher {
    /** Returns a complete segment or null; implementation must not wait indefinitely. */
    fun fetch(segmentUri: String): ByteArray?
}
