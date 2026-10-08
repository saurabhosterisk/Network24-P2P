package com.network24.player.core.diagnostics

import android.content.Context
import android.os.Build
import android.util.Base64
import com.network24.player.BuildConfig
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.core.remote.RemoteAgent
import com.network24.player.features.player.manager.PlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream

/**
 * The app's own log (an Android app may read the lines its own process wrote - no root, no adb), the last crash
 * kept on the device, and sending both to support: from the Logs tab in Stream health ("Send to support" gives a
 * short code) or when staff press "Get logs" in the console's Remote Help. Passwords and tokens are masked first.
 */
object AppLogs {
    private const val CRASH_FILE = "last_crash.txt"

    class Line(val level: Char, val text: String)

    /** Keeps the stack trace of a crash on the device; the Logs tab and the next upload show it. */
    fun installCrashSaver(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                File(app.filesDir, CRASH_FILE).writeText("Crash at " + stamp(System.currentTimeMillis()) + " on thread " + t.name +
                    " (build " + BuildConfig.VERSION_CODE + ")\n" + redact(sw.toString()).take(20_000))
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun lastCrash(context: Context): String? = runCatching { File(context.applicationContext.filesDir, CRASH_FILE).takeIf { it.exists() }?.readText() }.getOrNull()

    /** The newest [max] lines this app wrote (logcat -v time), masked. */
    suspend fun read(max: Int = 2000): List<Line> = withContext(Dispatchers.IO) {
        val cmd = mutableListOf("logcat", "-d", "-v", "time", "-t", max.toString())
        if (Build.VERSION.SDK_INT >= 24) { cmd += "--pid"; cmd += android.os.Process.myPid().toString() }
        val out = runCatching { ProcessBuilder(cmd).redirectErrorStream(true).start().inputStream.bufferedReader().readLines() }.getOrDefault(emptyList())
        out.filter { it.isNotBlank() && !it.startsWith("-----") }.map { l ->
            // "10-09 03:12:01.123 E/Tag( 1234): text"
            val lv = Regex("^\\S+ \\S+ ([VDIWEF])/").find(l)?.groupValues?.get(1)?.first() ?: 'I'
            Line(lv, redact(l))
        }
    }

    /** Device, app and what is playing - the first lines of every upload. */
    fun header(context: Context): String {
        val p = PreferenceManager(context.applicationContext)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val net = runCatching {
            if (Build.VERSION.SDK_INT >= 23) cm?.getNetworkCapabilities(cm.activeNetwork)?.let { c ->
                when { c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "VPN"; c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"; c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"; else -> "Other" }
            } else null
        }.getOrNull() ?: "unknown"
        return buildString {
            append("Web24 app logs  ").append(stamp(System.currentTimeMillis())).append('\n')
            append("Account: ").append(p.getUsername()).append('\n')
            append("App: ").append(BuildConfig.VERSION_NAME).append(" (build ").append(BuildConfig.VERSION_CODE).append(")\n")
            append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("  ·  Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Network: ").append(net).append("  ·  Server: ").append(PreferenceManager.SERVER_URL).append('\n')
            append("Playing: stream ").append(PlayerManager.currentStreamId).append('\n')
        }
    }

    /** Uploads header + last crash + log to Main; returns the short code staff open it with. */
    suspend fun send(context: Context, reason: String): String {
        val text = buildString {
            append(header(context)).append("Sent by: ").append(reason).append("\n\n")
            lastCrash(context)?.let { append("===== LAST CRASH =====\n").append(it).append("\n\n") }
            append("===== APP LOG =====\n")
            read(3000).forEach { append(it.text).append('\n') }
        }
        val gz = withContext(Dispatchers.Default) {
            val bo = ByteArrayOutputStream(); GZIPOutputStream(bo).use { it.write(text.toByteArray()) }
            Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
        }
        val j = RemoteAgent.call("rm_log", "device_id" to RemoteAgent.deviceId(context), "reason" to reason, "log" to gz)
        return j.optString("code")
    }

    private fun stamp(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    private val secrets = listOf(
        Regex("(?i)(password|passwd|pwd|token|secret|api_key|apikey|auth)=([^&\\s\"']+)") to "$1=***",
        Regex("(?i)(\"(?:password|token|secret)\"\\s*:\\s*\")[^\"]*\"") to "$1***\"",
        // Xtream stream URLs carry /live/<user>/<pass>/<id>
        Regex("/(live|movie|series|timeshift)/[^/\\s]+/[^/\\s]+/") to "/$1/***/***/",
        Regex("(?i)(Bearer )[A-Za-z0-9._-]+") to "$1***",
    )

    fun redact(s: String): String = secrets.fold(s) { acc, (re, rep) -> acc.replace(re, rep) }
}
