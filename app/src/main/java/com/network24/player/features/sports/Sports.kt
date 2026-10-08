package com.network24.player.features.sports

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
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.network24.player.R
import com.network24.player.core.api.Web24Api
import com.network24.player.core.base.BaseActivity
import com.network24.player.features.catchup.CatchupActivity
import com.network24.player.features.dashboard.home.HomeFont
import com.network24.player.features.discover.CinemaPro
import com.network24.player.features.discover.EventCenter
import com.network24.player.features.discover.EventChannels
import com.network24.player.features.discover.EventsActivity
import com.network24.player.features.discover.Fmt
import com.network24.player.features.discover.Game
import com.network24.player.features.discover.GameCenter
import com.network24.player.features.discover.TeamAlerts
import com.network24.player.features.discover.Teams
import com.network24.player.features.guide.TvGuideActivity
import com.network24.player.features.livetv.LiveTvActivity
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Sports in the app's look: the home's top bar (Sports selected), a scoreboard billboard for the game in focus (both
 * teams' logos, records and score or start time, LIVE with the clock, venue and TV, the two teams' colours glowing
 * behind), tabs and league chips, then rows of score cards: Live now, Today, Tomorrow, the coming days (Results:
 * newest day first). Fights, golf, races and tennis have their own cards.
 *
 * Remote: the billboard follows the card in focus; OK on a card = Game Center (or the event page); hold OK / Menu =
 * Watch now or details. Watch on the billboard finds the channels showing it. Live scores refresh every minute.
 * My Teams and Find a Team open the team pages (follow, alerts) as before.
 */
class SportsActivity : BaseActivity() {
    private val uiScale by lazy { resources.displayMetrics.let { m -> minOf(1f, m.widthPixels / m.density / 960f, m.heightPixels / m.density / 400f) } }
    private val d by lazy { resources.displayMetrics.density * uiScale }
    private val fontD by lazy { d * resources.configuration.fontScale.coerceIn(0.85f, 1.15f) }
    private fun dp(v: Int) = (v * d).toInt()
    private fun dpf(v: Float) = v * d
    private val screenW by lazy { resources.displayMetrics.widthPixels }
    private val screenH by lazy { resources.displayMetrics.heightPixels }
    // phones in landscape have about 410 dp of height: the scoreboard is drawn a little smaller there
    private val compact by lazy { screenH / resources.displayMetrics.density < 500 }
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

    private var games: List<JSONObject> = emptyList()
    private var events: List<JSONObject> = emptyList()
    private var meta: Map<String, JSONObject> = emptyMap()
    private var tab = "games"
    private var league = ""
    private var heroItem: JSONObject? = null
    private var firstFocusDone = false

    private class Section(val title: String, val items: List<JSONObject>)
    private var sections: List<Section> = emptyList()

    private lateinit var glow: View
    private lateinit var waterAway: ImageView
    private lateinit var waterHome: ImageView
    private lateinit var heroBox: FrameLayout
    private lateinit var chipRow: LinearLayout
    private lateinit var rowsBox: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var busy: ProgressBar
    private lateinit var btnWatch: TextView
    private lateinit var btnInfo: TextView

    /** This page's tab in the top bar: UP into the bar lands there. */
    private var hereTab: android.view.View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
        load()
    }

    // ------------------------------------------------------------------------------------------------ building blocks
    private fun text(s: String, size: Float, color: Int = textMain, weight: Int = 500, lines: Int = 1) = TextView(this).apply {
        text = s; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * fontD); setTextColor(color); typeface = HomeFont.of(this@SportsActivity, weight)
        maxLines = lines; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
    }

    private fun shape(fill: Int, radius: Float, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dpf(radius); if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun focusable(v: View, radius: Float, scale: Float = 1.05f, ringColor: Int = Color.WHITE, onFocus: ((Boolean) -> Unit)? = null) {
        v.isFocusable = true; v.isClickable = true
        val stroke = dp(3)
        val ring = GradientDrawable().apply { cornerRadius = (dpf(radius) - stroke / 2f).coerceAtLeast(0f); setStroke(stroke, ringColor); setColor(Color.TRANSPARENT) }
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).translationZ(if (has) dpf(10f) else 0f).setDuration(130).start()
            view.foreground = if (has) ring else null
            onFocus?.invoke(has)
        }
    }

    private fun iconButton(icon: Int, label: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon); setColorFilter(textMain); setPadding(dp(8), dp(8), dp(8), dp(8)); contentDescription = label
        background = shape(0x14FFFFFF, 18f, 0x1FFFFFFF); setOnClickListener { onClick() }
        focusable(this, 18f, 1.08f)
        com.network24.player.core.ui.IconHint.attach(this, label)
    }

    private fun pill(label: String, primary: Boolean, onClick: () -> Unit) = text(label, 14f, if (primary) bg else textMain, 700).apply {
        gravity = Gravity.CENTER; setPadding(dp(20), dp(10), dp(20), dp(10))
        background = shape(if (primary) Color.WHITE else 0x26FFFFFF, 22f)
        setOnClickListener { onClick() }
        // white buttons get the violet ring (a white ring on white did not show), like Home and Catch-up
        focusable(this, 22f, 1.06f, if (primary) accent else Color.WHITE)
    }

    // ------------------------------------------------------------------------------------------------ data helpers
    private fun leagueName(code: String) = meta[code]?.optString("name")?.takeIf { it.isNotBlank() } ?: when (code) {
        "NCAAF" -> "College Football"; "NCAAB" -> "College Basketball"; "EPL" -> "Premier League"
        "UCL" -> "Champions League"; "LALIGA" -> "La Liga"; else -> code
    }
    private fun sport(code: String) = meta[code]?.optString("sport").orEmpty()
    private fun isEvent(g: JSONObject) = g.has("kind")
    private fun state(g: JSONObject) = g.optString("state")
    private fun startMs(g: JSONObject) = g.optLong("start") * 1000
    private fun teams(g: JSONObject) = GameCenter.teams(g)
    private fun short(t: JSONObject) = GameCenter.short(t)

    /** A team colour that shows on the dark page (near-black / near-white team colours fall back to the app's violet). */
    private fun glowColor(t: JSONObject): Int {
        val c = GameCenter.color(t, accent)
        val l = ColorUtils.calculateLuminance(c)
        return if (l < 0.03 || l > 0.85) accent else c
    }

    private fun followed(g: JSONObject): Boolean = !isEvent(g) && Game.parse(g)?.let { Teams.isFollowed(this, it) } == true

    private fun statusText(g: JSONObject): String = when (state(g)) {
        "in" -> g.optString("detail").ifBlank { "Live" }
        "post" -> g.optString("detail").ifBlank { "Final" }
        else -> if (isEvent(g)) EventCenter.dates(g) else if (g.optBoolean("tbd")) "Time TBD" else Fmt.clock(startMs(g))
    }

    // ------------------------------------------------------------------------------------------------ page
    private fun build() {
        val root = FrameLayout(this).apply { setBackgroundColor(bg) }
        val heroH = (screenH * 0.50f).toInt()
        // the two teams' colours glow behind the scoreboard, their logos large and faint at the edges
        glow = View(this).apply { alpha = 0f }
        root.addView(glow, FrameLayout.LayoutParams(-1, heroH))
        waterAway = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; alpha = 0f }
        root.addView(waterAway, FrameLayout.LayoutParams((heroH * 0.95f).toInt(), (heroH * 0.95f).toInt(), Gravity.TOP or Gravity.START).apply { marginStart = -(heroH * 0.22f).toInt(); topMargin = (heroH * 0.06f).toInt() })
        waterHome = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; alpha = 0f }
        root.addView(waterHome, FrameLayout.LayoutParams((heroH * 0.95f).toInt(), (heroH * 0.95f).toInt(), Gravity.TOP or Gravity.END).apply { marginEnd = -(heroH * 0.22f).toInt(); topMargin = (heroH * 0.06f).toInt() })
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x9908090C.toInt(), 0x3308090C, 0x6608090C, bg))
        }, FrameLayout.LayoutParams(-1, heroH + 2))

        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(48), dp(24), dp(48), 0); clipChildren = false; clipToPadding = false }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        val menu = iconButton(R.drawable.ic_more_vert, "More") {}
        page.addView(topBar(menu), LinearLayout.LayoutParams(-1, -2))

        heroBox = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        page.addView(heroBox, LinearLayout.LayoutParams(-1, heroH - dp(24) - dp(36) - dp(20)).apply { topMargin = dp(20) })
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        btnWatch = pill("▶  Watch", true) { heroItem?.let { watch(it) } }
        btnInfo = pill("Game Center", false) { heroItem?.let { open(it) } }
        btnRow.addView(btnWatch)
        btnRow.addView(btnInfo, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        heroBox.addView(btnRow, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(4) })
        btnRow.visibility = View.INVISIBLE; btnRow.tag = "btns"

        chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        page.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; setPadding(dp(8), dp(8), dp(8), dp(6)); addView(chipRow)
        }, LinearLayout.LayoutParams(-1, -2).apply { marginStart = -dp(8); topMargin = dp(8) })

        val box = FrameLayout(this)
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(20)) }
        rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(12), 0, 0, dp(28)) }
        scroll.addView(rowsBox)
        box.addView(scroll, FrameLayout.LayoutParams(-1, -1).apply { marginStart = -dp(12) })
        status = text("Loading games…", 15f, textSub, 600, lines = 3).apply { gravity = Gravity.CENTER }
        box.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        busy = ProgressBar(this).apply { visibility = View.GONE; indeterminateTintList = android.content.res.ColorStateList.valueOf(accent) }
        box.addView(busy, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        page.addView(box, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(setupGlobalRightDrawer(root, menu))
    }

    private fun topBar(menu: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        // brand: the Network24 logo - mark and wordmark in one image (4:1)
        addView(ImageView(this@SportsActivity).apply { setImageResource(R.drawable.app_logo_wide); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START }, LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(16) })
        fun go(cls: Class<*>) { startActivity(Intent(this@SportsActivity, cls)); finish() }
        val tabs = listOf<Pair<String, () -> Unit>>(
            "Home" to { finish() },
            "Live TV" to { go(LiveTvActivity::class.java) },
            "Movies" to { CinemaPro.open(this@SportsActivity) },
            "Sports" to { focusFirstCard() },
            "TV Guide" to { go(TvGuideActivity::class.java) },
            "Catch-up" to { go(CatchupActivity::class.java) },
        )
        val tabRow = LinearLayout(this@SportsActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(3), dp(3), dp(3), dp(3)); background = shape(0x14FFFFFF, 21f, 0x1FFFFFFF) }
        tabs.forEachIndexed { i, (label, onClick) ->
            val here = label == "Sports"
            val t = text(label, 13.5f, if (here) Color.parseColor("#08090C") else textSub, if (here) 700 else 600).apply {
                setPadding(dp(12), dp(7), dp(12), dp(7)); background = if (here) shape(Color.WHITE, 18f) else null
                setOnClickListener { onClick() }
            }
            focusable(t, 18f, 1.04f)
            if (here) hereTab = t
            tabRow.addView(t, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(2) })
        }
        addView(HorizontalScrollView(this@SportsActivity).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true; addView(android.widget.FrameLayout(this@SportsActivity).apply { addView(tabRow, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)) }) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(iconButton(R.drawable.ic_h_search, "Search") {
            com.network24.player.features.search.SearchOverlay.show(this@SportsActivity)
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(12) })
        addView(iconButton(R.drawable.ic_live_chat, "Live Support") {
            com.network24.player.features.help.HelpCenter.show(this@SportsActivity)
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(8) })
        // account and settings are in the More menu (the owner wants a shorter bar)
        addView(menu, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(8) })
    }

    // ------------------------------------------------------------------------------------------------ data
    private fun load(quiet: Boolean = false) = lifecycleScope.launch {
        if (!quiet) busy.visibility = View.VISIBLE
        val r = runCatching { Web24Api(this@SportsActivity).support("scores") }.getOrNull()
        busy.visibility = View.GONE
        if (r == null) {
            if (games.isEmpty()) { status.visibility = View.VISIBLE; status.text = "Scores could not be loaded.\nCheck the internet connection and try again." }
            return@launch
        }
        games = Web24Api.objects(r.optJSONArray("games"))
        events = Web24Api.objects(r.optJSONArray("events"))
        meta = Web24Api.objects(r.optJSONArray("leagues")).associateBy { it.optString("code") }
        TeamAlerts.update(this@SportsActivity, games.mapNotNull { Game.parse(it) })
        render(keepFocus = quiet)
    }

    /** League chips in the console's order, only the leagues that have something in this tab. */
    private fun presentLeagues(): List<String> {
        val all = (games + events).filter { if (tab == "results") state(it) == "post" else state(it) != "post" }.map { it.optString("league") }.toSet()
        val order = meta.values.sortedBy { it.optInt("order", 999) }.map { it.optString("code") }
        return order.filter { it in all } + all.filter { it !in order }
    }

    private fun buildSections(): List<Section> {
        val list = (games + events).filter { g -> (league == "" || g.optString("league") == league) && ((state(g) == "post") == (tab == "results")) }
        if (tab == "results") {
            return list.sortedByDescending { it.optLong("end").takeIf { e -> e > 0 } ?: it.optLong("start") }
                .groupBy { Fmt.day(startMs(it)) }.map { (day, l) -> Section(day, l) }
        }
        val out = mutableListOf<Section>()
        val liveNow = list.filter { state(it) == "in" }.sortedWith(compareBy({ if (followed(it)) 0 else 1 }, { it.optLong("start") }))
        if (liveNow.isNotEmpty()) out += Section("Live now", liveNow)
        val mine = list.filter { state(it) == "pre" && followed(it) }.sortedBy { it.optLong("start") }
        if (mine.isNotEmpty()) out += Section("★  Your teams", mine)
        list.filter { state(it) == "pre" }.sortedBy { it.optLong("start") }.groupBy { Fmt.day(startMs(it)) }.forEach { (day, l) -> out += Section(day, l) }
        return out
    }

    private fun render(keepFocus: Boolean = false) {
        renderChips()
        val next = buildSections()
        val same = next.size == sections.size && next.zip(sections).all { (a, b) -> a.title == b.title && a.items.map { key(it) } == b.items.map { key(it) } }
        sections = next
        status.visibility = if (next.isEmpty()) View.VISIBLE else View.GONE
        status.text = if (tab == "results") "No results yet." else "Nothing live or coming up right now.\nCheck Results for final scores."
        if (same && keepFocus) {
            // live refresh: same cards, new scores; nothing is rebuilt so the remote's focus stays put
            for (i in 0 until rowsBox.childCount) {
                val rv = (rowsBox.getChildAt(i) as? LinearLayout)?.getChildAt(1) as? RecyclerView ?: continue
                (rv.adapter as? CardAdapter)?.let { it.items = sections[i].items; it.notifyItemRangeChanged(0, it.items.size, "score") }
            }
            heroItem?.let { h -> (games + events).firstOrNull { key(it) == key(h) }?.let { showHero(it) } }
            return
        }
        rowsBox.removeAllViews()
        next.forEachIndexed { i, s -> rowsBox.addView(sectionView(s, i)) }
        scroll.scrollTo(0, 0)
        val feature = pickFeatured()
        if (feature != null) showHero(feature) else clearHero()
        if (!firstFocusDone && next.isNotEmpty()) { firstFocusDone = true; focusFirstCard() }
    }

    private fun key(g: JSONObject) = g.optString("league") + ":" + g.optString("id") + ":" + g.optLong("start")

    /** Billboard at the start: your team live, any live game, your team's next game, else the next big game. */
    private fun pickFeatured(): JSONObject? {
        val pool = sections.flatMap { it.items }
        return pool.firstOrNull { state(it) == "in" && followed(it) } ?: pool.firstOrNull { state(it) == "in" && !isEvent(it) }
            ?: pool.firstOrNull { state(it) == "pre" && followed(it) } ?: pool.firstOrNull { !isEvent(it) } ?: pool.firstOrNull()
    }

    private fun renderChips() {
        val hadFocus = chipRow.hasFocus()
        chipRow.removeAllViews()
        val tabs = listOf("games" to "Live & Upcoming", "results" to "Results", "mine" to "My Teams", "find" to "Find a Team")
        tabs.forEach { (k, label) -> chipRow.addView(chip(label, k == tab, "tab:$k") { pickTab(k) }) }
        val lgs = presentLeagues()
        if (league.isNotEmpty() && league !in lgs) league = ""
        if (lgs.size > 1) {
            chipRow.addView(View(this).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(dp(1), dp(24)).apply { marginStart = dp(6); marginEnd = dp(14) })
            chipRow.addView(chip("All", league == "", "lg:") { pickLeague("") })
            lgs.forEach { c -> chipRow.addView(chip(leagueName(c), c == league, "lg:$c") { pickLeague(c) }) }
        }
        if (hadFocus) chipRow.post { chipRow.findViewWithTag<View>("lg:$league")?.takeIf { tab == "games" || tab == "results" }?.requestFocus() ?: chipRow.findViewWithTag<View>("tab:$tab")?.requestFocus() }
    }

    private fun chip(label: String, on: Boolean, tag: String, onClick: () -> Unit) = text(label, 14f, if (on) bg else textMain, 700).apply {
        setPadding(dp(16), dp(9), dp(16), dp(9)); background = shape(if (on) Color.WHITE else 0x1AFFFFFF, 18f); this.tag = tag
        setOnClickListener { onClick() }
        focusable(this, 18f, 1.05f, if (on) accent else Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
    }

    private fun pickTab(k: String) {
        when (k) {
            "mine", "find" -> startActivity(Intent(this, EventsActivity::class.java).putExtra(EventsActivity.EXTRA_TAB, k))
            else -> { if (tab == k) return; tab = k; league = ""; render() }
        }
    }

    private fun pickLeague(c: String) { if (league == c) return; league = c; render() }

    // ------------------------------------------------------------------------------------------------ rows of cards
    private fun sectionView(s: Section, index: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
        val head = LinearLayout(this@SportsActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(dp(2), dp(14), 0, dp(4)) }
        if (s.title == "Live now") head.addView(View(this@SportsActivity).apply { background = shape(live, 4f) }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(8); bottomMargin = dp(5) })
        head.addView(text(s.title, 17f, textMain, 800))
        head.addView(text("${s.items.size}", 13f, textSub, 600).apply { setPadding(dp(10), 0, 0, dp(1)) })
        addView(head)
        val rv = RecyclerView(this@SportsActivity).apply {
            layoutManager = LinearLayoutManager(this@SportsActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = CardAdapter(s.items); itemAnimator = null; clipChildren = false; clipToPadding = false
            setPadding(dp(4), dp(10), dp(40), dp(10)); isHorizontalScrollBarEnabled = false; isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS; tag = "row:$index"
        }
        addView(rv, LinearLayout.LayoutParams(-1, dp(136)))
    }

    private inner class CardAdapter(var items: List<JSONObject>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = if (isEvent(items[position])) 1 else 0
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val card = if (viewType == 1) eventCard() else gameCard()
            card.layoutParams = RecyclerView.LayoutParams(dp(270), -1).apply { marginEnd = dp(12) }
            return object : RecyclerView.ViewHolder(card) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val g = items[position]
            val v = holder.itemView as LinearLayout
            if (isEvent(g)) bindEvent(v, g) else bindGame(v, g)
            focusable(v, 14f, 1.06f) { has -> if (has) { pendingHero?.let { handler.removeCallbacks(it) }; pendingHero = Runnable { showHero(g) }.also { handler.postDelayed(it, 180) } } }
            v.setOnClickListener { open(g) }
            v.setOnLongClickListener { options(g); true }
            v.tag = g
        }
    }

    private var pendingHero: Runnable? = null

    private fun cardBg(g: JSONObject): GradientDrawable {
        val isLive = state(g) == "in"
        if (isEvent(g)) return shape(surface, 14f, if (isLive) 0x99E5484D.toInt() else line)
        val (away, home) = teams(g)
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
            ColorUtils.blendARGB(surface, glowColor(away), 0.20f), surface, ColorUtils.blendARGB(surface, glowColor(home), 0.20f))).apply {
            cornerRadius = dpf(14f); setStroke(dp(1), if (isLive) 0x99E5484D.toInt() else line)
        }
    }

    private fun cardTop(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(text("", 11f, accentSoft, 800).apply { letterSpacing = 0.1f }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(text("", 11f, textSub, 700).apply { fontFeatureSettings = "tnum"; setPadding(dp(6), dp(2), dp(6), dp(2)) })
    }

    private fun bindTop(top: LinearLayout, g: JSONObject) {
        (top.getChildAt(0) as TextView).text = leagueName(g.optString("league")).uppercase().let { if (followed(g)) "★ $it" else it }
        val st = top.getChildAt(1) as TextView
        val isLive = state(g) == "in"
        st.text = if (isLive) "● " + statusText(g) else if (state(g) == "pre" && !isEvent(g) && tab != "results" && Fmt.day(startMs(g)) != "Today") "${Fmt.day(startMs(g))} · ${statusText(g)}" else statusText(g)
        st.setTextColor(if (isLive) Color.WHITE else textSub)
        st.background = if (isLive) shape(live, 4f) else null
    }

    private fun teamLine(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(this@SportsActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(28), dp(28)))
        addView(text("", 15f, textMain, 700), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        addView(text("", 11f, textSub, 600).apply { fontFeatureSettings = "tnum"; setPadding(dp(8), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(text("", 20f, textMain, 800).apply { fontFeatureSettings = "tnum" })
    }

    private fun gameCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(16), dp(12))
        addView(cardTop())
        addView(teamLine(), LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
        addView(teamLine(), LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun bindGame(v: LinearLayout, g: JSONObject) {
        v.background = cardBg(g)
        bindTop(v.getChildAt(0) as LinearLayout, g)
        val (away, home) = teams(g)
        val st = state(g)
        listOf(away to v.getChildAt(1) as LinearLayout, home to v.getChildAt(2) as LinearLayout).forEach { (t, row) ->
            GameCenter.logo(row.getChildAt(0) as ImageView, t.optString("logo"))
            val lost = st == "post" && t.has("winner") && !t.optBoolean("winner")
            (row.getChildAt(1) as TextView).apply { text = short(t); setTextColor(if (lost) textSub else textMain) }
            (row.getChildAt(2) as TextView).text = t.optString("record")
            (row.getChildAt(3) as TextView).apply { text = if (st == "pre") "" else t.optString("score"); setTextColor(if (lost) textSub else textMain) }
        }
    }

    private fun eventCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(16), dp(12))
        addView(cardTop())
        addView(text("", 15f, textMain, 700, lines = 2).apply { setPadding(0, dp(8), 0, 0); setLineSpacing(0f, 1.1f) })
        addView(text("", 12f, textSub, 600, lines = 2).apply { setPadding(0, dp(6), 0, 0) })
    }

    private fun bindEvent(v: LinearLayout, e: JSONObject) {
        v.background = cardBg(e)
        bindTop(v.getChildAt(0) as LinearLayout, e)
        (v.getChildAt(1) as TextView).text = e.optString("short").ifBlank { e.optString("name") }
        (v.getChildAt(2) as TextView).text = eventLine(e)
    }

    private fun eventLine(e: JSONObject): String {
        val liveNow = EventCenter.list(e.optJSONArray("live"))
        return when {
            state(e) == "in" && liveNow.isNotEmpty() -> "Now: " + liveNow.joinToString(" · ")
            e.optString("headline").isNotBlank() -> e.optString("headline")
            else -> listOf(e.optString("venue"), e.optString("city")).filter { it.isNotBlank() }.joinToString(", ")
        }
    }

    private fun focusFirstCard(tries: Int = 0) {
        val rv = rowsBox.findViewWithTag<RecyclerView>("row:0")
        val v = rv?.layoutManager?.findViewByPosition(0)
        if (v == null) { if (tries < 20) handler.postDelayed({ focusFirstCard(tries + 1) }, 50); return }
        v.requestFocus()
    }

    // ------------------------------------------------------------------------------------------------ billboard
    private fun clearHero() {
        heroItem = null
        heroBox.removeViews(0, heroBox.childCount - 1)
        heroBox.findViewWithTag<View>("btns")?.visibility = View.INVISIBLE
        glow.animate().alpha(0f).setDuration(200).start(); waterAway.animate().alpha(0f).start(); waterHome.animate().alpha(0f).start()
    }

    private fun showHero(g: JSONObject) {
        val same = heroItem?.let { key(it) == key(g) } == true
        heroItem = g
        // keep the buttons (last child), replace the scoreboard
        while (heroBox.childCount > 1) heroBox.removeViewAt(0)
        heroBox.addView(if (isEvent(g)) eventBoard(g) else gameBoard(g), 0, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(52) })
        heroBox.findViewWithTag<View>("btns")?.visibility = View.VISIBLE
        btnInfo.text = if (isEvent(g)) "Details" else "Game Center"
        btnWatch.alpha = if (state(g) == "post") 0.5f else 1f
        if (same) return
        if (isEvent(g)) {
            glow.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ColorUtils.setAlphaComponent(accent, 0x55), 0x0008090C, ColorUtils.setAlphaComponent(accent, 0x33)))
            waterAway.animate().alpha(0f).setDuration(200).start(); waterHome.animate().alpha(0f).setDuration(200).start()
        } else {
            val (away, home) = teams(g)
            glow.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(
                ColorUtils.setAlphaComponent(glowColor(away), 0x8C), ColorUtils.setAlphaComponent(glowColor(away), 0x1A), 0x0008090C,
                ColorUtils.setAlphaComponent(glowColor(home), 0x1A), ColorUtils.setAlphaComponent(glowColor(home), 0x8C)))
            waterAway.alpha = 0f; waterHome.alpha = 0f
            GameCenter.logo(waterAway, away.optString("logo")); GameCenter.logo(waterHome, home.optString("logo"))
            waterAway.animate().alpha(0.10f).setDuration(500).start(); waterHome.animate().alpha(0.10f).setDuration(500).start()
        }
        glow.alpha = 0f; glow.animate().alpha(1f).setDuration(450).start()
    }

    private fun gameBoard(g: JSONObject) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        val (away, home) = teams(g)
        val st = state(g)
        // league and state over the board
        val tagRow = LinearLayout(this@SportsActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        tagRow.addView(text(leagueName(g.optString("league")).uppercase(), 12f, Color.parseColor("#D9F2F3F5"), 800).apply { letterSpacing = 0.16f })
        if (followed(g)) tagRow.addView(text("★ YOUR TEAM", 11f, gold, 800).apply { letterSpacing = 0.12f; setPadding(dp(14), 0, 0, 0) })
        addView(tagRow)

        val board = LinearLayout(this@SportsActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun side(t: JSONObject, logoFirst: Boolean) = LinearLayout(this@SportsActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            val logo = ImageView(this@SportsActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; GameCenter.logo(this, t.optString("logo")) }
            val words = LinearLayout(this@SportsActivity).apply {
                orientation = LinearLayout.VERTICAL; gravity = if (logoFirst) Gravity.START else Gravity.END
                addView(text(short(t), if (compact) 20f else 24f, textMain, 800).apply { letterSpacing = -0.01f })
                addView(text(listOf(t.optString("record"), if (t.optBoolean("home")) "Home" else "Away").filter { it.isNotBlank() }.joinToString(" · "), 12f, textSub, 600).apply { setPadding(0, dp(4), 0, 0) })
            }
            if (logoFirst) { addView(logo, LinearLayout.LayoutParams(dp(if (compact) 52 else 66), dp(if (compact) 52 else 66))); addView(words, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) }) }
            else { addView(words, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(16) }); addView(logo, LinearLayout.LayoutParams(dp(if (compact) 52 else 66), dp(if (compact) 52 else 66))) }
        }
        board.addView(side(away, true), LinearLayout.LayoutParams(0, -2, 1f))
        val mid = LinearLayout(this@SportsActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(28), 0, dp(28), 0) }
        if (st == "pre") {
            mid.addView(text(if (g.optBoolean("tbd")) "TBD" else Fmt.clock(startMs(g)), if (compact) 28f else 34f, textMain, 800).apply { fontFeatureSettings = "tnum"; gravity = Gravity.CENTER })
            mid.addView(text(Fmt.day(startMs(g)).uppercase(), 12f, accentSoft, 800).apply { letterSpacing = 0.14f; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        } else {
            val score = LinearLayout(this@SportsActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            val aLost = st == "post" && away.has("winner") && !away.optBoolean("winner")
            val hLost = st == "post" && home.has("winner") && !home.optBoolean("winner")
            score.addView(text(away.optString("score"), if (compact) 40f else 50f, if (aLost) textSub else textMain, 800).apply { fontFeatureSettings = "tnum" })
            score.addView(text("–", 34f, textSub, 600).apply { setPadding(dp(16), 0, dp(16), 0) })
            score.addView(text(home.optString("score"), if (compact) 40f else 50f, if (hLost) textSub else textMain, 800).apply { fontFeatureSettings = "tnum" })
            mid.addView(score)
            mid.addView(text(if (st == "in") "●  LIVE  ·  " + statusText(g).uppercase() else statusText(g).uppercase(), 12f, Color.WHITE, 800).apply {
                letterSpacing = 0.1f; gravity = Gravity.CENTER; setPadding(dp(10), dp(4), dp(10), dp(4))
                background = shape(if (st == "in") live else 0x33FFFFFF, 4f)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8); gravity = Gravity.CENTER })
        }
        board.addView(mid)
        board.addView(side(home, false), LinearLayout.LayoutParams(0, -2, 1f))
        addView(board, LinearLayout.LayoutParams((screenW * 0.78f).toInt(), -2).apply { topMargin = dp(if (compact) 6 else 12) })

        val tv = EventCenter.list(g.optJSONArray("tv"))
        val info = listOf(g.optString("venue"), if (tv.isNotEmpty()) "TV: " + tv.joinToString(", ") else "").filter { it.isNotBlank() }.joinToString("   ·   ")
        if (info.isNotBlank()) addView(text(info, 13f, textSub, 600).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) })
    }

    private fun eventBoard(e: JSONObject) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        val st = state(e)
        val tagRow = LinearLayout(this@SportsActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        tagRow.addView(text(leagueName(e.optString("league")).uppercase(), 12f, Color.parseColor("#D9F2F3F5"), 800).apply { letterSpacing = 0.16f })
        tagRow.addView(text(if (st == "in") "●  LIVE" else statusText(e).uppercase(), 11f, Color.WHITE, 800).apply {
            letterSpacing = 0.1f; setPadding(dp(8), dp(3), dp(8), dp(3)); background = shape(if (st == "in") live else 0x33FFFFFF, 4f)
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        addView(tagRow)
        addView(text(e.optString("name"), 30f, textMain, 800, lines = 2).apply { gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0); letterSpacing = -0.01f },
            LinearLayout.LayoutParams((screenW * 0.7f).toInt(), -2))
        val line1 = eventLine(e)
        if (line1.isNotBlank()) addView(text(line1, 15f, Color.parseColor("#D9F2F3F5"), 600, lines = 2).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) },
            LinearLayout.LayoutParams((screenW * 0.7f).toInt(), -2))
        val tv = EventCenter.list(e.optJSONArray("tv"))
        val info = listOf(listOf(e.optString("venue"), e.optString("city")).filter { it.isNotBlank() }.joinToString(", "), if (tv.isNotEmpty()) "TV: " + tv.joinToString(", ") else "")
            .filter { it.isNotBlank() }.joinToString("   ·   ")
        if (info.isNotBlank()) addView(text(info, 13f, textSub, 600).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) })
    }

    // ------------------------------------------------------------------------------------------------ actions
    private fun withSport(g: JSONObject) = JSONObject(g.toString()).put("sport", sport(g.optString("league"))).put("league_name", leagueName(g.optString("league")))

    /** Game Center for a game, the event page for a fight card / tournament / race. */
    private fun open(g: JSONObject) {
        if (isEvent(g)) EventCenter.open(this, g, leagueName(g.optString("league")))
        else if (g.optString("id").isNotBlank()) GameCenter.open(this, withSport(g))
        else watch(g)
    }

    /** Finds the channels showing it and opens them (list + preview, like Live TV). */
    private fun watch(g: JSONObject) {
        if (state(g) == "post") { open(g); return }
        val onBusy: (Boolean) -> Unit = { busy.visibility = if (it) View.VISIBLE else View.GONE }
        if (isEvent(g)) EventChannels.watch(this, g, null, onBusy)
        else Game.parse(g)?.let { GameCenter.watch(this, it, onBusy) }
    }

    private fun options(g: JSONObject) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (state(g) != "post") items += "Watch now" to { watch(g) }
        items += (if (isEvent(g)) "Details" else "Game Center") to { open(g) }
        if (!isEvent(g)) {
            val (away, home) = teams(g)
            listOf(away, home).forEach { t -> items += "${t.optString("name").ifBlank { short(t) }} - team page" to { GameCenter.openTeam(this, g.optString("league"), t.optString("id"), t.optString("name")) } }
        }
        val title = if (isEvent(g)) g.optString("short").ifBlank { g.optString("name") } else teams(g).let { (a, h) -> "${short(a)} @ ${short(h)}" }
        showChoiceDialog(title = title, items = items.map { it.first }, selectedIndex = -1, focusIndex = 0) { which -> items[which].second() }
    }

    /**
     * The remote's way through the page, set by hand: Android's own search lost its way between the horizontal
     * rows inside the vertical scroll (UP did nothing, DOWN from the chips needed three presses, UP from the first
     * row landed on whichever chip sat above). Cards: UP / DOWN = the nearest card of the row above / below,
     * first row UP = the chosen chip; chips: DOWN = first row, UP = Watch; Watch / Game Center: DOWN = the chips.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (com.network24.player.core.ui.TopBarFocus.up(this, hereTab, event)) return true
        val f = currentFocus
        if (event.action == KeyEvent.ACTION_DOWN && f != null) {
            val card = f.tag as? JSONObject
            val rv = f.parent as? RecyclerView
            val row = (rv?.tag as? String)?.removePrefix("row:")?.toIntOrNull()
            val chipTag = f.tag as? String
            when {
                event.keyCode == KeyEvent.KEYCODE_MENU && card != null -> { options(card); return true }
                card != null && row != null && event.keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                    if (row == 0) selectedChip()?.requestFocus() else focusRow(row - 1, centerX(f))
                    return true
                }
                card != null && row != null && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (row < sections.size - 1) focusRow(row + 1, centerX(f))
                    return true
                }
                card != null && rv != null && (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -> {
                    val pos = rv.getChildAdapterPosition(f)
                    val last = (rv.adapter?.itemCount ?: 1) - 1
                    // the row's ends keep the focus (it jumped to another row or the chips)
                    if ((event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && pos >= last) || (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && pos <= 0)) return true
                }
                chipTag != null && (chipTag.startsWith("tab:") || chipTag.startsWith("lg:")) && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (sections.isNotEmpty()) focusRow(0, -1)
                    return true
                }
                chipTag != null && (chipTag.startsWith("tab:") || chipTag.startsWith("lg:")) && event.keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                    if (btnWatch.isShown) btnWatch.requestFocus() else return super.dispatchKeyEvent(event)
                    return true
                }
                (f === btnWatch || f === btnInfo) && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> { selectedChip()?.requestFocus(); return true }
                f === btnWatch && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> return true
                f === btnInfo && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun centerX(v: View): Int { val p = IntArray(2); v.getLocationOnScreen(p); return p[0] + v.width / 2 }

    /** The chosen league chip (or the chosen tab when the leagues are not shown). */
    private fun selectedChip(): View? = chipRow.findViewWithTag<View>("lg:$league")?.takeIf { tab == "games" || tab == "results" } ?: chipRow.findViewWithTag("tab:$tab")

    /** Focuses the card of row [index] nearest to screen x [x] (-1: the first card), scrolling the row into view. */
    private fun focusRow(index: Int, x: Int, tries: Int = 0) {
        val rv = rowsBox.findViewWithTag<RecyclerView>("row:$index") ?: return
        val section = rv.parent as View
        scroll.smoothScrollTo(0, (section.top - dp(8)).coerceAtLeast(0))
        val kids = (0 until rv.childCount).map { rv.getChildAt(it) }.filter { it.isShown }
        val target = if (x < 0) rv.layoutManager?.findViewByPosition((rv.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition().coerceAtLeast(0))
            else kids.minByOrNull { Math.abs(centerX(it) - x) }
        if (target == null) { if (tries < 10) handler.postDelayed({ focusRow(index, x, tries + 1) }, 50); return }
        target.requestFocus()
    }

    // ------------------------------------------------------------------------------------------------ lifecycle
    private val refresh = object : Runnable {
        override fun run() { load(quiet = true); handler.postDelayed(this, 60_000) }
    }

    override fun onStart() { super.onStart(); handler.postDelayed(refresh, 60_000) }
    override fun onStop() { handler.removeCallbacks(refresh); super.onStop() }

    override fun onRestart() {
        super.onRestart()
        // back from My Teams / Find a Team: followed teams may have changed
        load(quiet = true)
    }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
