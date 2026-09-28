package com.network24.player.core.vpn

import android.content.Context
import android.os.SystemClock
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * GoBackend tracks tunnel state as instance fields, not shared/static
 * state - every Activity that touches the VPN must go through this same
 * backend/tunnel instance, or state (connected/disconnected) drifts out
 * of sync between screens.
 */
object TunnelManager : Tunnel {

    private const val TUNNEL_NAME = "n24vpn"

    private var backend: GoBackend? = null
    private var stateListener: ((Tunnel.State) -> Unit)? = null

    override fun getName(): String = TUNNEL_NAME

    override fun onStateChange(newState: Tunnel.State) {
        stateListener?.invoke(newState)
    }

    fun setStateListener(listener: ((Tunnel.State) -> Unit)?) {
        stateListener = listener
    }

    fun getBackend(context: Context): GoBackend {
        return backend ?: GoBackend(context.applicationContext).also { backend = it }
    }

    fun currentState(context: Context): Tunnel.State {
        return try {
            getBackend(context).getState(this)
        } catch (e: Exception) {
            Tunnel.State.DOWN
        }
    }

    /**
     * The underlying Go/JNI backend can hit a one-off "UAPIOpen: mkdir
     * ... permission denied" on the very first tunnel bring-up of a
     * freshly-started process (a native init race, not a real permission
     * problem - confirmed on a real device: the identical config
     * succeeds immediately on retry). One retry after a short delay
     * absorbs that instead of surfacing a failure the user would have to
     * work around by toggling twice themselves.
     */
    @Throws(Exception::class)
    suspend fun bringUp(context: Context, config: Config) {
        try {
            getBackend(context).setState(this, Tunnel.State.UP, config)
        } catch (e: Exception) {
            delay(400)
            getBackend(context).setState(this, Tunnel.State.UP, config)
        }

        // The interface coming up says nothing about the relay server being
        // reachable - without this the UI said "Secure Relay connected" even
        // when no packet could get through. Require a WireGuard handshake.
        if (!awaitHandshake(context)) {
            try {
                bringDown(context)
            } catch (e: Exception) {
                // Already down.
            }
            throw IllegalStateException("VPN server did not answer the handshake")
        }
    }

    private suspend fun awaitHandshake(context: Context): Boolean = withContext(Dispatchers.IO) {
        // WireGuard only handshakes once there is traffic to send; a tiny
        // UDP packet into the tunnel starts it.
        try {
            DatagramSocket().use { socket ->
                val probe = byteArrayOf(0)
                socket.send(DatagramPacket(probe, probe.size, InetAddress.getByName("1.1.1.1"), 53))
            }
        } catch (e: Exception) {
            // Only a trigger - the stats below decide.
        }

        val deadline = SystemClock.elapsedRealtime() + HANDSHAKE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val handshaked = try {
                val stats = getBackend(context).getStatistics(this@TunnelManager)
                stats.peers().any { key ->
                    (stats.peer(key)?.latestHandshakeEpochMillis() ?: 0L) > 0L
                }
            } catch (e: Exception) {
                false
            }
            if (handshaked) return@withContext true
            delay(250)
        }
        false
    }

    private const val HANDSHAKE_TIMEOUT_MS = 8_000L

    @Throws(Exception::class)
    fun bringDown(context: Context) {
        getBackend(context).setState(this, Tunnel.State.DOWN, null)
    }
}
