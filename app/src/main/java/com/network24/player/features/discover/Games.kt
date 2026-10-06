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
data class Game(val league: String, val startMs: Long, val state: String, val home: Team, val away: Team, val json: String) {
    data class Team(val name: String, val short: String, val abbr: String)

    val title get() = "${away.short} @ ${home.short}"
    val key get() = "$league:$startMs:${home.abbr}"

    companion object {
        fun parse(g: JSONObject): Game? {
            val t = Web24Api.objects(g.optJSONArray("teams"))
            val homeJ = t.firstOrNull { it.optBoolean("home") } ?: t.getOrNull(0) ?: return null
            val awayJ = t.firstOrNull { it !== homeJ } ?: return null
            fun team(j: JSONObject) = Team(j.optString("name"), j.optString("short").ifBlank { j.optString("name") }, j.optString("abbr"))
            return Game(g.optString("league"), g.optLong("start") * 1000, g.optString("state"), team(homeJ), team(awayJ), g.toString())
        }

        fun fromJson(s: String?): Game? = s?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }
    }
}

/**
 * Finds the channels showing a game, best match first:
 *  1. event channels whose name carries the game (team names, start time within 12 h),
 *  2. any channel whose name names both teams,
 *  3. channels whose TV guide has a programme with a team's name at game time.
 * Replay categories and categories under the parental lock are left out.
 */
object GameChannels {
    private const val HOUR = 3_600_000L

    suspend fun find(context: Context, game: Game): List<ChannelEntity> = withContext(Dispatchers.IO) {
        val db = DatabaseProvider.get(context)
        val replay = db.categoryDao().getByType(CategoryType.LIVE).filter { it.name?.contains("REPLAY", true) == true }.map { it.categoryId }.toSet()
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
        score.entries.sortedByDescending { it.value }.take(12).mapNotNull { byId[it.key] }
    }

    /** How many of the two teams the text names (0, 1 or 2). */
    fun hits(text: String, game: Game): Int = listOf(game.home, game.away).count { t -> names(t).any { word(it).containsMatchIn(text) } }

    private fun names(t: Game.Team) = listOf(t.name, t.short).filter { it.length >= 3 }.distinct()
    private fun word(s: String) = Regex("(?<![A-Za-z])" + Regex.escape(s) + "(?![A-Za-z])", RegexOption.IGNORE_CASE)
}

/**
 * Alerts for followed teams (Events & Scores > My teams): a notification 5 minutes before their next games.
 * The list of upcoming games is read every few hours in the background and whenever Scores or the team list changes.
 */
object TeamAlerts {
    private const val LEAD_MS = 5 * 60_000L
    private const val AHEAD_MS = 48 * 3_600_000L
    private const val WORK = "team_alerts"

    fun teams(c: Context): Set<String> = c.getSharedPreferences("n24_teams", Context.MODE_PRIVATE).getStringSet("teams", emptySet()).orEmpty()

    fun followed(c: Context, game: Game, teams: Set<String> = teams(c)) =
        teams.any { f -> listOf(game.home, game.away).any { it.short.equals(f, true) || it.name.equals(f, true) } }

    /** Runs the background check every 6 hours (first run straight away). */
    fun schedule(c: Context) {
        val req = PeriodicWorkRequestBuilder<TeamAlertWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    suspend fun refresh(c: Context) {
        val games = runCatching { Web24Api.objects(Web24Api(c).support("scores").optJSONArray("games")).mapNotNull { Game.parse(it) } }.getOrNull() ?: return
        update(c, games)
    }

    /** Re-plans the alarms from this list of games (cancels the old ones first). */
    fun update(c: Context, games: List<Game>) {
        val prefs = c.getSharedPreferences("n24_team_alerts", Context.MODE_PRIVATE)
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        prefs.getStringSet("planned", emptySet()).orEmpty().forEach { json -> Game.fromJson(json)?.let { am.cancel(pending(c, it)) } }
        val teams = teams(c)
        val now = System.currentTimeMillis()
        val planned = games.filter { it.state == "pre" && it.startMs - LEAD_MS > now && it.startMs - now < AHEAD_MS && followed(c, it, teams) }
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
        if (TeamAlerts.teams(applicationContext).isEmpty()) {
            TeamAlerts.update(applicationContext, emptyList())
            return Result.success()
        }
        TeamAlerts.refresh(applicationContext)
        return Result.success()
    }
}
