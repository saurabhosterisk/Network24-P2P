package com.network24.player.core.p2p

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Network24IceTransportPolicyTest {
    @Test
    fun `runtime TURN switches peer connections to relay only`() {
        val servers = listOf(
            Network24IceServer("stun:stun.example:3478"),
            Network24IceServer("turn:turn.example:3478?transport=udp", "u", "p"),
            Network24IceServer("turns:turn.example:5349?transport=tcp", "u", "p"),
        )

        assertTrue(Network24IceTransportPolicy.relayOnly(true, servers))
    }

    @Test
    fun `missing TURN keeps direct fallback and disabled forcing stays direct`() {
        val stunOnly = listOf(Network24IceServer("stun:stun.example:3478"))
        val turn = listOf(Network24IceServer("turn:turn.example:3478", "u", "p"))

        assertFalse(Network24IceTransportPolicy.relayOnly(true, stunOnly))
        assertFalse(Network24IceTransportPolicy.relayOnly(false, turn))
    }
}
