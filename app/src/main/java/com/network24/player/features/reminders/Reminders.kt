package com.network24.player.features.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.network24.player.R
import com.network24.player.features.discover.ChannelLauncher
import com.network24.player.features.discover.FeatureListActivity
import com.network24.player.features.discover.Fmt
import com.network24.player.features.discover.Row
import com.network24.player.core.database.DatabaseProvider
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** "Remind me": a notification 2 minutes before a show or event starts; tapping it opens the channel. */
object Reminders {
    data class Item(val streamId: Int, val start: Long, val title: String, val channel: String)

    private fun prefs(c: Context) = c.getSharedPreferences("n24_reminders", Context.MODE_PRIVATE)

    fun all(c: Context): List<Item> = runCatching {
        val a = JSONArray(prefs(c).getString("list", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { Item(it.optInt("s"), it.optLong("t"), it.optString("title"), it.optString("ch")) }
    }.getOrDefault(emptyList()).filter { it.start > System.currentTimeMillis() - 3_600_000 }.sortedBy { it.start }

    private fun save(c: Context, list: List<Item>) = prefs(c).edit().putString("list", JSONArray(list.map {
        JSONObject().put("s", it.streamId).put("t", it.start).put("title", it.title).put("ch", it.channel)
    }).toString()).apply()

    fun has(c: Context, streamId: Int, start: Long) = all(c).any { it.streamId == streamId && it.start == start }

    /** Adds or removes the reminder; returns true when it is now set. */
    fun toggle(c: Context, streamId: Int, start: Long, title: String, channel: String): Boolean {
        val list = all(c).toMutableList()
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(c, streamId, start, title, channel)
        val old = list.firstOrNull { it.streamId == streamId && it.start == start }
        if (old != null) {
            list.remove(old); save(c, list); am.cancel(pi)
            Toast.makeText(c, "Reminder removed", Toast.LENGTH_SHORT).show()
            return false
        }
        if (start <= System.currentTimeMillis()) { Toast.makeText(c, "This has already started.", Toast.LENGTH_SHORT).show(); return false }
        list += Item(streamId, start, title, channel); save(c, list)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, (start - 120_000).coerceAtLeast(System.currentTimeMillis() + 5_000), pi)
        Toast.makeText(c, "Reminder set - 2 minutes before ${Fmt.clock(start)}", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun pending(c: Context, id: Int, start: Long, title: String, ch: String): PendingIntent {
        val i = Intent(c, ReminderReceiver::class.java).putExtra("id", id).putExtra("title", title).putExtra("ch", ch)
        return PendingIntent.getBroadcast(c, (id * 31 + start / 1000).toInt(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun channel(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel("reminders") == null)
                nm.createNotificationChannel(NotificationChannel("reminders", "Show reminders", NotificationManager.IMPORTANCE_HIGH))
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        Reminders.channel(c)
        val id = intent.getIntExtra("id", 0)
        val open = PendingIntent.getActivity(c, id, Intent(c, com.network24.player.features.discover.WatchLinkActivity::class.java).putExtra(EXTRA_WATCH, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(c, "reminders").setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Starting in 2 minutes: ${intent.getStringExtra("title")}")
            .setContentText("On ${intent.getStringExtra("ch")} - tap to watch").setContentIntent(open).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH).build()
        runCatching { NotificationManagerCompat.from(c).notify(id, n) }
    }
    companion object { const val EXTRA_WATCH = "reminder_watch_stream" }
}

class RemindersActivity : FeatureListActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title("Reminders", "You get a notification 2 minutes before each one. Select one to remove it.")
        load()
    }

    private fun load() {
        val list = Reminders.all(this)
        lifecycleScope.launch {
            val chans = DatabaseProvider.get(this@RemindersActivity).channelDao().getByStreamIds(list.map { it.streamId }).associateBy { it.streamId }
            show(list.map { r ->
                Row("${r.streamId}@${r.start}", r.title, "${Fmt.day(r.start)} ${Fmt.clock(r.start)}", r.channel, chans[r.streamId]?.icon, badge = "Remove",
                    onLong = { chans[r.streamId]?.let { ChannelLauncher.play(this@RemindersActivity, listOf(it), it) } }) {
                    Reminders.toggle(this@RemindersActivity, r.streamId, r.start, r.title, r.channel); load()
                }
            }, "No reminders.\nOpen Live Sports, Find a Show or the TV guide panel in the player to set one.")
        }
    }
}
