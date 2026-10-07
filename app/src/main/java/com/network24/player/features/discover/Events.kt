package com.network24.player.features.discover

import android.app.Activity
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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.network24.player.R
import com.network24.player.core.api.Web24Api
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.entity.CategoryType
import com.network24.player.core.database.entity.ChannelEntity
import com.network24.player.core.parental.ParentalLock
import com.network24.player.features.player.state.PlayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Non-team events from Live Sports (Main's support_api "scores" -> events): UFC / MMA fight cards, golf
 * tournaments, race weekends (F1, NASCAR, IndyCar) and tennis tournaments. Which leagues exist is set in the
 * console (Live Sports page); every event carries its kind.
 */
object EventCenter {
    fun open(c: Context, e: JSONObject, leagueName: String) {
        c.startActivity(Intent(c, EventActivity::class.java).putExtra(EventActivity.EXTRA_EVENT, e.toString()).putExtra(EventActivity.EXTRA_LEAGUE, leagueName))
    }

    /** "Oct 9 – 12" for a multi-day event, else "Thu, Oct 9 · 8:00 PM". */
    fun dates(e: JSONObject): String {
        val s = e.optLong("start") * 1000
        val end = e.optLong("end") * 1000
        // ESPN gives one-day cricket (T20, ODI) a 3-day end; only Tests / first-class run for days
        val oneDay = e.optString("kind") == "cricket" && !Regex("test|first-class|day", RegexOption.IGNORE_CASE).containsMatchIn(e.optString("format"))
        if (oneDay || end - s < 20 * 3_600_000L) return "${Fmt.day(s)} · ${Fmt.clock(s)}"
        val md = SimpleDateFormat("MMM d", Locale.US)
        val a = md.format(Date(s)); val b = md.format(Date(end))
        return if (a.substringBefore(' ') == b.substringBefore(' ')) "$a – ${b.substringAfter(' ')}" else "$a – $b"
    }

    fun list(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }

    /** The words that name an event in channel names and TV guide titles. */
    fun keywords(e: JSONObject, full: JSONObject?): Pair<List<String>, List<String>> {
        val lg = e.optString("league").uppercase()
        val brand = when {
            e.optString("kind") == "fight" -> listOf(if (lg == "UFC") "UFC" else lg, "PPV")
            lg == "F1" -> listOf("F1", "Formula 1", "Formula One")
            lg.startsWith("NASCAR") -> listOf("NASCAR")
            lg == "IRL" -> listOf("IndyCar", "Indy")
            e.optString("kind") == "golf" -> listOf("Golf", "PGA", "LPGA", "LIV")
            e.optString("kind") == "tennis" -> listOf("Tennis", "ATP", "WTA")
            e.optString("kind") == "cricket" -> listOf("Cricket", "Willow") + if (lg in setOf("IPL", "PSL", "BBL", "CPL", "MLC", "SA20", "ILT20", "BPL", "LPL", "WPL")) listOf(lg) else emptyList()
            else -> listOf(lg)
        }
        val specific = mutableListOf<String>()
        if (e.optString("kind") == "cricket") {
            Web24Api.objects(e.optJSONArray("teams")).map { it.optString("name") }.filter { it.length >= 4 }.forEach { specific += it }
            return brand to specific.distinct()
        }
        val name = e.optString("name")
        // "UFC Fight Night: Allen vs. Duncan" -> Allen, Duncan; "Singapore Grand Prix" -> Singapore
        Regex("""([A-Z][\p{L}'-]{3,})\s+vs\.?\s+([A-Z][\p{L}'-]{3,})""").find(name)?.let { specific += it.groupValues[1]; specific += it.groupValues[2] }
        full?.optJSONArray("bouts")?.optJSONObject(0)?.optJSONArray("fighters")?.let { f ->
            for (i in 0 until f.length()) f.optJSONObject(i)?.optString("name")?.split(' ')?.lastOrNull()?.takeIf { it.length >= 4 }?.let { specific += it }
        }
        val skip = setOf("Grand", "Prix", "Series", "Cup", "Open", "Championship", "Championships", "Classic", "Tour", "Masters", "Tennis", "Golf", "Invitational",
            "Fight", "Night", "Airlines", "Group", "Bank", "Presented", "NASCAR", "Formula", "Contender", "Season", "Week")
        if (e.optString("kind") != "fight") Regex("""\b[A-Z][a-z]{4,}\b""").findAll(name).map { it.value }.filter { it !in skip }.take(2).forEach { specific += it }
        return brand to specific.distinct()
    }
}

/** Finds the channels showing an event: event channels named after it, then TV guide programmes, then the sport's own channels. */
object EventChannels {
    private const val HOUR = 3_600_000L
    private fun word(s: String) = Regex("(?<![A-Za-z0-9])" + Regex.escape(s) + "(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)

    suspend fun find(context: Context, e: JSONObject, full: JSONObject?): List<ChannelEntity> = withContext(Dispatchers.IO) {
        val (brand, specific) = EventCenter.keywords(e, full)
        val db = DatabaseProvider.get(context)
        val categories = db.categoryDao().getByType(CategoryType.LIVE)
        val replay = categories.filter { it.name?.contains("REPLAY", true) == true }.map { it.categoryId }.toSet()
        val locked = ParentalLock.activeLockedIds(context)
        val all = db.channelDao().getAll().filter { it.categoryId !in replay && (it.categoryId == null || it.categoryId !in locked) }
        val byId = all.associateBy { it.streamId }
        val score = HashMap<Int, Int>()
        fun add(id: Int, pts: Int) { if (id in byId) score[id] = maxOf(score[id] ?: 0, pts) }
        val brandRx = brand.map { word(it) }
        val specRx = specific.map { word(it) }
        fun hits(t: String) = specRx.count { it.containsMatchIn(t) }
        fun isBrand(t: String) = brandRx.any { it.containsMatchIn(t) }

        val now = System.currentTimeMillis()
        val start = e.optLong("start") * 1000
        val end = maxOf(e.optLong("end") * 1000, start + 4 * HOUR)
        val nextAt = e.optJSONObject("next")?.optLong("start")?.times(1000)?.takeIf { it > 0 }
        val at = if (e.optString("state") == "in") now else nextAt ?: start

        EventParser.parse(all, replay).forEach { ev ->
            val n = ev.ch.name.orEmpty()
            if (ev.start in (start - 12 * HOUR)..(end + 12 * HOUR) && isBrand(n)) add(ev.ch.streamId, 100 + 20 * hits(n))
        }
        val byGuideId = all.filter { !it.epgChannelId.isNullOrBlank() }.groupBy { it.epgChannelId!!.lowercase() }
        (specific.take(3) + brand.take(2)).filter { it.length >= 2 }.distinct().forEach { q ->
            db.epgDao().searchProgramsInWindow(q, at, at + HOUR).forEach { p ->
                val t = p.title.orEmpty() + " " + p.description.orEmpty().take(200)
                val h = hits(t)
                if (h == 0 && !isBrand(p.title.orEmpty())) return@forEach
                val ids = if (p.streamId > 0) listOf(p.streamId) else byGuideId[p.epgChannelId?.lowercase()].orEmpty().map { it.streamId }
                ids.forEach { add(it, 50 + 20 * h) }
            }
        }
        // the sport's own channels (F1 TV, NASCAR channel, Golf Channel, Tennis Channel, UFC Fight Pass)
        all.forEach { ch -> if (isBrand(ch.name.orEmpty())) add(ch.streamId, 20) }
        val catName = categories.associate { it.categoryId to it.name.orEmpty() }
        fun rank(id: Int): Int {
            val ch = byId[id] ?: return 0
            return (score[id] ?: 0) - if (SlowChannels.isSlow(catName[ch.categoryId], ch.name)) 80 else 0
        }
        score.keys.sortedByDescending { rank(it) }.take(15).mapNotNull { byId[it] }
    }

    fun watch(a: Activity, e: JSONObject, full: JSONObject?, onBusy: (Boolean) -> Unit) {
        onBusy(true)
        (a as LifecycleOwner).lifecycleScope.launch {
            val chans = find(a, e, full)
            onBusy(false)
            val title = e.optString("short").ifBlank { e.optString("name") }
            if (chans.isEmpty()) {
                Toast.makeText(a, "No channel lists $title yet. Event channels usually add it shortly before the start.", Toast.LENGTH_LONG).show()
                return@launch
            }
            PlayerState.currentPosition = 0
            a.startActivity(Intent(a, com.network24.player.features.livetv.LiveTvActivity::class.java).putExtra("category_name", title)
                .putExtra(com.network24.player.features.livetv.LiveTvActivity.EXTRA_STREAM_IDS, chans.map { it.streamId }.toIntArray()))
        }
    }
}

/**
 * Event page: fight card (main event first, results with method and round), golf leaderboard, race weekend
 * (sessions with times and results) or tennis tournament (live, upcoming and finished matches per draw).
 * Live events refresh every minute.
 */
class EventActivity : BaseActivity() {
    private lateinit var body: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var brief: JSONObject
    private var full: JSONObject? = null
    private var failed = false
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable { override fun run() { load(); handler.postDelayed(this, 60_000) } }
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * d).toInt()
    private fun col(id: Int) = ContextCompat.getColor(this, id)
    private val live = Color.parseColor("#FF5252")
    private val good = Color.parseColor("#4CAF50")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (blockedByGate) return
        brief = runCatching { JSONObject(intent.getStringExtra(EXTRA_EVENT).orEmpty()) }.getOrNull() ?: run { finish(); return }
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

    override fun onResume() { super.onResume(); if (brief.optString("state") == "in") handler.postDelayed(refresh, 60_000) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(refresh) }

    private fun load() {
        if (full == null) progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val r = runCatching { Web24Api(this@EventActivity).support("event", "league" to brief.optString("league"), "id" to brief.optString("id")) }.getOrNull()
            progress.visibility = View.GONE
            if (r != null && r.optBoolean("ok")) { full = r; failed = false } else if (full == null) failed = true
            draw()
        }
    }

    // ------------------------------------------------------------------------------------------------ views
    private fun text(s: String, size: Float, color: Int = col(R.color.text_primary), bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun card(focusable: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_settings_action)
        setPadding(dp(18), dp(14), dp(18), dp(14))
        isFocusable = focusable
    }

    private fun add(v: View, top: Int = 12) = body.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })

    private fun heading(t: String) = text(t.uppercase(), 12f, col(R.color.primary_light), true).apply { letterSpacing = 0.12f; setPadding(dp(4), dp(18), 0, dp(2)) }

    /** One focusable line of a list (a fight, a player, a session, a match): the remote walks the list row by row. */
    private fun item(key: String) = card(true).apply { tag = key; setPadding(dp(16), dp(10), dp(16), dp(10)) }

    private fun pill(s: String, color: Int) = text(s, 13f, color, true).apply {
        setBackgroundResource(R.drawable.bg_program_pill); setPadding(dp(12), dp(4), dp(12), dp(4))
    }

    private fun row(vararg parts: Pair<View, Float>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        parts.forEach { (v, w) -> addView(v, if (w > 0) LinearLayout.LayoutParams(0, -2, w) else LinearLayout.LayoutParams(-2, -2)) }
    }

    private fun stateText(state: String, detail: String, start: Long) = when (state) {
        "in" -> "LIVE" + if (detail.isNotBlank() && !detail.equals("In progress", true)) " · $detail" else ""
        "post" -> detail.ifBlank { "Final" }
        else -> "${Fmt.day(start)} · ${Fmt.clock(start)}"
    }

    private var shownFull = false

    private fun draw() {
        // the first time the details arrive the remote moves into them (before that only Back could take it)
        val focused = if (!shownFull && full != null) null else currentFocus?.tag
        if (full != null) shownFull = true
        body.removeAllViews()
        val e = full ?: brief
        val state = e.optString("state")

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back); setColorFilter(Color.WHITE); setBackgroundResource(R.drawable.bg_back_focus)
            contentDescription = "Back"; isFocusable = true; tag = "back"; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        bar.addView(text(intent.getStringExtra(EXTRA_LEAGUE).orEmpty().ifBlank { e.optString("league") }, 18f, bold = true).apply { setPadding(dp(14), 0, 0, 0) })
        body.addView(bar)

        // hero
        val hero = card(false).apply { setPadding(dp(22), dp(18), dp(22), dp(18)) }
        hero.addView(text(e.optString("name"), 24f, bold = true))
        val when_ = EventCenter.dates(e)
        hero.addView(text(listOf(when_, e.optString("venue"), e.optString("city")).filter { it.isNotBlank() }.joinToString("  ·  "), 15f, col(R.color.text_secondary)).apply { setPadding(0, dp(6), 0, 0) })
        val tv = EventCenter.list(e.optJSONArray("tv"))
        if (tv.isNotEmpty()) hero.addView(text("TV: " + tv.joinToString(", "), 14f, col(R.color.text_hint)).apply { setPadding(0, dp(4), 0, 0) })
        val st = row(pill(when (state) { "in" -> "LIVE"; "post" -> e.optString("detail").ifBlank { "Final" }; else -> "Upcoming" }, if (state == "in") live else Color.WHITE) to 0f)
        hero.addView(st, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        val head = brief.optString("headline")
        if (head.isNotBlank() && e.optString("kind") != "cricket") hero.addView(text(head, 16f, bold = true).apply { setPadding(0, dp(10), 0, 0) })
        add(hero, 14)

        if (state != "post") {
            val watch = TextView(this).apply {
                text = if (state == "in") "▶  Watch live" else "▶  Find channels"; textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE); setBackgroundResource(R.drawable.bg_primary_action); isFocusable = true; isClickable = true; tag = "watch"
                setOnClickListener { EventChannels.watch(this@EventActivity, brief, full) { progress.visibility = if (it) View.VISIBLE else View.GONE } }
            }
            body.addView(watch, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(14) })
        }

        when {
            full == null && failed -> add(text("Details are not available right now. Try again in a minute.", 15f, col(R.color.text_hint)).apply { setPadding(dp(4), dp(16), 0, 0) })
            full == null -> Unit
            e.optString("kind") == "fight" -> fights(e)
            e.optString("kind") == "golf" -> golf(e)
            e.optString("kind") == "racing" -> racing(e)
            e.optString("kind") == "tennis" -> tennis(e)
            e.optString("kind") == "cricket" -> cricket(e)
        }

        val again = allViews(body).firstOrNull { focused != null && it.tag == focused }
        (again ?: body.findViewWithTag<View>("watch") ?: allViews(body).firstOrNull { it.isFocusable && it.tag != "back" })?.let { v -> v.post { v.requestFocus() } }
    }

    private fun allViews(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(allViews(v.getChildAt(i)))
    }

    // ------------------------------------------------------------------------------------------------ fight card
    private fun fights(e: JSONObject) {
        val bouts = Web24Api.objects(e.optJSONArray("bouts"))
        if (bouts.isEmpty()) return
        body.addView(heading("Fight card · ${bouts.size} fights"))
        bouts.forEachIndexed { i, b ->
            val c = item("bout" + b.optString("id"))
            val f = Web24Api.objects(b.optJSONArray("fighters"))
            val state = b.optString("state")
            val top = listOfNotNull(if (i == 0) "MAIN EVENT" else null, b.optString("weight").takeIf { it.isNotBlank() },
                b.optInt("rounds").takeIf { it > 0 }?.let { "$it rounds" }).joinToString(" · ")
            val status = when (state) {
                "post" -> b.optString("result").ifBlank { "Final" }
                "in" -> "LIVE"
                else -> Fmt.clock(b.optLong("start") * 1000)
            }
            c.addView(row(text(top, 12f, if (i == 0) col(R.color.primary_light) else col(R.color.text_hint), true) to 1f,
                text(status, 13f, if (state == "in") live else col(R.color.text_secondary), state != "pre") to 0f))
            fun fighter(x: JSONObject?, end: Boolean) = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = if (end) Gravity.END else Gravity.START
                val win = x?.optBoolean("winner") == true
                val anyWin = f.any { it.optBoolean("winner") }
                addView(text((if (win && !end) "✓ " else "") + x?.optString("name").orEmpty() + (if (win && end) " ✓" else ""), 17f,
                    if (anyWin && !win) col(R.color.text_hint) else if (win) good else Color.WHITE, true).apply { gravity = if (end) Gravity.END else Gravity.START })
                addView(text(listOf(x?.optString("record").orEmpty(), x?.optString("country").orEmpty()).filter { it.isNotBlank() }.joinToString(" · "), 13f, col(R.color.text_hint))
                    .apply { gravity = if (end) Gravity.END else Gravity.START })
            }
            c.addView(row(fighter(f.getOrNull(0), false) to 1f, text("vs", 14f, col(R.color.text_hint)).apply { setPadding(dp(12), 0, dp(12), 0) } to 0f,
                fighter(f.getOrNull(1), true) to 1f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            add(c, 8)
        }
    }

    // ------------------------------------------------------------------------------------------------ golf
    private fun golf(e: JSONObject) {
        val ps = Web24Api.objects(e.optJSONArray("players"))
        if (ps.isEmpty()) { add(text("The field is not out yet.", 15f, col(R.color.text_hint)).apply { setPadding(dp(4), dp(16), 0, 0) }); return }
        val rounds = maxOf(e.optInt("round"), ps.maxOfOrNull { it.optJSONArray("rounds")?.length() ?: 0 } ?: 0).coerceIn(1, 4)
        body.addView(heading("Leaderboard" + if (e.optString("state") == "in") " · Round $rounds" else ""))
        fun line(pos: String, name: String, sub: String, par: String, thru: String, rs: List<String>, header: Boolean, color: Int) = row(
            text(pos, 15f, col(R.color.text_hint), !header) to 0.6f,
            LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
                addView(text(name, if (header) 13f else 16f, if (header) col(R.color.text_hint) else Color.WHITE, !header))
                if (sub.isNotBlank()) addView(text(sub, 12f, col(R.color.text_hint))) } to 3f,
            text(par, if (header) 13f else 17f, color, !header).apply { gravity = Gravity.CENTER } to 0.9f,
            text(thru, 13f, col(R.color.text_hint)).apply { gravity = Gravity.CENTER } to 0.7f,
            *rs.map { text(it, 13f, col(R.color.text_secondary)).apply { gravity = Gravity.CENTER } to 0.6f }.toTypedArray())
        add(line("POS", "PLAYER", "", "TO PAR", "THRU", (1..rounds).map { "R$it" }, true, col(R.color.text_hint)).apply { setPadding(dp(16), 0, dp(16), 0) }, 4)
        ps.take(60).forEach { p ->
            val c = item("p" + p.optString("id"))
            val score = p.optString("score")
            val color = if (score.startsWith("-")) good else if (score.startsWith("+")) col(R.color.text_secondary) else Color.WHITE
            val rs = Web24Api.objects(p.optJSONArray("rounds"))
            c.addView(line(p.optString("place").ifBlank { p.optString("pos") }, p.optString("name"), p.optString("country"), score,
                p.optString("thru").let { if (it == "F" || it.isBlank()) it else "$it" }, (1..rounds).map { r -> rs.firstOrNull { it.optInt("r") == r }?.optInt("strokes")?.takeIf { it > 0 }?.toString() ?: "-" },
                false, color))
            add(c, 6)
        }
    }

    // ------------------------------------------------------------------------------------------------ racing
    private fun racing(e: JSONObject) {
        val ss = Web24Api.objects(e.optJSONArray("sessions"))
        if (ss.isEmpty()) return
        body.addView(heading("Weekend schedule"))
        ss.forEach { s ->
            val c = item("s" + s.optString("id"))
            val state = s.optString("state")
            val res = Web24Api.objects(s.optJSONArray("results"))
            val right = when (state) {
                "post" -> res.firstOrNull()?.let { "1st: " + it.optString("name") } ?: "Finished"
                "in" -> "LIVE"
                else -> ""
            }
            c.addView(row(text(s.optString("name"), 17f, bold = true) to 1.2f,
                text("${Fmt.day(s.optLong("start") * 1000)} · ${Fmt.clock(s.optLong("start") * 1000)}", 14f, col(R.color.text_secondary)) to 1.2f,
                text(right, 14f, if (state == "in") live else Color.WHITE, state != "pre").apply { gravity = Gravity.END } to 1.4f))
            add(c, 6)
        }
        // results of the race (or of the latest finished session)
        val done = ss.lastOrNull { it.optString("state") != "pre" && (it.optJSONArray("results")?.length() ?: 0) > 0 } ?: return
        val res = Web24Api.objects(done.optJSONArray("results"))
        body.addView(heading("${done.optString("name")} results" + if (done.optString("state") == "in") " · live order" else ""))
        res.take(25).forEach { r ->
            val c = item("r" + done.optString("id") + r.optString("id"))
            val p = r.optInt("pos")
            c.addView(row(text(if (p > 0) "$p" else "-", 16f, if (p in 1..3) col(R.color.primary_light) else col(R.color.text_hint), true) to 0.5f,
                text(r.optString("name"), 16f, bold = p in 1..3) to 3f, text(r.optString("country"), 13f, col(R.color.text_hint)).apply { gravity = Gravity.END } to 1.5f))
            add(c, 6)
        }
    }

    // ------------------------------------------------------------------------------------------------ cricket
    private fun cricket(e: JSONObject) {
        val teams = Web24Api.objects(e.optJSONArray("teams"))
        val anyWin = teams.any { it.optBoolean("winner") }
        body.addView(heading(listOf(e.optString("format"), e.optString("series")).filter { it.isNotBlank() }.joinToString(" · ")))
        val board = card(true).apply { tag = "board" }
        teams.forEach { t ->
            val win = t.optBoolean("winner")
            board.addView(row(text((if (win) "✓ " else "") + t.optString("name"), 20f, if (anyWin && !win) col(R.color.text_hint) else Color.WHITE, true) to 1f,
                text(t.optString("score").ifBlank { if (e.optString("state") == "pre") "" else "Yet to bat" }, 20f, if (anyWin && !win) col(R.color.text_hint) else Color.WHITE, true).apply { gravity = Gravity.END } to 0f),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        val note = e.optString("note")
        if (note.isNotBlank()) board.addView(text(note, 15f, col(R.color.primary_light), true).apply { setPadding(0, dp(10), 0, 0) })
        val desc = e.optString("description")
        if (desc.isNotBlank()) board.addView(text(desc, 13f, col(R.color.text_hint)).apply { setPadding(0, dp(6), 0, 0) })
        EventCenter.list(e.optJSONArray("notes")).forEach { board.addView(text(it, 13f, col(R.color.text_hint)).apply { setPadding(0, dp(2), 0, 0) }) }
        add(board, 8)

        Web24Api.objects(e.optJSONArray("cards")).forEach { c ->
            val bat = c.optString("type") == "batting"
            val inn = c.optInt("innings").takeIf { it > 0 }?.let { " · innings $it" } ?: ""
            body.addView(heading("${c.optString("team")} ${if (bat) "batting" else "bowling"}$inn" + (c.optString("total").takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")))
            val cols = if (bat) listOf("R", "B", "4s", "6s") else listOf("O", "M", "R", "W", "Econ")
            fun line(name: String, sub: String, vals: List<String>, header: Boolean) = row(
                LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
                    addView(text(name, if (header) 12f else 16f, if (header) col(R.color.text_hint) else Color.WHITE, !header))
                    if (sub.isNotBlank()) addView(text(sub, 12f, col(R.color.text_hint))) } to 3f,
                *vals.mapIndexed { i, v -> text(v, if (header) 12f else 15f, if (header) col(R.color.text_hint) else if (i == 0) Color.WHITE else col(R.color.text_secondary), !header && i == 0)
                    .apply { gravity = Gravity.CENTER } to 0.7f }.toTypedArray())
            add(line(if (bat) "BATTER" else "BOWLER", "", cols, true).apply { setPadding(dp(16), 0, dp(16), 0) }, 4)
            Web24Api.objects(c.optJSONArray("rows")).forEachIndexed { i, r ->
                val it = item("c" + c.optString("team") + c.optString("type") + i)
                it.addView(if (bat) line(r.optString("name"), r.optString("out"), listOf(r.optString("r"), r.optString("b"), r.optString("fours"), r.optString("sixes")), false)
                    else line(r.optString("name"), "", listOf(r.optString("o"), r.optString("m"), r.optString("r"), r.optString("w"), r.optString("econ")), false))
                add(it, 6)
            }
            if (bat && c.optString("extras").isNotBlank()) add(text("Extras: " + c.optString("extras"), 13f, col(R.color.text_hint)).apply { setPadding(dp(16), 0, 0, 0) }, 4)
        }
    }

    // ------------------------------------------------------------------------------------------------ tennis
    private fun tennis(e: JSONObject) {
        val now = System.currentTimeMillis() / 1000
        Web24Api.objects(e.optJSONArray("draws")).forEach { dr ->
            val ms = Web24Api.objects(dr.optJSONArray("matches")).filter { m -> Web24Api.objects(m.optJSONArray("players")).none { it.optString("name").equals("TBD", true) } }
            if (ms.isEmpty()) return@forEach
            // live first, then the next ones by time, then the latest results
            val liveM = ms.filter { it.optString("state") == "in" }
            val next = ms.filter { it.optString("state") == "pre" && it.optLong("start") > now - 6 * 3600 }.sortedBy { it.optLong("start") }
            val done = ms.filter { it.optString("state") == "post" }.sortedByDescending { it.optLong("start") }
            val shown = liveM + next.take(10) + done.take(12)
            body.addView(heading(dr.optString("name") + if (liveM.isNotEmpty()) " · ${liveM.size} live" else ""))
            shown.forEach { m -> add(match(m), 6) }
        }
    }

    private fun match(m: JSONObject): View {
        val c = item("m" + m.optString("id"))
        val state = m.optString("state")
        val start = m.optLong("start") * 1000
        c.addView(row(text(listOf(m.optString("round"), m.optString("court")).filter { it.isNotBlank() }.joinToString(" · "), 12f, col(R.color.text_hint), true) to 1f,
            text(stateText(state, if (state == "pre") "" else m.optString("detail"), start), 12f, if (state == "in") live else col(R.color.text_secondary), state == "in") to 0f))
        val ps = Web24Api.objects(m.optJSONArray("players"))
        val anyWin = ps.any { it.optBoolean("winner") }
        ps.forEach { p ->
            val win = p.optBoolean("winner")
            val sets = (0 until (p.optJSONArray("sets")?.length() ?: 0)).map { p.optJSONArray("sets")!!.optInt(it) }
            val rank = p.optInt("rank").takeIf { it > 0 }?.let { " ($it)" } ?: ""
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(text((if (win) "✓ " else "") + p.optString("name") + rank, 16f, if (anyWin && !win) col(R.color.text_hint) else Color.WHITE, win || !anyWin), LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(text(p.optString("country"), 12f, col(R.color.text_hint)).apply { setPadding(0, 0, dp(12), 0) })
            sets.forEach { s -> line.addView(text("$s", 16f, if (win) Color.WHITE else col(R.color.text_secondary), win).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(30), -2)) }
            c.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        val note = m.optString("note")
        if (state == "post" && note.isNotBlank() && ps.isEmpty()) c.addView(text(note, 13f, col(R.color.text_secondary)))
        return c
    }

    companion object { const val EXTRA_EVENT = "event"; const val EXTRA_LEAGUE = "league_name" }
}
