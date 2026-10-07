package com.network24.player.core.diagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * The Stream health speed test: internet download / upload (Cloudflare's speed test servers, the same ones
 * speed.cloudflare.com uses), ping and jitter, and the time to reach the server the channel comes from.
 * Every part runs a few seconds; the stream keeps playing (it shares the line, so the result is what is left).
 */
object SpeedTest {

    data class Ping(val ms: Int, val jitterMs: Int, val lost: Int)

    data class Result(
        val downMbps: Double = 0.0,
        val upMbps: Double = 0.0,
        val ping: Ping? = null,
        val serverPing: Ping? = null,
        val dnsMs: Int = -1,
        val at: Long = System.currentTimeMillis()
    )

    /** The last finished test (shown again when the screen is reopened). */
    @Volatile var last: Result? = null

    private const val CF = "speed.cloudflare.com"

    /** TCP connects to host:port; the median time is the ping, the spread is the jitter. */
    suspend fun ping(host: String, port: Int, tries: Int = 6): Ping? = withContext(Dispatchers.IO) {
        val times = ArrayList<Int>()
        var lost = 0
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return@withContext null
        // the first connect warms up the route (ARP, NAT, radio); it is not counted
        repeat(tries + 1) { i ->
            val t0 = System.nanoTime()
            val ok = runCatching { Socket().use { s -> s.connect(InetSocketAddress(address, port), 2500) } }.isSuccess
            if (i == 0) return@repeat
            if (ok) times += ((System.nanoTime() - t0) / 1_000_000).toInt() else lost++
        }
        if (times.isEmpty()) return@withContext Ping(-1, 0, lost)
        val sorted = times.sorted()
        val jitter = if (times.size > 1) times.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average().toInt() else 0
        Ping(sorted[sorted.size / 2], jitter, lost)
    }

    suspend fun dnsMs(host: String): Int = withContext(Dispatchers.IO) {
        val t0 = System.nanoTime()
        if (runCatching { InetAddress.getAllByName(host) }.isSuccess) ((System.nanoTime() - t0) / 1_000_000).toInt() else -1
    }

    /** Parallel downloads for [seconds]; [progress] gets the speed so far (Mbps) about 4 times a second. */
    suspend fun download(seconds: Int = 8, streams: Int = 4, progress: (Double) -> Unit): Double = coroutineScope {
        val bytes = AtomicLong(0)
        val t0 = System.nanoTime()
        val end = t0 + seconds * 1_000_000_000L
        val jobs = (1..streams).map {
            async(Dispatchers.IO) {
                while (System.nanoTime() < end && coroutineContext.isActive) {
                    val ok = runCatching {
                        val c = (URL("https://$CF/__down?bytes=25000000").openConnection() as HttpURLConnection).apply {
                            connectTimeout = 4000; readTimeout = 4000; useCaches = false
                        }
                        try {
                            c.inputStream.use { input ->
                                val buf = ByteArray(64 * 1024)
                                while (System.nanoTime() < end) {
                                    val n = input.read(buf); if (n < 0) break
                                    bytes.addAndGet(n.toLong())
                                }
                            }
                        } finally { c.disconnect() }
                    }.isSuccess
                    if (!ok) break
                }
            }
        }
        val reporter = async {
            while (System.nanoTime() < end) {
                kotlinx.coroutines.delay(250)
                val secs = (System.nanoTime() - t0) / 1e9
                if (secs > 0.3) withContext(Dispatchers.Main) { progress(bytes.get() * 8 / secs / 1e6) }
            }
        }
        jobs.awaitAll(); reporter.cancel()
        val secs = ((System.nanoTime() - t0) / 1e9).coerceAtLeast(0.5)
        bytes.get() * 8 / secs / 1e6
    }

    /** Parallel uploads for [seconds] (2 MB pieces). */
    suspend fun upload(seconds: Int = 5, streams: Int = 3, progress: (Double) -> Unit): Double = coroutineScope {
        val bytes = AtomicLong(0)
        val piece = ByteArray(2_000_000)
        val t0 = System.nanoTime()
        val end = t0 + seconds * 1_000_000_000L
        val jobs = (1..streams).map {
            async(Dispatchers.IO) {
                while (System.nanoTime() < end && coroutineContext.isActive) {
                    val ok = runCatching {
                        val c = (URL("https://$CF/__up").openConnection() as HttpURLConnection).apply {
                            connectTimeout = 4000; readTimeout = 6000; doOutput = true; requestMethod = "POST"
                            setFixedLengthStreamingMode(piece.size); setRequestProperty("Content-Type", "application/octet-stream")
                        }
                        try {
                            c.outputStream.use { out ->
                                var off = 0
                                while (off < piece.size && System.nanoTime() < end) {
                                    val n = minOf(64 * 1024, piece.size - off)
                                    out.write(piece, off, n); off += n; bytes.addAndGet(n.toLong())
                                }
                            }
                            if (System.nanoTime() < end) c.inputStream.use { it.readBytes() }
                        } finally { c.disconnect() }
                    }.isSuccess
                    if (!ok) break
                }
            }
        }
        val reporter = async {
            while (System.nanoTime() < end) {
                kotlinx.coroutines.delay(250)
                val secs = (System.nanoTime() - t0) / 1e9
                if (secs > 0.3) withContext(Dispatchers.Main) { progress(bytes.get() * 8 / secs / 1e6) }
            }
        }
        jobs.awaitAll(); reporter.cancel()
        val secs = ((System.nanoTime() - t0) / 1e9).coerceAtLeast(0.5)
        bytes.get() * 8 / secs / 1e6
    }

    fun cloudflareHost() = CF
}
