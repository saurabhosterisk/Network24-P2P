package com.network24.player.core.remote

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.google.firebase.firestore.FirebaseFirestore
import com.network24.player.BuildConfig
import com.network24.player.core.api.Web24Api
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.repository.FavoritesRepository
import com.network24.player.core.diagnostics.DeviceHealthCollector
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.core.sync.AutoRefreshWorker
import com.network24.player.features.discover.ChannelLauncher
import com.network24.player.features.live.repository.CategorySettingsRepository
import com.network24.player.features.parental.WebStateRepository
import com.network24.player.features.player.state.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Remote Setup & Help (console page "Remote Help"): while the app is open it
 *  - says hello to Main every 8 minutes (device name, app version, screen, settings), so staff see the customer's TVs;
 *  - reads its command file (/remote/c/<token>.json, plain nginx file, 304 when nothing changed) every 30 s, every 3 s
 *    while staff are setting it up and every second during a help session, and runs the commands staff queued:
 *    sync favourites / lock, settings, Easy Mode, play a channel, open a screen, remote keys and text, a message...;
 *  - hands help-session changes to [HelpSession] (screen pictures + "Support is helping you" bar).
 * Nothing runs while the app is in the background.
 */
object RemoteAgent {

    private const val PREFS = "n24_remote"
    private const val HELLO_EVERY_MS = 8 * 60_000L   // Main counts a device as "open now" for 10 min after a hello

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    private val keys = Executors.newSingleThreadExecutor()
    private lateinit var app: Application
    private var loop: Job? = null
    private var file: String? = null
    private var lastModified: String? = null
    private var lastHello = 0L
    private var pollMs = 10_000L
    private var resumed = 0
    private val main = Handler(Looper.getMainLooper())

    /** The activity on screen (commands like "play" and "message" need one). */
    var top: Activity? = null
        private set

    fun deviceId(c: Context): String {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getString("device_id", null) ?: UUID.randomUUID().toString().also { p.edit().putString("device_id", it).apply() }
    }

    fun init(application: Application) {
        app = application
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) {
                top = a; resumed++
                if (HelpSession.state.isNotEmpty()) RemoteUi.bar(a)
                if (pendingMessage != null && !isPassing(a)) { val m = pendingMessage!!; pendingMessage = null; main.postDelayed({ RemoteUi.message(a, m) }, 400) }
                watchBackHold(a)
                ensureLoop()
            }
            override fun onActivityPaused(a: Activity) {
                resumed = (resumed - 1).coerceAtLeast(0)
                // stop shortly after the app leaves the screen (activity changes pause + resume right away)
                main.postDelayed({ if (resumed == 0) { loop?.cancel(); loop = null; top = null; bye() } }, 4000)
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) { HelpSession.attach(a) }
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) { if (top === a) top = null }
        })
    }

    // Holding BACK on a real remote (about 1.5 s) stops remote help. Staff keys are short presses, so they never do it.
    private class HoldBack(private val act: Activity, private val inner: android.view.Window.Callback) : android.view.Window.Callback by inner {
        override fun dispatchKeyEvent(e: KeyEvent): Boolean {
            if (e.keyCode == KeyEvent.KEYCODE_BACK && HelpSession.state.let { it == "live" || it == "waiting" }) {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0 && e.eventTime - e.downTime >= 1500) {
                    if (e.repeatCount < 40 && !asking) { asking = true; HelpSession.askStop(act); main.postDelayed({ asking = false }, 3000) }
                    return true
                }
                if (e.action == KeyEvent.ACTION_UP && e.eventTime - e.downTime >= 1500) return true
            }
            return inner.dispatchKeyEvent(e)
        }
        private var asking = false
    }

    private fun watchBackHold(a: Activity) {
        val w = a.window ?: return
        val cb = w.callback ?: return
        if (cb !is HoldBack) w.callback = HoldBack(a, cb)
    }

    private fun loggedIn() = PreferenceManager(app).let { it.getUsername().isNotBlank() && it.getPassword().isNotBlank() }

    /** Help asked on the sign-in screen (no account yet): the agent runs for it anyway, as a "guest" device. */
    @Volatile var guest = false
    private var lastUser = ""

    /**
     * A Remote Help call to Main: signed in = support_api with the account; on the sign-in screen = the same action as
     * rm_guest_* (device id only; Main allows nothing but hello / help / pictures / key confirmations that way).
     */
    suspend fun call(action: String, vararg fields: Pair<String, String>): JSONObject {
        if (loggedIn()) return Web24Api(app).support(action, *fields)
        return withContext(Dispatchers.IO) {
            val form = okhttp3.FormBody.Builder().add("action", action.replaceFirst("rm_", "rm_guest_"))
            fields.forEach { form.add(it.first, it.second) }
            val text = client.newCall(Request.Builder().url(PreferenceManager.SERVER_URL + "/support_api.php").post(form.build()).build()).execute().use { it.body?.string().orEmpty() }
            val j = JSONObject(text)
            if (!j.optBoolean("result")) throw Exception(j.optString("message", "Support could not be reached."))
            j
        }
    }

    /** Makes sure Main knows this device (before the first help request on the sign-in screen). */
    suspend fun ensureKnown() {
        if (!loggedIn()) guest = true
        if (file == null) hello()
        ensureLoop()
    }

    private fun ensureLoop() {
        if (loop?.isActive == true || !(loggedIn() || guest)) return
        loop = scope.launch {
            while (isActive) {
                if (!loggedIn() && !guest) { delay(5000); continue }
                // signed in (or another account): tell Main at once
                val user = PreferenceManager(app).getUsername()
                if (user != lastUser) { lastUser = user; lastHello = 0L; if (user.isNotBlank()) guest = false }
                // the first report waits for the real screen (not the logo screen the app opens on)
                if (top?.let { isPassing(it) } == true) { delay(1000); continue }
                if (file == null || System.currentTimeMillis() - lastHello > HELLO_EVERY_MS) hello()
                file?.let { read(it) }
                delay(pollMs)
            }
        }
    }

    /** The app left the screen: staff see it is not open any more (and the next opening says hello at once). */
    private fun bye() {
        lastHello = 0L
        if (!loggedIn() || file == null) return
        scope.launch { runCatching { Web24Api(app).support("rm_bye", "device_id" to deviceId(app)) } }
    }

    /** Immediately (re)reads the command file - e.g. right after the customer asked for help. */
    fun poke() {
        scope.launch { file?.let { read(it) } }
    }

    private suspend fun hello() {
        runCatching {
            val j = call("rm_hello", "device_id" to deviceId(app), "model" to model(),
                "app_ver" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", "state" to state().toString())
            lastHello = System.currentTimeMillis()
            val f = j.optString("file")
            if (f.isNotBlank() && f != file) { file = f; lastModified = null }
        }
    }

    fun model(): String {
        val m = Build.MODEL.orEmpty()
        val brand = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val name = when {
            m.startsWith("AFT") -> "Fire TV ($m)"
            m.startsWith(brand, ignoreCase = true) -> m
            else -> "$brand $m"
        }
        return name.take(80)
    }

    private suspend fun read(path: String) {
        val text = withContext(Dispatchers.IO) {
            val b = Request.Builder().url(PreferenceManager.SERVER_URL + path).header("User-Agent", "Web24Remote")
            lastModified?.let { b.header("If-Modified-Since", it) }
            runCatching {
                client.newCall(b.build()).execute().use { r ->
                    when {
                        r.code == 304 -> ""
                        r.code == 404 -> { file = null; "" }
                        !r.isSuccessful -> ""
                        else -> { lastModified = r.header("Last-Modified"); r.body?.string().orEmpty() }
                    }
                }
            }.getOrDefault("")
        }
        if (text.isBlank()) return
        val j = runCatching { JSONObject(text) }.getOrNull() ?: return
        pollMs = j.optLong("poll_ms", j.optInt("poll", 10) * 1000L).coerceIn(300L, 60_000L)
        HelpSession.onServer(app, j.optJSONObject("help"))
        val cmds = j.optJSONArray("cmds") ?: return
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var done = p.getInt("done_seq", 0)
        // a reinstall / other account starts again from the server's numbering
        val serverSeq = j.optInt("seq")
        if (done > serverSeq) done = 0
        val results = JSONObject()
        var ran = false
        for (i in 0 until cmds.length()) {
            val c = cmds.optJSONObject(i) ?: continue
            val seq = c.optInt("seq")
            if (seq <= done) continue
            val result = runCatching { run(c.optString("cmd"), c.optJSONObject("args") ?: JSONObject()) }.getOrElse { "failed: " + (it.message ?: "error") }
            if (result.isNotBlank()) results.put(seq.toString(), result.take(240))
            done = seq; ran = true
            p.edit().putInt("done_seq", done).apply()
        }
        if (ran) {
            runCatching { call("rm_ack", "device_id" to deviceId(app), "upto" to done.toString(), "results" to results.toString(), "state" to state().toString()) }
        }
    }

    // ------------------------------------------------------------------ commands

    private suspend fun run(cmd: String, a: JSONObject): String {
        val act = top
        return when (cmd) {
            "key" -> { key(a.optString("key")); "" }
            "text" -> { val s = a.optString("text"); keys.execute { runCatching { Instrumentation().sendStringSync(s) } }; "" }
            "sync" -> sync()
            "settings" -> settings(a)
            "message" -> showMessage(a.optString("text"))
            "play" -> play(a.optInt("stream_id"))
            "open" -> { open(a.optString("screen")); "opened" }
            "update" -> { act?.startActivity(settingsIntent(act).putExtra(EXTRA_CHECK_UPDATE, true)); "checking for update" }
            "fresh_start" -> { act?.startActivity(settingsIntent(act).putExtra(EXTRA_FRESH_START, true)); "done" }
            "restart" -> { restart(); "restarting" }
            "logs" -> "logs sent - code " + com.network24.player.core.diagnostics.AppLogs.send(app, "support (console)")
            else -> "unknown command"
        }
    }

    // a message that arrived while the splash / sign-in screen was up waits for the next real screen
    private var pendingMessage: String? = null

    private fun isPassing(a: Activity) = a.javaClass.simpleName.let { it.contains("Splash") || it == "ProjectionActivity" }

    private fun showMessage(text: String): String {
        val act = top
        if (act == null || isPassing(act) || act.isFinishing) { pendingMessage = text; return "will show on the next screen" }
        RemoteUi.message(act, text)
        return "shown"
    }

    const val EXTRA_CHECK_UPDATE = "n24_remote_check_update"
    const val EXTRA_FRESH_START = "n24_remote_fresh_start"

    private fun settingsIntent(c: Context) = Intent(c, com.network24.player.features.settings.activity.SettingsActivity::class.java)

    private fun key(name: String) {
        if (name == "HOME") { main.post { open("home") }; return }
        val code = if (name.length == 1 && name[0].isDigit()) KeyEvent.KEYCODE_0 + (name[0] - '0') else KeyEvent.keyCodeFromString("KEYCODE_$name")
        if (code == KeyEvent.KEYCODE_UNKNOWN) return
        // Instrumentation delivers the key to this app's focused window (dialogs too); never on the main thread
        keys.execute {
            runCatching { Instrumentation().sendKeyDownUpSync(code) }
            // staff see the result at once (not at the next picture)
            HelpSession.kick()
        }
    }

    private suspend fun sync(): String {
        val user = PreferenceManager(app).getUsername()
        val db = DatabaseProvider.get(app)
        withContext(Dispatchers.IO) {
            runCatching { FavoritesRepository(db.favoritesDao(), FirebaseFirestore.getInstance()).syncFromCloud(user) }
            runCatching { CategorySettingsRepository(FirebaseFirestore.getInstance()).syncFromCloud(user) }
            runCatching { WebStateRepository(app).sync(force = true) }
        }
        // the screen on show redraws with the new favourites / categories
        top?.let { if (it !is com.network24.player.features.player.activity.PlayerActivity) it.recreate() }
        return "favourites, categories and lock reloaded"
    }

    private fun settings(a: JSONObject): String {
        val prefs = PreferenceManager(app)
        val out = ArrayList<String>()
        if (a.has("auto_update_hours")) {
            prefs.setAutoRefreshHours(a.optInt("auto_update_hours"))
            AutoRefreshWorker.schedule(app, intervalChanged = true)
            out += "auto update ${a.optInt("auto_update_hours")} h"
        }
        if (a.has("easy_mode")) {
            val on = a.optBoolean("easy_mode")
            EasyMode.set(app, on)
            top?.let { EasyMode.goHome(it) }
            out += "Easy Mode " + if (on) "on" else "off"
        }
        if (a.has("ai_assistant")) { prefs.setAiAssistantEnabled(a.optBoolean("ai_assistant")); out += "AI button" }
        return out.joinToString(", ").ifBlank { "nothing changed" }
    }

    private suspend fun play(id: Int): String {
        val act = top ?: return "app not on screen"
        val ch = withContext(Dispatchers.IO) { DatabaseProvider.get(app).channelDao().getByStreamIds(listOf(id)).firstOrNull() }
            ?: return "channel not in this account"
        ChannelLauncher.play(act, listOf(ch), ch)
        return "playing ${ch.name}"
    }

    fun open(screen: String) {
        val act = top ?: return
        val cls: Class<*> = when (screen) {
            "livetv" -> com.network24.player.features.livetv.LiveTvActivity::class.java
            "guide" -> com.network24.player.features.guide.TvGuideActivity::class.java
            "settings" -> com.network24.player.features.settings.activity.SettingsActivity::class.java
            else -> { EasyMode.goHome(act); return }
        }
        act.startActivity(Intent(act, cls))
    }

    private fun restart() {
        val i = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        main.postDelayed({
            app.startActivity(i)
            Runtime.getRuntime().exit(0)
        }, 600)
    }

    // ------------------------------------------------------------------ what staff see about this device

    private suspend fun state(): JSONObject {
        val prefs = PreferenceManager(app)
        val dev = runCatching { DeviceHealthCollector.collect(app) }.getOrNull()
        val favs = withContext(Dispatchers.IO) { runCatching { DatabaseProvider.get(app).favoritesDao().getByType("LIVE_CHANNEL").size }.getOrNull() }
        val m = app.resources.displayMetrics
        return JSONObject().apply {
            put("screen", screenName(top))
            if (top is com.network24.player.features.player.activity.PlayerActivity) PlayerState.currentChannel()?.name?.let { put("channel", it) }
            put("android", Build.VERSION.RELEASE)
            put("tv", app.packageManager.hasSystemFeature("android.software.leanback") || app.packageManager.hasSystemFeature("android.hardware.type.television"))
            put("screen_size", "${m.widthPixels}x${m.heightPixels}")
            dev?.let { put("network", it.networkType + (it.wifiRssiDbm?.let { r -> " $r dBm" } ?: "")) }
            favs?.let { put("favorites", it) }
            put("settings", JSONObject().apply {
                put("easy_mode", EasyMode.isOn(app))
                put("auto_update_hours", prefs.getAutoRefreshHours())
                put("ai_assistant", prefs.isAiAssistantEnabled())
            })
        }
    }

    fun screenName(a: Activity?): String = when (a?.javaClass?.simpleName) {
        null -> "app closed"
        "DashboardActivity" -> "Home"
        "EasyHomeActivity" -> "Easy Mode home"
        "PlayerActivity" -> "Player"
        "LiveTvActivity" -> "Live TV"
        "TvGuideActivity" -> "TV Guide"
        "SettingsActivity" -> "Settings"
        "LoginActivity" -> "Sign in"
        "MultiViewActivity" -> "MultiView"
        else -> a.javaClass.simpleName.removeSuffix("Activity").replace(Regex("([a-z])([A-Z])"), "$1 $2")
    }

    /** Current state for help-session pictures (no database read). */
    fun quickState(): JSONObject = JSONObject().apply {
        put("screen", screenName(top))
        if (top is com.network24.player.features.player.activity.PlayerActivity) PlayerState.currentChannel()?.name?.let { put("channel", it) }
    }
}
