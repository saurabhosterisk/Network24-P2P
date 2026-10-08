package com.network24.player.features.catchup

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
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.load
import com.network24.player.R
import com.network24.player.core.api.Web24Api
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.entity.CategoryType
import com.network24.player.core.database.entity.ChannelEntity
import com.network24.player.core.net.StreamDataSourceFactory
import com.network24.player.features.dashboard.home.HomeFont
import com.network24.player.features.discover.ChannelLauncher
import com.network24.player.features.discover.CinemaPro
import com.network24.player.features.discover.Fmt
import com.network24.player.features.player.manager.PlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

/**
 * Catch-up TV, in the same look and flow as the home: the same top bar (Catch-up selected), a billboard with the
 * artwork of a recorded show, then rows - "Just aired" across every catch-up channel and one row per category with
 * its channels. A channel opens its own page in place: days on top, that day's shows below. Select a past show to
 * watch it from the start (pause, rewind, fast-forward); a future show sets a reminder; the show on now plays live.
 */
class CatchupActivity : BaseActivity() {
    private val uiScale by lazy { resources.displayMetrics.let { m -> minOf(1f, m.widthPixels / m.density / 960f, m.heightPixels / m.density / 400f) } }
    private val d by lazy { resources.displayMetrics.density * uiScale }
    private val fontD by lazy { d * resources.configuration.fontScale.coerceIn(0.85f, 1.15f) }
    private fun dp(v: Int) = (v * d).toInt()
    private fun dpf(v: Float) = v * d
    private val screenH by lazy { resources.displayMetrics.heightPixels }
    private val screenW by lazy { resources.displayMetrics.widthPixels }
    private val handler = Handler(Looper.getMainLooper())

    // the home's palette
    private val bg = Color.parseColor("#08090C")
    private val surface = Color.parseColor("#14161B")
    private val line = Color.parseColor("#1FFFFFFF")
    private val textMain = Color.parseColor("#F2F3F5")
    private val textSub = Color.parseColor("#9BA1AD")
    private val live = Color.parseColor("#E5484D")
    private val gold = Color.parseColor("#F5B841")

    private class Show(val ch: ChannelEntity, val p: Web24Api.Programme)

    private var channels: List<ChannelEntity> = emptyList()
    private val guides = HashMap<Int, List<Web24Api.Programme>>()
    private var cur: ChannelEntity? = null
    private var day = 0L
    private var heroShow: Show? = null
    private var pendingHero: Runnable? = null

    private lateinit var root: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var page: LinearLayout
    private lateinit var backdrop: ImageView
    private lateinit var heroBox: LinearLayout
    private lateinit var heroTag: TextView
    private lateinit var heroChLogo: ImageView
    private lateinit var heroArtLogo: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroDesc: TextView
    private lateinit var btnWatch: View
    private lateinit var btnChannel: TextView
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private lateinit var catchupTab: View

    private val backToOverview = object : OnBackPressedCallback(false) { override fun handleOnBackPressed() = showOverview() }

    /** This page's tab in the top bar: UP into the bar lands there. */
    private var hereTab: android.view.View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
        onBackPressedDispatcher.addCallback(this, backToOverview)
        load()
    }

    // ------------------------------------------------------------------------------------------------ building blocks
    private fun text(s: String, size: Float, color: Int = textMain, weight: Int = 500, lines: Int = 1) = TextView(this).apply {
        text = s; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * fontD); setTextColor(color); typeface = HomeFont.of(this@CatchupActivity, weight)
        maxLines = lines; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
    }

    private fun shape(fill: Int, radius: Float, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dpf(radius); if (stroke != 0) setStroke(dp(1), stroke)
    }

    /** The home's focus: slightly larger, a white ring, lifted. */
    private fun focusable(v: View, radius: Float, scale: Float = 1.06f, ringColor: Int = Color.WHITE, onFocus: (() -> Unit)? = null) {
        v.isFocusable = true; v.isClickable = true
        val stroke = dp(3)
        val ring = GradientDrawable().apply { cornerRadius = (dpf(radius) - stroke / 2f).coerceAtLeast(0f); setStroke(stroke, ringColor); setColor(Color.TRANSPARENT) }
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).translationZ(if (has) dpf(10f) else 0f).setDuration(150).start()
            view.foreground = if (has) ring else null
            if (has) { keepInView(view); onFocus?.invoke() }
        }
    }

    private fun keepInView(v: View) {
        val loc = IntArray(2); v.getLocationInWindow(loc)
        when {
            loc[1] + v.height > screenH * 0.94f -> scroll.smoothScrollBy(0, (loc[1] + v.height - screenH * 0.86f).toInt())
            loc[1] < screenH * 0.1f && scroll.scrollY > 0 -> scroll.smoothScrollBy(0, (loc[1] - screenH * 0.25f).toInt())
        }
    }

    private fun iconButton(icon: Int, label: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon); setColorFilter(textMain); setPadding(dp(8), dp(8), dp(8), dp(8)); contentDescription = label
        background = shape(0x14FFFFFF, 18f, 0x1FFFFFFF); setOnClickListener { onClick() }
        focusable(this, 18f, 1.08f)
        com.network24.player.core.ui.IconHint.attach(this, label)
    }

    private fun button(label: String, icon: Int?, primary: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), 0, dp(24), 0)
        background = shape(if (primary) Color.WHITE else 0x26FFFFFF, 10f)
        icon?.let { addView(ImageView(this@CatchupActivity).apply { setImageResource(it); setColorFilter(if (primary) bg else textMain) }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) }) }
        addView(text(label, 15f, if (primary) bg else textMain, 700))
        setOnClickListener { onClick() }
        focusable(this, 10f, 1.06f, if (primary) Color.parseColor("#7C5CFF") else Color.WHITE)
    }

    private val prefix = Regex("^[A-Z]{2,3}\\s*\\|\\s*")
    private fun cleanName(n: String?) = n.orEmpty().replace(prefix, "").trim()
    private val marks = Regex("[ʰ-˿ᴀ-ᶿ⁰-₟]+")
    private fun cleanTitle(t: String?) = t.orEmpty().replace(marks, "").replace(Regex("\\s+"), " ").trim().ifBlank { "Untitled show" }
    private fun niceName(n: String) = n.trim().split(" ").joinToString(" ") { w ->
        if (w.length <= 3 && w.all { it.isUpperCase() || !it.isLetter() }) w else w.lowercase().replaceFirstChar { it.uppercase() }
    }

    // ------------------------------------------------------------------------------------------------ page
    private fun build() {
        root = FrameLayout(this).apply { setBackgroundColor(bg) }
        val heroH = (screenH * 0.70f).toInt()
        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, heroH))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF508090C.toInt(), 0xCC08090C.toInt(), 0x4008090C, 0x0008090C))
        }, FrameLayout.LayoutParams((screenW * 0.75f).toInt(), heroH))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x0008090C, 0xB308090C.toInt(), bg))
        }, FrameLayout.LayoutParams(-1, (heroH * 0.45f).toInt(), Gravity.TOP).apply { topMargin = (heroH * 0.55f).toInt() + 1 })
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xD908090C.toInt(), 0x0008090C))
        }, FrameLayout.LayoutParams(-1, dp(120)))

        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; isFocusable = false }
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(48), dp(24), 0, dp(40)); clipChildren = false; clipToPadding = false }
        scroll.addView(page)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        val menu = iconButton(R.drawable.ic_more_vert, "More") {}
        page.addView(topBar(menu), LinearLayout.LayoutParams(-1, -2).apply { marginEnd = dp(48) })
        heroBox = hero()
        val heroArea = FrameLayout(this).apply { clipChildren = false; clipToPadding = false; minimumHeight = (screenH * 0.54f).toInt() - dp(24 + 36 + 28) }
        heroArea.addView(heroBox, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        page.addView(heroArea, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(28) })
        status = text("Loading the recordings…", 15f, textSub, 500).apply { setPadding(0, dp(24), 0, 0) }
        page.addView(status)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        page.addView(rows, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        setContentView(setupGlobalRightDrawer(root, menu))
    }

    /** The home's top bar with Catch-up selected; the other tabs lead to their pages the same way as on the home. */
    private fun topBar(menu: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        // brand: the Network24 logo - mark and wordmark in one image (4:1)
        addView(ImageView(this@CatchupActivity).apply { setImageResource(R.drawable.app_logo_wide); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START }, LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(16) })
        fun go(cls: Class<*>, extra: (Intent.() -> Unit)? = null) { startActivity(Intent(this@CatchupActivity, cls).apply { extra?.invoke(this) }); finish() }
        val tabs = listOf<Pair<String, () -> Unit>>(
            "Home" to { finish() },
            "Live TV" to { go(com.network24.player.features.livetv.LiveTvActivity::class.java) },
            "Cinema" to { CinemaPro.open(this@CatchupActivity) },
            "Sports" to { go(com.network24.player.features.sports.SportsActivity::class.java) },
            "TV Guide" to { go(com.network24.player.features.guide.TvGuideActivity::class.java) },
            "Catch-up" to { showOverview() },
        )
        val tabRow = LinearLayout(this@CatchupActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(3), dp(3), dp(3), dp(3)); background = shape(0x14FFFFFF, 21f, 0x1FFFFFFF) }
        tabs.forEachIndexed { i, (label, onClick) ->
            val here = label == "Catch-up"
            val t = text(label, 13.5f, if (here) Color.parseColor("#08090C") else textSub, if (here) 700 else 600).apply {
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = if (here) shape(Color.WHITE, 18f) else null
                setOnClickListener { onClick() }
            }
            focusable(t, 18f, 1.04f)
            if (here) hereTab = t
            if (here) catchupTab = t
            tabRow.addView(t, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(2) })
        }
        addView(HorizontalScrollView(this@CatchupActivity).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true; addView(android.widget.FrameLayout(this@CatchupActivity).apply { addView(tabRow, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)) }) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(iconButton(R.drawable.ic_h_search, "Search") {
            com.network24.player.features.search.SearchOverlay.show(this@CatchupActivity)
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(12) })
        addView(iconButton(R.drawable.ic_live_chat, "Live Support") {
            com.network24.player.features.help.HelpCenter.show(this@CatchupActivity)
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(8) })
        // account and settings are in the More menu (the owner wants a shorter bar)
        addView(menu, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(8) })
    }

    /** Billboard: a recorded show - its channel, title (or the show's title logo), when it aired, what it is about. */
    private fun hero() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
        val tagRow = LinearLayout(this@CatchupActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tagRow.addView(text("CATCH-UP", 11f, Color.WHITE, 800).apply {
            letterSpacing = 0.12f; setPadding(dp(8), dp(4), dp(8), dp(4)); background = shape(Color.parseColor("#7C5CFF"), 4f)
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(12) })
        heroChLogo = ImageView(this@CatchupActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        tagRow.addView(heroChLogo, LinearLayout.LayoutParams(dp(44), dp(24)).apply { marginStart = dp(12) })
        heroTag = text("WATCH WHAT YOU MISSED", 12f, Color.parseColor("#D9F2F3F5"), 700).apply { letterSpacing = 0.12f; setPadding(dp(10), 0, 0, 0) }
        tagRow.addView(heroTag)
        addView(tagRow)
        heroArtLogo = ImageView(this@CatchupActivity).apply { scaleType = ImageView.ScaleType.FIT_START; adjustViewBounds = true; visibility = View.GONE; maxHeight = dp(120); maxWidth = (screenW * 0.42f).toInt() }
        addView(heroArtLogo, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        heroTitle = text("Catch-up TV", 36f, weight = 800, lines = 2).apply { letterSpacing = -0.025f; setPadding(0, dp(12), 0, 0); setShadowLayer(dpf(12f), 0f, dpf(2f), 0x99000000.toInt()) }
        addView(heroTitle, LinearLayout.LayoutParams((screenW * 0.55f).toInt(), -2))
        heroMeta = text("", 14f, textSub, 600).apply { setPadding(0, dp(12), 0, 0); fontFeatureSettings = "tnum" }
        addView(heroMeta)
        heroDesc = text("Shows from the last few days on the channels that keep recordings.", 14f, Color.parseColor("#CCF2F3F5"), 500, lines = 2).apply { setPadding(0, dp(8), 0, 0); setLineSpacing(0f, 1.15f) }
        addView(heroDesc, LinearLayout.LayoutParams((screenW * 0.5f).toInt(), -2))
        val buttons = LinearLayout(this@CatchupActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        btnWatch = button("Watch from start", R.drawable.ic_play, true) { heroShow?.let { watch(it) } }
        buttons.addView(btnWatch, LinearLayout.LayoutParams(-2, dp(48)))
        val more = button("More from this channel", R.drawable.ic_h_history, false) { heroShow?.let { open(it.ch) } }
        btnChannel = (more as LinearLayout).getChildAt(1) as TextView
        buttons.addView(more, LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(14) })
        buttons.visibility = View.INVISIBLE; tag = buttons
        addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(22) })
    }

    // ------------------------------------------------------------------------------------------------ data
    /**
     * The page opens from the guides saved on this device (instantly); they are asked again from the server when
     * older than 15 minutes - shown at once next time - or when there are none yet (then 8 channels at a time).
     */
    private fun load() = lifecycleScope.launch {
        val db = DatabaseProvider.get(this@CatchupActivity)
        val flagged = db.channelDao().getAll().filter { (it.tvArchive ?: 0) == 1 }.sortedBy { it.name }
        val cats = db.categoryDao().getByType(CategoryType.LIVE).sortedBy { it.position }.map { it.categoryId to it.name }
        val cached = withContext(Dispatchers.IO) { readCache() }
        if (cached != null) {
            show(flagged, cached.second, cats)
            if (System.currentTimeMillis() - cached.first < CACHE_FRESH_MS) return@launch
            launch { fetch(flagged) }   // fresh guides for the next visit; this page stays as it is
            return@launch
        }
        val fresh = fetch(flagged)
        if (fresh == null) { status.text = "The recordings could not be loaded right now. Please try again."; return@launch }
        show(flagged, fresh, cats)
    }

    private fun show(flagged: List<ChannelEntity>, data: Map<Int, List<Web24Api.Programme>>, cats: List<Pair<String, String>>) {
        guides.clear(); guides.putAll(data)
        channels = flagged.filter { it.streamId in data.keys }
        if (channels.isEmpty()) { status.text = "No channel with catch-up on your plan yet."; return }
        buildOverview(cats)
        val want = intent.getIntExtra(EXTRA_STREAM, 0)
        channels.firstOrNull { it.streamId == want }?.let { open(it) }
    }

    /** Recordable channels (Main lists them) and their guides; saved on this device. Null when nothing came back. */
    private suspend fun fetch(flagged: List<ChannelEntity>): Map<Int, List<Web24Api.Programme>>? = coroutineScope {
        // The catch-up flag alone is not enough: a channel only has recordings when it runs always-on on its
        // recording server. Main lists those; without an answer (older Main) every flagged channel is used.
        val api = Web24Api(this@CatchupActivity)
        val recordable = runCatching { api.support("catchup").optJSONArray("streams") }.getOrNull()
        val list = if (recordable == null) flagged else (0 until recordable.length()).map { recordable.optInt(it) }.toSet().let { ids -> flagged.filter { it.streamId in ids } }
        val gate = Semaphore(8)
        val got = list.map { ch -> async { gate.withPermit { ch.streamId to runCatching { api.fullGuide(ch.streamId) }.getOrNull() } } }.awaitAll()
        if (got.isNotEmpty() && got.all { it.second == null }) return@coroutineScope null
        val data = got.associate { (id, g) -> id to g.orEmpty() }
        withContext(Dispatchers.IO) { writeCache(data) }
        data
    }

    private val cacheFile by lazy { java.io.File(cacheDir, "catchup_guides.json") }

    private fun writeCache(data: Map<Int, List<Web24Api.Programme>>) = runCatching {
        val g = JSONObject()
        data.forEach { (id, list) ->
            g.put(id.toString(), org.json.JSONArray().apply {
                list.forEach { p -> put(org.json.JSONArray().put(p.title).put(p.description).put(p.start).put(p.end).put(p.hasArchive)) }
            })
        }
        cacheFile.writeText(JSONObject().put("at", System.currentTimeMillis()).put("guides", g).toString())
    }

    private fun readCache(): Pair<Long, Map<Int, List<Web24Api.Programme>>>? = runCatching {
        val o = JSONObject(cacheFile.readText())
        val g = o.getJSONObject("guides")
        val data = g.keys().asSequence().associate { k ->
            val a = g.getJSONArray(k)
            k.toInt() to (0 until a.length()).map { a.getJSONArray(it) }.map { Web24Api.Programme(it.getString(0), it.getString(1), it.getLong(2), it.getLong(3), it.getBoolean(4)) }
        }
        // a cache older than a day is of no use (recordings are kept a few days, the guide moves on)
        o.getLong("at").takeIf { System.currentTimeMillis() - it < 86_400_000L }?.let { it to data }
    }.getOrNull()

    private fun canPlay(s: Show): Boolean {
        val now = System.currentTimeMillis()
        val keepMs = (s.ch.tvArchiveDuration ?: 0) * 86_400_000L
        return s.p.end <= now && (s.p.hasArchive || now - s.p.start < keepMs)
    }

    /** Recorded shows of a channel, newest first. */
    private fun recorded(ch: ChannelEntity) = guides[ch.streamId].orEmpty().map { Show(ch, it) }.filter { canPlay(it) }.sortedByDescending { it.p.end }

    /** Where the viewer left this show in the catch-up player (ms), 0 when not started or finished. */
    // (from one minute in, like the player: a show only peeked into starts from the beginning again)
    private fun resumeAt(s: Show) = getSharedPreferences("n24_catchup_pos", MODE_PRIVATE).getLong("${s.ch.streamId}:${s.p.start}", 0L).takeIf { it >= 60_000 } ?: 0L
    private fun watched(s: Show): Int = resumeAt(s).let { if (it <= 0) -1 else (it * 1000 / (s.p.end - s.p.start)).toInt().coerceIn(0, 1000) }

    /** Shows left part-way, the ones watched most recently ... approximated by the newest broadcast first. */
    private fun continueList() = channels.flatMap { recorded(it) }.filter { resumeAt(it) > 0 }.sortedByDescending { it.p.end }.take(20)

    private fun progressBar(value: Int) = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000; progress = value; progressDrawable = getDrawable(R.drawable.home_progress)
    }

    private fun when_(s: Show) = "${Fmt.day(s.p.start)} ${Fmt.clock(s.p.start)}"
    private fun minutes(s: Show) = ((s.p.end - s.p.start) / 60_000).let { if (it >= 60) "${it / 60} h ${it % 60} min".replace(" 0 min", "") else "$it min" }

    // ------------------------------------------------------------------------------------------------ overview
    private lateinit var catOrder: List<Pair<String, String>>

    /** The categories with their catch-up channels (that have recordings), in the panel's order. */
    private fun groups(cats: List<Pair<String, String>>): List<Pair<String, List<ChannelEntity>>> {
        val byCat = channels.filter { recorded(it).isNotEmpty() }.groupBy { it.categoryId }
        val known = cats.map { it.first }.toSet()
        return cats.mapNotNull { (id, name) -> byCat[id]?.let { niceName(name) to it } } +
            listOfNotNull(byCat.filterKeys { it !in known }.values.flatten().takeIf { it.isNotEmpty() }?.let { "More channels" to it })
    }

    // the viewer's choice: tiles (rows of cards, like the home) or a list (categories | channels, like Live TV)
    private fun listView() = getSharedPreferences("n24_catchup", MODE_PRIVATE).getString("view", "tiles") == "list"

    private fun buildOverview(cats: List<Pair<String, String>>) {
        catOrder = cats
        rows.removeAllViews()
        val all = channels.flatMap { recorded(it) }
        if (all.isEmpty()) { status.text = "No recordings are ready yet. Please check again later."; return }
        status.visibility = View.GONE
        // Just aired: the newest recordings over every channel (a few per channel, so one channel does not fill it)
        val justAired = channels.flatMap { recorded(it).take(3) }.sortedByDescending { it.p.end }.take(20)
        rows.addView(viewSwitch())
        // shows left part-way come first, in both views
        addRow("Continue watching", "pick up where you left off", continueList().map { showCard(it) })
        if (listView()) buildList(cats) else {
            addRow("Just aired", "recorded in the last hours", justAired.map { showCard(it) })
            // one row per category: its channels, each with its latest recording
            groups(cats).forEach { (name, list) -> addRow(name, "${list.size} channel" + if (list.size == 1) "" else "s", list.map { channelCard(it) }) }
        }
        if (heroShow == null) showHero(justAired.first())
        btnWatch.post { btnWatch.requestFocus() }
    }

    /** "View: Tiles | List" above the rows. */
    private fun viewSwitch() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false
        setPadding(0, 0, 0, dp(6))
        addView(text("View", 13f, textSub, 600).apply { setPadding(0, 0, dp(12), 0) })
        listOf("Tiles" to false, "List" to true).forEachIndexed { i, (label, list) ->
            val on = listView() == list
            val t = text(label, 14f, if (on) bg else textMain, 700).apply {
                setPadding(dp(18), dp(9), dp(18), dp(9)); background = shape(if (on) Color.WHITE else 0x1AFFFFFF, 18f)
                setOnClickListener {
                    if (listView() == list) return@setOnClickListener
                    getSharedPreferences("n24_catchup", MODE_PRIVATE).edit().putString("view", if (list) "list" else "tiles").apply()
                    buildOverview(catOrder)
                    rows.post { rows.findViewWithTag<View>("view:$label")?.requestFocus() }
                }
                tag = "view:$label"
            }
            focusable(t, 18f, 1.05f, if (on) Color.parseColor("#7C5CFF") else Color.WHITE)
            addView(t, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(8) })
        }
    }

    /** List view: categories on the left, the selected category's channels on the right (like Live TV, no guide). */
    private fun buildList(cats: List<Pair<String, String>>) {
        val groups = groups(cats)
        if (groups.isEmpty()) return
        val pane = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; setPadding(0, dp(14), dp(48), 0) }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        pane.addView(left, LinearLayout.LayoutParams(dp(300), -2))
        pane.addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(24) })
        val catViews = mutableListOf<TextView>()
        var shown = -1
        fun fill(i: Int) {
            if (shown == i) return
            shown = i
            catViews.forEachIndexed { n, v -> v.setTextColor(if (n == i) textMain else textSub); v.background = if (n == i) shape(0x1FFFFFFF, 10f) else null }
            right.removeAllViews()
            right.addView(text(groups[i].first, 19f, weight = 700).apply { setPadding(0, 0, 0, dp(10)) })
            groups[i].second.forEach { ch -> right.addView(channelLine(ch, catViews[i]), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }
        }
        groups.forEachIndexed { i, (name, list) ->
            val v = text("$name   ${list.size}", 15f, textSub, 700).apply {
                setPadding(dp(16), dp(13), dp(16), dp(13))
                setOnClickListener { fill(i); (right.getChildAt(1))?.requestFocus() }
                id = View.generateViewId()
            }
            focusable(v, 10f, 1.03f) { fill(i) }
            catViews += v
            left.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        rows.addView(pane)
        fill(0)
    }

    /** One channel in the list view: logo, name, how many shows and the latest one; selecting it opens its page. */
    private fun channelLine(ch: ChannelEntity, category: View) = LinearLayout(this).apply {
        val shows = recorded(ch)
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(18), dp(12)); background = shape(surface, 12f, line)
        addView(ImageView(this@CatchupActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; load(ch.icon?.takeIf { it.isNotBlank() }) }, LinearLayout.LayoutParams(dp(72), dp(42)))
        val mid = LinearLayout(this@CatchupActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
        mid.addView(text(cleanName(ch.name), 16f, weight = 700))
        mid.addView(text("${shows.size} shows  ·  latest ${shows.firstOrNull()?.let { cleanTitle(it.p.title) + " · " + when_(it) }.orEmpty()}", 13f, textSub, 500).apply { setPadding(0, dp(4), 0, 0) })
        addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        addView(text("›", 26f, textSub, 600))
        setOnClickListener { open(ch) }
        // LEFT goes back to the category it belongs to
        id = View.generateViewId(); nextFocusLeftId = category.id
        focusable(this, 12f, 1.02f) {
            pendingHero?.let { handler.removeCallbacks(it) }
            shows.firstOrNull()?.let { s -> pendingHero = Runnable { showHero(s) }.also { handler.postDelayed(it, 650) } }
        }
    }

    private fun addRow(title: String, sub: String, items: List<View>) {
        if (items.isEmpty()) return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        head.addView(text(title, 19f, weight = 700).apply { letterSpacing = -0.01f })
        if (sub.isNotBlank()) head.addView(text(sub, 13f, textSub, 500).apply { setPadding(dp(12), 0, 0, dp(1)) })
        box.addView(head)
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        items.forEachIndexed { i, v -> strip.addView(v, (v.layoutParams as LinearLayout.LayoutParams).apply { if (i > 0) marginStart = dp(16) }) }
        items.forEach { if (it.id == View.NO_ID) it.id = View.generateViewId() }
        items.last().nextFocusRightId = items.last().id
        items.first().nextFocusLeftId = items.first().id
        box.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false; clipChildren = false
            setPadding(dp(16), dp(14), dp(48), dp(18)); addView(strip)
        }, LinearLayout.LayoutParams(-1, -2).apply { marginStart = -dp(16) })
        rows.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    /** 16:9 card with the show's artwork (TMDB), the channel's logo as a badge, the title and when it aired. */
    private fun card(s: Show, title: String, sub: String, progress: Int = -1, onClick: () -> Unit) = FrameLayout(this).apply {
        background = shape(surface, 14f, line)
        clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        layoutParams = LinearLayout.LayoutParams(dp(288), dp(162))
        val logo = ImageView(this@CatchupActivity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            load(s.ch.icon?.takeIf { it.isNotBlank() }) { placeholder(R.drawable.app_logo); error(R.drawable.app_logo) }
        }
        addView(logo, FrameLayout.LayoutParams(dp(132), dp(62), Gravity.CENTER).apply { bottomMargin = dp(26) })
        val pic = ImageView(this@CatchupActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        addView(pic, FrameLayout.LayoutParams(-1, -1))
        addView(View(this@CatchupActivity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0x33000000, 0xF0000000.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))
        val badge = ImageView(this@CatchupActivity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(6), dp(4), dp(6), dp(4)); background = shape(0xB3000000.toInt(), 8f); visibility = View.GONE
            load(s.ch.icon?.takeIf { it.isNotBlank() })
        }
        addView(badge, FrameLayout.LayoutParams(dp(62), dp(34), Gravity.TOP or Gravity.START).apply { setMargins(dp(10), dp(10), 0, 0) })
        val info = LinearLayout(this@CatchupActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(14), dp(14)) }
        info.addView(text(title, 15f, weight = 700))
        info.addView(text(sub, 12f, textSub, 600).apply { setPadding(0, dp(4), 0, 0) })
        addView(info, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        // how much of the show was watched (catch-up player's resume point)
        if (progress >= 0) addView(progressBar(progress), FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM).apply { setMargins(dp(14), 0, dp(14), dp(8)) })
        art(cleanTitle(s.p.title)) { a ->
            val url = a.optString("backdrop").ifBlank { return@art }.replace("/w1280/", "/w500/")
            pic.load(url) { crossfade(true); listener(onSuccess = { _, _ -> pic.animate().alpha(1f).setDuration(350).start(); logo.visibility = View.GONE; badge.visibility = View.VISIBLE }) }
        }
        setOnClickListener { onClick() }
        focusable(this, 14f) {
            pendingHero?.let { handler.removeCallbacks(it) }
            pendingHero = Runnable { showHero(s) }.also { handler.postDelayed(it, 650) }
        }
    }

    private fun showCard(s: Show) = card(s, cleanTitle(s.p.title), "${cleanName(s.ch.name)} · ${when_(s)}", watched(s)) { watch(s) }

    private fun channelCard(ch: ChannelEntity): View {
        val shows = recorded(ch)
        val last = shows.first()
        return card(last, cleanName(ch.name), "${shows.size} shows · latest ${cleanTitle(last.p.title)}") { open(ch) }
    }

    private fun showHero(s: Show) {
        if (heroShow === s) return
        heroShow = s
        heroChLogo.load(s.ch.icon?.takeIf { it.isNotBlank() })
        heroTag.text = cleanName(s.ch.name).uppercase()
        heroTitle.text = cleanTitle(s.p.title); heroTitle.visibility = View.VISIBLE; heroArtLogo.visibility = View.GONE
        val at = resumeAt(s)
        heroMeta.text = "Aired ${when_(s)}  ·  ${minutes(s)}" + if (at > 0) "  ·  watched up to ${clock(at)}" else ""
        ((btnWatch as LinearLayout).getChildAt(1) as TextView).text = if (at > 0) "Resume" else "Watch from start"
        heroDesc.text = s.p.description.ifBlank { "Recorded on ${cleanName(s.ch.name)}. Watch it from the start." }
        btnChannel.text = "More from ${cleanName(s.ch.name)}"
        (heroBox.tag as View).visibility = View.VISIBLE
        backdrop.animate().alpha(0f).setDuration(200).start()
        heroBox.alpha = 0.4f; heroBox.animate().alpha(1f).setDuration(300).start()
        art(cleanTitle(s.p.title)) { a ->
            if (heroShow !== s) return@art
            a.optString("backdrop").takeIf { it.isNotBlank() }?.let { u ->
                backdrop.load(u) { crossfade(true); listener(onSuccess = { _, _ -> if (heroShow === s) backdrop.animate().alpha(1f).setDuration(500).start() }) }
            }
            a.optString("logo").takeIf { it.isNotBlank() }?.let { u ->
                heroArtLogo.load(u) { listener(onSuccess = { _, _ -> if (heroShow === s) { heroArtLogo.visibility = View.VISIBLE; heroTitle.visibility = View.GONE } }) }
            }
        }
    }

    private fun showOverview() {
        if (cur == null) { scroll.smoothScrollTo(0, 0); return }
        cur = null; backToOverview.isEnabled = false
        heroBox.visibility = View.VISIBLE
        heroShow = null
        buildOverview(catOrder)
        scroll.scrollTo(0, 0)
    }

    // ------------------------------------------------------------------------------------------------ one channel
    /** The channel's page in place of the rows: its logo and name, the days, that day's shows. */
    private fun open(ch: ChannelEntity) {
        cur = ch; backToOverview.isEnabled = true
        pendingHero?.let { handler.removeCallbacks(it) }
        recorded(ch).firstOrNull()?.let { heroShow = null; showHero(it) }
        val now = System.currentTimeMillis()
        // catch-up is the past: today and the days before only (shows still to come are not listed here)
        val today = Fmt.startOfDay(now)
        val days = guides[ch.streamId].orEmpty().filter { it.start <= now }.map { Fmt.startOfDay(it.start) }.distinct().filter { it <= today }.sortedDescending()
        day = today.takeIf { it in days } ?: days.firstOrNull() ?: 0L
        curDays = days
        renderChannel(days)
        scroll.post { scroll.smoothScrollTo(0, (heroBox.parent as View).bottom - dp(40)) }
    }

    private var curDays: List<Long> = emptyList()

    /**
     * Back from the player: the resume points changed, so the page shows them again - the channel page as it was,
     * or the overview (Continue watching, progress on the cards) with the same show on the billboard.
     */
    override fun onRestart() {
        super.onRestart()
        if (guides.isEmpty() || !::catOrder.isInitialized) return
        val keep = heroShow
        if (cur == null) buildOverview(catOrder) else renderChannel(curDays)
        heroShow = null
        keep?.let { showHero(it) }
    }

    private fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun renderChannel(days: List<Long>) {
        val ch = cur ?: return
        rows.removeAllViews()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; load(ch.icon?.takeIf { it.isNotBlank() }) }, LinearLayout.LayoutParams(dp(64), dp(40)))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        names.addView(text(cleanName(ch.name), 22f, weight = 800))
        names.addView(text("Recordings of the last ${ch.tvArchiveDuration ?: 0} days  ·  select a show to watch it from the start", 13f, textSub, 500).apply { setPadding(0, dp(4), 0, 0) })
        head.addView(names)
        rows.addView(head)
        // days as tabs
        val dayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        var selectedDay: View? = null
        days.forEachIndexed { i, dd ->
            val on = dd == day
            val t = text(Fmt.day(dd), 14f, if (on) bg else textMain, 700).apply {
                setPadding(dp(16), dp(10), dp(16), dp(10)); background = shape(if (on) Color.WHITE else 0x1AFFFFFF, 18f)
                setOnClickListener { if (day != dd) { day = dd; renderChannel(days); rows.post { rows.findViewWithTag<View>("day:$dd")?.requestFocus() } } }
                tag = "day:$dd"
            }
            focusable(t, 18f, 1.05f, if (on) Color.parseColor("#7C5CFF") else Color.WHITE)
            if (on) selectedDay = t
            dayRow.addView(t, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(10) })
        }
        rows.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false; clipChildren = false; setPadding(dp(8), dp(16), dp(48), dp(8)); addView(dayRow)
        }, LinearLayout.LayoutParams(-1, -2).apply { marginStart = -dp(8); topMargin = dp(10) })
        val now = System.currentTimeMillis()
        val list = guides[ch.streamId].orEmpty().filter { Fmt.startOfDay(it.start) == day && it.start <= now }
        if (list.isEmpty()) rows.addView(text("No TV guide for this day.", 15f, textSub).apply { setPadding(0, dp(16), 0, 0) })
        var focusTarget: View? = null
        list.forEach { p ->
            val v = showRow(ch, p, days)
            rows.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); marginEnd = dp(48) })
            if (p.start <= now) focusTarget = v
        }
        (focusTarget ?: selectedDay)?.let { t -> rows.post { t.requestFocus() } }
    }

    /** One show of the day: time, title and description, and what selecting it does (watch / from the start). */
    private fun showRow(ch: ChannelEntity, p: Web24Api.Programme, days: List<Long>) = LinearLayout(this).apply {
        val now = System.currentTimeMillis()
        val s = Show(ch, p)
        val past = p.end <= now; val isLive = now in p.start until p.end
        val playable = canPlay(s)
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(14)); background = shape(surface, 12f, line)
        addView(text(Fmt.clock(p.start), 15f, if (isLive) Color.WHITE else textSub, 700).apply { fontFeatureSettings = "tnum" }, LinearLayout.LayoutParams(dp(96), -2))
        val mid = LinearLayout(this@CatchupActivity).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(text(cleanTitle(p.title), 16f, if (past && !playable) textSub else textMain, 700))
        val desc = p.description.ifBlank { "${Fmt.clock(p.start)} – ${Fmt.clock(p.end)}" }
        mid.addView(text(desc, 13f, textSub, 500, lines = 2).apply { setPadding(0, dp(4), 0, 0) })
        // on now: how far the broadcast is; recorded and started: how much the viewer watched
        val seen = watched(s)
        val bar = if (isLive) ((now - p.start) * 1000 / (p.end - p.start)).toInt() else seen
        if (bar >= 0) mid.addView(progressBar(bar), LinearLayout.LayoutParams(dp(220), dp(3)).apply { topMargin = dp(8) })
        addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        val (label, fill, color) = when {
            isLive -> Triple("▶  From start", live, Color.WHITE)
            playable && seen >= 0 -> Triple("▶  Resume", Color.WHITE, bg)
            playable -> Triple("▶  Watch", Color.WHITE, bg)
            else -> Triple("Not recorded", 0x14FFFFFF, textSub)
        }
        addView(text(label, 13f, color, 700).apply { setPadding(dp(14), dp(7), dp(14), dp(7)); background = shape(fill, 16f) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
        setOnClickListener {
            when {
                // the show on now, from its start up to this minute (the rest is still being recorded)
                isLive -> watch(s, live = true)
                playable -> watch(s)
                else -> Toast.makeText(this@CatchupActivity, "This show was not recorded.", Toast.LENGTH_SHORT).show()
            }
        }
        focusable(this, 12f, 1.02f)
    }

    /**
     * Opens the catch-up player with this show and, for "next show", the channel's recorded shows after it.
     * [live]: the show on now, recorded up to this minute.
     */
    private fun watch(s: Show, live: Boolean = false) {
        if (!live && !canPlay(s)) { Toast.makeText(this, "This show was not recorded.", Toast.LENGTH_SHORT).show(); return }
        val now = System.currentTimeMillis()
        fun item(p: Web24Api.Programme, end: Long = p.end) = JSONObject().put("title", cleanTitle(p.title)).put("desc", p.description)
            .put("start", p.start).put("end", end)
        val shows = org.json.JSONArray().put(item(s.p, if (live) now - 60_000 else s.p.end))
        if (!live) recorded(s.ch).filter { it.p.start >= s.p.end }.sortedBy { it.p.start }.take(12).forEach { shows.put(item(it.p)) }
        startActivity(Intent(this, CatchupPlayerActivity::class.java)
            .putExtra(CatchupPlayerActivity.EXTRA_STREAM, s.ch.streamId)
            .putExtra(CatchupPlayerActivity.EXTRA_CHANNEL, cleanName(s.ch.name))
            .putExtra(CatchupPlayerActivity.EXTRA_LOGO, s.ch.icon.orEmpty())
            .putExtra(CatchupPlayerActivity.EXTRA_SHOWS, shows.toString()))
    }

    // ------------------------------------------------------------------------------------------------ artwork
    // Same as the home: titles are collected for a moment and asked 24 per request (support_api "art", TMDB).
    private val artDone = HashMap<String, JSONObject?>()
    private val artWait = HashMap<String, MutableList<(JSONObject) -> Unit>>()
    private val artQueue = LinkedHashSet<String>()
    private var artFlushing = false
    private val artFlush = Runnable { flushArt() }

    private fun art(title: String, onArt: (JSONObject) -> Unit) {
        when {
            title.isBlank() -> return
            artDone.containsKey(title) -> artDone[title]?.let(onArt)
            artWait.containsKey(title) -> artWait[title]!!.add(onArt)
            else -> { artWait[title] = mutableListOf(onArt); artQueue += title }
        }
        if (artQueue.isNotEmpty()) { handler.removeCallbacks(artFlush); handler.postDelayed(artFlush, 120) }
    }

    private fun flushArt() {
        if (artFlushing || artQueue.isEmpty()) return
        artFlushing = true
        val batch = artQueue.take(24); artQueue.removeAll(batch.toSet())
        lifecycleScope.launch {
            val r = runCatching { Web24Api(this@CatchupActivity).support("art", "titles" to batch.joinToString("\n")) }.getOrNull()
            val art = r?.optJSONObject("art")
            if (r == null || art == null || r.optBoolean("limited")) batch.forEach { artWait.remove(it) }
            else batch.forEach { t ->
                val a = art.optJSONObject(t)
                artDone[t] = a
                val cbs = artWait.remove(t).orEmpty()
                if (a != null) cbs.forEach { it(a) }
            }
            artFlushing = false
            if (artQueue.isNotEmpty()) handler.postDelayed(artFlush, if (r?.optBoolean("limited") == true) 20_000 else 50)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (com.network24.player.core.ui.TopBarFocus.up(this, hereTab, event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    companion object { const val EXTRA_STREAM = "stream_id"; private const val CACHE_FRESH_MS = 15 * 60_000L }
}
