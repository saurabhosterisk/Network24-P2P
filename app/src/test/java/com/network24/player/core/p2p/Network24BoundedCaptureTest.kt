package com.network24.player.core.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Network24BoundedCaptureTest {
    @Test
    fun `unknown length HLS response is cached only after EOF`() {
        val body = ByteArray(4096) { (it % 127).toByte() }
        val capture = Network24BoundedCapture(-1, 8192)
        capture.append(body, 0, 2048)
        assertNull(capture.complete(4096))
        capture.append(body, 2048, 2048)
        assertArrayEquals(body, capture.complete(4096))
    }

    @Test
    fun `partial known response and oversized response are discarded`() {
        val partial = Network24BoundedCapture(100, 1024)
        partial.append(ByteArray(50), 0, 50)
        assertNull(partial.complete(50))

        val oversized = Network24BoundedCapture(-1, 32)
        oversized.append(ByteArray(33), 0, 33)
        assertNull(oversized.complete(33))
    }
}
