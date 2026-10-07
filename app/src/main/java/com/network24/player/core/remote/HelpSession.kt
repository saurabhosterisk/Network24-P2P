package com.network24.player.core.remote

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.PixelCopy
import android.view.View
import com.network24.player.core.api.Web24Api
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Remote Help on the customer's side.
 *  - "Get remote help" (Help Center, Settings, Easy Mode): the customer agrees, then the TV shows a 4-digit code to
 *    read to support. Staff join from the console.
 *  - Staff can also ask from the console: the TV asks "Allow?" and nothing happens until the customer presses Allow.
 *  - While live, a bar "Support is helping you" stays on top; a picture of the app's screen goes to Main every ~1.2 s
 *    and staff's remote keys arrive through [RemoteAgent]. The customer can stop it any time; it also ends when the
 *    app is closed.
 * Pictures show only this app (its screens and pop-ups), drawn by the app itself. Android's screen sharing
 * (MediaProjection) is NOT used: on Fire TV it crashed the whole system (surfaceflinger abort, 2026-10-07), and it
 * would also show other apps and need one more system prompt.
 */
object HelpSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())

    /** "" (nothing), "waiting" (code shown, staff not joined yet), "asking" (staff asked, customer to answer), "live". */
    var state = ""
        private set
    var code = ""
        private set
    private var by = ""
    private var asked = false
    private var upload: Job? = null
    @Volatile private var kicked = false

    /** A remote key was just pressed: send the next picture right away. */
    fun kick() { kicked = true }

    // ------------------------------------------------------------------ customer actions

    /** The customer pressed "Get remote help". */
    fun request(act: Activity) {
        if (state == "live" || state == "waiting") { askStop(act); return }
        RemoteUi.confirm(act, "Get help from Network24?",
            "When support joins, they can see the Network24 app on your TV and press its remote buttons for you. " +
                "You can stop it at any time.", "Start help", "Cancel") {
            scope.launch {
                runCatching { RemoteAgent.ensureKnown(); RemoteAgent.call("rm_help", "device_id" to RemoteAgent.deviceId(act), "op" to "request") }
                    .onSuccess { j -> state = "waiting"; code = j.optString("code"); refreshBars(); RemoteAgent.poke() }
                    .onFailure { RemoteUi.message(act, it.message ?: "Support could not be reached. Please try again.") }
            }
        }
    }

    /** "Stop remote help?" - from the same Get help buttons, or by holding BACK on the remote. */
    fun askStop(act: Activity) {
        if (state != "live" && state != "waiting") return
        RemoteUi.confirm(act, "Stop remote help?", "Network24 support will not see your screen or press your buttons any more.", "Stop help", "Keep helping") { stop(act) }
    }

    fun stop(c: Context) {
        val ctx = c.applicationContext
        end()
        scope.launch { runCatching { RemoteAgent.call("rm_help", "device_id" to RemoteAgent.deviceId(ctx), "op" to "end") } }
    }

    // ------------------------------------------------------------------ server changes (command file)

    fun onServer(c: Context, help: JSONObject?) {
        val s = help?.optString("state").orEmpty()
        if (s == state && (s != "waiting" || help?.optString("code") == code)) return
        when (s) {
            "" -> end()
            "waiting" -> { state = s; code = help?.optString("code").orEmpty(); refreshBars() }
            "asking" -> {
                state = s; by = help?.optString("by").orEmpty()
                val act = RemoteAgent.top ?: return
                if (asked) return
                asked = true
                RemoteUi.confirm(act, "Network24 support wants to help you",
                    "If you allow it, support can see the Network24 app on your TV and press its remote buttons for you. You can stop it at any time.",
                    "Allow", "No, thanks", onNo = {
                        asked = false; state = ""
                        scope.launch { runCatching { RemoteAgent.call("rm_help", "device_id" to RemoteAgent.deviceId(c), "op" to "deny") } }
                    }) {
                    scope.launch {
                        runCatching { RemoteAgent.call("rm_help", "device_id" to RemoteAgent.deviceId(c), "op" to "allow") }
                        RemoteAgent.poke()
                    }
                }
            }
            "live" -> {
                state = s; asked = false; by = help?.optString("by").orEmpty()
                refreshBars()
                startUpload(c.applicationContext)
            }
        }
    }

    private fun end() {
        state = ""; code = ""; asked = false
        upload?.cancel(); upload = null
        refreshBars()
    }

    // ------------------------------------------------------------------ pictures

    private fun startUpload(c: Context) {
        if (upload?.isActive == true) return
        upload = scope.launch {
            val sending = ArrayList<Job>()
            while (isActive && state == "live") {
                // the next picture is taken while earlier ones are still on their way (two uploads at a time: the trip to
                // Main takes about a second from India)
                sending.removeAll { !it.isActive }
                if (sending.size < 2) {
                    val bmp = appScreen()
                    if (bmp != null) {
                        val st = RemoteAgent.quickState().toString()
                        sending += launch {
                            val b64 = withContext(Dispatchers.Default) { jpeg(bmp) }
                            val r = runCatching { RemoteAgent.call("rm_shot", "device_id" to RemoteAgent.deviceId(c), "img" to b64, "state" to st) }.getOrNull()
                            if (r?.optBoolean("stop") == true) end()
                        }
                    }
                }
                // about every 0.5 s, sooner (0.2 s) after a remote key
                var waited = 0
                while (waited < 500 && !kicked) { delay(40); waited += 40 }
                if (kicked) { kicked = false; delay(200) }
            }
        }
    }

    private fun jpeg(src: Bitmap): String {
        val bmp = src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 50, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
    }

    /**
     * A picture of what this app shows: the activity's window (PixelCopy, video included) with the app's own pop-up
     * windows (Search, Help Center, dialogs...) drawn on top at their places. Other apps are never captured.
     */
    private suspend fun appScreen(): Bitmap? {
        val act = RemoteAgent.top ?: return null
        val decor = act.window?.decorView ?: return null
        if (decor.width <= 0 || decor.height <= 0) return null
        // taken straight at 720 px wide (PixelCopy scales): far less work for a Firestick than full HD + resize
        val scale = minOf(1f, 720f / decor.width)
        val bmp = Bitmap.createBitmap((decor.width * scale).toInt().coerceAtLeast(1), (decor.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        var ok = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ok = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                runCatching { PixelCopy.request(act.window, bmp, { r -> cont.resume(r == PixelCopy.SUCCESS) {} }, main) }
                    .onFailure { cont.resume(false) {} }
            }
        }
        val canvas = android.graphics.Canvas(bmp)
        canvas.scale(scale, scale)
        if (!ok) runCatching { decor.draw(canvas) }
        val base = IntArray(2).also { decor.getLocationOnScreen(it) }
        for (v in windowRoots()) {
            if (v === decor || !v.isShown || v.width <= 0 || v.height <= 0) continue
            runCatching {
                val at = IntArray(2).also { v.getLocationOnScreen(it) }
                canvas.drawColor(0x66000000)
                canvas.save()
                canvas.translate((at[0] - base[0]).toFloat(), (at[1] - base[1]).toFloat())
                v.draw(canvas)
                canvas.restore()
            }
        }
        return bmp
    }

    /** Root views of this app's windows (activity + dialogs), oldest first. */
    @Suppress("UNCHECKED_CAST")
    private fun windowRoots(): List<View> = runCatching {
        val cls = Class.forName("android.view.WindowManagerGlobal")
        val inst = cls.getMethod("getInstance").invoke(null)
        val f = cls.getDeclaredField("mViews").apply { isAccessible = true }
        ArrayList(f.get(inst) as List<View>)
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------ the bar on every screen

    fun attach(act: Activity) {
        main.post { RemoteUi.bar(act) }
    }

    private fun refreshBars() {
        main.post { RemoteAgent.top?.let { RemoteUi.bar(it) } }
    }

    fun barText(): String? = when (state) {
        "waiting" -> "Your help code: $code  ·  read it to Network24 support  ·  hold BACK to cancel"
        "live" -> "Network24 support" + (if (by.isNotBlank()) " ($by)" else "") + " is helping you  ·  hold BACK to stop"
        else -> null
    }
}
