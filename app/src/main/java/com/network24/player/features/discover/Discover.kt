package com.network24.player.features.discover

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.network24.player.core.api.Web24Api
import com.network24.player.core.database.entity.CategoryType
import com.network24.player.core.database.entity.ChannelEntity
import com.network24.player.features.reminders.ReminderReceiver
import com.network24.player.features.reminders.Reminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

private val LIVE = Color.parseColor("#FF5252")
private val GOOD = Color.parseColor("#4CAF50")
private val GOLD = Color.parseColor("#FFC107")

// ------------------------------------------------------------------------------------------------ live events parser
/**
 * Live events (games, fights, PPV) are not in the TV guide; providers put them in the channel name, e.g.
 * "NFL 05 | Bears vs Lions 10/5 1:00 PM ET". Same rules as the web player's Live events page.
 */
object EventParser {
    data class Event(val ch: ChannelEntity, val start: Long, val title: String, val sub: String)
    private val ET = TimeZone.getTimeZone("America/New_York")
    private val MONTHS = mapOf("JAN" to 1, "FEB" to 2, "MAR" to 3, "APR" to 4, "MAY" to 5, "JUN" to 6, "JUL" to 7, "AUG" to 8, "SEP" to 9, "OCT" to 10, "NOV" to 11, "DEC" to 12)
    private const val MON = "(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEPT|SEP|OCT|NOV|DEC)[A-Z]*\\.?"
    private val skip = Regex("NO EVENT|NO GAME|\\|\\s*AVAILABLE\\s*$|OFF ?AIR|OFFLINE|\\bTBA\\b|REPLAY|TIME ONLY")
    private val full = Regex("\\b(20\\d\\d)-(\\d{1,2})-(\\d{1,2})\\s+(\\d{1,2}):(\\d{2})\\s*(AM|PM)?")
    private val slash = Regex("\\b(\\d{1,2})[/.](\\d{1,2})\\s+(\\d{1,2}):(\\d{2})\\s*(AM|PM)")
    private val named = Regex("\\b$MON\\s+(\\d{1,2})(?:,?\\s+\\d{4})?\\s+(\\d{1,2}):(\\d{2})\\s*(AM|PM)")
    private val dayNamed = Regex("\\b(?:MON|TUE|WED|THU|FRI|SAT|SUN)[A-Z]*\\.?\\s+(\\d{1,2})\\s+$MON\\s+(\\d{1,2}):(\\d{2})\\s*(AM|PM)")
    private val keepUpper = setOf("NFL", "NBA", "NHL", "MLB", "UFC", "PPV", "WWE", "MLS", "NCAA", "F1", "EPL", "UEFA", "AEW", "PFL", "BKFC", "ESPN", "DAZN")

    fun parse(channels: List<ChannelEntity>, replayCats: Set<String>): List<Event> {
        val now = System.currentTimeMillis()
        return channels.asSequence().filter { it.categoryId !in replayCats }.mapNotNull { one(it) }
            .filter { it.start > now - 6 * 3_600_000L && it.start < now + 8 * 86_400_000L }.sortedBy { it.start }.toList()
    }

    private fun h24(h: Int, ap: String?) = if (ap.isNullOrBlank()) h else (h % 12) + if (ap == "PM") 12 else 0

    private fun one(c: ChannelEntity): Event? {
        val name = (c.name ?: return null).uppercase().replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        if (skip.containsMatchIn(name)) return null
        val cal = Calendar.getInstance(ET)
        var y = cal.get(Calendar.YEAR); val mo: Int; val d: Int; val h: Int; val mi: Int
        val mFull = full.find(name); val mSlash = slash.find(name); val mDay = dayNamed.find(name); val mNamed = named.find(name)
        val m = mFull ?: mSlash ?: mDay ?: mNamed ?: return null
        val g = m.groupValues
        when (m) {
            mFull -> { y = g[1].toInt(); mo = g[2].toInt(); d = g[3].toInt(); h = h24(g[4].toInt(), g[6]); mi = g[5].toInt() }
            mSlash -> { mo = g[1].toInt(); d = g[2].toInt(); h = h24(g[3].toInt(), g[5]); mi = g[4].toInt() }
            mDay -> { d = g[1].toInt(); mo = MONTHS[g[2].take(3)] ?: return null; h = h24(g[3].toInt(), g[5]); mi = g[4].toInt() }
            else -> { mo = MONTHS[g[1].take(3)] ?: return null; d = g[2].toInt(); h = h24(g[3].toInt(), g[5]); mi = g[4].toInt() }
        }
        if (mo !in 1..12 || d !in 1..31 || h !in 0..23 || mi !in 0..59) return null
        cal.clear(); cal.timeZone = ET; cal.set(y, mo - 1, d, h, mi, 0)
        var start = cal.timeInMillis
        if (start < System.currentTimeMillis() - 183 * 86_400_000L) { cal.set(y + 1, mo - 1, d, h, mi, 0); start = cal.timeInMillis }
        val rest = name.replace(m.value, " | ").replace(Regex("\\(\\s*\\)|@|\\bET\\b|\\bEDT\\b|\\bEST\\b"), " ")
        val parts = rest.split('|').map { it.replace(Regex("\\s+"), " ").trim(' ', '-', ':', ',', '–', '(', ')') }.filter { it.isNotBlank() }.toMutableList()
        var guard = 0
        while (parts.size > 1 && guard++ < 2 && Regex("^[A-Z0-9+ &]{1,20}\\s?\\d{1,3}$|^[A-Z+]{2,3}$").matches(parts[0])) parts.removeAt(0)
        if (parts.isEmpty()) return null
        val sub = if (parts.size > 1) title(parts.removeAt(0)) else ""
        return Event(c, start, parts.joinToString(" · ") { title(it) }, sub)
    }

    private fun title(s: String) = s.lowercase().split(' ').joinToString(" ") { w ->
        when { w.uppercase() in keepUpper -> w.uppercase(); w == "vs" || w == "vs." -> "vs"; else -> w.replaceFirstChar { it.uppercase() } }
    }
}

// ------------------------------------------------------------------------------------------------ Events & Scores
class EventsActivity : FeatureListActivity() {
    // Only ESPN's data is listed: channel names can carry a wrong time or title, ESPN does not.
    // (Channel names are still used behind the scenes to find the channels showing a game.)
    private var tab = "games"
    private var league = ""
    private var firstFocusDone = false
    private var games: List<JSONObject> = emptyList()
    // fights, golf, races, tennis (one row per event) and the leagues the console has switched on, in its order
    private var events: List<JSONObject> = emptyList()
    private var meta: Map<String, JSONObject> = emptyMap()
    private var allTeams: List<TeamInfo> = emptyList()
    private var next: Map<String, Game?> = emptyMap()
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title("Live Sports", "Games, fights, races and tournaments from ESPN. Select one for the details and the channels showing it.")
        action("Refresh") { load() }
        enableSearch("Search a team: Cowboys, Lakers, Real Madrid...", live = true) { q ->
            query = q.trim()
            if (tab == "find") render()
        }
        b.searchRow.visibility = View.GONE
        tabs()
        intent.getStringExtra(EXTRA_TAB)?.let { tab = it; onTab() }
        load()
    }

    companion object { const val EXTRA_GAME = "game_json"; const val EXTRA_TAB = "tab" }

    // back from a team page: it may have followed or unfollowed the team
    override fun onRestart() {
        super.onRestart()
        if (tab == "mine") loadMine() else if (tab == "find") render()
    }

    private fun tabs() = chips(listOf("games" to "Games", "results" to "Results", "mine" to "My Teams", "find" to "Find a Team"), tab) {
        tab = it; league = ""; onTab()
    }

    /** League filter under the tabs (Games and Results only), in US order with the names US viewers use. */
    private fun leagues() {
        if (tab != "games" && tab != "results") { chips2(emptyList(), "") {}; return }
        val wanted = if (tab == "results") "post" else null
        val present = (games + events).filter { g -> if (wanted != null) g.optString("state") == wanted else g.optString("state") != "post" }
            .map { it.optString("league") }.toSet()
        val known = if (meta.isNotEmpty()) meta.keys.toList() else US_ORDER
        val order = known + present.filter { it !in known }
        val items = listOf("" to "All") + order.filter { it in present }.map { it to leagueName(it) }
        if (league.isNotEmpty() && league !in present) league = ""
        chips2(if (items.size > 2) items else emptyList(), league) { league = it; render() }
    }

    // how US viewers rank them: football first, college right after its pro league, soccer last
    private val US_ORDER = listOf("NFL", "NCAAF", "NBA", "NCAAB", "WNBA", "NHL", "MLB", "MLS", "EPL", "UCL", "LALIGA")

    private fun leagueName(code: String) = meta[code]?.optString("name")?.takeIf { it.isNotBlank() } ?: when (code) {
        "NCAAF" -> "College Football"; "NCAAB" -> "College Basketball"; "EPL" -> "Premier League"
        "UCL" -> "Champions League"; "LALIGA" -> "La Liga"; else -> code
    }

    /** Short league label at the start of a row: the console name when it fits ("UFC", "Liga MX"), else the code. */
    private fun lead(code: String) = leagueName(code).takeIf { it.length <= 10 } ?: code.takeIf { it.length <= 6 }
        ?: sport(code).replace('-', ' ').replaceFirstChar { it.uppercase() }.takeIf { it.isNotBlank() && it.length <= 10 } ?: code.take(8)

    private fun sport(code: String) = meta[code]?.optString("sport").orEmpty()

    private fun onTab() {
        b.searchRow.visibility = if (tab == "find") View.VISIBLE else View.GONE
        when (tab) {
            "mine" -> loadMine()
            "find" -> withTeams { render() }
            else -> render()
        }
    }

    private fun load() {
        loading(true)
        lifecycleScope.launch {
            val r = runCatching { Web24Api(this@EventsActivity).support("scores") }.getOrNull()
            games = Web24Api.objects(r?.optJSONArray("games"))
            events = Web24Api.objects(r?.optJSONArray("events"))
            meta = Web24Api.objects(r?.optJSONArray("leagues")).associateBy { it.optString("code") }
            loading(false)
            TeamAlerts.update(this@EventsActivity, games.mapNotNull { Game.parse(it) } + next.values.filterNotNull())
            render()
            Game.fromJson(intent.getStringExtra(EXTRA_GAME))?.let { g ->
                intent.removeExtra(EXTRA_GAME)
                tab = "games"; onTab(); openGame(g)
            }
            if (tab == "mine") loadMine()
        }
    }

    /** Loads the team list once (cached on the device for a day), then runs [then]. */
    private fun withTeams(then: () -> Unit) {
        if (allTeams.isNotEmpty()) { then(); return }
        loading(true)
        lifecycleScope.launch {
            allTeams = Teams.all(this@EventsActivity)
            loading(false)
            then()
        }
    }

    private fun loadMine() = withTeams {
        loading(true)
        lifecycleScope.launch {
            next = Teams.nextGames(this@EventsActivity)
            loading(false)
            render()
            TeamAlerts.update(this@EventsActivity, games.mapNotNull { Game.parse(it) } + next.values.filterNotNull())
        }
    }

    private fun teamName(t: JSONObject) = t.optString("short").ifBlank { t.optString("name") }

    /** Start text in this device's time zone (ESPN's own text is US Eastern); "time TBD" when ESPN has no time yet. */
    private fun whenText(startMs: Long, tbd: Boolean) = if (tbd) "${Fmt.day(startMs)} · TBD" else "${Fmt.day(startMs)} · ${Fmt.clock(startMs)}"

    /** Finds the channels showing this game and opens them like a Live TV category (list + preview); none -> says so. */
    private fun openGame(game: Game) {
        loading(true)
        lifecycleScope.launch {
            val chans = GameChannels.find(this@EventsActivity, game)
            loading(false)
            if (chans.isEmpty()) {
                toast(
                    if (game.state == "post") "${game.title} has ended."
                    else "No channel lists ${game.title} yet. Event channels usually add it shortly before the start."
                )
                return@launch
            }
            // Same screen as Live TV: channels on the left, preview on the right, so the viewer can check which
            // channel really has the game before going full screen.
            com.network24.player.features.player.state.PlayerState.currentPosition = 0
            startActivity(
                Intent(this@EventsActivity, com.network24.player.features.livetv.LiveTvActivity::class.java)
                    .putExtra("category_name", "${game.title} · ${game.league}")
                    .putExtra(com.network24.player.features.livetv.LiveTvActivity.EXTRA_STREAM_IDS, chans.map { it.streamId }.toIntArray())
            )
        }
    }

    private fun render() {
        tabs()
        leagues()
        when (tab) {
            "games" -> renderGames(results = false)
            "results" -> renderGames(results = true)
            "mine" -> renderMine()
            else -> renderFind()
        }
        // the remote starts on the first game, not on the Back button
        if (!firstFocusDone && games.isNotEmpty()) { firstFocusDone = true; focusFirstRow() }
    }

    /**
     * Games = live first, then upcoming by start time. Results = finished games, newest first, with ESPN's own
     * end-of-game text ("Final", "Final/OT").
     * Scores read "Suns 97 @ Pistons 103" so each number sits next to its team.
     */
    private fun renderGames(results: Boolean) {
        val keys = Teams.keys(this)
        val list = (games + events).filter { g -> (league == "" || g.optString("league") == league) && (g.optString("state") == "post") == results }
            .let { l -> if (results) l.sortedByDescending { it.optLong("end").takeIf { e -> e > 0 } ?: it.optLong("start") } else l.sortedWith(compareBy({ if (it.optString("state") == "in") 0 else 1 }, { it.optLong("start") })) }
        val rows = list.map { g -> if (g.has("kind")) eventRow(g) else gameRow(g, keys) }
        show(rows, if (results) "No results yet." else "Nothing live or coming up right now.\nCheck Results for final scores.")
    }

    /** A fight card, golf tournament, race weekend or tennis tournament. */
    private fun eventRow(e: JSONObject): Row {
        val state = e.optString("state")
        val lg = e.optString("league")
        val live = EventCenter.list(e.optJSONArray("live"))
        val next = e.optJSONObject("next")
        val head = e.optString("headline")
        if (e.optString("kind") == "cricket") {
            // "IND 245/8 (62.4 ov) · AUS 174", then the result / match situation
            val score = Web24Api.objects(e.optJSONArray("teams")).filter { it.optString("score").isNotBlank() }
                .joinToString("  ·  ") { it.optString("short").ifBlank { it.optString("name") } + " " + it.optString("score") }
            val line = listOf(score, head).filter { it.isNotBlank() }.joinToString("  ·  ")
            return Row("ev:$lg:" + e.optString("id"), e.optString("name"), line.ifBlank { listOf(e.optString("format"), e.optString("venue")).filter { it.isNotBlank() }.joinToString(" · ") },
                e.optString("series"), showIcon = false, lead = lead(lg),
                badge = when (state) { "in" -> "LIVE"; "post" -> e.optString("detail").ifBlank { "Result" }; else -> "${Fmt.day(e.optLong("start") * 1000)} · ${Fmt.clock(e.optLong("start") * 1000)}" },
                badgeColor = if (state == "in") LIVE else 0) { EventCenter.open(this, e, leagueName(lg)) }
        }
        val sub = when {
            state == "in" && live.isNotEmpty() -> "LIVE: " + live.joinToString(" · ")
            head.isNotBlank() -> head
            next != null && next.optLong("start") > 0 -> listOf(next.optString("name"), "${Fmt.day(next.optLong("start") * 1000)} at ${Fmt.clock(next.optLong("start") * 1000)}").filter { it.isNotBlank() }.joinToString(" · ")
            else -> ""
        }
        val sub2 = listOf(e.optString("venue"), e.optString("city")).filter { it.isNotBlank() }.joinToString(", ")
        return Row("ev:$lg:" + e.optString("id"), e.optString("name"), sub, sub2, showIcon = false, lead = lead(lg),
            badge = when (state) { "in" -> "LIVE"; "post" -> e.optString("detail").ifBlank { "Final" }; else -> EventCenter.dates(e) },
            badgeColor = if (state == "in") LIVE else 0) { EventCenter.open(this, e, leagueName(lg)) }
    }

    private fun gameRow(g: JSONObject, keys: Set<String>): Row = run {
            val t = Web24Api.objects(g.optJSONArray("teams"))
            val home = t.firstOrNull { it.optBoolean("home") } ?: t.getOrNull(0) ?: JSONObject()
            val away = t.firstOrNull { it !== home } ?: t.getOrNull(1) ?: JSONObject()
            val state = g.optString("state")
            val parsed = Game.parse(g)
            val followed = parsed != null && Teams.isFollowed(this, parsed, keys)
            val startMs = g.optLong("start") * 1000
            val tbd = g.optBoolean("tbd")
            val result = if (state == "post") resultOf(away, home) else null
            val title = when {
                state == "pre" -> "${teamName(away)} @ ${teamName(home)}"
                result != null -> result.first
                else -> "${teamName(away)} ${away.optString("score")}  @  ${teamName(home)} ${home.optString("score")}"
            }
            val sub = when (state) {
                "in" -> g.optString("detail").ifBlank { "In progress" }
                // ESPN's own end-of-game text ("Final", "Final/OT", "FT") is the badge
                "post" -> "${result?.second ?: ""} · at ${teamName(home)} · ${Fmt.day(startMs)}".trimStart(' ', '·')
                else -> if (tbd) "${Fmt.day(startMs)} · start time to be announced" else "Starts ${Fmt.day(startMs)} at ${Fmt.clock(startMs)}"
            }
            Row(g.optString("league") + g.optLong("start") + teamName(home), title, sub, if (followed) "★ Your team" else "",
                showIcon = false, lead = lead(g.optString("league")),
                badge = when (state) { "in" -> "LIVE"; "post" -> g.optString("detail").ifBlank { "Final" }; else -> whenText(startMs, tbd) },
                badgeColor = if (state == "in") LIVE else if (followed) GOLD else 0) {
                // Game Center (score, stats, Watch button); older cached scores have no game id yet
                if (g.optString("id").isNotBlank()) GameCenter.open(this, JSONObject(g.toString()).put("sport", sport(g.optString("league"))).put("league_name", leagueName(g.optString("league"))))
                else if (state == "post") toast("This game is over. Final: ${teamName(away)} ${away.optString("score")}, ${teamName(home)} ${home.optString("score")}.")
                else parsed?.let { openGame(it) }
            }
    }

    /**
     * A finished game the way a scoreboard reads it: winner first ("Lakers 127 – Kings 103") and who won by how
     * much ("Lakers won by 24"), or "Draw". Null when a score is missing or not a number.
     */
    private fun resultOf(away: JSONObject, home: JSONObject): Pair<String, String>? {
        val a = away.optString("score").trim().toIntOrNull() ?: return null
        val h = home.optString("score").trim().toIntOrNull() ?: return null
        val (w, ws, l, ls) = if (a >= h) listOf(teamName(away), a, teamName(home), h) else listOf(teamName(home), h, teamName(away), a)
        val title = "$w $ws – $l $ls"
        return title to if (a == h) "Draw" else "$w won by ${(ws as Int) - (ls as Int)}"
    }

    private fun order(t: TeamInfo) = Teams.LEAGUE_ORDER.indexOf(t.league).let { if (it < 0) 99 else it }

    private fun renderMine() {
        val byKey = allTeams.associateBy { it.key }
        val mine = Teams.keys(this).mapNotNull { byKey[it] }.sortedWith(compareBy({ order(it) }, { it.name }))
        show(mine.map { t ->
            val g = next[t.key]
            val isHome = g?.home?.id == t.id
            val opp = if (g == null) "" else if (isHome) "vs ${g.away.short}" else "@ ${g.home.short}"
            val sub: String
            val badge: String
            val color: Int
            when {
                g == null -> { sub = "No game scheduled right now"; badge = "Following"; color = GOOD }
                g.state == "in" -> {
                    val sc = runCatching {
                        val tj = Web24Api.objects(JSONObject(g.json).optJSONArray("teams"))
                        val h = tj.firstOrNull { it.optBoolean("home") }; val a = tj.firstOrNull { !it.optBoolean("home") }
                        "  ${a?.optString("score")} - ${h?.optString("score")}"
                    }.getOrDefault("")
                    sub = "LIVE now $opp$sc"; badge = "LIVE"; color = LIVE
                }
                else -> { sub = "Next: ${whenText(g.startMs, g.tbd)} $opp"; badge = whenText(g.startMs, g.tbd); color = 0 }
            }
            Row("mine:${t.key}", t.name, sub, "", showIcon = false, lead = t.league, badge = badge, badgeColor = color) {
                val options = listOfNotNull(g?.let { "Watch ${it.title}" }, "Team page - schedule and results", "Unfollow ${t.name}")
                pickOne(t.name, options, 0) { i ->
                    val pick = if (g == null) i + 1 else i
                    if (pick == 0 && g != null) openGame(g)
                    else if (pick == 1) GameCenter.openTeam(this, t.league, t.id, t.name)
                    else lifecycleScope.launch {
                        Teams.set(this@EventsActivity, t, false, allTeams)
                        toast("${t.name} removed from My Teams")
                        render()
                        TeamAlerts.update(this@EventsActivity, games.mapNotNull { Game.parse(it) } + next.values.filterNotNull())
                    }
                }
            }
        }, "You don't follow any team yet.\nOpen Find a Team, search for your team and select it to follow it.\nYou get an alert 5 minutes before each of its games.")
    }

    private fun renderFind() {
        val keys = Teams.keys(this)
        val q = query.lowercase()
        val list = if (q.isBlank()) allTeams.filter { it.league != "NCAAF" && it.league != "NCAAB" }
        else allTeams.filter { t -> listOf(t.name, t.short, t.abbr, t.location).any { it.lowercase().contains(q) } }
        val rows = mutableListOf<Row>()
        // the hint lives in the search box (a hint row in the list only caught the remote's focus)
        b.editSearch.hint = "Search ${String.format(java.util.Locale.US, "%,d", allTeams.size)} teams - pro teams below, college teams when you type (e.g. Alabama)"
        rows += list.sortedWith(compareBy({ order(it) }, { it.name })).take(400).map { t ->
            val on = t.key in keys
            Row("find:${t.key}", t.name, if (on) "Following - select for the team page" else "Select for the team page and Follow · hold to follow right away",
                showIcon = false, lead = t.league, badge = if (on) "Following" else "Follow", badgeColor = if (on) GOOD else 0,
                onLong = {
                lifecycleScope.launch {
                    Teams.set(this@EventsActivity, t, !on, allTeams)
                    toast(if (on) "${t.name} removed from My Teams" else "${t.name} added to My Teams")
                    render()
                    next = Teams.nextGames(this@EventsActivity)
                    TeamAlerts.update(this@EventsActivity, games.mapNotNull { Game.parse(it) } + next.values.filterNotNull())
                }
            }) { GameCenter.openTeam(this, t.league, t.id, t.name) }
        }
        show(rows, if (allTeams.isEmpty()) "The team list could not be loaded. Select Refresh to try again." else "No team matches \"$query\".")
    }
}

// ------------------------------------------------------------------------------------------------ Find a Show
/** Old entry point: the app has one search now (channels, shows on now and later, sports, catch-up). */
class ShowSearchActivity : androidx.appcompat.app.AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.network24.player.features.search.SearchOverlay.show(this, intent.getStringExtra(EXTRA_QUERY)) { finish() }
    }

    companion object { const val EXTRA_QUERY = "query" }
}

// ------------------------------------------------------------------------------------------------ open a channel from a notification
class WatchLinkActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(ReminderReceiver.EXTRA_WATCH, 0)
        val act = this
        kotlinx.coroutines.MainScope().launch {
            val db = com.network24.player.core.database.DatabaseProvider.get(act)
            val ch = runCatching { db.channelDao().getByStreamIds(listOf(id)).firstOrNull() }.getOrNull()
            if (ch != null) ChannelLauncher.play(act, db.channelDao().getByCategory(ch.categoryId.orEmpty()), ch)
            else startActivity(Intent(act, com.network24.player.features.splash.activity.SplashActivity::class.java))
            finish()
        }
    }
}
