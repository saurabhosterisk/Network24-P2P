package com.network24.player.features.discover

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.network24.player.R
import com.network24.player.core.api.Web24Api
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.entity.CategoryType
import com.network24.player.core.database.entity.ChannelEntity
import com.network24.player.core.parental.ParentalLock
import com.network24.player.features.reminders.Reminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** One league game from Scores (Main's support_api.php "scores", from the ESPN scoreboard). */
data class Game(val league: String, val startMs: Long, val state: String, val home: Team, val away: Team, val json: String, val tbd: Boolean = false) {
    data class Team(val name: String, val short: String, val abbr: String, val id: String = "") {
        fun key(league: String) = "$league:$id"
    }

    val title get() = "${away.short} @ ${home.short}"
    val key get() = "$league:$startMs:${home.abbr}"

    companion object {
        fun parse(g: JSONObject): Game? {
            val t = Web24Api.objects(g.optJSONArray("teams"))
            val homeJ = t.firstOrNull { it.optBoolean("home") } ?: t.getOrNull(0) ?: return null
            val awayJ = t.firstOrNull { it !== homeJ } ?: return null
            fun team(j: JSONObject) = Team(j.optString("name"), j.optString("short").ifBlank { j.optString("name") }, j.optString("abbr"), j.optString("id"))
            return Game(g.optString("league"), g.optLong("start") * 1000, g.optString("state"), team(homeJ), team(awayJ), g.toString(), g.optBoolean("tbd"))
        }

        fun fromJson(s: String?): Game? = s?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
    }
}

/**
 * Finds the channels showing a game, best match first:
 *  1. event channels whose name carries the game (team names, start time within 12 h),
 *  2. any channel whose name names both teams,
 *  3. channels whose TV guide has a programme with a team's name at game time.
 * Replay categories and categories under the parental lock are left out. Channels in the league's own package
 * category (NHL package for NHL games...) come first; on-demand ESPN Unlimited channels come last because they
 * take up to a minute to start.
 */
object GameChannels {
    private const val HOUR = 3_600_000L

    /** Words that mark a category as the league's own package. */
    private val PACKAGE = mapOf(
        "NFL" to listOf("NFL"), "NBA" to listOf("NBA"), "WNBA" to listOf("WNBA"), "NHL" to listOf("NHL"), "MLB" to listOf("MLB"),
        "MLS" to listOf("MLS"), "NCAAF" to listOf("NCAA", "COLLEGE"), "NCAAB" to listOf("NCAA", "COLLEGE"),
        "EPL" to listOf("PREMIER", "EPL"), "UCL" to listOf("CHAMPIONS", "UCL"), "LALIGA" to listOf("LALIGA", "LA LIGA")
    )

    suspend fun find(context: Context, game: Game): List<ChannelEntity> = withContext(Dispatchers.IO) {
        val db = DatabaseProvider.get(context)
        val categories = db.categoryDao().getByType(CategoryType.LIVE)
        val catName = categories.associate { it.categoryId to it.name.orEmpty() }
        val replay = categories.filter { it.name?.contains("REPLAY", true) == true }.map { it.categoryId }.toSet()
        val locked = ParentalLock.activeLockedIds(context)
        val all = db.channelDao().getAll().filter { it.categoryId !in replay && (it.categoryId == null || it.categoryId !in locked) }
        val byId = all.associateBy { it.streamId }
        val score = HashMap<Int, Int>()
        fun add(id: Int, pts: Int) { if (id in byId) score[id] = maxOf(score[id] ?: 0, pts) }

        EventParser.parse(all, replay).forEach { e ->
            val hits = hits(e.ch.name.orEmpty(), game)
            if (hits > 0 && abs(e.start - game.startMs) <= 12 * HOUR) add(e.ch.streamId, 100 + 20 * hits - (abs(e.start - game.startMs) / HOUR).toInt())
        }
        all.forEach { ch -> if (hits(ch.name.orEmpty(), game) == 2) add(ch.streamId, 90) }

        // TV guide rows from the XMLTV feed carry the guide id, not the stream id (streamId = 0)
        val byGuideId = all.filter { !it.epgChannelId.isNullOrBlank() }.groupBy { it.epgChannelId!!.lowercase() }
        val now = System.currentTimeMillis()
        val at = if (game.state == "in") now else game.startMs
        // one guide search per team: the short name ("Knicks") is part of the full one ("New York Knicks")
        listOf(game.home, game.away).map { if (it.short.length >= 3) it.short else it.name }.filter { it.length >= 3 }.distinct().forEach { q ->
            db.epgDao().searchProgramsInWindow(q, at, at + HOUR).forEach { p ->
                val h = hits(p.title.orEmpty() + " " + p.description.orEmpty().take(200), game)
                if (h == 0) return@forEach
                val ids = if (p.streamId > 0) listOf(p.streamId) else byGuideId[p.epgChannelId?.lowercase()].orEmpty().map { it.streamId }
                ids.forEach { add(it, 40 + 20 * h) }
            }
        }
        val words = PACKAGE[game.league].orEmpty()
        fun rank(id: Int): Int {
            val ch = byId[id] ?: return 0
            val cat = catName[ch.categoryId].orEmpty()
            val pkg = if (words.any { Regex("(?<![A-Za-z])" + Regex.escape(it) + "(?![A-Za-z])", RegexOption.IGNORE_CASE).containsMatchIn(cat) }) 60 else 0
            val slow = if (SlowChannels.isSlow(cat, ch.name)) 80 else 0
            return (score[id] ?: 0) + pkg - slow
        }
        score.keys.sortedByDescending { rank(it) }.take(12).mapNotNull { byId[it] }
    }

    /** How many of the two teams the text names (0, 1 or 2). */
    fun hits(text: String, game: Game): Int = listOf(game.home, game.away).count { t -> names(t).any { word(it).containsMatchIn(text) } }

    private fun names(t: Game.Team) = listOf(t.name, t.short).filter { it.length >= 3 }.distinct()
    private fun word(s: String) = Regex("(?<![A-Za-z])" + Regex.escape(s) + "(?![A-Za-z])", RegexOption.IGNORE_CASE)
}

/** On-demand channels (ESPN Unlimited / ESPN+) that take up to a minute to start. */
object SlowChannels {
    private val PATTERN = Regex("""ESPN\s*(UNLTD|UNLIMITED|\+|PLUS)""", RegexOption.IGNORE_CASE)
    fun isSlow(category: String?, name: String?) = PATTERN.containsMatchIn(category.orEmpty()) || PATTERN.containsMatchIn(name.orEmpty())
    const val HINT = "On-demand channel · can take up to a minute to start"
}

/** One team from Main's "teams" list (all teams of the leagues in Scores, from ESPN). */
data class TeamInfo(val league: String, val id: String, val name: String, val short: String, val abbr: String, val location: String) {
    val key get() = "$league:$id"
}

/**
 * Followed teams. The app keeps them as league:id (several leagues share names: Kings, Panthers, Giants...). The
 * account's "teams" list on Main (used by the web player) keeps getting the short names, as before.
 * Teams followed by name in older builds are turned into league:id the first time the team list is loaded.
 */
object Teams {
    private const val FILE = "espn_teams.json"
    private const val DAY = 86_400_000L
    val LEAGUE_ORDER = listOf("NFL", "NBA", "NHL", "MLB", "MLS", "WNBA", "EPL", "UCL", "LALIGA", "NCAAF", "NCAAB")

    private fun prefs(c: Context) = c.getSharedPreferences("n24_teams", Context.MODE_PRIVATE)
    fun keys(c: Context): Set<String> = prefs(c).getStringSet("keys", emptySet()).orEmpty()
    private fun legacy(c: Context): Set<String> = if (prefs(c).getBoolean("keys_ready", false)) emptySet() else prefs(c).getStringSet("teams", emptySet()).orEmpty()
    fun hasAny(c: Context) = keys(c).isNotEmpty() || legacy(c).isNotEmpty()

    fun isFollowed(c: Context, game: Game, keys: Set<String> = keys(c), legacy: Set<String> = legacy(c)) =
        listOf(game.home, game.away).any { t ->
            (t.id.isNotEmpty() && t.key(game.league) in keys) || legacy.any { it.equals(t.short, true) || it.equals(t.name, true) }
        }

    /** All teams, cached on the device for a day (falls back to an older copy when Main cannot be reached). */
    suspend fun all(c: Context): List<TeamInfo> = withContext(Dispatchers.IO) {
        val f = java.io.File(c.filesDir, FILE)
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < DAY
        val text = if (fresh) f.readText() else runCatching { Web24Api(c).support("teams").toString().also { if (it.contains("\"teams\"")) f.writeText(it) } }
            .getOrNull() ?: f.takeIf { it.exists() }?.readText()
        val list = runCatching { Web24Api.objects(JSONObject(text ?: "{}").optJSONArray("teams")) }.getOrDefault(emptyList()).map {
            TeamInfo(it.optString("league"), it.optString("id"), it.optString("name"), it.optString("short"), it.optString("abbr"), it.optString("location"))
        }
        if (list.isNotEmpty()) migrate(c, list)
        list
    }

    private fun migrate(c: Context, all: List<TeamInfo>) {
        val p = prefs(c)
        if (p.getBoolean("keys_ready", false)) return
        val keys = keys(c).toMutableSet()
        p.getStringSet("teams", emptySet()).orEmpty().forEach { n ->
            all.filter { it.short.equals(n, true) || it.name.equals(n, true) }
                .minByOrNull { LEAGUE_ORDER.indexOf(it.league).let { i -> if (i < 0) 99 else i } }?.let { keys += it.key }
        }
        p.edit().putStringSet("keys", keys).putBoolean("keys_ready", true).apply()
    }

    /** Follows or unfollows; saves the short names to the account for the web player. */
    suspend fun set(c: Context, team: TeamInfo, follow: Boolean, all: List<TeamInfo>) {
        val keys = keys(c).toMutableSet().apply { if (follow) add(team.key) else remove(team.key) }
        val byKey = all.associateBy { it.key }
        val names = keys.mapNotNull { byKey[it]?.short?.ifBlank { byKey[it]?.name } }.distinct().take(40)
        prefs(c).edit().putStringSet("keys", keys).putStringSet("teams", names.toSet()).putBoolean("keys_ready", true).apply()
        runCatching { Web24Api(c).support("web_state_set", "key" to "teams", *names.mapIndexed { i, x -> "value[$i]" to x }.toTypedArray()) }
    }

    /** Next (or live) game of each followed team, from the team schedules on ESPN (cached on Main for an hour). */
    suspend fun nextGames(c: Context, keys: Collection<String> = keys(c)): Map<String, Game?> {
        if (keys.isEmpty()) return emptyMap()
        val g = runCatching { Web24Api(c).support("team_next", "teams" to keys.joinToString(",")).optJSONObject("games") }.getOrNull() ?: return emptyMap()
        return keys.associateWith { k -> g.optJSONObject(k)?.let { Game.parse(it) } }
    }
}

/**
 * Alerts for followed teams (Events & Scores > My teams): a notification 5 minutes before their next games.
 * The list of upcoming games is read every few hours in the background and whenever Scores or the team list changes.
 */
object TeamAlerts {
    private const val LEAD_MS = 5 * 60_000L
    private const val AHEAD_MS = 48 * 3_600_000L
    private const val WORK = "team_alerts"


    /** Runs the background check every 6 hours (first run straight away). */
    fun schedule(c: Context) {
        val req = PeriodicWorkRequestBuilder<TeamAlertWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Scoreboard games plus the next game of every followed team (so a team not on today's scoreboard is covered too). */
    suspend fun refresh(c: Context) {
        val games = runCatching { Web24Api.objects(Web24Api(c).support("scores").optJSONArray("games")).mapNotNull { Game.parse(it) } }.getOrDefault(emptyList())
        val next = Teams.nextGames(c).values.filterNotNull()
        if (games.isEmpty() && next.isEmpty()) return
        update(c, games + next)
    }

    /** Re-plans the alarms from this list of games (cancels the old ones first). */
    fun update(c: Context, games: List<Game>) {
        val prefs = c.getSharedPreferences("n24_team_alerts", Context.MODE_PRIVATE)
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        prefs.getStringSet("planned", emptySet()).orEmpty().forEach { json -> Game.fromJson(json)?.let { am.cancel(pending(c, it)) } }
        val keys = Teams.keys(c)
        val now = System.currentTimeMillis()
        val planned = games.filter { it.state == "pre" && !it.tbd && it.startMs - LEAD_MS > now && it.startMs - now < AHEAD_MS && Teams.isFollowed(c, it, keys) }
            .distinctBy { it.key }
        planned.forEach { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.startMs - LEAD_MS, pending(c, it)) }
        prefs.edit().putStringSet("planned", planned.map { it.json }.toSet()).apply()
    }

    private fun pending(c: Context, g: Game): PendingIntent =
        PendingIntent.getBroadcast(c, g.key.hashCode(), Intent(c, TeamGameReceiver::class.java).putExtra(EventsActivity.EXTRA_GAME, g.json),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

class TeamGameReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val json = intent.getStringExtra(EventsActivity.EXTRA_GAME)
        val game = Game.fromJson(json) ?: return
        Reminders.channel(c)
        val open = PendingIntent.getActivity(c, game.key.hashCode(),
            Intent(c, EventsActivity::class.java).putExtra(EventsActivity.EXTRA_GAME, json)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, "reminders").setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("${game.away.name} @ ${game.home.name} starts in 5 minutes")
            .setContentText("${game.league} · ${Fmt.clock(game.startMs)} - tap to find a channel and watch")
            .setContentIntent(open).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        runCatching { NotificationManagerCompat.from(c).notify(game.key.hashCode(), n) }
    }
}

class TeamAlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!Teams.hasAny(applicationContext)) {
            TeamAlerts.update(applicationContext, emptyList())
            return Result.success()
        }
        TeamAlerts.refresh(applicationContext)
        return Result.success()
    }
}
