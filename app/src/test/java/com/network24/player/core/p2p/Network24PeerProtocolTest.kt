package com.network24.player.core.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Network24PeerProtocolTest {
    private val key = "a".repeat(64)

    @Test
    fun `binary chunk framing round trips and rejects truncation`() {
        val payload = ByteArray(Network24PeerProtocol.CHUNK_BYTES) { (it % 251).toByte() }
        val encoded = Network24PeerProtocol.encodeChunk("request-1", key, 3, payload)
        val decoded = Network24PeerProtocol.decodeChunk(encoded)
        assertNotNull(decoded)

        assertEquals("request-1", decoded!!.requestId)
        assertEquals(key, decoded.segmentKey)
        assertEquals(3, decoded.index)
        assertArrayEquals(payload, decoded.bytes)
        assertNull(Network24PeerProtocol.decodeChunk(encoded.copyOf(encoded.size - 1)))
    }

    @Test
    fun `assembler releases only complete size and hash validated media`() {
        val bytes = ByteArray(Network24PeerProtocol.CHUNK_BYTES + 731) { (it % 199).toByte() }
        val hash = Network24MediaRequest.sha256(bytes)
        val count = 2
        val assembler = Network24SegmentAssembler(key, -1)
        assertTrue(assembler.acceptMeta(key, bytes.size, count, hash))

        val first = bytes.copyOfRange(0, Network24PeerProtocol.CHUNK_BYTES)
        val second = bytes.copyOfRange(Network24PeerProtocol.CHUNK_BYTES, bytes.size)
        assertTrue(assembler.acceptChunk(Network24PeerProtocol.Chunk("request", key, 0, first)))
        assertNull(assembler.complete())
        assertTrue(assembler.acceptChunk(Network24PeerProtocol.Chunk("request", key, 1, second)))
        assertArrayEquals(bytes, assembler.complete())
    }

    @Test
    fun `assembler rejects wrong range duplicate and corrupted media`() {
        val bytes = ByteArray(100) { it.toByte() }
        val hash = Network24MediaRequest.sha256(bytes)
        val wrongRange = Network24SegmentAssembler(key, 99)
        assertFalse(wrongRange.acceptMeta(key, bytes.size, 1, hash))

        val corrupted = Network24SegmentAssembler(key, 100)
        assertTrue(corrupted.acceptMeta(key, bytes.size, 1, hash))
        val bad = bytes.copyOf().also { it[0] = 99 }
        val chunk = Network24PeerProtocol.Chunk("request", key, 0, bad)
        assertTrue(corrupted.acceptChunk(chunk))
        assertFalse(corrupted.acceptChunk(chunk))
        assertNull(corrupted.complete())
    }
}
