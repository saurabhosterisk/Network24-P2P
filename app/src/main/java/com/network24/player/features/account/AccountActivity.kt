package com.network24.player.features.account

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.network24.player.R
import com.network24.player.core.api.Web24Api
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.features.dashboard.home.HomeFont
import com.network24.player.features.discover.Fmt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Kept for older entry points (menus, deep links): opens the Account layer and closes itself. */
class AccountActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AccountCenter.show(this) { finish() }
    }
}

/**
 * Account & Connections over the page (no new screen), in the app's look: the plan with its time left, the
 * screens in use as slots (filled = playing now), what plays on each with the device, network and how long, and
 * Stop to free one at once. Refreshes every 15 s while open.
 * No payment in the app (resellers share it with their customers): an ending plan says "contact your provider".
 */
class AccountCenter private constructor(private val act: AppCompatActivity, private val onClose: (() -> Unit)?) :
    Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    companion object {
        fun show(act: AppCompatActivity, onClose: (() -> Unit)? = null) = AccountCenter(act, onClose).show()
    }

    private val res = act.resources
    private val uiScale = res.displayMetrics.let { m -> minOf(1f, m.widthPixels / m.density / 960f, m.heightPixels / m.density / 400f) }
    private val d = res.displayMetrics.density * uiScale
    private fun dp(v: Int) = (v * d).toInt()
    private fun dpf(v: Float) = v * d
    private val prefs = PreferenceManager(act)

    private val surface = Color.parseColor("#14161B")
    private val line = Color.parseColor("#1FFFFFFF")
    private val textMain = Color.parseColor("#F2F3F5")
    private val textSub = Color.parseColor("#9BA1AD")
    private val accent = Color.parseColor("#7C5CFF")
    private val accentSoft = Color.parseColor("#A894FF")
    private val live = Color.parseColor("#E5484D")
    private val gold = Color.parseColor("#F5B841")
    private val good = Color.parseColor("#3DD68C")

    private lateinit var slots: LinearLayout
    private lateinit var slotsText: TextView
    private lateinit var list: LinearLayout
    private var poll: Job? = null
    private var focused = false
    private lateinit var refreshBtn: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        setContentView(build())
        window?.decorView?.alpha = 0f
        window?.decorView?.animate()?.alpha(1f)?.setDuration(180)?.start()
        poll = act.lifecycleScope.launch { while (isActive) { load(); delay(15_000) } }
        setOnDismissListener { poll?.cancel(); onClose?.invoke() }
    }

    // ------------------------------------------------------------------------------------------------ building blocks
    private fun text(s: CharSequence, size: Float, color: Int = textMain, weight: Int = 500, lines: Int = 1) = TextView(act).apply {
        text = s; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * d); setTextColor(color); typeface = HomeFont.of(act, weight)
        maxLines = lines; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
    }

    private fun shape(fill: Int, radius: Float, stroke: Int = 0) = GradientDrawable().apply { setColor(fill); cornerRadius = dpf(radius); if (stroke != 0) setStroke(dp(1), stroke) }

    private fun focusable(v: View, radius: Float, ring: Int = Color.WHITE) {
        v.isFocusable = true; v.isClickable = true
        val r = GradientDrawable().apply { cornerRadius = dpf(radius); setStroke(dp(2), ring); setColor(Color.TRANSPARENT) }
        v.setOnFocusChangeListener { view, has -> view.foreground = if (has) r else null }
    }

    private fun card() = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16))
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#261E1A3A"), surface)).apply { cornerRadius = dpf(16f); setStroke(dp(1), line) }
    }

    private fun label(s: String) = text(s, 11f, textSub, 800).apply { letterSpacing = 0.14f }

    // ------------------------------------------------------------------------------------------------ page
    private fun build(): View {
        val root = FrameLayout(act).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#FF1A1438"), Color.parseColor("#FF08090C"), Color.parseColor("#FF08090C")))
        }
        val page = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(48), dp(22), dp(48), dp(14)) }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val b = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            page.setPadding(dp(48) + b.left, dp(22) + b.top, dp(48) + b.right, dp(14) + b.bottom); ins
        }

        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val words = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        words.addView(text("Account", 24f, textMain, 800))
        words.addView(text("Your plan and the screens playing on it right now.", 13f, textSub, 600).apply { setPadding(0, dp(5), 0, 0) })
        head.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(ImageView(act).apply {
            setImageResource(R.drawable.ic_cu_close); setColorFilter(textMain); setPadding(dp(9), dp(9), dp(9), dp(9)); contentDescription = "Close"
            background = shape(0x1AFFFFFF, 20f); setOnClickListener { dismiss() }; focusable(this, 20f)
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        page.addView(head)

        val body = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(planCard(), LinearLayout.LayoutParams(0, -1, 1f))

        // screens: slots + what plays on each
        val conn = card()
        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(label("SCREENS IN USE"), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(text("↻  Refresh", 12f, accentSoft, 700).also { refreshBtn = it }.apply { setPadding(dp(10), dp(6), dp(10), dp(6)); setOnClickListener { act.lifecycleScope.launch { load() } }; focusable(this, 12f) })
        conn.addView(top)
        slotsText = text("Checking…", 22f, textMain, 800).apply { setPadding(0, dp(10), 0, 0) }
        conn.addView(slotsText)
        slots = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, dp(6)) }
        conn.addView(slots)
        conn.addView(text("One screen = one channel playing. You can be signed in on many devices; Stop frees a screen at once.", 12f, textSub, 500, lines = 3).apply { setPadding(0, dp(4), 0, dp(10)); setLineSpacing(0f, 1.15f) })
        list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        conn.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(conn, LinearLayout.LayoutParams(0, -1, 1.5f).apply { marginStart = dp(16) })
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(18) })
        return root
    }

    private fun planCard() = card().apply {
        val exp = prefs.getExpiry() * 1000
        val now = System.currentTimeMillis()
        val ended = exp in 1..now
        val days = if (exp > 0) com.network24.player.core.util.ExpiryDays.from(exp) else null
        val color = when { ended -> live; days != null && days <= 7 -> gold; else -> good }

        addView(label("YOUR PLAN"))
        addView(text(prefs.getUsername(), 20f, textMain, 800).apply { setPadding(0, dp(10), 0, 0) })
        val tags = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        fun tag(s: String, c: Int) = text(s, 11f, c, 800).apply { letterSpacing = 0.08f; setPadding(dp(8), dp(4), dp(8), dp(4)); background = shape(0x1AFFFFFF, 6f) }
        tags.addView(tag(if (prefs.isTrial()) "TRIAL" else "PREMIUM", accentSoft))
        tags.addView(tag(if (ended) "ENDED" else "ACTIVE", color), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        addView(tags)

        // time left: big number + a bar that empties over the last 30 days
        addView(text(when { exp <= 0 -> "No end date"; ended -> "Plan ended"; days == 0L -> "Ends today"; days == 1L -> "1 day left"; else -> "$days days left" },
            30f, color, 800).apply { setPadding(0, dp(22), 0, 0) })
        if (exp > 0) {
            addView(ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 30; progress = if (ended) 0 else (days ?: 0L).coerceIn(0, 30).toInt()
                progressDrawable = act.getDrawable(R.drawable.home_progress)
            }, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(12) })
            addView(text((if (ended) "Ended on " else "Active until ") + Fmt.date(exp), 13f, textSub, 600).apply { setPadding(0, dp(10), 0, 0) })
        }
        if (ended || (days != null && days <= 7)) {
            addView(text(if (ended) "To keep watching, please contact the provider you got your account from."
                else "Your plan ends soon. To renew, please contact the provider you got your account from.",
                13f, textMain, 600, lines = 4).apply { setPadding(dp(12), dp(10), dp(12), dp(10)); background = shape(0x1AF5B841, 10f); setLineSpacing(0f, 1.15f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        }
        addView(View(act), LinearLayout.LayoutParams(1, 0, 1f))
        addView(text("App ${appVersion()}  ·  ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}", 11f, textSub, 600))
    }

    private fun appVersion() = runCatching { act.packageManager.getPackageInfo(act.packageName, 0).let { "${it.versionName} (${if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()})" } }.getOrDefault("")

    // ------------------------------------------------------------------------------------------------ connections
    private suspend fun load() {
        val r = runCatching { Web24Api(act).support("connections") }
        r.onSuccess { j -> show(Web24Api.objects(j.optJSONArray("connections")), j.optInt("max_connections", prefs.getMaxConnections())) }
            .onFailure { if (list.childCount == 0) { slotsText.text = "Could not check"; list.addView(text(it.message ?: "Please try again.", 13f, textSub, 600, lines = 3)) } }
    }

    private fun show(conns: List<JSONObject>, max: Int) {
        val hadFocus = list.findFocus()?.tag
        slotsText.text = "${conns.size} of $max"
        slots.removeAllViews()
        repeat(maxOf(max, conns.size)) { i ->
            val on = i < conns.size
            slots.addView(ImageView(act).apply {
                setImageResource(R.drawable.ic_slot_tv); setColorFilter(if (on) Color.WHITE else textSub)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = if (on) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(accent, Color.parseColor("#5B3FD9"))).apply { cornerRadius = dpf(10f) } else shape(0x0FFFFFFF, 10f, line)
            }, LinearLayout.LayoutParams(dp(52), dp(40)).apply { marginEnd = dp(8) })
        }
        list.removeAllViews()
        if (conns.isEmpty()) list.addView(text("Nothing is playing on your account right now.", 14f, textSub, 600).apply { setPadding(0, dp(10), 0, 0) })
        conns.forEach { c -> list.addView(connRow(c), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }) }
        if (hadFocus != null) list.findViewWithTag<View>(hadFocus)?.requestFocus()
        // first open: the remote starts on the first screen playing (it started on Close, so OK closed the page)
        else if (!focused) { focused = true; (list.getChildAt(0)?.takeIf { it.isFocusable } ?: refreshBtn).requestFocus() }
    }

    private fun connRow(c: JSONObject) = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)); tag = "c:" + c.optString("activity_id")
        background = shape(Color.parseColor("#1A1C23"), 12f, line)
        val mins = ((System.currentTimeMillis() / 1000 - c.optLong("since")) / 60).coerceAtLeast(0)
        val here = c.optBoolean("this_network")
        addView(View(act).apply { background = shape(live, 5f) }, LinearLayout.LayoutParams(dp(10), dp(10)))
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(10), 0) }
        col.addView(text(c.optString("stream_name").ifBlank { "Channel " + c.optString("stream_id") }, 15f, textMain, 700))
        col.addView(text("${device(c.optString("user_agent"))}  ·  ${if (here) "this internet connection" else "another network"}  ·  " +
            (if (mins < 60) "$mins min" else "${mins / 60} h ${mins % 60} min"), 12f, textSub, 600).apply { setPadding(0, dp(4), 0, 0) })
        addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        addView(text("Stop", 13f, Color.WHITE, 800).apply { setPadding(dp(14), dp(7), dp(14), dp(7)); background = shape(0x33E5484D, 14f, 0x99E5484D.toInt()) })
        focusable(this, 12f)
        setOnClickListener {
            AlertDialog.Builder(act).setTitle("Stop this screen?")
                .setMessage("The device watching ${c.optString("stream_name")} stops playing and the screen is free at once.")
                .setPositiveButton("Stop") { _, _ ->
                    act.lifecycleScope.launch {
                        runCatching { Web24Api(act).support("connection_stop", "activity_id" to c.optString("activity_id")) }
                            .onSuccess { Toast.makeText(act, "Stopped", Toast.LENGTH_SHORT).show(); delay(1200); load() }
                            .onFailure { Toast.makeText(act, it.message ?: "Could not stop it", Toast.LENGTH_SHORT).show() }
                    }
                }.setNegativeButton("Cancel", null).show()
        }
    }

    private fun device(ua: String): String = when {
        Regex("N24PlayerPlayer|N24Player", RegexOption.IGNORE_CASE).containsMatchIn(ua) -> "Network24 app"
        ua.contains("TiviMate", true) -> "TiviMate"; ua.contains("Smarters", true) -> "IPTV Smarters"
        Regex("AppleCoreMedia|iPhone|iPad").containsMatchIn(ua) -> "iPhone / iPad"
        ua.contains("Chrome/") -> "Browser"; ua.contains("Safari/") -> "Safari"; ua.contains("VLC", true) -> "VLC"
        ua.isBlank() -> "Unknown app"; else -> ua.split(' ', '/').first()
    }

}
