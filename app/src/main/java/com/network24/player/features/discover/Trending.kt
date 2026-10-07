package com.network24.player.features.discover

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.network24.player.R
import com.network24.player.core.api.Web24Api
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.entity.CategoryType
import com.network24.player.core.database.entity.ChannelEntity
import com.network24.player.core.database.entity.EpgEntity
import com.network24.player.core.parental.ParentalLock
import com.network24.player.features.dashboard.home.HomeFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Trending now, Top-10 style: the channel most viewers watch as a billboard (its show's artwork, "214 watching
 * now", what is on, Watch now), then the rest as picture tiles with a big rank number. Refreshes every minute
 * without moving the remote's focus. Locked and adult categories are never listed.
 */
class TrendingActivity : BaseActivity() {
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
    private val live = Color.parseColor("#E5484D")

    private class Item(val rank: Int, val ch: ChannelEntity, val viewers: Int, val cat: String, val now: EpgEntity?)
    private var items: List<Item> = emptyList()
    private val art = HashMap<String, String>()

    private lateinit var backdrop: ImageView
    private lateinit var heroLogo: ImageView
    private lateinit var heroChannel: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroViewers: TextView
    private lateinit var heroBar: ProgressBar
    private lateinit var heroBox: View
    private lateinit var btnWatch: View
    private lateinit var btnBack: View
    private lateinit var gridTitle: TextView
    private lateinit var grid: RecyclerView
    private lateinit var status: TextView
    private val adapter = TileAdapter()
    private val cols by lazy { if (screenW / resources.displayMetrics.density / uiScale >= 900) 4 else 3 }
    private var startFocused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
        load()
    }

    // ------------------------------------------------------------------------------------------------ building blocks
    private fun text(s: CharSequence, size: Float, color: Int = textMain, weight: Int = 500, lines: Int = 1) = TextView(this).apply {
        text = s; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * d); setTextColor(color); typeface = HomeFont.of(this@TrendingActivity, weight)
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

    /** The eye icon in front of a viewer count, tinted like its text. */
    private fun eye(t: TextView, sizeDp: Int) {
        val ic = getDrawable(R.drawable.ic_eye)?.mutate() ?: return
        ic.setTint(t.currentTextColor); ic.setBounds(0, 0, dp(sizeDp), dp(sizeDp))
        t.setCompoundDrawables(ic, null, null, null); t.compoundDrawablePadding = dp(6); t.gravity = Gravity.CENTER_VERTICAL
    }

    private val prefix = Regex("^[A-Z]{2,3}\\s*\\|\\s*")
    private fun clean(n: String?) = n.orEmpty().replace(prefix, "").trim()
    private val marks = Regex("[ʰ-˿ᴀ-ᶿ⁰-₟]+")
    private fun cleanTitle(t: String?) = t.orEmpty().replace(marks, "").replace(Regex("\\s+"), " ").trim()
    private fun niceName(n: String) = n.trim().split(" ").joinToString(" ") { w -> if (w.length <= 3 && w.all { it.isUpperCase() || !it.isLetter() }) w else w.lowercase().replaceFirstChar { it.uppercase() } }
    private fun progress(p: EpgEntity?, now: Long): Int {
        val s = p?.startTimestamp ?: return -1; val e = p.stopTimestamp ?: return -1
        return if (e > s) ((now - s) * 1000 / (e - s)).toInt().coerceIn(0, 1000) else -1
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

        val scroll = android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; clipChildren = false }
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
        words.addView(text("Trending now", 22f, textMain, 800))
        words.addView(text("What most Network24 viewers are watching right now · updates every minute", 12f, textSub, 600).apply { setPadding(0, dp(4), 0, 0) })
        head.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        val menu = ImageView(this).apply {
            setImageResource(R.drawable.ic_more_vert); setColorFilter(textMain); setPadding(dp(10), dp(10), dp(10), dp(10)); contentDescription = "Menu"
            background = shape(0x1AFFFFFF, 21f); focusable(this, 21f, 1.08f)
        }
        head.addView(menu, LinearLayout.LayoutParams(dp(42), dp(42)))
        page.addView(head)

        // billboard: #1
        val hero = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM; minimumHeight = heroH - dp(110); clipChildren = false; clipToPadding = false; visibility = View.INVISIBLE }
        val tagRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tagRow.addView(text("#1  TRENDING", 11f, Color.WHITE, 800).apply {
            letterSpacing = 0.12f; setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(accent, Color.parseColor("#E5484D"))).apply { cornerRadius = dpf(4f) }
        })
        heroLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        tagRow.addView(heroLogo, LinearLayout.LayoutParams(dp(48), dp(26)).apply { marginStart = dp(12) })
        heroChannel = text("", 12f, Color.parseColor("#D9F2F3F5"), 700).apply { letterSpacing = 0.12f; setPadding(dp(10), 0, 0, 0) }
        tagRow.addView(heroChannel)
        hero.addView(tagRow)
        heroTitle = text("", 34f, textMain, 800, lines = 2).apply { letterSpacing = -0.02f; setPadding(0, dp(12), 0, 0); setShadowLayer(dpf(10f), 0f, dpf(2f), 0x99000000.toInt()) }
        hero.addView(heroTitle, LinearLayout.LayoutParams((screenW * 0.55f).toInt(), -2))
        val metaRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
        heroViewers = text("", 14f, Color.WHITE, 700).apply { setPadding(dp(10), dp(4), dp(10), dp(4)); background = shape(0x26FFFFFF, 12f) }
        eye(heroViewers, 18)
        metaRow.addView(heroViewers)
        heroMeta = text("", 14f, textSub, 600).apply { fontFeatureSettings = "tnum"; setPadding(dp(14), 0, 0, 0) }
        metaRow.addView(heroMeta)
        heroBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progressDrawable = getDrawable(R.drawable.home_progress) }
        metaRow.addView(heroBar, LinearLayout.LayoutParams(dp(160), dp(4)).apply { marginStart = dp(14) })
        hero.addView(metaRow)
        btnWatch = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(20), dp(11), dp(22), dp(11)); background = shape(Color.WHITE, 12f)
            addView(ImageView(this@TrendingActivity).apply { setImageResource(R.drawable.ic_play); setColorFilter(bg) }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
            addView(text("Watch now", 15f, bg, 700))
            setOnClickListener { items.firstOrNull()?.let { play(it) } }
            focusable(this, 12f, 1.06f, accent)
            if (android.os.Build.VERSION.SDK_INT >= 26) isFocusedByDefault = true
        }
        hero.addView(btnWatch, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
        heroBox = hero
        page.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        gridTitle = text("", 18f, textMain, 800).apply { setPadding(0, dp(26), 0, dp(4)) }
        page.addView(gridTitle)
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@TrendingActivity, cols); adapter = this@TrendingActivity.adapter
            isNestedScrollingEnabled = false; itemAnimator = null; clipChildren = false; clipToPadding = false; setPadding(0, dp(10), 0, dp(10))
            isFocusable = false; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        page.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { marginStart = -dp(8); marginEnd = -dp(8) })
        status = text("Finding what everyone is watching…", 16f, textSub, 600, lines = 3).apply { gravity = Gravity.CENTER; setPadding(0, dp(60), 0, 0) }
        page.addView(status, LinearLayout.LayoutParams(-1, -2))

        setContentView(setupGlobalRightDrawer(root, menu))
    }

    // ------------------------------------------------------------------------------------------------ data
    private fun load() = lifecycleScope.launch {
        val pop = runCatching { Web24Api.objects(Web24Api(this@TrendingActivity).support("popular").optJSONArray("streams")).map { it.optInt("stream_id") to it.optInt("viewers") } }.getOrNull()
        if (pop == null) { if (items.isEmpty()) status.text = "Could not load what is trending. Try again in a minute."; return@launch }
        val now = System.currentTimeMillis()
        val next = withContext(Dispatchers.IO) {
            val cats = db.categoryDao().getByType(CategoryType.LIVE).associate { it.categoryId to it.name.orEmpty() }
            val adult = Regex("ADULT|XXX|18\\+", RegexOption.IGNORE_CASE)
            val hidden = (if (ParentalLock.isEnabled(this@TrendingActivity)) ParentalLock.lockedIds(this@TrendingActivity) else emptySet()) + cats.filterValues { adult.containsMatchIn(it) }.keys
            val chans = db.channelDao().getByStreamIds(pop.map { it.first }).associateBy { it.streamId }
            val list = pop.mapNotNull { (id, v) -> chans[id]?.takeIf { it.categoryId == null || it.categoryId !in hidden }?.let { it to v } }
            val ids = list.mapNotNull { it.first.epgChannelId?.takeIf { e -> e.isNotBlank() } }.distinct()
            val nows = if (ids.isEmpty()) emptyMap() else runCatching { db.epgDao().getNowByEpgChannelIdsChunked(ids, now) }.getOrDefault(emptyList()).filter { it.epgChannelId != null }.associateBy { it.epgChannelId!! }
            list.mapIndexed { i, (ch, v) -> Item(i + 1, ch, v, niceName(cats[ch.categoryId].orEmpty()), nows[ch.epgChannelId]) }
        }
        val focusKey = (currentFocus?.tag as? Item)?.ch?.streamId
        items = next
        status.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        if (items.isEmpty()) status.text = "Nothing is trending right now. Try again in a minute."
        heroBox.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        gridTitle.text = if (items.size > 1) "Top ${items.size}" else ""
        adapter.notifyDataSetChanged()
        items.firstOrNull()?.let { showHero(it) }
        if (focusKey != null) grid.post { (0 until grid.childCount).map { grid.getChildAt(it) }.firstOrNull { (it.tag as? Item)?.ch?.streamId == focusKey }?.requestFocus() }
        if (items.isNotEmpty() && !startFocused) { startFocused = true; btnWatch.postDelayed({ btnWatch.requestFocus() }, 150) }
        items.mapNotNull { it.now?.let { p -> cleanTitle(p.title) } }.filter { it.isNotBlank() && it !in art }.distinct().take(24).let { if (it.isNotEmpty()) loadArt(it) }
    }

    private fun showHero(it: Item) {
        val now = System.currentTimeMillis()
        heroLogo.load(it.ch.icon?.takeIf { s -> s.isNotBlank() })
        heroChannel.text = (clean(it.ch.name) + if (it.cat.isNotBlank()) "  ·  ${it.cat}" else "").uppercase()
        val p = it.now
        heroTitle.text = p?.let { e -> cleanTitle(e.title) }?.ifBlank { null } ?: clean(it.ch.name)
        heroViewers.text = "${it.viewers} watching now"
        heroMeta.text = if (p != null) {
            val e = p.stopTimestamp ?: now
            "${Fmt.clock(p.startTimestamp ?: now)} – ${Fmt.clock(e)}  ·  " + if (e - now < 60_000) "ending now" else "${(e - now) / 60_000} min left"
        } else ""
        val pr = progress(p, now)
        heroBar.visibility = if (pr >= 0) View.VISIBLE else View.GONE
        if (pr >= 0) heroBar.progress = pr
        p?.let { cleanTitle(it.title) }?.let { art[it] }?.let { u -> showBackdrop(u) }
    }

    private var backdropUrl: String? = null
    private fun showBackdrop(u: String) {
        if (backdropUrl == u) return
        backdropUrl = u
        backdrop.load(u) { crossfade(true); listener(onSuccess = { _, _ -> backdrop.animate().alpha(0.9f).setDuration(400).start() }) }
    }

    private fun loadArt(titles: List<String>) = lifecycleScope.launch {
        val r = runCatching { Web24Api(this@TrendingActivity).support("art", "titles" to titles.joinToString("\n")) }.getOrNull()
        val a = r?.optJSONObject("art") ?: return@launch
        titles.forEach { t -> a.optJSONObject(t)?.optString("backdrop")?.takeIf { it.isNotBlank() }?.let { art[t] = it } }
        adapter.notifyDataSetChanged()
        items.firstOrNull()?.let { showHero(it) }
    }

    // ------------------------------------------------------------------------------------------------ tiles
    private inner class TileAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = (items.size - 1).coerceAtLeast(0)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val w = (screenW - dp(96) + dp(16)) / cols - dp(16)
            val picH = w * 9 / 16
            val card = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(-1, picH + dp(64)).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) }
                background = shape(surface, 14f, line); clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            }
            val pic = FrameLayout(parent.context)
            pic.addView(ImageView(parent.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
            pic.addView(View(parent.context).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xE608090C.toInt(), 0x4008090C, 0x0008090C)) }, FrameLayout.LayoutParams(-1, -1))
            // the big rank number, violet to red
            pic.addView(text("", 64f, Color.WHITE, 800).apply { includeFontPadding = false; setPadding(dp(12), 0, 0, dp(2)); setShadowLayer(dpf(12f), 0f, dpf(2f), 0xAA000000.toInt()) },
                FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
            pic.addView(ImageView(parent.context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, FrameLayout.LayoutParams(dp(60), dp(34), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(10), dp(12), 0) })
            card.addView(pic, FrameLayout.LayoutParams(-1, picH))
            val info = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
            val top = LinearLayout(parent.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(text("", 14f, textMain, 700), LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(text("", 11f, Color.parseColor("#A894FF"), 800).apply { setPadding(dp(8), 0, 0, 0) })
            info.addView(top)
            info.addView(text("", 11f, textSub, 600).apply { setPadding(0, dp(3), 0, 0) })
            card.addView(info, FrameLayout.LayoutParams(-1, dp(64), Gravity.BOTTOM))
            card.addView(ProgressBar(parent.context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progressDrawable = getDrawable(R.drawable.home_progress) },
                FrameLayout.LayoutParams(-1, dp(3), Gravity.TOP).apply { topMargin = picH - dp(3) })
            focusable(card, 14f, 1.05f)
            return object : RecyclerView.ViewHolder(card) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val it = items[position + 1]
            val card = holder.itemView as FrameLayout
            val pic = card.getChildAt(0) as FrameLayout
            val img = pic.getChildAt(0) as ImageView
            val rank = pic.getChildAt(2) as TextView
            val logo = pic.getChildAt(3) as ImageView
            val title = it.now?.let { p -> cleanTitle(p.title) }.orEmpty()
            val u = art[title]
            if (u != null) { img.scaleType = ImageView.ScaleType.CENTER_CROP; img.setPadding(0, 0, 0, 0); img.background = null; img.load(u) { crossfade(true) }; logo.visibility = View.VISIBLE; logo.load(it.ch.icon) }
            else {
                logo.visibility = View.GONE; img.setBackgroundColor(Color.parseColor("#1A1C23"))
                img.scaleType = ImageView.ScaleType.FIT_CENTER; img.setPadding(dp(90), dp(26), dp(30), dp(26))
                img.load(it.ch.icon?.takeIf { s -> s.isNotBlank() })
            }
            rank.text = it.rank.toString()
            rank.post { rank.paint.shader = LinearGradient(0f, 0f, 0f, rank.height.toFloat(), Color.WHITE, Color.parseColor("#A894FF"), Shader.TileMode.CLAMP); rank.invalidate() }
            val info = card.getChildAt(1) as LinearLayout
            val top = info.getChildAt(0) as LinearLayout
            (top.getChildAt(0) as TextView).text = clean(it.ch.name)
            (top.getChildAt(1) as TextView).apply { text = it.viewers.toString(); eye(this, 14) }
            (info.getChildAt(1) as TextView).text = title.ifBlank { it.cat.ifBlank { "Live" } }
            val bar = card.getChildAt(2) as ProgressBar
            val pr = progress(it.now, System.currentTimeMillis())
            bar.visibility = if (pr >= 0) View.VISIBLE else View.INVISIBLE
            if (pr >= 0) bar.progress = pr
            card.tag = it
            card.setOnClickListener { _ -> play(it) }
        }
    }

    /** Watch now DOWN = the first tile, tiles of the first row UP = Watch now, Watch now UP = Back. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val f = currentFocus
            val pos = if (f?.parent === grid) grid.getChildAdapterPosition(f) else -1
            when {
                f === btnWatch && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && adapter.itemCount > 0 -> { grid.getChildAt(0)?.requestFocus(); return true }
                f === btnWatch && event.keyCode == KeyEvent.KEYCODE_DPAD_UP -> { btnBack.requestFocus(); return true }
                pos in 0 until cols && event.keyCode == KeyEvent.KEYCODE_DPAD_UP -> { btnWatch.requestFocus(); return true }
                f === btnBack && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> { btnWatch.requestFocus(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun play(it: Item) = ChannelLauncher.play(this, items.map { i -> i.ch }, it.ch)

    private val refresh = object : Runnable { override fun run() { load(); handler.postDelayed(this, 60_000) } }
    override fun onStart() { super.onStart(); handler.postDelayed(refresh, 60_000) }
    override fun onStop() { handler.removeCallbacks(refresh); super.onStop() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
