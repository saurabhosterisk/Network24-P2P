package com.network24.player.core.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Network24PeerFirstResolverTest {
    private val request = Network24MediaRequest.create("stream", "https://host/live/u/p/123_9.ts", 0, -1)

    @Test
    fun `peer media wins before HTTP and is stored for another peer`() {
        val media = ByteArray(2048) { (it % 241).toByte() }
        var cached: ByteArray? = null
        var rejected = false
        val bridge = FakeBridge(Network24PeerFetchOutcome.Hit(request, "request", "peer", media, "host", 1L)) {
            rejected = true
        }
        val resolver = Network24PeerFirstResolver({ cached }, { _, bytes -> cached = bytes; true }, bridge)

        val result = resolver.resolve(request, 750) as Network24PeerFirstResolver.Resolution.Peer
        assertArrayEquals(media, result.hit.bytes)
        assertArrayEquals(media, cached)
        assertFalse(rejected)
    }

    @Test
    fun `peer timeout selects immediate HTTP fallback without fake hit`() {
        val bridge = FakeBridge(Network24PeerFetchOutcome.Miss(Network24PeerMissReason.TIMEOUT))
        val resolver = Network24PeerFirstResolver({ null }, { _, _ -> false }, bridge)

        val result = resolver.resolve(request, 750) as Network24PeerFirstResolver.Resolution.Http
        assertEquals(Network24PeerMissReason.TIMEOUT, result.reason)
        assertEquals(1, bridge.fetches)
    }

    @Test
    fun `wrong length peer body is discarded and HTTP selected`() {
        val ranged = Network24MediaRequest.create("stream", "https://host/live/u/p/123_9.ts", 0, 100)
        var rejected = false
        val hit = Network24PeerFetchOutcome.Hit(ranged, "request", "peer", ByteArray(99), "host", 1L)
        val resolver = Network24PeerFirstResolver({ null }, { _, _ -> false }, FakeBridge(hit) { rejected = true })

        val result = resolver.resolve(ranged, 750) as Network24PeerFirstResolver.Resolution.Http
        assertEquals(Network24PeerMissReason.INTEGRITY, result.reason)
        assertTrue(rejected)
    }

    @Test
    fun `peer loss falls back and later peer recovery needs no resolver restart`() {
        val media = ByteArray(512) { 7 }
        val bridge = FakeBridge(Network24PeerFetchOutcome.Miss(Network24PeerMissReason.NO_PEER))
        val resolver = Network24PeerFirstResolver({ null }, { _, _ -> true }, bridge)
        assertTrue(resolver.resolve(request, 750) is Network24PeerFirstResolver.Resolution.Http)

        bridge.outcome = Network24PeerFetchOutcome.Hit(request, "recovered", "peer", media, "srflx", 1L)
        val recovered = resolver.resolve(request, 750) as Network24PeerFirstResolver.Resolution.Peer
        assertArrayEquals(media, recovered.hit.bytes)
    }

    private class FakeBridge(
        var outcome: Network24PeerFetchOutcome,
        private val onReject: () -> Unit = {},
    ) : Network24MediaBridge {
        var fetches = 0
        override fun currentStreamId(): String = "stream"
        override fun fetch(request: Network24MediaRequest, timeoutMs: Long): Network24PeerFetchOutcome { fetches++; return outcome }
        override fun consumed(hit: Network24PeerFetchOutcome.Hit, bytes: Long, durationMs: Long) = Unit
        override fun reject(hit: Network24PeerFetchOutcome.Hit, reason: String) = onReject()
        override fun cancel(hit: Network24PeerFetchOutcome.Hit, reason: String) = Unit
        override fun recordHttpBytes(bytes: Long) = Unit
        override fun httpSegmentComplete(request: Network24MediaRequest, bytes: Int, reason: Network24PeerMissReason, durationMs: Long) = Unit
    }
}
