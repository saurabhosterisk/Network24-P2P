package com.network24.player.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class Network24MediaRequestTest {
    @Test
    fun `different account credentials produce the same segment key`() {
        val room = Network24MediaRequest.streamIdentity("123", "https://iptv.example/live/alice/secret/123.m3u8")
        val first = Network24MediaRequest.create(room, "https://cdn.example/live/alice/secret/123_456.ts?token=one&seq=456", 0, -1)
        val second = Network24MediaRequest.create(room, "https://edge.example/live/bob/other/123_456.ts?token=two&seq=456", 0, -1)

        assertEquals(first.segmentKey, second.segmentKey)
        assertFalse(first.logLabel.contains("alice"))
        assertFalse(first.logLabel.contains("secret"))
    }

    @Test
    fun `stream origin stream id and byte range scope every key`() {
        val roomA = Network24MediaRequest.streamIdentity("123", "https://one.example/live/u/p/123.m3u8")
        val roomB = Network24MediaRequest.streamIdentity("123", "https://two.example/live/u/p/123.m3u8")
        assertNotEquals(roomA, roomB)

        val full = Network24MediaRequest.create(roomA, "https://one.example/hls/123/456.m4s", 0, -1)
        val range = Network24MediaRequest.create(roomA, "https://one.example/hls/123/456.m4s", 100, 200)
        assertNotEquals(full.segmentKey, range.segmentKey)
    }
}
