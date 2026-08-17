package com.network24.player.core.p2p

import android.content.Context
import java.io.File
import java.io.IOException

/** Bounded, private sliding cache for complete, validated live-media requests. */
class Network24SegmentCache(
    context: Context,
    private val maxSegmentBytes: Int = 8 * 1024 * 1024,
    private val maxCacheBytes: Long = 64L * 1024L * 1024L,
    private val maxEntryAgeMs: Long = 90_000L,
) {
    private val directory = File(context.applicationContext.cacheDir, "network24_segments").apply { mkdirs() }

    @Synchronized
    fun get(segmentKey: String): ByteArray? {
        if (!segmentKey.matches(SEGMENT_ID_PATTERN)) return null
        return getFile(File(directory, segmentKey))
    }

    private fun getFile(file: File): ByteArray? {
        if (!file.isFile || file.length() <= 0L || file.length() > maxSegmentBytes) return null
        if (System.currentTimeMillis() - file.lastModified() > maxEntryAgeMs) {
            file.delete()
            return null
        }
        return try {
            file.setLastModified(System.currentTimeMillis())
            file.readBytes()
        } catch (_: IOException) {
            null
        }
    }

    @Synchronized
    fun put(segmentKey: String, bytes: ByteArray): Boolean {
        if (!segmentKey.matches(SEGMENT_ID_PATTERN) || bytes.isEmpty() || bytes.size > maxSegmentBytes) return false
        val target = File(directory, segmentKey)
        val temporary = File(directory, "${target.name}.tmp-${System.nanoTime()}")
        return try {
            temporary.writeBytes(bytes)
            if (target.exists() && !target.delete()) throw IOException("cache_replace_failed")
            if (!temporary.renameTo(target)) throw IOException("cache_move_failed")
            trim()
            true
        } catch (_: IOException) {
            temporary.delete()
            false
        }
    }

    @Synchronized
    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
    }

    @Synchronized
    fun recentKeys(limit: Int = 32): List<String> = directory.listFiles()
        ?.asSequence()
        ?.filter { it.isFile && it.name.matches(SEGMENT_ID_PATTERN) && System.currentTimeMillis() - it.lastModified() <= maxEntryAgeMs }
        ?.sortedByDescending { it.lastModified() }
        ?.take(limit.coerceIn(0, 128))
        ?.map { it.name }
        ?.toList()
        .orEmpty()

    private fun trim() {
        var total = directory.listFiles()?.sumOf { it.length() } ?: 0L
        if (total <= maxCacheBytes) return
        directory.listFiles()
            ?.filterNot { it.name.contains(".tmp-") }
            ?.sortedBy { it.lastModified() }
            ?.forEach { file ->
                if (total <= maxCacheBytes) return@forEach
                total -= file.length()
                file.delete()
            }
    }

    companion object {
        private val SEGMENT_ID_PATTERN = Regex("[0-9a-f]{64}")
    }
}
