package com.network24.player.core.p2p

import java.nio.ByteBuffer

/** Bounded binary framing for media payloads; JSON remains control-only. */
object Network24PeerProtocol {
    const val VERSION = 2
    const val CHUNK_BYTES = 16 * 1024
    const val MAX_SEGMENT_BYTES = 8 * 1024 * 1024
    const val MAX_CHUNKS = MAX_SEGMENT_BYTES / CHUNK_BYTES
    private const val MAGIC = 0x4e323450 // N24P
    private const val TYPE_CHUNK: Byte = 1
    private const val MAX_REQUEST_ID_BYTES = 128
    private const val SEGMENT_KEY_BYTES = 64

    data class Chunk(val requestId: String, val segmentKey: String, val index: Int, val bytes: ByteArray)

    fun encodeChunk(requestId: String, segmentKey: String, index: Int, bytes: ByteArray): ByteArray {
        val request = requestId.toByteArray(Charsets.UTF_8)
        val key = segmentKey.toByteArray(Charsets.US_ASCII)
        require(request.isNotEmpty() && request.size <= MAX_REQUEST_ID_BYTES) { "invalid_request_id" }
        require(key.size == SEGMENT_KEY_BYTES && segmentKey.matches(Regex("[0-9a-f]{64}"))) { "invalid_segment_key" }
        require(index in 0 until MAX_CHUNKS && bytes.isNotEmpty() && bytes.size <= CHUNK_BYTES) { "invalid_chunk" }
        return ByteBuffer.allocate(4 + 1 + 1 + 2 + 2 + 4 + 4 + request.size + key.size + bytes.size).apply {
            putInt(MAGIC)
            put(VERSION.toByte())
            put(TYPE_CHUNK)
            putShort(request.size.toShort())
            putShort(key.size.toShort())
            putInt(index)
            putInt(bytes.size)
            put(request)
            put(key)
            put(bytes)
        }.array()
    }

    fun decodeChunk(payload: ByteArray): Chunk? {
        if (payload.size < 18) return null
        return runCatching {
            val input = ByteBuffer.wrap(payload)
            if (input.int != MAGIC || input.get().toInt() != VERSION || input.get() != TYPE_CHUNK) return null
            val requestLength = input.short.toInt() and 0xffff
            val keyLength = input.short.toInt() and 0xffff
            val index = input.int
            val dataLength = input.int
            if (requestLength !in 1..MAX_REQUEST_ID_BYTES || keyLength != SEGMENT_KEY_BYTES ||
                index !in 0 until MAX_CHUNKS || dataLength !in 1..CHUNK_BYTES ||
                input.remaining() != requestLength + keyLength + dataLength
            ) return null
            val request = ByteArray(requestLength).also(input::get).toString(Charsets.UTF_8)
            val key = ByteArray(keyLength).also(input::get).toString(Charsets.US_ASCII)
            val data = ByteArray(dataLength).also(input::get)
            if (!key.matches(Regex("[0-9a-f]{64}"))) return null
            Chunk(request, key, index, data)
        }.getOrNull()
    }
}
