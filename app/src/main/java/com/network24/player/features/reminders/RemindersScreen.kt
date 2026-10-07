package com.network24.player.features.reminders

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import coil.load
import com.network24.player.R
import com.network24.player.core.api.Web24Api
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.entity.ChannelEntity
import com.network24.player.core.database.entity.EpgEntity
import com.network24.player.features.dashboard.home.HomeFont
import com.network24.player.features.discover.ChannelLauncher
import com.network24.player.features.discover.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reminders in the app's look: the next one as a billboard (the show's artwork, a live countdown, Watch now once
 * it starts), then every other one by day (Today, Tomorrow, ...) as picture tiles with the time, the show, the
 * channel and what the guide says about it. OK on a tile = Watch the channel now / Remove; Clear all.
 */
class RemindersActivity : BaseActivity() {
    private val uiScale by lazy { resources.displayMetrics.let { m -> minOf(1f, m.widthPixels / m.density / 960f, m.heightPixels / m.density / 400f) } }
    private val d by lazy { resources.displayMetrics.density * uiScale }
    private fun dp(v: Int) = (v * d).toInt()
    private fun dpf(v: Float) = v * d
    private val screenW by lazy { resources.displayMetrics.widthPixels }
    private val screenH by lazy { resources.displayMetrics.heightPixels }
    private val db by lazy { DatabaseProvider.get(this) }
    private val handler = Handler(Looper.getMainLooper())

    private val bg = Color.parseColor("#08090C")
    private val surface = Color.parseColor("#14161B")
    private val line = Color.parseColor("#1FFFFFFF")
    private val textMain = Color.parseColor("#F2F3F5")
    private val textSub = Color.parseColor("#9BA1AD")
    private val accent = Color.parseColor("#7C5CFF")
    private val accentSoft = Color.parseColor("#A894FF")
    private val live = Color.parseColor("#E5484D")
    private val gold = Color.parseColor("#F5B841")

    private class Item(val r: Reminders.Item, val ch: ChannelEntity?, val p: EpgEntity?)
    private var items: List<Item> = emptyList()
    private val art = HashMap<String, String>()

    private lateinit var backdrop: ImageView
    private lateinit var heroBox: LinearLayout
    private lateinit var heroTag: TextView
    private lateinit var heroLogo: ImageView
    private lateinit var heroChannel: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroWhen: TextView
    private lateinit var heroDesc: TextView
    private lateinit var btnWatch: LinearLayout
    private lateinit var btnWatchText: TextView
    private lateinit var btnRemove: View
    private lateinit var btnClear: View
    private lateinit var btnBack: View
    private lateinit var days: LinearLayout
    private lateinit var empty: LinearLayout
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
    }

    override fun onResume() { super.onResume(); load(); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }

    // ------------------------------------------------------------------------------------------------ building blocks
    private fun text(s: CharSequence, size: Float, color: Int = textMain, weight: Int = 500, lines: Int = 1) = TextView(this).apply {
        text = s; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * d); setTextColor(color); typeface = HomeFont.of(this@RemindersActivity, weight)
        maxLines = lines; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
    }

    private fun shape(fill: Int, radius: Float, stroke: Int = 0) = GradientDrawable().apply { setColor(fill); cornerRadius = dpf(radius); if (stroke != 0) setStroke(dp(1), stroke) }

    private fun focusable(v: View, radius: Float, scale: Float = 1.05f, ring: Int = Color.WHITE) {
        v.isFocusable = true; v.isClickable = true
        val stroke = dp(3)
        val r = GradientDrawable().apply { cornerRadius = (dpf(radius) - stroke / 2f).coerceAtLeast(0f); setStroke(stroke, ring); setColor(Color.TRANSPARENT) }
        v.setOnFocusChangeListener { view, has ->
            view.foreground = if (has) r else null
            view.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).translationZ(if (has) dpf(10f) else 0f).setDuration(130).start()
            // the page scrolls far enough for the whole (slightly bigger) card and its ring to show
            if (has) view.post { view.requestRectangleOnScreen(android.graphics.Rect(-dp(24), -dp(24), view.width + dp(24), view.height + dp(24)), false) }
        }
    }

    private fun pill(label: String, primary: Boolean, icon: Int?, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(20), dp(11), dp(22), dp(11))
        background = shape(if (primary) Color.WHITE else 0x26FFFFFF, 12f)
        if (icon != null) addView(ImageView(this@RemindersActivity).apply { setImageResource(icon); setColorFilter(if (primary) bg else textMain) }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
        addView(text(label, 15f, if (primary) bg else textMain, 700))
        setOnClickListener { onClick() }
        focusable(this, 12f, 1.06f, if (primary) accent else Color.WHITE)
    }

    private val prefix = Regex("^[A-Z]{2,3}\\s*\\|\\s*")
    private fun clean(n: String?) = n.orEmpty().replace(prefix, "").trim()

    private fun until(ms: Long): String {
        if (ms <= 0) return "On now"
        val m = ms / 60_000
        return when { m < 1 -> "Starts in under a minute"; m < 60 -> "Starts in $m min"; m < 24 * 60 -> "Starts in ${m / 60} h ${m % 60} min".replace(" 0 min", ""); else -> "Starts ${Fmt.day(System.currentTimeMillis() + ms)}" }
    }

    // ------------------------------------------------------------------------------------------------ page
    private fun build() {
        val root = FrameLayout(this).apply { setBackgroundColor(bg) }
        val heroH = (screenH * 0.52f).toInt()
        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        root.addView(backdrop, FrameLayout.LayoutParams((screenW * 0.66f).toInt(), heroH, Gravity.TOP or Gravity.END))
        root.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(bg, 0xD908090C.toInt(), 0x4008090C, 0x0008090C)) },
            FrameLayout.LayoutParams((screenW * 0.66f).toInt(), heroH, Gravity.TOP or Gravity.END))
        root.addView(View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x8008090C.toInt(), 0x0008090C, bg)) },
            FrameLayout.LayoutParams(-1, heroH + 2))

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; clipChildren = false }
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(48), dp(24), dp(48), dp(30)); clipChildren = false; clipToPadding = false }
        scroll.addView(page)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        btnBack = ImageView(this).apply {
            setImageResource(R.drawable.ic_back); setColorFilter(textMain); setPadding(dp(10), dp(10), dp(10), dp(10)); contentDescription = "Back"
            background = shape(0x1AFFFFFF, 21f); setOnClickListener { finish() }; focusable(this, 21f, 1.08f)
        }
        head.addView(btnBack, LinearLayout.LayoutParams(dp(42), dp(42)))
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
        words.addView(text("Reminders", 22f, textMain, 800))
        words.addView(text("We remind you 2 minutes before each one starts", 12f, textSub, 600).apply { setPadding(0, dp(4), 0, 0) })
        head.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        btnClear = pill("Clear all", false, null) { clearAll() }
        head.addView(btnClear)
        val menu = ImageView(this).apply {
            setImageResource(R.drawable.ic_more_vert); setColorFilter(textMain); setPadding(dp(10), dp(10), dp(10), dp(10)); contentDescription = "Menu"
            background = shape(0x1AFFFFFF, 21f); focusable(this, 21f, 1.08f)
        }
        head.addView(menu, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(10) })
        page.addView(head)

        // billboard: the next reminder
        heroBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM; minimumHeight = heroH - dp(110); visibility = View.GONE; clipChildren = false; clipToPadding = false }
        val tagRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heroTag = text("", 11f, Color.WHITE, 800).apply { letterSpacing = 0.12f; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        tagRow.addView(heroTag)
        heroLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        tagRow.addView(heroLogo, LinearLayout.LayoutParams(dp(48), dp(26)).apply { marginStart = dp(12) })
        heroChannel = text("", 12f, Color.parseColor("#D9F2F3F5"), 700).apply { letterSpacing = 0.12f; setPadding(dp(10), 0, 0, 0) }
        tagRow.addView(heroChannel)
        heroBox.addView(tagRow)
        heroTitle = text("", 34f, textMain, 800, lines = 2).apply { letterSpacing = -0.02f; setPadding(0, dp(12), 0, 0); setShadowLayer(dpf(10f), 0f, dpf(2f), 0x99000000.toInt()) }
        heroBox.addView(heroTitle, LinearLayout.LayoutParams((screenW * 0.55f).toInt(), -2))
        heroWhen = text("", 20f, gold, 800).apply { setPadding(0, dp(10), 0, 0); fontFeatureSettings = "tnum" }
        heroBox.addView(heroWhen)
        heroDesc = text("", 14f, Color.parseColor("#CCF2F3F5"), 500, lines = 2).apply { setPadding(0, dp(8), 0, 0); setLineSpacing(0f, 1.15f) }
        heroBox.addView(heroDesc, LinearLayout.LayoutParams((screenW * 0.5f).toInt(), -2))
        // no clipping: a focused button grows a little and its ring sits just outside it
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(18), 0, dp(6)); clipChildren = false; clipToPadding = false }
        btnWatch = pill("Watch channel", true, R.drawable.ic_play) { items.firstOrNull()?.let { watch(it) } }
        btnWatchText = btnWatch.getChildAt(1) as TextView
        if (android.os.Build.VERSION.SDK_INT >= 26) btnWatch.isFocusedByDefault = true
        btns.addView(btnWatch)
        btnRemove = pill("Remove reminder", false, null) { items.firstOrNull()?.let { remove(it) } }
        btns.addView(btnRemove, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        heroBox.addView(btns)
        page.addView(heroBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        days = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        page.addView(days, LinearLayout.LayoutParams(-1, -2))

        // nothing set: where to find something
        empty = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, dp(70), 0, 0); visibility = View.GONE }
        empty.addView(text("⏰", 46f, gold, 700).apply { gravity = Gravity.CENTER })
        empty.addView(text("No reminders yet", 22f, textMain, 800).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0) })
        empty.addView(text("Pick a show or a game that has not started and select Remind me. We let you know 2 minutes before.", 14f, textSub, 600, lines = 3).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(20)) },
            LinearLayout.LayoutParams((screenW * 0.5f).toInt(), -2))
        val go = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        go.addView(pill("TV Guide", true, null) { startActivity(Intent(this, com.network24.player.features.guide.TvGuideActivity::class.java)) })
        go.addView(pill("Sports", false, null) { startActivity(Intent(this, com.network24.player.features.sports.SportsActivity::class.java)) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        go.addView(pill("Search", false, null) { com.network24.player.features.search.SearchOverlay.show(this) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        empty.addView(go)
        page.addView(empty, LinearLayout.LayoutParams(-1, -2))

        setContentView(setupGlobalRightDrawer(root, menu))
    }

    // ------------------------------------------------------------------------------------------------ data
    private fun load() = lifecycleScope.launch {
        val list = Reminders.all(this@RemindersActivity)
        items = withContext(Dispatchers.IO) {
            val chans = runCatching { db.channelDao().getByStreamIds(list.map { it.streamId }) }.getOrDefault(emptyList()).associateBy { it.streamId }
            // what the guide says about each one (description, end time)
            val ids = chans.values.mapNotNull { it.epgChannelId?.takeIf { e -> e.isNotBlank() } }.distinct()
            val from = (list.minOfOrNull { it.start } ?: 0L) - 60_000; val to = (list.maxOfOrNull { it.start } ?: 0L) + 60_000
            val progs = if (ids.isEmpty() || list.isEmpty()) emptyList() else runCatching { db.epgDao().getByEpgChannelIdsChunked(ids, from, to + 3_600_000) }.getOrDefault(emptyList())
            list.map { r ->
                val ch = chans[r.streamId]
                Item(r, ch, progs.firstOrNull { it.epgChannelId == ch?.epgChannelId && kotlin.math.abs((it.startTimestamp ?: 0) - r.start) < 60_000 })
            }
        }
        render()
        items.map { it.r.title }.filter { it.isNotBlank() && it !in art }.distinct().take(24).let { if (it.isNotEmpty()) loadArt(it) }
    }

    private fun render() {
        val has = items.isNotEmpty()
        heroBox.visibility = if (has) View.VISIBLE else View.GONE
        btnClear.visibility = if (has) View.VISIBLE else View.GONE
        empty.visibility = if (has) View.GONE else View.VISIBLE
        if (!has) backdrop.animate().alpha(0f).start()
        items.firstOrNull()?.let { showHero(it) }
        val focusKey = (currentFocus?.tag as? Item)?.r?.let { "${it.streamId}@${it.start}" }
        days.removeAllViews()
        items.drop(1).groupBy { Fmt.day(it.r.start) }.forEach { (day, list) -> days.addView(dayRow(day, list)) }
        if (focusKey != null) days.findViewWithTag<View>(focusKey)?.requestFocus()
        if (has && !started) { started = true; btnWatch.postDelayed({ btnWatch.requestFocus() }, 150) }
    }

    private fun showHero(it: Item) {
        val now = System.currentTimeMillis()
        val on = it.r.start <= now
        heroTag.text = if (on) "ON NOW" else "NEXT REMINDER"
        heroTag.background = shape(if (on) live else accent, 4f)
        heroLogo.load(it.ch?.icon?.takeIf { s -> s.isNotBlank() })
        heroChannel.text = clean(it.r.channel.ifBlank { it.ch?.name }).uppercase()
        heroTitle.text = it.r.title
        heroWhen.text = until(it.r.start - now) + "  ·  ${Fmt.day(it.r.start)} ${Fmt.clock(it.r.start)}"
        heroWhen.setTextColor(if (on) live else gold)
        heroDesc.text = it.p?.description?.takeIf { d -> d.isNotBlank() } ?: (it.p?.stopTimestamp?.let { e -> "${Fmt.clock(it.r.start)} – ${Fmt.clock(e)}" } ?: "")
        btnWatchText.text = if (on || it.r.start - now < 10 * 60_000) "Watch now" else "Watch channel"
        art[it.r.title]?.let { u -> backdrop.load(u) { crossfade(true); listener(onSuccess = { _, _ -> backdrop.animate().alpha(0.9f).setDuration(400).start() }) } }
    }

    private fun dayRow(day: String, list: List<Item>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
        addView(text("$day  ·  ${list.size}", 18f, textMain, 800).apply { setPadding(0, dp(24), 0, dp(4)) })
        val row = LinearLayout(this@RemindersActivity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; setPadding(dp(8), dp(10), dp(40), dp(10)) }
        list.forEach { row.addView(tile(it), LinearLayout.LayoutParams(dp(300), -2).apply { marginEnd = dp(14) }) }
        addView(HorizontalScrollView(this@RemindersActivity).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(row) },
            LinearLayout.LayoutParams(-1, -2).apply { marginStart = -dp(8) })
    }

    private fun tile(it: Item) = FrameLayout(this).apply {
        tag = "${it.r.streamId}@${it.r.start}"
        val picH = dp(300) * 9 / 16
        background = shape(surface, 14f, line); clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        val pic = FrameLayout(this@RemindersActivity)
        val img = ImageView(this@RemindersActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.parseColor("#1A1C23")) }
        pic.addView(img, FrameLayout.LayoutParams(-1, -1))
        pic.addView(View(this@RemindersActivity).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x3308090C, 0xCC08090C.toInt())) }, FrameLayout.LayoutParams(-1, -1))
        pic.addView(text(Fmt.clock(it.r.start), 22f, Color.WHITE, 800).apply { fontFeatureSettings = "tnum"; setShadowLayer(dpf(8f), 0f, 0f, 0xAA000000.toInt()) },
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(12), 0, 0, dp(10)) })
        val logo = ImageView(this@RemindersActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        pic.addView(logo, FrameLayout.LayoutParams(dp(56), dp(32), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(10), dp(10), 0) })
        pic.addView(text("⏰", 13f, gold, 700).apply { setPadding(dp(6), dp(3), dp(6), dp(3)); background = shape(0x99000000.toInt(), 6f) },
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(10), dp(10), 0, 0) })
        addView(pic, FrameLayout.LayoutParams(-1, picH))
        val info = LinearLayout(this@RemindersActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(12)) }
        info.addView(text(it.r.title, 15f, textMain, 700))
        info.addView(text(clean(it.r.channel.ifBlank { it.ch?.name }) + (it.p?.stopTimestamp?.let { e -> "  ·  ${(e - it.r.start) / 60_000} min" } ?: ""), 12f, textSub, 600).apply { setPadding(0, dp(4), 0, 0) })
        addView(info, FrameLayout.LayoutParams(-1, -2).apply { topMargin = picH })
        val u = art[it.r.title]
        if (u != null) { img.load(u) { crossfade(true) }; logo.load(it.ch?.icon) }
        else { img.scaleType = ImageView.ScaleType.FIT_CENTER; img.setPadding(dp(70), dp(30), dp(70), dp(30)); img.load(it.ch?.icon?.takeIf { s -> s.isNotBlank() }) }
        focusable(this, 14f, 1.05f)
        setOnClickListener { _ -> options(it) }
        setOnLongClickListener { _ -> remove(it); true }
    }

    private fun loadArt(titles: List<String>) = lifecycleScope.launch {
        val r = runCatching { Web24Api(this@RemindersActivity).support("art", "titles" to titles.joinToString("\n")) }.getOrNull()
        val a = r?.optJSONObject("art") ?: return@launch
        titles.forEach { t -> a.optJSONObject(t)?.optString("backdrop")?.takeIf { it.isNotBlank() }?.let { art[t] = it } }
        render()
    }

    // ------------------------------------------------------------------------------------------------ actions
    private fun options(it: Item) {
        val opts = arrayOf("Watch ${clean(it.r.channel)} now", "Remove reminder")
        AlertDialog.Builder(this).setTitle(it.r.title).setItems(opts) { _, w -> if (w == 0) watch(it) else remove(it) }.show()
    }

    private fun watch(it: Item) {
        val ch = it.ch ?: return
        ChannelLauncher.play(this, listOf(ch), ch)
    }

    private fun remove(it: Item) {
        Reminders.toggle(this, it.r.streamId, it.r.start, it.r.title, it.r.channel)
        load()
    }

    private fun clearAll() {
        showConfirmDialog("Clear all reminders?", "You will not be reminded about any of them.", "Clear all", onPositive = { Reminders.clear(this); load() })
    }

    // the countdown on the billboard moves every 30 s
    private val tick = object : Runnable { override fun run() { items.firstOrNull()?.let { showHero(it) }; handler.postDelayed(this, 30_000) } }

    /** Watch now DOWN = the first tile, first tiles UP = Watch now, Watch now UP = Clear all; Menu on a tile = options. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val f = currentFocus
            val onTile = (f?.tag as? String)?.contains("@") == true
            when {
                (f === btnWatch || f === btnRemove) && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> { firstTile()?.requestFocus(); return true }
                (f === btnWatch || f === btnRemove) && event.keyCode == KeyEvent.KEYCODE_DPAD_UP -> { btnClear.requestFocus(); return true }
                f === btnClear && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && heroBox.isShown -> { btnWatch.requestFocus(); return true }
                onTile && event.keyCode == KeyEvent.KEYCODE_DPAD_UP && isFirstRow(f!!) -> { btnWatch.requestFocus(); return true }
                onTile && event.keyCode == KeyEvent.KEYCODE_MENU -> { items.firstOrNull { "${it.r.streamId}@${it.r.start}" == f!!.tag }?.let { options(it) }; return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun firstTile(): View? = ((days.getChildAt(0) as? LinearLayout)?.getChildAt(1) as? HorizontalScrollView)?.let { (it.getChildAt(0) as LinearLayout).getChildAt(0) }
    private fun isFirstRow(v: View): Boolean = v.parent === (((days.getChildAt(0) as? LinearLayout)?.getChildAt(1) as? HorizontalScrollView)?.getChildAt(0))

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}

/**
 * Reminders inside the app: Fire TV does not always show phone-style notifications, so while the app is open a
 * card pops up on any screen 2 minutes before a reminder starts: "Starting in 2 min · Watch now".
 */
object ReminderAlert {
    private val shown = HashSet<String>()

    fun attach(activity: AppCompatActivity) {
        var job: Job? = null
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                job = activity.lifecycleScope.launch { while (isActive) { check(activity); delay(20_000) } }
            }
            override fun onPause(owner: LifecycleOwner) { job?.cancel() }
        })
    }

    private suspend fun check(activity: AppCompatActivity) {
        val now = System.currentTimeMillis()
        val due = Reminders.all(activity).firstOrNull { it.start - now in -60_000..150_000 && "${it.streamId}@${it.start}" !in shown } ?: return
        shown += "${due.streamId}@${due.start}"
        if (activity.isFinishing || activity.isDestroyed) return
        val ch = withContext(Dispatchers.IO) { runCatching { DatabaseProvider.get(activity).channelDao().getByStreamIds(listOf(due.streamId)).firstOrNull() }.getOrNull() }
        val mins = ((due.start - now) / 60_000).coerceAtLeast(0)
        AlertDialog.Builder(activity).setTitle(if (mins <= 0) "Starting now" else "Starting in $mins min")
            .setMessage("${due.title}\non ${due.channel}")
            .setPositiveButton("Watch now") { _, _ -> ch?.let { ChannelLauncher.play(activity, listOf(it), it) } }
            .setNegativeButton("Not now", null).show()
    }
}
