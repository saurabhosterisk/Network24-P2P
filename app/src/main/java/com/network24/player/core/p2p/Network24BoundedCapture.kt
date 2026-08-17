package com.network24.player.core.p2p

import java.io.ByteArrayOutputStream

/** Captures an HTTP media response only while it remains bounded and only releases a complete body. */
class Network24BoundedCapture(
    private val expectedBytes: Long,
    private val maxBytes: Int,
) {
    private var output: ByteArrayOutputStream? = ByteArrayOutputStream(
        if (expectedBytes in 1..maxBytes.toLong()) expectedBytes.toInt() else 64 * 1024
    )

    fun append(bytes: ByteArray, offset: Int, length: Int) {
        val current = output ?: return
        if (length <= 0 || current.size() + length > maxBytes) {
            output = null
            return
        }
        current.write(bytes, offset, length)
    }

    fun complete(totalBytesRead: Long): ByteArray? {
        val result = output?.toByteArray() ?: return null
        val expectedMatches = expectedBytes < 0L || expectedBytes == totalBytesRead
        return result.takeIf { it.isNotEmpty() && it.size.toLong() == totalBytesRead && expectedMatches }
    }
}
