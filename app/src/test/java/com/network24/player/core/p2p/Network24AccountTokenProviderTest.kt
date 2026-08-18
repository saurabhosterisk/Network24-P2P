package com.network24.player.core.p2p

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Network24AccountTokenProviderTest {
    @Test
    fun `token response parses single and array TURN urls without exposing credentials in config`() {
        val json = JsonParser.parseString(
            """
            {
              "token": "short-lived",
              "expires_in": 300,
              "turn": {
                "urls": [
                  "turn:p2p.web24.live:3478?transport=udp",
                  "turn:p2p.web24.live:3478?transport=tcp",
                  "turns:p2p.web24.live:5349?transport=tcp"
                ],
                "username": "temporary-user",
                "password": "temporary-password"
              }
            }
            """.trimIndent()
        )

        val servers = Network24AccountTokenProvider.parseTurnServers(json.asJsonObject)

        assertEquals(3, servers.size)
        assertTrue(servers.all { it.username == "temporary-user" })
        assertTrue(servers.all { it.password == "temporary-password" })
        assertEquals("turn:p2p.web24.live:3478?transport=udp", servers.first().urls)
    }

    @Test
    fun `TURN is ignored when credentials or supported urls are missing`() {
        val noCredentials = JsonParser.parseString(
            "{" +
                "\"turn\":{" +
                "\"urls\":\"turn:p2p.web24.live:3478\"" +
                "}" +
                "}"
        )
        val unsupported = JsonParser.parseString(
            "{" +
                "\"turn\":{" +
                "\"urls\":\"stun:p2p.web24.live:3478\",\"username\":\"u\",\"password\":\"p\"" +
                "}" +
                "}"
        )

        assertTrue(Network24AccountTokenProvider.parseTurnServers(noCredentials.asJsonObject).isEmpty())
        assertTrue(Network24AccountTokenProvider.parseTurnServers(unsupported.asJsonObject).isEmpty())
    }
}
