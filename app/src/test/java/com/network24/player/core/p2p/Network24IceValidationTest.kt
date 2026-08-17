package com.network24.player.core.p2p

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Network24IceValidationTest {
    @Test
    fun `only structurally complete canonical ICE reaches native WebRTC`() {
        assertTrue(Network24IceValidation.candidate("candidate:1 1 UDP 2122260223 192.0.2.1 9 typ host"))
        assertTrue(Network24IceValidation.candidate("candidate:2 1 udp 1 203.0.113.2 3478 typ relay raddr 0.0.0.0 rport 0"))
        assertFalse(Network24IceValidation.candidate(null))
        assertFalse(Network24IceValidation.candidate("candidate:x"))
        assertFalse(Network24IceValidation.candidate("candidate:1 1 UDP 1 192.0.2.1 9 typ fake"))
        assertFalse(Network24IceValidation.candidate("candidate:1 1 UDP 1 192.0.2.1 9 typ host\nmalformed"))
        assertFalse(Network24IceValidation.sdpMid(null))
        assertTrue(Network24IceValidation.sdpMid("0"))
    }
}
