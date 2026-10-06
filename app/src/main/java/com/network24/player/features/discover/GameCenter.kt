package com.network24.player.features.discover

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import coil.load
import com.network24.player.R
import com.network24.player.core.api.Web24Api
import com.network24.player.core.base.BaseActivity
import com.network24.player.features.live.activity.ChannelListActivity
import com.network24.player.features.player.state.PlayerState
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Opens the Game Center for a game from Scores / a team schedule (needs ESPN's event id), or its channels without one. */
object GameCenter {
    fun open(c: Context, g: JSONObject) {
        val id = g.optString("id")
        if (id.isBlank()) {
            Toast.makeText(c, "Game details are not available for this game yet.", Toast.LENGTH_SHORT).show()
            return
        }
        c.startActivity(Intent(c, GameActivity::class.java).putExtra(GameActivity.EXTRA_GAME, g.toString()))
    }

    fun openTeam(c: Context, league: String, id: String, name: String) {
        if (id.isBlank()) return
        c.startActivity(Intent(c, TeamActivity::class.java).putExtra(TeamActivity.EXTRA_LEAGUE, league)
            .putExtra(TeamActivity.EXTRA_ID, id).putExtra(TeamActivity.EXTRA_NAME, name))
    }

    /** Finds the channels showing the game and opens them like a Live TV category (list + preview). */
    fun watch(a: android.app.Activity, game: Game, onBusy: (Boolean) -> Unit) {
        onBusy(true)
        (a as androidx.lifecycle.LifecycleOwner).lifecycleScope.launch {
            val chans = GameChannels.find(a, game)
            onBusy(false)
            if (chans.isEmpty()) {
                Toast.makeText(a, if (game.state == "post") "${game.title} has ended."
                    else "No channel lists ${game.title} yet. Event channels usually add it shortly before the start.", Toast.LENGTH_LONG).show()
                return@launch
            }
            PlayerState.currentPosition = 0
            a.startActivity(Intent(a, ChannelListActivity::class.java)
                .putExtra("category_name", "${game.title} · ${game.league}")
                .putExtra(ChannelListActivity.EXTRA_STREAM_IDS, chans.map { it.streamId }.toIntArray()))
        }
    }

    fun teams(g: JSONObject): Pair<JSONObject, JSONObject> {
        val t = Web24Api.objects(g.optJSONArray("teams"))
        val home = t.firstOrNull { it.optBoolean("home") } ?: t.getOrNull(0) ?: JSONObject()
        val away = t.firstOrNull { it !== home } ?: t.getOrNull(1) ?: JSONObject()
        return away to home
    }

    fun short(t: JSONObject) = t.optString("short").ifBlank { t.optString("name") }

    /** ESPN's logo made for dark backgrounds (a black Iowa hawk is invisible on our cards). */
    fun darkLogo(url: String) = if ("espncdn.com/i/teamlogos/" in url) url.replace("/500/", "/500-dark/") else url

    /** Dark-background logo, falling back to the normal one when a team has no dark version. */
    fun logo(iv: ImageView, url: String) {
        val dark = darkLogo(url)
        iv.load(dark) { if (dark != url) listener(onError = { _, _ -> iv.load(url) }) }
    }

    /** "LAL 127" style colour of a team, falling back to the app purple. */
    fun color(t: JSONObject, fallback: Int): Int =
        runCatching { Color.parseColor("#" + t.optString("color").trim().removePrefix("#")) }.getOrDefault(fallback)
}

/**
 * Game Center: big scoreboard with both teams (logo, record, score), status, venue and TV network, a Watch button that
 * finds our channels, the score by period, top performers and team stats. Live games refresh every 30 seconds.
 */
class GameActivity : BaseActivity() {
    private lateinit var body: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var game: JSONObject
    private var summary: JSONObject? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable { override fun run() { load(); handler.postDelayed(this, 30_000) } }
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()
    private fun col(id: Int) = ContextCompat.getColor(this, id)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (blockedByGate) return
        game = runCatching { JSONObject(intent.getStringExtra(EXTRA_GAME).orEmpty()) }.getOrNull() ?: run { finish(); return }
        val root = FrameLayout(this).apply { setBackgroundResource(R.drawable.bg_dashboard) }
        val scroll = ScrollView(this).apply { isFillViewport = true; isFocusable = false }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(24)) }
        scroll.addView(body)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
        setContentView(root)
        draw()
        load()
    }

    override fun onResume() { super.onResume(); if (game.optString("state") == "in") handler.postDelayed(refresh, 30_000) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(refresh) }

    private fun load() {
        if (summary == null) progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val s = runCatching { Web24Api(this@GameActivity).support("game", "league" to game.optString("league"), "id" to game.optString("id")) }.getOrNull()
            progress.visibility = View.GONE
            if (s != null && s.optBoolean("ok")) { summary = s; draw() }
        }
    }

    // ------------------------------------------------------------------------------------------------ drawing
    private fun text(s: String, size: Float, color: Int = col(R.color.text_primary), bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    /** A card that takes the remote's focus, so DOWN walks (and scrolls) through the sections. */
    private fun card(focusable: Boolean = true) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_settings_action)
        setPadding(dp(18), dp(14), dp(18), dp(14))
        isFocusable = focusable; isClickable = false
    }

    private fun add(v: View, top: Int = 12) = body.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })

    private fun section(title: String) = card().also { it.tag = title; it.addView(text(title.uppercase(), 12f, col(R.color.primary_light), true).apply { letterSpacing = 0.12f }) }

    private fun draw() {
        val focused = currentFocus?.tag
        body.removeAllViews()
        val s = summary
        val src = s ?: game
        val (away, home) = GameCenter.teams(src)
        val state = src.optString("state")
        val league = src.optString("league")

        // top bar: back + league / note
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back); setColorFilter(Color.WHITE); setBackgroundResource(R.drawable.bg_back_focus)
            contentDescription = "Back"; isFocusable = true; tag = "back"; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        bar.addView(text(listOf(game.optString("league_name").ifBlank { league }, s?.optString("note").orEmpty()).filter { it.isNotBlank() }.joinToString(" · "), 18f, bold = true).apply { setPadding(dp(14), 0, 0, 0) })
        body.addView(bar)

        // scoreboard
        val board = card(false).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18)) }
        board.addView(teamColumn(away), LinearLayout.LayoutParams(0, -2, 1f))
        board.addView(centre(away, home, state, src), LinearLayout.LayoutParams(0, -2, 1.3f))
        board.addView(teamColumn(home), LinearLayout.LayoutParams(0, -2, 1f))
        add(board, 14)

        // facts line
        val facts = mutableListOf<String>()
        val venue = s?.optString("venue").orEmpty().ifBlank { game.optString("venue") }
        if (venue.isNotBlank()) facts += venue + (s?.optString("city")?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: "")
        if ((s?.optInt("attendance") ?: 0) > 0) facts += "Attendance " + String.format(java.util.Locale.US, "%,d", s!!.optInt("attendance"))
        val tv = (s?.optJSONArray("tv")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty() +
            (game.optJSONArray("tv")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty())).distinct().filter { it.isNotBlank() }
        if (tv.isNotEmpty()) facts += "TV: " + tv.joinToString(", ")
        if (facts.isNotEmpty()) add(text(facts.joinToString("  ·  "), 14f, col(R.color.text_hint)).apply { gravity = Gravity.CENTER })

        // actions
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val parsed = Game.parse(game)
        if (state != "post" && parsed != null) actions.addView(button(if (state == "in") "▶  Watch live" else "▶  Find channels", true, "watch") {
            GameCenter.watch(this, parsed) { progress.visibility = if (it) View.VISIBLE else View.GONE }
        }, LinearLayout.LayoutParams(0, dp(48), 1.4f))
        listOf(away, home).forEach { t ->
            actions.addView(button(GameCenter.short(t) + "  ›", false, "team" + t.optString("id")) {
                GameCenter.openTeam(this, league, t.optString("id"), t.optString("name"))
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(10) })
        }
        add(actions, 14)

        if (s != null) {
            lineScore(away, home)
            leaders(s, away, home)
            stats(s, away, home)
        }

        // keep the remote where it was after a live refresh, else start on Watch (or the first team)
        val again = (0 until body.childCount).asSequence().flatMap { allViews(body.getChildAt(it)) }.firstOrNull { focused != null && it.tag == focused }
        (again ?: body.findViewWithTag<View>("watch") ?: body.findViewWithTag("team" + away.optString("id")))?.let { v -> v.post { v.requestFocus() } }
    }

    private fun allViews(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(allViews(v.getChildAt(i)))
    }

    private fun button(label: String, primary: Boolean, key: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE); setBackgroundResource(if (primary) R.drawable.bg_primary_action else R.drawable.bg_outline_pill)
        isFocusable = true; isClickable = true; tag = key; setOnClickListener { onClick() }
    }

    private fun teamColumn(t: JSONObject) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(ImageView(this@GameActivity).apply { GameCenter.logo(this, t.optString("logo")); scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(76), dp(76)))
        addView(text(t.optString("name").ifBlank { GameCenter.short(t) }, 16f, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
        val rec = t.optString("record")
        addView(text((if (t.optBoolean("home")) "Home" else "Away") + if (rec.isNotBlank()) " · $rec" else "", 13f, col(R.color.text_hint)).apply { gravity = Gravity.CENTER })
    }

    private fun centre(away: JSONObject, home: JSONObject, state: String, src: JSONObject) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        if (state == "pre") {
            val start = src.optLong("start") * 1000
            addView(text(Fmt.day(start), 18f, bold = true).apply { gravity = Gravity.CENTER })
            addView(text(if (src.optBoolean("tbd")) "Time TBD" else Fmt.clock(start), 30f, bold = true).apply { gravity = Gravity.CENTER })
            addView(text("Upcoming", 13f, col(R.color.text_hint)).apply { gravity = Gravity.CENTER })
        } else {
            val a = away.optString("score").toIntOrNull(); val h = home.optString("score").toIntOrNull()
            val row = LinearLayout(this@GameActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            fun score(v: String, win: Boolean) = text(v, 44f, if (state == "post" && !win) col(R.color.text_hint) else Color.WHITE, true)
            row.addView(score(away.optString("score"), a != null && h != null && a >= h))
            row.addView(text("  –  ", 30f, col(R.color.text_hint)))
            row.addView(score(home.optString("score"), a != null && h != null && h >= a))
            addView(row)
            val pill = text(if (state == "in") "LIVE · " + src.optString("detail") else src.optString("detail").ifBlank { "Final" }, 14f, Color.WHITE, true).apply {
                setBackgroundResource(R.drawable.bg_program_pill); setPadding(dp(14), dp(5), dp(14), dp(5))
                if (state == "in") setTextColor(Color.parseColor("#FF5252"))
            }
            addView(pill, LinearLayout.LayoutParams(-2, -2))
            if (state == "post" && a != null && h != null) {
                val (w, m) = if (a == h) "" to 0 else if (a > h) GameCenter.short(away) to a - h else GameCenter.short(home) to h - a
                addView(text(if (m == 0) "Draw" else "$w won by $m", 14f, col(R.color.text_secondary)).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) })
            }
        }
    }

    /** Score by period: 1 2 3 4 (OT…) and the total. */
    private fun lineScore(away: JSONObject, home: JSONObject) {
        val la = away.optJSONArray("lines") ?: JSONArray(); val lh = home.optJSONArray("lines") ?: JSONArray()
        val n = maxOf(la.length(), lh.length())
        if (n == 0) return
        val c = section("Score by period")
        fun row(name: String, cells: List<String>, header: Boolean) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0)
            addView(text(name, 15f, if (header) col(R.color.text_hint) else Color.WHITE, !header), LinearLayout.LayoutParams(0, -2, 2.2f))
            cells.forEachIndexed { i, v ->
                addView(text(v, 15f, if (header) col(R.color.text_hint) else Color.WHITE, i == cells.lastIndex).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        val labels = (1..n).map { if (it <= regulation(n)) "$it" else if (n - regulation(n) == 1) "OT" else "OT${it - regulation(n)}" } + "T"
        c.addView(row("", labels, true))
        listOf(away to la, home to lh).forEach { (t, l) -> c.addView(row(GameCenter.short(t), (0 until n).map { l.optString(it, "-") } + t.optString("score"), false)) }
        add(c, 16)
    }

    private fun regulation(n: Int): Int {
        val lg = game.optString("league")
        // periods in regulation: soccer/rugby halves, hockey periods, baseball innings, college basketball halves
        val reg = when (game.optString("sport")) {
            "soccer", "rugby", "rugby-league" -> 2; "hockey", "field-hockey" -> 3; "baseball" -> 9
            else -> when (lg) { "NHL" -> 3; "MLB" -> 9; "MLS", "EPL", "UCL", "LALIGA" -> 2; "NCAAB" -> 2; else -> 4 }
        }
        return if (n < reg) n else reg
    }

    /** Top performers per team (points, rebounds, passing yards…). */
    private fun leaders(s: JSONObject, away: JSONObject, home: JSONObject) {
        val all = Web24Api.objects(s.optJSONArray("leaders"))
        if (all.isEmpty()) return
        // before the game ESPN sends the season leaders
        val c = section(if (s.optString("state") == "pre") "Season leaders" else "Top performers")
        val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(away, home).forEach { t ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), dp(12), 0) }
            box.addView(text(GameCenter.short(t), 15f, GameCenter.color(t, col(R.color.primary_light)).let { if (Color.luminance(it) < 0.15f) col(R.color.primary_light) else it }, true))
            all.firstOrNull { it.optString("team") == t.optString("id") }?.let { tl ->
                Web24Api.objects(tl.optJSONArray("items")).forEach { i ->
                    box.addView(text(i.optString("category"), 12f, col(R.color.text_hint)).apply { setPadding(0, dp(8), 0, 0) })
                    box.addView(text(i.optString("player"), 15f, bold = true))
                    box.addView(text(i.optString("value"), 14f, col(R.color.text_secondary)))
                }
            }
            cols.addView(box, LinearLayout.LayoutParams(0, -2, 1f))
        }
        c.addView(cols)
        add(c, 16)
    }

    /** Team stats side by side with a bar showing who leads each one. */
    private fun stats(s: JSONObject, away: JSONObject, home: JSONObject) {
        val list = Web24Api.objects(s.optJSONArray("stats"))
        if (list.isEmpty()) return
        val c = section("Team stats")
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        head.addView(text(GameCenter.short(away), 14f, col(R.color.text_hint), true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(text(GameCenter.short(home), 14f, col(R.color.text_hint), true).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(head)
        list.forEach { st ->
            val v = st.optJSONObject("values") ?: return@forEach
            val av = v.optString(away.optString("id")); val hv = v.optString(home.optString("id"))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
            row.addView(text(av, 16f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text(st.optString("label"), 14f, col(R.color.text_secondary)).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 2f))
            row.addView(text(hv, 16f, bold = true).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
            c.addView(row)
            val eff = st.optString("label").contains("efficiency", true)
            val an = number(av, eff); val hn = number(hv, eff)
            if (an != null && hn != null && an + hn > 0) {
                val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
                bar.addView(View(this).apply { setBackgroundColor(if (an >= hn) col(R.color.primary_light) else Color.parseColor("#444444")) }, LinearLayout.LayoutParams(0, dp(4), an.toFloat().coerceAtLeast(0.01f)))
                bar.addView(View(this), LinearLayout.LayoutParams(dp(4), dp(4)))
                bar.addView(View(this).apply { setBackgroundColor(if (hn >= an) col(R.color.primary_light) else Color.parseColor("#444444")) }, LinearLayout.LayoutParams(0, dp(4), hn.toFloat().coerceAtLeast(0.01f)))
                c.addView(bar)
            }
        }
        add(c, 16)
    }

    /**
     * The number a stat bar compares: "52" -> 52, "31:20" -> 31, an efficiency "4-8" -> 0.5. Other "a-b" stats
     * (penalties "5-45", made-attempted) get no bar, because their first number alone says little.
     */
    private fun number(s: String, efficiency: Boolean): Double? {
        Regex("""^\s*(\d+)-(\d+)\s*$""").find(s)?.let { m ->
            if (!efficiency) return null
            val made = m.groupValues[1].toDouble(); val att = m.groupValues[2].toDouble()
            return if (att > 0) made / att else 0.0
        }
        return Regex("""\d+(\.\d+)?""").find(s)?.value?.toDoubleOrNull()
    }

    companion object { const val EXTRA_GAME = "game" }
}

/** Team page: record, standing, follow button, upcoming games (with TV) and recent results; a game opens its Game Center. */
class TeamActivity : FeatureListActivity() {
    private var info: JSONObject? = null
    private var tab = "upcoming"
    private val league by lazy { intent.getStringExtra(EXTRA_LEAGUE).orEmpty() }
    private val teamId by lazy { intent.getStringExtra(EXTRA_ID).orEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title(intent.getStringExtra(EXTRA_NAME).orEmpty(), "Loading team…")
        load()
    }

    private fun load() {
        loading(true)
        lifecycleScope.launch {
            info = runCatching { Web24Api(this@TeamActivity).support("team", "league" to league, "id" to teamId) }.getOrNull()?.takeIf { it.optBoolean("ok") }
            loading(false)
            render()
            focusFirstRow()
        }
    }

    private fun me(): TeamInfo? = info?.let { TeamInfo(league, teamId, it.optString("name"), it.optString("short"), it.optString("abbr"), "") }

    private fun render() {
        val t = info ?: run { show(emptyList(), "Team information is not available right now."); return }
        val rec = t.optJSONObject("record")
        val recText = listOfNotNull(rec?.optString("total")?.takeIf { it.isNotBlank() },
            rec?.optString("home")?.takeIf { it.isNotBlank() }?.let { "Home $it" }, rec?.optString("road")?.takeIf { it.isNotBlank() }?.let { "Road $it" }).joinToString(" · ")
        title(t.optString("name"), listOf(league, t.optString("standing"), recText, t.optString("venue")).filter { it.isNotBlank() }.joinToString("  ·  "))
        val following = "$league:$teamId" in Teams.keys(this)
        action(if (following) "★ Following" else "+ Follow") {
            lifecycleScope.launch {
                val all = Teams.all(this@TeamActivity)
                val ti = all.firstOrNull { it.key == "$league:$teamId" } ?: me() ?: return@launch
                Teams.set(this@TeamActivity, ti, !following, all)
                toast(if (following) "${t.optString("name")} removed from My Teams" else "${t.optString("name")} added to My Teams - alert 5 minutes before each game")
                render()
            }
        }
        chips(listOf("upcoming" to "Upcoming", "results" to "Results"), tab) { tab = it; render() }
        val games = Web24Api.objects(t.optJSONArray(if (tab == "upcoming") "upcoming" else "recent"))
        show(games.map { g ->
            val (away, home) = GameCenter.teams(g)
            val weHome = home.optString("id") == teamId
            val opp = JSONObject((if (weHome) away else home).toString()).apply { put("logo", GameCenter.darkLogo(optString("logo"))) }
            val us = if (weHome) home else away
            val vs = (if (weHome) "vs " else "@ ") + opp.optString("name").ifBlank { GameCenter.short(opp) }
            val start = g.optLong("start") * 1000
            val tv = g.optJSONArray("tv")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().filter { it.isNotBlank() }
            when (g.optString("state")) {
                "post" -> {
                    val u = us.optString("score").toIntOrNull(); val o = opp.optString("score").toIntOrNull()
                    val wl = when { u == null || o == null -> ""; u > o -> "W"; u < o -> "L"; else -> "T" }
                    val by = if (u == null || o == null) "" else if (u > o) "Won by ${u - o}" else if (u < o) "Lost by ${o - u}" else "Tied"
                    Row("r" + g.optString("id"), vs, listOf(by, Fmt.day(start)).filter { it.isNotBlank() }.joinToString("  ·  "), "",
                        opp.optString("logo"), badge = listOf(wl, "${us.optString("score")}-${opp.optString("score")}").filter { it.isNotBlank() }.joinToString(" "),
                        badgeColor = when (wl) { "W" -> Color.parseColor("#4CAF50"); "L" -> Color.parseColor("#FF5252"); else -> 0 }) { GameCenter.open(this, g) }
                }
                "in" -> Row("l" + g.optString("id"), vs, "LIVE · ${us.optString("score")} – ${opp.optString("score")} · ${g.optString("detail")}", tv.joinToString(", "),
                    opp.optString("logo"), badge = "LIVE", badgeColor = Color.parseColor("#FF5252")) { GameCenter.open(this, g) }
                else -> Row("u" + g.optString("id"), vs,
                    if (g.optBoolean("tbd")) "${Fmt.day(start)} · time to be announced" else "${Fmt.day(start)} at ${Fmt.clock(start)}",
                    listOfNotNull(tv.takeIf { it.isNotEmpty() }?.let { "TV: " + it.joinToString(", ") }, g.optString("venue").takeIf { it.isNotBlank() }).joinToString("  ·  "),
                    opp.optString("logo"), badge = if (weHome) "Home" else "Away") { GameCenter.open(this, g) }
            }
        }, if (tab == "upcoming") "No upcoming games on the schedule yet." else "No results yet this season.")
    }

    companion object { const val EXTRA_LEAGUE = "league"; const val EXTRA_ID = "team_id"; const val EXTRA_NAME = "team_name" }
}
