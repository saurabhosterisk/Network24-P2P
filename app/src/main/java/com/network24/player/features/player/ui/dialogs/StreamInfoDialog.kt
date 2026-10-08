package com.network24.player.features.player.ui.dialogs

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.diagnostics.DeviceHealthCollector
import com.network24.player.core.diagnostics.DeviceHealthSnapshot
import com.network24.player.core.diagnostics.PlaybackLog
import com.network24.player.core.diagnostics.SpeedTest
import com.network24.player.core.net.SpeedMonitor
import com.network24.player.core.player.SoftwareDecoding
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.features.dashboard.home.HomeFont
import com.network24.player.features.player.manager.PlayerManager
import com.network24.player.features.player.manager.PlayerManager.StreamErrorType
import com.network24.player.features.player.state.PlayerState
import com.network24.player.features.support.repository.SupportRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stream health: everything about the channel being watched, live, in the app's look.
 *  - a health score and a plain-words diagnosis (why it buffers and what to do), with fixes one press away:
 *    speed test, "Fix with AI" (the support AI tests other sources for this channel on the server), restart stream;
 *  - Live: stream speed and buffer graphs, picture details, dropped frames, the last two minutes of buffering;
 *  - Speed test: internet download / upload, ping, jitter and the ping to the server the channel comes from;
 *  - Stream / Network / Device details and the playback event log (kept by [PlaybackLog]).
 */
class StreamInfoDialog : DialogFragment() {

    companion object {
        fun newInstance(): StreamInfoDialog = StreamInfoDialog()
        private const val TEN_MIN = 10 * 60_000L
    }

    private val bg = Color.parseColor("#08090C")
    private val surface = Color.parseColor("#14161B")
    private val line = Color.parseColor("#1FFFFFFF")
    private val textMain = Color.parseColor("#F2F3F5")
    private val textSub = Color.parseColor("#9BA1AD")
    private val accent = Color.parseColor("#7C5CFF")
    private val accentSoft = Color.parseColor("#A894FF")
    private val cyan = Color.parseColor("#22D3EE")
    private val live = Color.parseColor("#E5484D")
    private val gold = Color.parseColor("#F5B841")
    private val good = Color.parseColor("#3DD68C")

    private lateinit var ctx: Context
    private var d = 1f
    private fun dp(v: Int) = (v * d).toInt()
    private fun dpf(v: Float) = v * d

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000L) }
    }
    private var device: DeviceHealthSnapshot? = null
    private var deviceAt = 0L

    // header / health
    private lateinit var subTitle: TextView
    private lateinit var ring: HealthRing
    private lateinit var verdictTag: TextView
    private lateinit var verdictTitle: TextView
    private lateinit var verdictText: TextView
    private lateinit var stepsBox: LinearLayout
    private lateinit var aiLine: TextView
    private lateinit var runBtn: TextView
    private lateinit var fixBtn: TextView
    private lateinit var restartBtn: TextView

    // tabs
    private var tab = "live"
    private lateinit var tabRow: LinearLayout
    private val tabViews = LinkedHashMap<String, TextView>()
    private lateinit var content: FrameLayout

    // live tab
    private var liveViews: LiveViews? = null
    private class LiveViews(
        val root: View,
        val speed: TextView, val speedSub: TextView, val speedLine: Sparkline,
        val ahead: TextView, val aheadSub: TextView, val aheadLine: Sparkline,
        val picture: TextView, val pictureSub: TextView,
        val dropped: TextView, val droppedSub: TextView,
        val timeline: Timeline, val timelineSub: TextView,
        val counters: List<TextView>
    )

    // speed test tab
    private var testViews: TestViews? = null
    private class TestViews(
        val root: View, val gauge: Gauge, val phase: TextView, val start: TextView,
        val down: TextView, val up: TextView, val ping: TextView, val server: TextView,
        val downSub: TextView, val upSub: TextView, val pingSub: TextView, val serverSub: TextView,
        val verdict: TextView
    )
    private var testJob: Job? = null
    private var aiJob: Job? = null

    // ------------------------------------------------------------------------------------------------ lifecycle
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        ctx = requireContext()
        val m = resources.displayMetrics
        val uiScale = minOf(1f, m.widthPixels / m.density / 960f, m.heightPixels / m.density / 400f)
        d = m.density * uiScale
        return Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            setContentView(build())
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            window?.let { w -> androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false); androidx.core.view.WindowInsetsControllerCompat(w, w.decorView).apply { hide(androidx.core.view.WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE } }
            window?.decorView?.alpha = 0f
            window?.decorView?.animate()?.alpha(1f)?.setDuration(180)?.start()
        }
    }

    override fun onStart() { super.onStart(); handler.post(ticker) }
    override fun onStop() { handler.removeCallbacks(ticker); super.onStop() }
    override fun onDestroy() { testJob?.cancel(); aiJob?.cancel(); handler.removeCallbacks(ticker); super.onDestroy() }

    // ------------------------------------------------------------------------------------------------ building blocks
    private fun text(s: CharSequence, size: Float, color: Int = textMain, weight: Int = 500, lines: Int = 1) = TextView(ctx).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_PX, size * d); setTextColor(color); typeface = HomeFont.of(ctx, weight)
        maxLines = lines; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
    }

    private fun label(s: String, color: Int = textSub) = text(s.uppercase(Locale.US), 10.5f, color, 700).apply { letterSpacing = 0.12f }

    private fun shape(fill: Int, radius: Float, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dpf(radius); if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun focusable(v: View, radius: Float, scale: Float = 1.05f, ring: Int = Color.WHITE) {
        v.isFocusable = true; v.isClickable = true
        val r = GradientDrawable().apply { cornerRadius = dpf(radius); setStroke(dp(3), ring); setColor(Color.TRANSPARENT) }
        v.setOnFocusChangeListener { view, has ->
            view.foreground = if (has) r else null
            view.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).setDuration(110).start()
        }
    }

    private fun pill(s: String, primary: Boolean = false, onClick: () -> Unit) = text(s, 13f, if (primary) bg else textMain, 700).apply {
        gravity = Gravity.CENTER; setPadding(dp(16), dp(10), dp(16), dp(10))
        background = shape(if (primary) Color.WHITE else 0x1FFFFFFF, 20f)
        setOnClickListener { onClick() }; focusable(this, 20f, 1.06f, if (primary) accent else Color.WHITE)
    }

    private fun card(pad: Int = 14) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(pad), dp(pad - 2), dp(pad), dp(pad - 2))
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#221E1A3A"), surface)).apply {
            cornerRadius = dpf(16f); setStroke(dp(1), line)
        }
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    // ------------------------------------------------------------------------------------------------ page
    private fun build(): View {
        val root = FrameLayout(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#FF1A1438"), bg, bg))
        }
        val page = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(32), dp(20), dp(32), dp(18)); clipChildren = false; clipToPadding = false
        }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))

        // header: title, channel, close
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text("Stream health", 22f, textMain, 800))
        subTitle = text("", 13f, textSub, 500).apply { setPadding(0, dp(5), 0, 0) }
        titles.addView(subTitle)
        head.addView(titles, lp(0, -2, 1f))
        head.addView(pill("Close") { dismiss() })
        page.addView(head, lp(-1, -2))

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        page.addView(body, lp(-1, 0, 1f).apply { topMargin = dp(14) })

        body.addView(buildHealth(), lp(0, -1, 0.37f))
        val right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        body.addView(right, lp(0, -1, 0.63f).apply { marginStart = dp(16) })

        tabRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; setPadding(dp(2), dp(2), dp(2), dp(2)) }
        listOf("live" to "Live", "test" to "Speed test", "stream" to "Stream", "network" to "Network", "device" to "Device", "events" to "Events", "logs" to "Logs").forEach { (key, name) ->
            val t = text(name, 13f, textSub, 700).apply {
                id = View.generateViewId()
                gravity = Gravity.CENTER; setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { showTab(key) }
                focusable(this, 18f, 1.06f, accent)   // the focused tab is the white one: violet ring
                val base = onFocusChangeListener
                setOnFocusChangeListener { v, has -> base.onFocusChange(v, has); if (has && tab != key) showTab(key) }
            }
            tabViews[key] = t
            tabRow.addView(t, lp(-2, -2).apply { marginEnd = dp(6) })
        }
        right.addView(tabRow, lp(-1, -2))
        content = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
        right.addView(content, lp(-1, 0, 1f).apply { topMargin = dp(10) })

        // RIGHT from the health buttons goes back to the open tab (not the nearest one, which would switch tabs)
        fixBtn.nextFocusRightId = restartBtn.id.takeIf { it != View.NO_ID } ?: View.generateViewId().also { restartBtn.id = it }
        listOf(runBtn, restartBtn).forEach { b -> b.setOnKeyListener { _, code, e ->
            if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && e.action == android.view.KeyEvent.ACTION_DOWN) { tabViews[tab]?.requestFocus(); true } else false
        } }
        showTab("live")
        tabViews["live"]?.post { tabViews["live"]?.requestFocus() }
        return root
    }

    private fun buildHealth(): View {
        val c = card(16)
        c.clipChildren = false; c.clipToPadding = false
        val upper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ring = HealthRing(ctx)
        top.addView(ring, lp(dp(88), dp(88)))
        val tv = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        verdictTag = label("Checking", good)
        tv.addView(verdictTag)
        verdictTitle = text("Checking the stream…", 17f, textMain, 800, 2).apply { setPadding(0, dp(6), 0, 0); setLineSpacing(0f, 1.1f) }
        tv.addView(verdictTitle)
        top.addView(tv, lp(0, -2, 1f))
        upper.addView(top)

        verdictText = text("", 12.5f, textSub, 500, 2).apply { setLineSpacing(0f, 1.2f); setPadding(0, dp(12), 0, 0) }
        upper.addView(verdictText)

        upper.addView(label("What to do").apply { setPadding(0, dp(12), 0, dp(6)) })
        stepsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        upper.addView(stepsBox)

        aiLine = text("", 12f, accentSoft, 600, 3).apply { setLineSpacing(0f, 1.15f); visibility = View.GONE; setPadding(0, dp(8), 0, 0) }
        upper.addView(aiLine)
        c.addView(upper, lp(-1, 0, 1f))
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        runBtn = pill("Run speed test", primary = true) { showTab("test"); startTest() }.apply { id = View.generateViewId() }
        c.addView(runBtn, lp(-1, -2).apply { topMargin = dp(10) })
        fixBtn = pill("Fix with AI") { fixWithAi() }
        restartBtn = pill("Restart stream") { restart() }
        actions.addView(fixBtn, lp(0, -2, 1f))
        actions.addView(restartBtn, lp(0, -2, 1f).apply { marginStart = dp(8) })
        c.addView(actions, lp(-1, -2).apply { topMargin = dp(8) })
        return c
    }

    private fun showTab(key: String) {
        tab = key
        tabViews.forEach { (k, v) ->
            val on = k == key
            v.setTextColor(if (on) bg else textSub)
            v.background = if (on) shape(Color.WHITE, 18f) else null
        }
        content.removeAllViews(); liveViews = null; testViews = null
        content.clipChildren = key !in listOf("live", "test")
        when (key) {
            "live" -> content.addView(ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; isFocusable = false; addView(buildLive()) })
            "test" -> content.addView(buildTest())
            "logs" -> content.addView(buildLogs())
            else -> content.addView(ScrollView(ctx).apply {
                // every list fits the screen; the scroll is only for small phones (touch), never a remote focus stop
                isVerticalScrollBarEnabled = false; isFocusable = false
                addView(LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; tag = "rows" })
            })
        }
        refresh()
    }

    // ------------------------------------------------------------------------------------------------ live tab
    private fun tile(title: String, withLine: Boolean, lineColor: Int): Array<View?> {
        val c = card(12)
        c.addView(label(title))
        val v = text("-", 19f, textMain, 800).apply { setPadding(0, dp(8), 0, 0) }
        c.addView(v)
        val s = text("", 11.5f, textSub, 500, 1).apply { setPadding(0, dp(5), 0, 0) }
        c.addView(s)
        var sl: Sparkline? = null
        if (withLine) {
            sl = Sparkline(ctx, lineColor)
            c.addView(sl, lp(-1, dp(34)).apply { topMargin = dp(8) })
        } else {
            c.addView(View(ctx), lp(-1, dp(34)).apply { topMargin = dp(8) })
        }
        return arrayOf(c, v, s, sl)
    }

    private fun buildLive(): View {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val t1 = tile("Stream speed", true, cyan)
        val t2 = tile("Buffer ahead", true, accentSoft)
        val t3 = tile("Picture", false, 0)
        val t4 = tile("Dropped", false, 0)
        listOf(t1, t2, t3, t4).forEachIndexed { i, t ->
            row.addView(t[0], lp(0, -2, 1f).apply { if (i > 0) marginStart = dp(10) })
        }
        col.addView(row, lp(-1, -2))

        val tl = card(14)
        val tlHead = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tlHead.addView(label("Last 2 minutes"), lp(0, -2, 1f))
        tlHead.addView(legend(good, "Playing")); tlHead.addView(legend(live, "Buffering").apply { setPadding(dp(14), 0, 0, 0) })
        tl.addView(tlHead)
        val timeline = Timeline(ctx)
        tl.addView(timeline, lp(-1, dp(26)).apply { topMargin = dp(10) })
        val tlFoot = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        val tlSub = text("", 11f, textSub, 500)
        tlFoot.addView(tlSub, lp(0, -2, 1f)); tlFoot.addView(text("now", 11f, textSub, 600))
        tl.addView(tlFoot)
        col.addView(tl, lp(-1, -2).apply { topMargin = dp(10) })

        val counters = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val values = ArrayList<TextView>()
        listOf("Bufferings", "Time buffering", "Start time", "Connect time").forEachIndexed { i, n ->
            val c = card(12)
            c.addView(label(n))
            val v = text("-", 16f, textMain, 800).apply { setPadding(0, dp(7), 0, 0) }
            c.addView(v); values += v
            counters.addView(c, lp(0, -2, 1f).apply { if (i > 0) marginStart = dp(10) })
        }
        col.addView(counters, lp(-1, -2).apply { topMargin = dp(10) })

        liveViews = LiveViews(
            col,
            t1[1] as TextView, t1[2] as TextView, t1[3] as Sparkline,
            t2[1] as TextView, t2[2] as TextView, t2[3] as Sparkline,
            t3[1] as TextView, t3[2] as TextView,
            t4[1] as TextView, t4[2] as TextView,
            timeline, tlSub, values
        )
        return col
    }

    private fun legend(color: Int, s: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(View(ctx).apply { background = shape(color, 4f) }, lp(dp(8), dp(8)))
        addView(text(s, 11f, textSub, 600).apply { setPadding(dp(6), 0, 0, 0) })
    }

    // ------------------------------------------------------------------------------------------------ speed test tab
    private fun result(title: String): Array<TextView> {
        val c = card(12)
        c.addView(label(title))
        val v = text("-", 20f, textMain, 800).apply { setPadding(0, dp(8), 0, 0) }
        val s = text("", 11.5f, textSub, 500, 2).apply { setPadding(0, dp(5), 0, 0) }
        c.addView(v); c.addView(s)
        c.tag = "card"
        return arrayOf(v, s, TextView(ctx).apply { tag = c })
    }

    private fun buildTest(): View {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        val gauge = Gauge(ctx)
        top.addView(gauge, lp(dp(190), dp(150)))
        val side = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, 0, 0); clipChildren = false; clipToPadding = false }
        val phase = text("Ready", 18f, textMain, 800, 2)
        side.addView(phase)
        val verdict = text("Tests your internet (download, upload, ping) and the route to the server this channel comes from. Takes about 20 seconds; the channel keeps playing.", 12.5f, textSub, 500, 4).apply {
            setLineSpacing(0f, 1.2f); setPadding(0, dp(8), 0, dp(12))
        }
        side.addView(verdict)
        val start = pill("Start test", primary = true) { startTest() }.apply { nextFocusLeftId = runBtn.id }
        side.addView(start, lp(-2, -2))
        top.addView(side, lp(0, -2, 1f))
        col.addView(top, lp(-1, -2))

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val r = listOf(result("Download"), result("Upload"), result("Ping · jitter"), result("Server ping"))
        r.forEachIndexed { i, a -> row.addView(a[2].tag as View, lp(0, -2, 1f).apply { if (i > 0) marginStart = dp(10) }) }
        col.addView(row, lp(-1, -2).apply { topMargin = dp(14) })

        testViews = TestViews(col, gauge, phase, start, r[0][0], r[1][0], r[2][0], r[3][0], r[0][1], r[1][1], r[2][1], r[3][1], verdict)
        SpeedTest.last?.let { showTestResult(it) }
        if (testJob?.isActive == true) { phase.text = "Testing…"; start.text = "Testing…" }
        return col
    }

    private fun startTest() {
        if (testJob?.isActive == true) return
        val host = streamHost()
        testJob = lifecycleScope.launch {
            fun tv() = testViews
            tv()?.start?.text = "Testing…"
            tv()?.verdict?.text = "Measuring… the channel keeps playing."
            var res = SpeedTest.Result()
            tv()?.phase?.text = "Ping"
            val ping = SpeedTest.ping(SpeedTest.cloudflareHost(), 443)
            res = res.copy(ping = ping)
            tv()?.ping?.text = ping?.takeIf { it.ms >= 0 }?.let { "${it.ms} ms" } ?: "No reply"
            tv()?.pingSub?.text = ping?.takeIf { it.ms >= 0 }?.let { "jitter ${it.jitterMs} ms" } ?: "the internet did not answer"
            if (host != null) {
                tv()?.phase?.text = "Channel server"
                val dns = SpeedTest.dnsMs(host.first)
                val sp = SpeedTest.ping(host.first, host.second)
                res = res.copy(serverPing = sp, dnsMs = dns)
                tv()?.server?.text = sp?.takeIf { it.ms >= 0 }?.let { "${it.ms} ms" } ?: "No reply"
                tv()?.serverSub?.text = sp?.let { if (it.lost > 0) "${it.lost} of 6 tries lost" else "jitter ${it.jitterMs} ms · DNS ${if (dns >= 0) "$dns ms" else "-"}" } ?: "could not be reached"
            }
            tv()?.phase?.text = "Download"
            val down = SpeedTest.download { mbps -> testViews?.gauge?.set(mbps.toFloat(), cyan); testViews?.down?.text = mbpsText(mbps) }
            res = res.copy(downMbps = down)
            tv()?.down?.text = mbpsText(down)
            tv()?.phase?.text = "Upload"
            val up = SpeedTest.upload { mbps -> testViews?.gauge?.set(mbps.toFloat(), accentSoft); testViews?.up?.text = mbpsText(mbps) }
            res = res.copy(upMbps = up)
            SpeedTest.last = res
            PlaybackLog.add(PlaybackLog.Kind.INFO, "Speed test: ${mbpsText(down)} down, ${mbpsText(up)} up, ping ${ping?.ms ?: -1} ms")
            showTestResult(res)
            refresh()
        }
    }

    private fun showTestResult(r: SpeedTest.Result) {
        val t = testViews ?: return
        t.start.text = "Run again"
        t.gauge.set(r.downMbps.toFloat(), cyan)
        t.phase.text = qualityFor(r.downMbps)
        t.down.text = mbpsText(r.downMbps); t.downSub.text = "for watching"
        t.up.text = mbpsText(r.upMbps); t.upSub.text = "for sending"
        r.ping?.let { p ->
            t.ping.text = if (p.ms >= 0) "${p.ms} ms" else "No reply"
            t.pingSub.text = if (p.ms >= 0) "jitter ${p.jitterMs} ms${if (p.lost > 0) " · ${p.lost} lost" else ""}" else "the internet did not answer"
        }
        r.serverPing?.let { p ->
            t.server.text = if (p.ms >= 0) "${p.ms} ms" else "No reply"
            t.serverSub.text = if (p.lost > 0) "${p.lost} of 6 tries lost" else "jitter ${p.jitterMs} ms · DNS ${if (r.dnsMs >= 0) "${r.dnsMs} ms" else "-"}"
        } ?: run { t.server.text = "-"; t.serverSub.text = "no channel playing" }
        val need = requiredMbps()
        t.verdict.text = buildString {
            append("Measured ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(r.at))}. ")
            if (need > 0) append(if (r.downMbps >= need * 1.5) "Plenty for this channel (it needs about ${need.toInt()} Mbps)." else if (r.downMbps >= need) "Just enough for this channel (needs about ${need.toInt()} Mbps) — other devices on the line can cause buffering." else "Too slow for this channel (it needs about ${need.toInt()} Mbps).")
            else append("Live channels need about 3 Mbps (SD), 6 (HD), 12 (Full HD), 30 (4K).")
        }
    }

    private fun qualityFor(mbps: Double) = when {
        mbps <= 0.0 -> "Test did not finish"
        mbps >= 30 -> "Fast enough for 4K"
        mbps >= 12 -> "Fast enough for Full HD"
        mbps >= 6 -> "Fast enough for HD"
        mbps >= 3 -> "Enough for SD only"
        else -> "Too slow for live TV"
    }

    // ------------------------------------------------------------------------------------------------ actions
    private fun restart() {
        if (PlayerManager.getExoPlayerOrNull() == null) { toast("No channel is playing."); return }
        PlayerManager.retryCurrent()
        PlaybackLog.add(PlaybackLog.Kind.INFO, "Stream restarted from Stream health")
        toast("Restarting the stream…")
    }

    private fun fixWithAi() {
        val id = PlayerManager.currentStreamId
        if (id <= 0) { toast("Start a channel first."); return }
        if (aiJob?.isActive == true) return
        val prefs = PreferenceManager(ctx)
        val repo = SupportRepository(prefs)
        val down = PlayerManager.getLastError() != null && !PlayerManager.isPlaying()
        aiLine.visibility = View.VISIBLE
        aiLine.text = "AI: checking this channel on our server…"
        aiJob = lifecycleScope.launch {
            val name = runCatching { DatabaseProvider.get(ctx).channelDao().getByStreamIds(listOf(id)).firstOrNull()?.name }.getOrNull()
                ?: PlayerState.currentChannel()?.name.orEmpty()
            val text = if (down) "This channel is not working" else "This channel keeps buffering"
            repo.aiAsk("$text (from Stream health: ${reportLine()})", if (down) "down" else "buffering", id, name)
                .onSuccess { r ->
                    var last = r.message?.id ?: 0
                    r.message?.text?.let { aiLine.text = "AI: " + it.replace("**", "") }
                    if (!r.online) { aiLine.text = "AI is offline right now — please try again in a few minutes."; return@launch }
                    // follow the AI while it tests sources (about a minute)
                    repeat(30) {
                        delay(3000)
                        repo.aiPoll(last).onSuccess { p ->
                            p.messages.orEmpty().filter { it.fromBot }.lastOrNull()?.let { m -> m.text?.let { aiLine.text = "AI: " + it.replace("**", "") } }
                            last = maxOf(last, p.messages.orEmpty().maxOfOrNull { it.id } ?: last)
                        }
                    }
                }
                .onFailure { aiLine.text = it.message ?: "AI could not be reached." }
        }
    }

    private fun reportLine(): String {
        val p = PlayerManager.getExoPlayerOrNull()
        val f = p?.videoFormat
        return "speed ${mbpsText(SpeedMonitor.getMbps())}, buffer ${String.format(Locale.US, "%.1f", aheadMs() / 1000.0)}s, " +
            "${PlaybackLog.bufferingsSince(TEN_MIN)} bufferings in 10 min, ${f?.height ?: 0}p, net ${device?.networkType ?: "-"}" +
            (device?.wifiRssiDbm?.let { ", wifi $it dBm" } ?: "") +
            (SpeedTest.last?.let { ", speed test ${mbpsText(it.downMbps)}" } ?: "")
    }

    private fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------------------------------------ refresh
    private fun refresh() {
        if (!::ring.isInitialized) return
        val now = System.currentTimeMillis()
        if (device == null || now - deviceAt > 3000) {
            device = runCatching { DeviceHealthCollector.collect(ctx) }.getOrNull(); deviceAt = now
        }
        val ch = PlayerState.currentChannel()?.name
        val p = PlayerManager.getExoPlayerOrNull()
        subTitle.text = listOfNotNull(ch, p?.let { playerState(it) }, device?.networkType).joinToString("  ·  ")

        val dx = diagnose()
        ring.set(dx.score, dx.color)
        verdictTag.text = dx.tag.uppercase(Locale.US); verdictTag.setTextColor(dx.color)
        verdictTitle.text = dx.title
        verdictText.text = dx.detail
        if (stepsBox.tag != dx.steps) {
            stepsBox.tag = dx.steps
            stepsBox.removeAllViews()
            dx.steps.forEachIndexed { i, s ->
                val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, if (i > 0) dp(6) else 0, 0, 0) }
                r.addView(text("${i + 1}", 10.5f, bg, 800).apply {
                    gravity = Gravity.CENTER; background = shape(dx.color, 9f)
                }, lp(dp(18), dp(18)))
                r.addView(text(s, 12.5f, textMain, 500, 2).apply { setPadding(dp(9), dp(1), 0, 0); setLineSpacing(0f, 1.1f) }, lp(0, -2, 1f))
                stepsBox.addView(r)
            }
        }

        liveViews?.let { fillLive(it, p) }
        when (tab) {
            "stream" -> rows(streamRows(p))
            "network" -> rows(networkRows())
            "device" -> rows(deviceRows())
            "events" -> events()
        }
    }

    private fun fillLive(v: LiveViews, p: ExoPlayer?) {
        val samples = PlaybackLog.samples()
        val mbps = SpeedMonitor.getMbps()
        val need = requiredMbps()
        v.speed.text = mbpsText(mbps)
        v.speedSub.text = if (need > 0) "needs ~${need.toInt()} Mbps" else "on this stream"
        v.speed.setTextColor(if (need > 0 && mbps > 0 && mbps < need) gold else textMain)
        v.speedLine.set(samples.map { it.mbps })

        val ahead = aheadMs()
        v.ahead.text = String.format(Locale.US, "%.1f s", ahead / 1000.0)
        val trend = samples.takeLast(10).let { if (it.size >= 2) it.last().aheadMs - it.first().aheadMs else 0L }
        v.aheadSub.text = when {
            p == null -> "-"
            ahead < 2000 && p.playWhenReady -> "running low"
            trend < -2500 && ahead < 8000 -> "going down"
            trend > 2500 -> "filling up"
            else -> "steady"
        }
        v.ahead.setTextColor(if (ahead < 2000 && p?.playWhenReady == true) gold else textMain)
        v.aheadLine.set(samples.map { it.aheadMs / 1000f })

        val f = p?.videoFormat
        v.picture.text = f?.takeIf { it.height > 0 }?.let { "${it.height}p" + if (it.frameRate > 0) " · ${it.frameRate.toInt()} fps" else "" } ?: "-"
        v.pictureSub.text = listOfNotNull(
            codec(f?.sampleMimeType),
            f?.bitrate?.takeIf { it > 0 }?.let { mbpsText(it / 1e6) },
            p?.audioFormat?.let { a -> codec(a.sampleMimeType) + if (a.channelCount > 2) " ${a.channelCount}ch" else "" }
        ).joinToString(" · ").ifBlank { "-" }

        val counters = runCatching { p?.videoDecoderCounters }.getOrNull()
        val dropped = counters?.droppedBufferCount ?: 0
        val rendered = counters?.renderedOutputBufferCount ?: 0
        val pct = if (dropped + rendered > 0) dropped * 100.0 / (dropped + rendered) else 0.0
        v.dropped.text = dropped.toString()
        v.dropped.setTextColor(if (pct > 3) gold else textMain)
        v.droppedSub.text = String.format(Locale.US, "%.1f%% · %s", pct, if (SoftwareDecoding.enabled) "software" else "hardware")

        v.timeline.set(samples)
        val buf = samples.count { it.buffering }
        v.timelineSub.text = if (samples.isEmpty()) "recording starts when a channel plays" else if (buf == 0) "no buffering in the last ${samples.size} s" else "buffering $buf s of the last ${samples.size} s"

        v.counters[0].text = PlayerManager.getRebufferCount().toString()
        v.counters[0].setTextColor(if (PlayerManager.getRebufferCount() > 0) gold else textMain)
        v.counters[1].text = duration(PlayerManager.getTotalBufferingMsIncludingActive())
        v.counters[2].text = PlaybackLog.lastStartupMs.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f s", it / 1000.0) } ?: "-"
        v.counters[3].text = PlayerManager.getLastConnectSetupMs().takeIf { it > 0 }?.let { "$it ms" } ?: "-"
    }

    // ------------------------------------------------------------------------------------------------ diagnosis
    private class Dx(val score: Int, val tag: String, val color: Int, val title: String, val detail: String, val steps: List<String>)

    private fun diagnose(): Dx {
        val p = PlayerManager.getExoPlayerOrNull()
        val dev = device
        val need = requiredMbps()
        val test = SpeedTest.last?.takeIf { System.currentTimeMillis() - it.at < 30 * 60_000L }
        val recent = PlaybackLog.bufferingsSince(TEN_MIN)
        val bufferingNow = p?.playbackState == Player.STATE_BUFFERING && p.playWhenReady && PlayerManager.hasEverStartedPlayback()
        val rssi = dev?.wifiRssiDbm
        val weakWifi = dev?.networkType == "WiFi" && rssi != null && rssi < -70
        val counters = runCatching { p?.videoDecoderCounters }.getOrNull()
        val dropped = counters?.droppedBufferCount ?: 0
        val rendered = counters?.renderedOutputBufferCount ?: 0
        val dropPct = if (rendered + dropped > 200) dropped * 100.0 / (dropped + rendered) else 0.0
        val lowRam = dev != null && dev.availableRamMb in 1..250
        val slowNet = test != null && need > 0 && test.downMbps > 0 && test.downMbps < need * 1.3
        val fastNet = test != null && need > 0 && test.downMbps >= need * 2
        val badRoute = test?.serverPing?.let { it.ms < 0 || it.ms > 250 || it.lost > 1 } == true

        var score = 100
        score -= minOf(48, recent * 12)
        if (bufferingNow) score -= 20
        if (PlayerManager.getLastError() != null) score -= 30
        if (weakWifi) score -= 12
        if (slowNet) score -= 20
        if (dropPct > 3) score -= 10
        if (lowRam) score -= 10
        if (p != null && p.isPlaying && aheadMs() < 1500) score -= 8
        score = score.coerceIn(0, 100)
        val color = if (score >= 80) good else if (score >= 55) gold else live
        val tag = if (score >= 80) "Good" else if (score >= 55) "Fair" else "Poor"
        fun dx(title: String, detail: String, vararg steps: String) = Dx(score, tag, color, title, detail, steps.toList())

        if (dev?.networkType == "Offline" || dev?.internetValidated == false) return dx(
            "No internet connection", "This device is not connected to the internet right now, so the channel can't load.",
            "Check the WiFi / network cable of this device", "Restart your router (unplug for 30 seconds)", "Then press Restart here")
        if (p == null) return dx("No channel playing", "Start a channel to see its health. The internet speed test works anyway.",
            "Press Speed test to check your internet")
        if (PlayerManager.getLastError() != null) return when (PlayerManager.getStreamErrorType()) {
            StreamErrorType.SOURCE -> dx("This channel's source is down", "Our server could not get this channel from its source. It is not your internet.",
                "Press Fix with AI — it tests other sources for this channel", "Watch another channel meanwhile")
            StreamErrorType.NETWORK -> dx("Can't reach our server", "The connection to the channel's server failed. Usually the internet line or the provider blocking it.",
                "Press Speed test to check your internet", "Turn on Secure Relay (shield button in the player)", "Restart your router")
            else -> dx("The channel stopped", "The player is reconnecting by itself. If it doesn't come back in a minute, try the fixes below.",
                "Press Restart", "Press Fix with AI", "Try another channel")
        }
        if (recent > 0 || bufferingNow) {
            val what = if (bufferingNow) "Buffering right now" else "$recent buffering${if (recent > 1) "s" else ""} in the last 10 minutes"
            return when {
                slowNet -> dx("Your internet is too slow for this channel", "$what. Your speed test gave ${mbpsText(test!!.downMbps)}; this channel needs about ${need.toInt()} Mbps.",
                    "Stop downloads / other TVs on the same internet", "Use a network cable or move closer to the router", "Ask your internet provider for more speed")
                weakWifi -> dx("Weak WiFi signal", "$what. The WiFi signal here is weak ($rssi dBm, good is above -65), so data arrives in bursts.",
                    "Move the router closer or use a network cable", "Use the 5 GHz WiFi of your router", "Run Speed test to confirm")
                badRoute -> dx("Slow route to our server", "$what. Your internet is fine but the path to the channel's server is slow (${test?.serverPing?.ms} ms).",
                    "Turn on Secure Relay (shield button in the player)", "Restart your router", "Press Fix with AI if it continues")
                fastNet -> dx("The channel's source is slow, not your internet", "$what. Your internet (${mbpsText(test!!.downMbps)}) is more than enough, so the problem is on the channel's side.",
                    "Press Fix with AI — it tests faster sources", "Press Restart", "Try the same channel again in a few minutes")
                dropPct > 3 -> dx("This device is struggling", "$what. It drops ${String.format(Locale.US, "%.1f", dropPct)}% of frames while decoding.",
                    "Switch decoding (chip button in the player)", "Close other apps or use Fresh start in Settings", "Restart the device")
                else -> dx(what, "Let's find out why: the speed test shows if it is your internet or the channel.",
                    "Press Speed test", "If your internet is fast: press Fix with AI", "Press Restart")
            }
        }
        if (dropPct > 3) return dx("The picture is stuttering", "This device drops ${String.format(Locale.US, "%.1f", dropPct)}% of frames while decoding this channel.",
            "Switch decoding (chip button in the player)", "Close other apps or use Fresh start in Settings")
        if (lowRam) return dx("Device memory is low", "Only ${dev?.availableRamMb} MB of memory is free, which can make playback unstable.",
            "Close other apps", "Restart the device")
        if (weakWifi) return dx("Playing well — WiFi is weak", "No buffering so far, but the WiFi signal is weak ($rssi dBm) and could cause buffering later.",
            "Move the router closer or use a network cable")
        if (p.playbackState == Player.STATE_BUFFERING && !PlayerManager.hasEverStartedPlayback()) return dx("Loading the channel…", "The stream is starting.",
            "Wait a few seconds", "If it doesn't start, press Restart")
        return dx("Everything looks good", "Plays smoothly. If it buffers later, open this again — it keeps the last 2 minutes.",
            "Press Speed test to check your internet", "Use Fix with AI if a channel misbehaves")
    }

    // ------------------------------------------------------------------------------------------------ detail tabs
    private fun rows(list: List<Pair<String, String>>) {
        val box = content.findViewWithTag<LinearLayout>("rows") ?: return
        if (box.childCount == 0) {
            val c = card(16)
            list.forEachIndexed { i, _ ->
                val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
                r.addView(text("", 13f, textSub, 600), lp(0, -2, 0.38f))
                r.addView(text("", 13.5f, textMain, 600, 1), lp(0, -2, 0.62f))
                c.addView(r)
                if (i < list.size - 1) c.addView(View(ctx).apply { setBackgroundColor(line) }, lp(-1, dp(1)))
            }
            box.addView(c)
        }
        val c = box.getChildAt(0) as LinearLayout
        var k = 0
        for (i in 0 until c.childCount) {
            val r = c.getChildAt(i) as? LinearLayout ?: continue
            if (k >= list.size) break
            (r.getChildAt(0) as TextView).text = list[k].first
            (r.getChildAt(1) as TextView).text = list[k].second
            k++
        }
    }

    private fun streamRows(p: ExoPlayer?): List<Pair<String, String>> {
        val f = p?.videoFormat
        val a = p?.audioFormat
        val url = PlayerManager.getCurrentUrlOrEmpty()
        return listOf(
            "Channel" to (PlayerState.currentChannel()?.name ?: "-"),
            "Player" to (p?.let { playerState(it) } ?: "-"),
            "Server" to (streamHost()?.let { "${it.first}:${it.second}" } ?: "-"),
            "Format" to when { url.contains(".m3u8") -> "HLS"; url.contains(".ts") -> "MPEG-TS"; url.isBlank() -> "-"; else -> "Live stream" },
            "Resolution" to (f?.takeIf { it.width > 0 }?.let { "${it.width} × ${it.height}" + if (it.frameRate > 0) " at ${it.frameRate.toInt()} fps" else "" } ?: "-"),
            "Video" to listOfNotNull(codec(f?.sampleMimeType), f?.bitrate?.takeIf { it > 0 }?.let { mbpsText(it / 1e6) }).joinToString(" · ").ifBlank { "-" },
            "Audio" to (a?.let { listOfNotNull(codec(it.sampleMimeType), if (it.channelCount > 0) "${it.channelCount} channels" else null, if (it.sampleRate > 0) "${it.sampleRate / 1000} kHz" else null).joinToString(" · ") } ?: "-"),
            "Decoding" to if (SoftwareDecoding.enabled) "Software" else "Hardware",
            "Buffered ahead" to String.format(Locale.US, "%.1f s", aheadMs() / 1000.0),
            "Live reloads" to PlayerManager.getBehindLiveWindowCount().toString(),
            "Last error" to (PlayerManager.getLastError()?.errorCodeName ?: "None")
        )
    }

    private fun networkRows(): List<Pair<String, String>> {
        val dev = device
        val t = SpeedTest.last
        return listOf(
            "Connection" to (dev?.networkType ?: "-"),
            "Internet" to (dev?.internetValidated?.let { if (it) "Working" else "Not working" } ?: "-"),
            "WiFi signal" to (dev?.wifiRssiDbm?.let { "$it dBm  ·  " + when { it >= -55 -> "excellent"; it >= -65 -> "good"; it >= -72 -> "fair"; else -> "weak" } } ?: "Not on WiFi"),
            "WiFi link" to (dev?.wifiLinkSpeedMbps?.let { "$it Mbps" + if (it in 1..60) " (slow link)" else "" } ?: "-"),
            "Stream speed" to mbpsText(SpeedMonitor.getMbps()),
            "Channel needs" to (requiredMbps().takeIf { it > 0 }?.let { "about ${it.toInt()} Mbps" } ?: "-"),
            "Server connect" to (PlayerManager.getLastConnectSetupMs().takeIf { it > 0 }?.let { "$it ms (slowest ${PlayerManager.getSlowestConnectSetupMs()} ms)" } ?: "-"),
            "Slow connects" to "${PlayerManager.getSlowConnectSetupCount()} over 1.5 s",
            "Speed test" to (t?.let { "${mbpsText(it.downMbps)} down · ${mbpsText(it.upMbps)} up · ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(it.at))}" } ?: "Not run yet"),
            "Ping" to (t?.ping?.takeIf { it.ms >= 0 }?.let { "${it.ms} ms · jitter ${it.jitterMs} ms" } ?: "-"),
            "Channel server ping" to (t?.serverPing?.takeIf { it.ms >= 0 }?.let { "${it.ms} ms · jitter ${it.jitterMs} ms" } ?: "-")
        )
    }

    private fun deviceRows(): List<Pair<String, String>> {
        val dev = device
        val pkg = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        return listOfNotNull(
            "Device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "Android" to "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
            "App" to (pkg?.let { "${it.versionName} (build ${if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()})" } ?: "-"),
            "Free memory" to (dev?.let { "${it.availableRamMb} MB of ${it.totalRamMb} MB" + if (it.availableRamMb in 1..250) " (low)" else "" } ?: "-"),
            "Free storage" to (dev?.let { "${it.freeStorageMb} MB" + if (it.freeStorageMb in 1..500) " (low)" else "" } ?: "-"),
            "Screen" to resources.displayMetrics.let { "${it.widthPixels} × ${it.heightPixels}" },
            if (dev?.batteryPresent == true) "Battery" to "${dev.batteryPercent ?: "-"}%" + (dev.batteryTemperatureC?.let { String.format(Locale.US, " · %.0f °C", it) } ?: "") else "Power" to "Mains powered"
        )
    }

    private fun events() {
        val box = content.findViewWithTag<LinearLayout>("rows") ?: return
        val list = PlaybackLog.events().asReversed().take(10)
        if (box.tag == list.size.toString() + (list.firstOrNull()?.at ?: 0)) return
        box.tag = list.size.toString() + (list.firstOrNull()?.at ?: 0)
        box.removeAllViews()
        val c = card(16)
        if (list.isEmpty()) c.addView(text("Nothing recorded yet. Events appear here as channels play.", 13f, textSub, 500, 2))
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        list.forEach { e ->
            val col = when (e.kind) {
                PlaybackLog.Kind.BUFFER, PlaybackLog.Kind.ERROR -> live
                PlaybackLog.Kind.BUFFER_END, PlaybackLog.Kind.RECOVERED, PlaybackLog.Kind.START -> good
                PlaybackLog.Kind.CHANNEL -> accentSoft
                PlaybackLog.Kind.INFO -> cyan
            }
            val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            r.addView(text(fmt.format(Date(e.at)), 12.5f, textSub, 600), lp(dp(76), -2))
            r.addView(View(ctx).apply { background = shape(col, 5f) }, lp(dp(9), dp(9)))
            r.addView(text(if (e.kind == PlaybackLog.Kind.CHANNEL) "Opened ${e.text}" else e.text, 13f, textMain, 600, 1).apply { setPadding(dp(12), 0, 0, 0) }, lp(0, -2, 1f))
            c.addView(r)
        }
        box.addView(c)
    }

    // ------------------------------------------------------------------------------------------------ logs tab
    // the app's own log on the screen, the last crash, and "Send to support" (a short code staff open in the console)
    private var logLines: List<com.network24.player.core.diagnostics.AppLogs.Line> = emptyList()
    private var logFilter = "all"

    private fun buildLogs(): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        val view = text("Reading the app log…", 11.5f, textSub, 500, 100000).apply {
            typeface = android.graphics.Typeface.MONOSPACE; setLineSpacing(0f, 1.12f); setTextIsSelectable(false)
        }
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = true; isFocusable = true; isFocusableInTouchMode = true; addView(view)
            background = shape(Color.parseColor("#0D0F14"), 14f); setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        focusable(scroll, 14f, 1.0f, accent)
        val chips = LinkedHashMap<String, TextView>()
        fun paint() = chips.forEach { (k, v) -> v.setTextColor(if (k == logFilter) bg else textSub); v.background = if (k == logFilter) shape(Color.WHITE, 16f) else shape(0x14FFFFFF, 16f) }
        fun render() {
            val crash = com.network24.player.core.diagnostics.AppLogs.lastCrash(ctx)
            val pick = logLines.filter { l ->
                when (logFilter) {
                    "err" -> l.level in "EFW"
                    "player" -> Regex("ExoPlayer|Media|Codec|Player|PesReader|Audio|Video", RegexOption.IGNORE_CASE).containsMatchIn(l.text)
                    "net" -> Regex("N24Api|OkHttp|Socket|SSL|http|Remote|Firestore|DNS", RegexOption.IGNORE_CASE).containsMatchIn(l.text)
                    else -> true
                }
            }.takeLast(400)
            val sb = android.text.SpannableStringBuilder()
            if (crash != null && logFilter != "player") {
                val s = sb.length; sb.append("LAST CRASH\n").append(crash.take(3000)).append("\n\n")
                sb.setSpan(android.text.style.ForegroundColorSpan(live), s, sb.length, 0)
            }
            if (pick.isEmpty()) sb.append(if (logLines.isEmpty()) "No log lines yet." else "Nothing for this filter.")
            pick.forEach { l ->
                val s = sb.length; sb.append(l.text).append('\n')
                val col = when (l.level) { 'E', 'F' -> live; 'W' -> gold; 'D', 'V' -> textSub; else -> Color.parseColor("#C9CDD6") }
                sb.setSpan(android.text.style.ForegroundColorSpan(col), s, sb.length, 0)
            }
            view.text = sb
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        listOf("all" to "All", "err" to "Errors", "player" to "Player", "net" to "Network").forEach { (k, n) ->
            val c = text(n, 12.5f, textSub, 700).apply {
                gravity = Gravity.CENTER; setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener { logFilter = k; paint(); render() }
                focusable(this, 16f, 1.05f, accent)
            }
            chips[k] = c; bar.addView(c, lp(-2, -2).apply { marginEnd = dp(6) })
        }
        bar.addView(View(ctx), lp(0, 1, 1f))
        val status = text("", 12f, accentSoft, 700)
        bar.addView(status, lp(-2, -2).apply { marginEnd = dp(10) })
        var sending = false
        bar.addView(pill("Send to support", primary = true) {
            if (!sending) {
            sending = true; status.text = "Sending…"
            lifecycleScope.launch {
                runCatching { com.network24.player.core.diagnostics.AppLogs.send(ctx, "viewer (Stream health)") }
                    .onSuccess { code -> status.text = "Sent  ·  code $code"; android.widget.Toast.makeText(ctx, "Logs sent. Tell support this code: $code", android.widget.Toast.LENGTH_LONG).show() }
                    .onFailure { status.text = "Not sent - " + (it.message ?: "try again") }
                sending = false
            }
            }
        }, lp(-2, -2))
        box.addView(bar, lp(-1, -2))
        box.addView(text("This app's own log - what support needs when something does not work. Passwords and tokens are hidden.", 11.5f, textSub, 500, 2).apply { setPadding(dp(2), dp(8), 0, dp(8)) })
        box.addView(scroll, lp(-1, 0, 1f))
        paint()
        lifecycleScope.launch { logLines = com.network24.player.core.diagnostics.AppLogs.read(1500); render() }
        return box
    }

    // ------------------------------------------------------------------------------------------------ helpers
    private fun aheadMs(): Long {
        val p = PlayerManager.getExoPlayerOrNull() ?: return 0L
        return (p.bufferedPosition - p.currentPosition).coerceAtLeast(0L)
    }

    private fun requiredMbps(): Double {
        val f = PlayerManager.getExoPlayerOrNull()?.videoFormat ?: return 0.0
        val byHeight = when { f.height >= 2160 -> 30.0; f.height >= 1080 -> 12.0; f.height >= 720 -> 6.0; f.height > 0 -> 3.0; else -> 0.0 }
        val byBitrate = if (f.bitrate > 0) f.bitrate / 1e6 * 1.5 else 0.0
        return maxOf(byHeight, byBitrate)
    }

    private fun streamHost(): Pair<String, Int>? = runCatching {
        val u = android.net.Uri.parse(PlayerManager.getCurrentUrlOrEmpty())
        val h = u.host ?: return null
        h to (if (u.port > 0) u.port else if (u.scheme == "https") 443 else 80)
    }.getOrNull()

    private fun playerState(p: Player) = when (p.playbackState) {
        Player.STATE_BUFFERING -> "Buffering"
        Player.STATE_READY -> if (p.isPlaying) "Playing" else "Paused"
        Player.STATE_ENDED -> "Ended"
        else -> "Stopped"
    }

    private fun codec(mime: String?) = when {
        mime == null -> null
        mime.contains("avc") -> "H.264"
        mime.contains("hevc") -> "H.265"
        mime.contains("mp4a") -> "AAC"
        mime.contains("ac3") && mime.contains("e") -> "Dolby Digital+"
        mime.contains("ac3") -> "Dolby Digital"
        mime.contains("mpeg") -> "MPEG"
        else -> mime.substringAfter('/').uppercase(Locale.US)
    }

    private fun mbpsText(v: Double) = when {
        v <= 0.0 -> "-"
        v >= 100 -> String.format(Locale.US, "%.0f Mbps", v)
        else -> String.format(Locale.US, "%.1f Mbps", v)
    }

    private fun duration(ms: Long): String {
        if (ms <= 0L) return "0 s"
        val s = ms / 1000
        return if (s >= 60) "${s / 60} min ${s % 60} s" else String.format(Locale.US, "%.1f s", ms / 1000.0)
    }

    // ------------------------------------------------------------------------------------------------ drawn views
    /** Score ring: a gradient arc around the number. */
    private inner class HealthRing(c: Context) : View(c) {
        private var score = 0f
        private var shown = 0f
        private var color = good
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0x1FFFFFFF }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textMain; textAlign = Paint.Align.CENTER; typeface = HomeFont.of(c, 800) }
        private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textSub; textAlign = Paint.Align.CENTER; typeface = HomeFont.of(c, 700); letterSpacing = 0.1f }
        fun set(s: Int, col: Int) {
            score = s.toFloat(); color = col
            animateTo()
        }
        private fun animateTo() {
            val from = shown
            android.animation.ValueAnimator.ofFloat(from, score).apply {
                duration = 500; addUpdateListener { shown = it.animatedValue as Float; invalidate() }; start()
            }
        }
        override fun onDraw(canvas: Canvas) {
            val w = dpf(8f)
            track.strokeWidth = w; arc.strokeWidth = w
            val r = RectF(w, w, width - w, height - w)
            canvas.drawArc(r, 135f, 270f, false, track)
            arc.shader = SweepGradient(width / 2f, height / 2f, intArrayOf(accent, color, color), floatArrayOf(0f, 0.5f, 1f))
            canvas.save(); canvas.rotate(90f, width / 2f, height / 2f)
            canvas.drawArc(r, 45f, 270f * shown / 100f, false, arc)
            canvas.restore()
            num.textSize = dpf(28f); small.textSize = dpf(9f)
            canvas.drawText(shown.toInt().toString(), width / 2f, height / 2f + dpf(8f), num)
            canvas.drawText("HEALTH", width / 2f, height - dpf(10f), small)
        }
    }

    /** Small line graph with a soft fill. */
    private inner class Sparkline(c: Context, private val col: Int) : View(c) {
        private var values: List<Float> = emptyList()
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dpf(2f); color = col; strokeJoin = Paint.Join.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        fun set(v: List<Float>) { values = v.takeLast(60); invalidate() }
        override fun onDraw(canvas: Canvas) {
            if (values.size < 2) {
                stroke.alpha = 60; canvas.drawLine(0f, height - dpf(2f), width.toFloat(), height - dpf(2f), stroke); stroke.alpha = 255; return
            }
            val max = (values.maxOrNull() ?: 1f).coerceAtLeast(0.1f) * 1.15f
            val step = width.toFloat() / (PlaybackLog.MAX_SAMPLES / 2 - 1)
            val x0 = width - step * (values.size - 1)
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = x0 + i * step; val y = height - dpf(2f) - (v / max) * (height - dpf(4f))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val area = Path(path).apply { lineTo(width.toFloat(), height.toFloat()); lineTo(x0, height.toFloat()); close() }
            fill.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), (col and 0x00FFFFFF) or 0x55000000, col and 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawPath(area, fill)
            canvas.drawPath(path, stroke)
        }
    }

    /** Two minutes, one bar per second: violet-green playing, red buffering, empty before recording. */
    private inner class Timeline(c: Context) : View(c) {
        private var samples: List<PlaybackLog.Sample> = emptyList()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun set(s: List<PlaybackLog.Sample>) { samples = s; invalidate() }
        override fun onDraw(canvas: Canvas) {
            val n = PlaybackLog.MAX_SAMPLES
            val gap = dpf(1.5f)
            val bw = (width - gap * (n - 1)) / n
            val offset = n - samples.size
            for (i in 0 until n) {
                val s = samples.getOrNull(i - offset)
                paint.color = when { s == null -> 0x14FFFFFF; s.buffering -> live; else -> good }
                if (s != null && !s.buffering) paint.alpha = 200
                val x = i * (bw + gap)
                canvas.drawRoundRect(RectF(x, 0f, x + bw, height.toFloat()), dpf(2f), dpf(2f), paint)
            }
        }
    }

    /** Speed test dial: 240° arc on a log scale up to 500 Mbps. */
    private inner class Gauge(c: Context) : View(c) {
        private var value = 0f
        private var shown = 0f
        private var col = cyan
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0x1FFFFFFF }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textMain; textAlign = Paint.Align.CENTER; typeface = HomeFont.of(c, 800) }
        private val unit = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textSub; textAlign = Paint.Align.CENTER; typeface = HomeFont.of(c, 700) }
        private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textSub; textAlign = Paint.Align.CENTER; typeface = HomeFont.of(c, 600) }
        fun set(v: Float, color: Int) {
            value = v; col = color
            val from = shown
            android.animation.ValueAnimator.ofFloat(from, v).apply { duration = 240; addUpdateListener { shown = it.animatedValue as Float; invalidate() }; start() }
        }
        private fun frac(v: Float) = (kotlin.math.ln(1f + v.coerceAtLeast(0f)) / kotlin.math.ln(501f)).coerceIn(0f, 1f)
        override fun onDraw(canvas: Canvas) {
            val w = dpf(11f)
            track.strokeWidth = w; arc.strokeWidth = w
            val size = minOf(width.toFloat(), height * 1.25f)
            val cx = width / 2f
            val r = RectF(cx - size / 2 + w, w, cx + size / 2 - w, size - w)
            canvas.drawArc(r, 150f, 240f, false, track)
            arc.shader = LinearGradient(r.left, 0f, r.right, 0f, accent, col, Shader.TileMode.CLAMP)
            canvas.drawArc(r, 150f, 240f * frac(shown), false, arc)
            tick.textSize = dpf(9f)
            listOf(1f, 10f, 100f, 500f).forEach { t ->
                val a = Math.toRadians((150 + 240 * frac(t)).toDouble())
                val rr = r.width() / 2 - w * 1.9f
                canvas.drawText(if (t >= 1f) t.toInt().toString() else "0", r.centerX() + (rr * kotlin.math.cos(a)).toFloat(), r.centerY() + (rr * kotlin.math.sin(a)).toFloat() + dpf(3f), tick)
            }
            num.textSize = dpf(30f); unit.textSize = dpf(11f)
            canvas.drawText(if (shown <= 0f) "–" else if (shown >= 100) shown.toInt().toString() else String.format(Locale.US, "%.1f", shown), r.centerX(), r.centerY() + dpf(10f), num)
            canvas.drawText("Mbps", r.centerX(), r.centerY() + dpf(28f), unit)
        }
    }
}
