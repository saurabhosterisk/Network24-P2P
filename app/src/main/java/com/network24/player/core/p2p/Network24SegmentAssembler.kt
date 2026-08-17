package com.network24.player.core.p2p

import java.io.ByteArrayOutputStream

/** Thread-safe all-or-nothing assembly and integrity validation for one peer response. */
class Network24SegmentAssembler(
    private val segmentKey: String,
    private val requestedLength: Long,
) {
    private var totalSize = 0
    private var chunkCount = 0
    private var expectedHash: String? = null
    private val chunks = HashMap<Int, ByteArray>()

    @Synchronized
    fun acceptMeta(key: String, size: Int, count: Int, hash: String): Boolean {
        val expectedCount = (size + Network24PeerProtocol.CHUNK_BYTES - 1) / Network24PeerProtocol.CHUNK_BYTES
        if (key != segmentKey || size !in 1..Network24PeerProtocol.MAX_SEGMENT_BYTES || count != expectedCount ||
            count !in 1..Network24PeerProtocol.MAX_CHUNKS || !hash.matches(Regex("[0-9a-f]{64}")) ||
            (requestedLength >= 0L && requestedLength != size.toLong())
        ) return false
        totalSize = size
        chunkCount = count
        expectedHash = hash
        chunks.clear()
        return true
    }

    @Synchronized
    fun acceptChunk(chunk: Network24PeerProtocol.Chunk): Boolean {
        if (chunk.segmentKey != segmentKey || chunkCount == 0 || chunk.index !in 0 until chunkCount ||
            chunks.containsKey(chunk.index)
        ) return false
        chunks[chunk.index] = chunk.bytes
        if (chunks.values.sumOf { it.size } > totalSize) {
            chunks.remove(chunk.index)
            return false
        }
        return true
    }

    @Synchronized
    fun complete(): ByteArray? {
        if (chunkCount == 0 || chunks.size != chunkCount) return null
        val output = ByteArrayOutputStream(totalSize)
        for (index in 0 until chunkCount) output.write(chunks[index] ?: return null)
        val result = output.toByteArray()
        return result.takeIf { it.size == totalSize && Network24MediaRequest.sha256(it) == expectedHash }
    }
}
