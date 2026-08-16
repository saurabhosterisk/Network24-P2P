package com.network24.player.core.p2p

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Bounded, private cache for recently downloaded HLS media segments. */
class Network24SegmentCache(
    context: Context,
    private val maxSegmentBytes: Int = 8 * 1024 * 1024,
    private val maxCacheBytes: Long = 64L * 1024L * 1024L
) {
    private val directory = File(context.applicationContext.cacheDir, "network24_segments").apply { mkdirs() }

    @Synchronized
    fun get(uri: String): ByteArray? {
        val file = fileFor(uri)
        if (!file.isFile || file.length() <= 0L || file.length() > maxSegmentBytes) return null
        return try {
            file.setLastModified(System.currentTimeMillis())
            file.readBytes()
        } catch (_: IOException) {
            null
        }
    }

    @Synchronized
    fun put(uri: String, bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > maxSegmentBytes) return false
        val target = fileFor(uri)
        val temporary = File(directory, "${target.name}.tmp-${System.nanoTime()}")
        return try {
            temporary.writeBytes(bytes)
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
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

    private fun fileFor(uri: String): File = File(directory, sha256(uri))

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

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
