package com.network24.player.core.p2p

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayInputStream
import java.io.IOException

/** Media3 DataSource that resolves each complete HLS media request from peer-or-HTTP. */
@Suppress("UnsafeOptInUsageError")
class Network24HybridDataSource(
    private val httpDataSource: DataSource,
    private val cache: Network24SegmentCache,
    private val mediaBridge: Network24MediaBridge?,
    private val p2pTimeoutMs: Long = 750L,
) : DataSource {
    private enum class Source { NONE, CACHE, P2P, HTTP }

    private var source = Source.NONE
    private var memoryInput: ByteArrayInputStream? = null
    private var openedUri: Uri? = null
    private var request: Network24MediaRequest? = null
    private var peerHit: Network24PeerFetchOutcome.Hit? = null
    private var capture: Network24BoundedCapture? = null
    private var expectedHttpBytes = C.LENGTH_UNSET.toLong()
    private var bytesRead = 0L
    private var complete = false
    private var startedAtMs = 0L
    private var fallbackReason = Network24PeerMissReason.NO_SESSION
    private val peerFirstResolver = Network24PeerFirstResolver(cache::get, cache::put, mediaBridge)

    override fun addTransferListener(transferListener: TransferListener) {
        httpDataSource.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        close()
        openedUri = dataSpec.uri
        startedAtMs = SystemClock.elapsedRealtime()
        val bridge = mediaBridge
        val currentStream = bridge?.currentStreamId()
        val eligible = currentStream != null && dataSpec.position >= 0L && isPeerEligible(dataSpec.uri)
        val mediaRequest = if (eligible) {
            Network24MediaRequest.create(currentStream!!, dataSpec.uri.toString(), dataSpec.position, dataSpec.length)
        } else null
        request = mediaRequest

        if (mediaRequest != null && bridge != null) {
            when (val resolution = peerFirstResolver.resolve(mediaRequest, p2pTimeoutMs)) {
                is Network24PeerFirstResolver.Resolution.Cache -> {
                    source = Source.CACHE
                    memoryInput = ByteArrayInputStream(resolution.bytes)
                    return resolution.bytes.size.toLong()
                }
                is Network24PeerFirstResolver.Resolution.Peer -> {
                    source = Source.P2P
                    peerHit = resolution.hit
                    memoryInput = ByteArrayInputStream(resolution.hit.bytes)
                    return resolution.hit.bytes.size.toLong()
                }
                is Network24PeerFirstResolver.Resolution.Http -> fallbackReason = resolution.reason
            }
        }

        source = Source.HTTP
        val length = httpDataSource.open(dataSpec)
        expectedHttpBytes = when {
            dataSpec.length >= 0L -> dataSpec.length
            length >= 0L -> length
            else -> C.LENGTH_UNSET.toLong()
        }
        if (mediaRequest != null && (expectedHttpBytes == C.LENGTH_UNSET.toLong() || expectedHttpBytes in 1..MAX_CACHE_BYTES)) {
            capture = Network24BoundedCapture(expectedHttpBytes, MAX_CACHE_BYTES.toInt())
        }
        return length
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        check(source != Source.NONE) { "data_source_not_open" }
        val read = when (source) {
            Source.CACHE, Source.P2P -> memoryInput?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
            Source.HTTP -> httpDataSource.read(buffer, offset, length)
            Source.NONE -> C.RESULT_END_OF_INPUT
        }
        if (read > 0) {
            bytesRead += read
            if (source == Source.HTTP) {
                mediaBridge?.recordHttpBytes(read.toLong())
                capture?.append(buffer, offset, read)
                if (expectedHttpBytes >= 0L && bytesRead == expectedHttpBytes) finishComplete()
            }
            if ((source == Source.P2P || source == Source.CACHE) && memoryInput?.available() == 0) finishComplete()
        } else if (read == C.RESULT_END_OF_INPUT) {
            finishComplete()
        }
        return read
    }

    override fun getUri(): Uri? = if (source == Source.HTTP) httpDataSource.uri else openedUri

    @Throws(IOException::class)
    override fun close() {
        try {
            if (source == Source.HTTP) httpDataSource.close()
            if (!complete && source == Source.P2P) peerHit?.let { mediaBridge?.cancel(it, "media3_closed_early") }
        } finally {
            reset()
        }
    }

    private fun finishComplete() {
        if (complete) return
        complete = true
        val mediaRequest = request
        val duration = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)
        when (source) {
            Source.P2P -> peerHit?.let { mediaBridge?.consumed(it, bytesRead, duration) }
            Source.HTTP -> if (mediaRequest != null) {
                val bytes = capture?.complete(bytesRead)
                if (bytes != null && validLength(mediaRequest, bytes.size) &&
                    cache.put(mediaRequest.segmentKey, bytes)
                ) {
                    mediaBridge?.httpSegmentComplete(mediaRequest, bytes.size, fallbackReason, duration)
                } else {
                    mediaBridge?.httpSegmentComplete(mediaRequest, bytesRead.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), fallbackReason, duration)
                }
            }
            else -> Unit
        }
    }

    private fun reset() {
        source = Source.NONE
        memoryInput = null
        openedUri = null
        request = null
        peerHit = null
        capture = null
        expectedHttpBytes = C.LENGTH_UNSET.toLong()
        bytesRead = 0L
        complete = false
        startedAtMs = 0L
        fallbackReason = Network24PeerMissReason.NO_SESSION
    }

    private fun validLength(request: Network24MediaRequest, size: Int): Boolean =
        size in 1..MAX_CACHE_BYTES.toInt() && (request.length < 0L || request.length == size.toLong())

    private fun isPeerEligible(uri: Uri): Boolean {
        val path = uri.path?.lowercase() ?: return false
        if (path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".json") ||
            path.endsWith(".xml") || path.endsWith(".key")
        ) return false
        return path.isNotBlank()
    }

    companion object {
        private const val MAX_CACHE_BYTES = 8L * 1024L * 1024L
    }
}

interface Network24MediaBridge {
    fun currentStreamId(): String?
    fun fetch(request: Network24MediaRequest, timeoutMs: Long): Network24PeerFetchOutcome
    fun consumed(hit: Network24PeerFetchOutcome.Hit, bytes: Long, durationMs: Long)
    fun reject(hit: Network24PeerFetchOutcome.Hit, reason: String)
    fun cancel(hit: Network24PeerFetchOutcome.Hit, reason: String)
    fun recordHttpBytes(bytes: Long)
    fun httpSegmentComplete(request: Network24MediaRequest, bytes: Int, reason: Network24PeerMissReason, durationMs: Long)
}

sealed interface Network24PeerFetchOutcome {
    data class Hit(
        val request: Network24MediaRequest,
        val requestId: String,
        val peerId: String,
        val bytes: ByteArray,
        val transport: String,
        val generation: Long,
    ) : Network24PeerFetchOutcome

    data class Miss(val reason: Network24PeerMissReason) : Network24PeerFetchOutcome
}

enum class Network24PeerMissReason { NO_SESSION, NO_PEER, UNAVAILABLE, TIMEOUT, INTEGRITY, SEND_FAILED, SWITCHED }
