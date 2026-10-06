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
    private var tab = "scores"
    private var league = ""
    private var games: List<JSONObject> = emptyList()
    private var allTeams: List<TeamInfo> = emptyList()
    private var next: Map<String, Game?> = emptyMap()
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title("Events & Scores", "Live scores and games from the leagues - select a game to see the channels showing it")
        action("Refresh") { load() }
        enableSearch("Search a team: Cowboys, Lakers, Real Madrid...", live = true) { q ->
            query = q.trim()
            if (tab == "find") render()
        }
        b.searchRow.visibility = View.GONE
        tabs()
        load()
    }

    companion object { const val EXTRA_GAME = "game_json" }

    private fun tabs() = chips(listOf("scores" to "Scores", "mine" to "My teams", "find" to "Find a team"), tab) {
        tab = it; league = ""; onTab()
    }

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
            games = runCatching { Web24Api.objects(Web24Api(this@EventsActivity).support("scores").optJSONArray("games")) }.getOrDefault(emptyList())
            loading(false)
            TeamAlerts.update(this@EventsActivity, games.mapNotNull { Game.parse(it) } + next.values.filterNotNull())
            render()
            Game.fromJson(intent.getStringExtra(EXTRA_GAME))?.let { g ->
                intent.removeExtra(EXTRA_GAME)
                tab = "scores"; onTab(); openGame(g)
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
    private fun whenText(startMs: Long, tbd: Boolean) = if (tbd) "${Fmt.day(startMs)} · time TBD" else "${Fmt.day(startMs)} · ${Fmt.clock(startMs)}"

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
                Intent(this@EventsActivity, com.network24.player.features.live.activity.ChannelListActivity::class.java)
                    .putExtra("category_name", "${game.title} · ${game.league}")
                    .putExtra(com.network24.player.features.live.activity.ChannelListActivity.EXTRA_STREAM_IDS, chans.map { it.streamId }.toIntArray())
            )
        }
    }

    private fun render() {
        tabs()
        when (tab) {
            "scores" -> renderScores()
            "mine" -> renderMine()
            else -> renderFind()
        }
    }

    private fun renderScores() {
        val keys = Teams.keys(this)
        val leagues = games.map { it.optString("league") }.distinct()
        val list = games.filter { league == "" || it.optString("league") == league }
            .sortedWith(compareBy({ when (it.optString("state")) { "in" -> 0; "pre" -> 1; else -> 2 } }, { it.optLong("start") }))
        val rows = mutableListOf<Row>()
        if (leagues.size > 1) rows += Row("leagues", "Leagues: " + (listOf("All") + leagues).joinToString(" · "), "Select to switch league" + if (league.isNotBlank()) " (now $league)" else "", showIcon = false) {
            val all = listOf("") + leagues; league = all[(all.indexOf(league) + 1) % all.size]; render()
        }
        rows += list.map { g ->
            val t = Web24Api.objects(g.optJSONArray("teams"))
            val home = t.firstOrNull { it.optBoolean("home") } ?: t.getOrNull(0) ?: JSONObject()
            val away = t.firstOrNull { it !== home } ?: t.getOrNull(1) ?: JSONObject()
            val state = g.optString("state")
            val parsed = Game.parse(g)
            val followed = parsed != null && Teams.isFollowed(this, parsed, keys)
            val score = if (state == "pre") "" else "  ${away.optString("score")} - ${home.optString("score")}"
            val startMs = g.optLong("start") * 1000
            val tbd = g.optBoolean("tbd")
            val sub = if (state == "pre") "Starts " + whenText(startMs, tbd) else g.optString("detail")
            Row(g.optString("league") + g.optLong("start") + teamName(home), "${teamName(away)} @ ${teamName(home)}$score",
                sub, if (followed) "★ A team you follow" else "", showIcon = false, lead = g.optString("league"),
                badge = when (state) { "in" -> "LIVE"; "post" -> "Final"; else -> whenText(startMs, tbd) },
                badgeColor = if (state == "in") LIVE else if (followed) GOLD else 0) {
                parsed?.let { openGame(it) }
            }
        }
        show(rows, "No scores right now.")
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
                val options = listOfNotNull(g?.let { "Watch ${it.title}" }, "Unfollow ${t.name}")
                pickOne(t.name, options, 0) { i ->
                    if (g != null && i == 0) openGame(g)
                    else lifecycleScope.launch {
                        Teams.set(this@EventsActivity, t, false, allTeams)
                        toast("${t.name} removed from My teams")
                        render()
                        TeamAlerts.update(this@EventsActivity, games.mapNotNull { Game.parse(it) } + next.values.filterNotNull())
                    }
                }
            }
        }, "You don't follow any team yet.\nOpen Find a team, search for your team and select it to follow it.\nYou get an alert 5 minutes before each of its games.")
    }

    private fun renderFind() {
        val keys = Teams.keys(this)
        val q = query.lowercase()
        val list = if (q.isBlank()) allTeams.filter { it.league != "NCAAF" && it.league != "NCAAB" }
        else allTeams.filter { t -> listOf(t.name, t.short, t.abbr, t.location).any { it.lowercase().contains(q) } }
        val rows = mutableListOf<Row>()
        if (q.isBlank()) rows += Row("find:hint", "Type a team name above to search all ${allTeams.size} teams",
            "College teams (NCAAF, NCAAB) show up when you search", showIcon = false)
        rows += list.sortedWith(compareBy({ order(it) }, { it.name })).take(400).map { t ->
            val on = t.key in keys
            Row("find:${t.key}", t.name, if (on) "Following - in My teams, alert 5 minutes before each game" else "Select to follow",
                showIcon = false, lead = t.league, badge = if (on) "Following" else "Follow", badgeColor = if (on) GOOD else 0) {
                lifecycleScope.launch {
                    Teams.set(this@EventsActivity, t, !on, allTeams)
                    toast(if (on) "${t.name} removed from My teams" else "${t.name} added to My teams")
                    render()
                    next = Teams.nextGames(this@EventsActivity)
                    TeamAlerts.update(this@EventsActivity, games.mapNotNull { Game.parse(it) } + next.values.filterNotNull())
                }
            }
        }
        show(rows, if (allTeams.isEmpty()) "The team list could not be loaded. Select Refresh to try again." else "No team matches \"$query\".")
    }
}

// ------------------------------------------------------------------------------------------------ Trending now
class TrendingActivity : FeatureListActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title("Trending Now", "The channels most Network24 viewers are watching right now")
        action("Refresh") { load() }
        load()
    }

    private fun load() {
        loading(true)
        lifecycleScope.launch {
            val pop = runCatching { Web24Api.objects(Web24Api(this@TrendingActivity).support("popular").optJSONArray("streams")).map { it.optInt("stream_id") to it.optInt("viewers") } }.getOrDefault(emptyList())
            val chans = db.channelDao().getByStreamIds(pop.map { it.first }).associateBy { it.streamId }
            val cats = db.categoryDao().getByType(CategoryType.LIVE).associate { it.categoryId to it.name.orEmpty() }
            val list = pop.mapNotNull { (id, _) -> chans[id] }
            val now = System.currentTimeMillis()
            val epg = list.mapNotNull { it.epgChannelId?.takeIf { e -> e.isNotBlank() } }.distinct().let { ids -> if (ids.isEmpty()) emptyMap() else db.epgDao().getNowByEpgChannelIdsChunked(ids, now).associateBy { it.epgChannelId } }
            loading(false)
            show(pop.mapIndexedNotNull { i, (id, viewers) ->
                val ch = chans[id] ?: return@mapIndexedNotNull null
                val p = epg[ch.epgChannelId]
                Row("t$id", ch.name.orEmpty(), cats[ch.categoryId].orEmpty(), p?.title.orEmpty(), ch.icon, lead = "#${i + 1}",
                    badge = "$viewers watching", progress = p?.let { e -> val s = e.startTimestamp ?: 0; val t = e.stopTimestamp ?: 0; if (t > s) ((now - s) * 1000 / (t - s)).toInt().coerceIn(0, 1000) else -1 } ?: -1) {
                    play(list, ch)
                }
            }, "Nothing to show right now. Try again in a minute.")
        }
    }
}

// ------------------------------------------------------------------------------------------------ Find a Show
/** Search the TV guide for a show, team or film (live now, later today and the coming days). Voice search too. */
class ShowSearchActivity : FeatureListActivity() {
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title("Find a Show", "Search the TV guide: what is on now and later. Select a show to watch or to set a reminder.")
        enableSearch("Show, team, film or news…", live = true) { q -> search(q) }
        intent.getStringExtra(EXTRA_QUERY)?.let { b.editSearch.setText(it); search(it) }
        b.editSearch.requestFocus()
        show(emptyList(), "Type at least 3 letters, or use the microphone.")
    }

    private fun search(q0: String) {
        job?.cancel()
        val q = q0.trim()
        if (q.length < 3) { show(emptyList(), "Type at least 3 letters, or use the microphone."); return }
        job = lifecycleScope.launch {
            delay(350); loading(true)
            val now = System.currentTimeMillis()
            val progs = withContext(Dispatchers.IO) { db.epgDao().searchProgramsInWindow(q, now, now + 7 * 86_400_000L) }.take(200)
            val chans = withContext(Dispatchers.IO) { db.channelDao().getAll() }
            val byEpg = chans.filter { !it.epgChannelId.isNullOrBlank() }.groupBy { it.epgChannelId }
            val named = chans.filter { it.name?.contains(q, true) == true }.take(30)
            loading(false)
            val rows = mutableListOf<Row>()
            rows += named.map { ch -> Row("c${ch.streamId}", ch.name.orEmpty(), "Channel", icon = ch.icon, badge = "Watch") { play(named, ch) } }
            progs.forEach { p ->
                val ch = byEpg[p.epgChannelId]?.firstOrNull() ?: return@forEach
                val s = p.startTimestamp ?: return@forEach; val e = p.stopTimestamp ?: return@forEach
                val isNow = now in s until e
                val set = Reminders.has(this@ShowSearchActivity, ch.streamId, s)
                rows += Row("p${ch.streamId}@$s", p.title.orEmpty(), "${ch.name.orEmpty()}", if (isNow) "On now · ends ${Fmt.clock(e)}" else "${Fmt.day(s)} ${Fmt.clock(s)}", ch.icon,
                    badge = when { isNow -> "LIVE"; set -> "Reminder set"; else -> "Remind me" }, badgeColor = if (isNow) LIVE else if (set) GOLD else 0,
                    progress = if (isNow) ((now - s) * 1000 / (e - s)).toInt() else -1) {
                    if (isNow || s - now < 10 * 60_000L) play(listOf(ch), ch) else { Reminders.toggle(this@ShowSearchActivity, ch.streamId, s, p.title.orEmpty(), ch.name.orEmpty()); search(q) }
                }
            }
            show(rows, "Nothing found for \"$q\" in the TV guide.")
        }
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
