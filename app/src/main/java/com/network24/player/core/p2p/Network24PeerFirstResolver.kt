package com.network24.player.core.p2p

/** Deterministic peer-first decision used directly by the Media3 DataSource open path. */
class Network24PeerFirstResolver(
    private val cacheGet: (String) -> ByteArray?,
    private val cachePut: (String, ByteArray) -> Boolean,
    private val mediaBridge: Network24MediaBridge?,
) {
    sealed interface Resolution {
        data class Cache(val bytes: ByteArray) : Resolution
        data class Peer(val hit: Network24PeerFetchOutcome.Hit) : Resolution
        data class Http(val reason: Network24PeerMissReason) : Resolution
    }

    fun resolve(request: Network24MediaRequest, timeoutMs: Long): Resolution {
        cacheGet(request.segmentKey)?.takeIf { validLength(request, it.size) }?.let { return Resolution.Cache(it) }
        val bridge = mediaBridge ?: return Resolution.Http(Network24PeerMissReason.NO_SESSION)
        return when (val outcome = bridge.fetch(request, timeoutMs)) {
            is Network24PeerFetchOutcome.Hit -> if (validLength(request, outcome.bytes.size)) {
                cachePut(request.segmentKey, outcome.bytes)
                Resolution.Peer(outcome)
            } else {
                bridge.reject(outcome, "range_length_mismatch")
                Resolution.Http(Network24PeerMissReason.INTEGRITY)
            }
            is Network24PeerFetchOutcome.Miss -> Resolution.Http(outcome.reason)
        }
    }

    private fun validLength(request: Network24MediaRequest, size: Int): Boolean =
        size in 1..Network24PeerProtocol.MAX_SEGMENT_BYTES && (request.length < 0L || request.length == size.toLong())
}
