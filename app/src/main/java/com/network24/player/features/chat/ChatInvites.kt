package com.network24.player.features.chat

import android.app.AlertDialog
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.mapper.toLiveChannel
import com.network24.player.features.player.activity.PlayerActivity
import com.network24.player.features.player.state.PlayerState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watch Party invites reach the customer on whatever screen is open: "SkipperFan invited you to a Watch Party
 * on ESPN - Join?". Only accounts that use the chat (have a chat name) are asked, once a minute while the app is
 * in front. Join plays the party's channel with the chat open.
 */
object ChatInvites {
    private val shown = HashSet<String>()
    private var lastCheck = 0L

    fun attach(activity: AppCompatActivity) {
        var job: Job? = null
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                job = activity.lifecycleScope.launch {
                    while (isActive) {
                        if (ChatApi.used(activity) && System.currentTimeMillis() - lastCheck > 55_000) { lastCheck = System.currentTimeMillis(); check(activity) }
                        delay(60_000)
                    }
                }
            }
            override fun onPause(owner: LifecycleOwner) { job?.cancel() }
        })
    }

    private suspend fun check(activity: AppCompatActivity) {
        val api = ChatApi(activity)
        val me = runCatching { api.me() }.getOrNull() ?: return
        val inv = me.optJSONArray("invites") ?: return
        for (i in 0 until inv.length()) {
            val o = inv.getJSONObject(i)
            val room = o.optString("room")
            if (room in shown || room == ChatApi.party(activity)) continue
            shown += room
            if (activity.isFinishing || activity.isDestroyed) return
            val on = o.optString("channel").takeIf { it.isNotBlank() }?.let { " on $it" } ?: ""
            com.network24.player.core.remote.RemoteUi.popup(activity,
                title = "${o.optString("from")} invited you",
                body = "Watch together$on and chat during the game.",
                buttons = listOf("Join" to { join(activity, room) }, "Not now" to {}, "Decline" to { activity.lifecycleScope.launch { api.declineInvite(room) }; Unit }),
                chip = "🎉  Watch Party", chipColor = android.graphics.Color.parseColor("#A894FF"))
            return
        }
    }

    private fun join(activity: AppCompatActivity, room: String) {
        activity.lifecycleScope.launch {
            val api = ChatApi(activity)
            runCatching { api.partyJoinRoom(room) }.onSuccess { j ->
                ChatApi.setParty(activity, j.optString("room"))
                val id = j.optJSONObject("channel")?.optInt("id") ?: 0
                val db = DatabaseProvider.get(activity)
                val ch = if (id > 0) runCatching { db.channelDao().getByStreamIds(listOf(id)).firstOrNull() }.getOrNull() else null
                if (ch == null) {
                    Toast.makeText(activity, "You joined the Watch Party. Open any channel and press Chat to talk.", Toast.LENGTH_LONG).show()
                    return@onSuccess
                }
                val all = runCatching { db.channelDao().getByCategory(ch.categoryId.orEmpty()) }.getOrDefault(emptyList()).ifEmpty { listOf(ch) }
                PlayerState.channels.clear(); PlayerState.channels.addAll(all.map { it.toLiveChannel() })
                PlayerState.currentPosition = all.indexOfFirst { it.streamId == id }.coerceAtLeast(0)
                ChatPanel.openOnNextPlayer = true
                if (activity is PlayerActivity) { ChatPanel.openOnNextPlayer = false; activity.playSelectedChannel(); activity.openChat() }
                else activity.startActivity(Intent(activity, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_PLAY_SELECTED_CHANNEL, true))
            }.onFailure { Toast.makeText(activity, it.message, Toast.LENGTH_LONG).show() }
        }
    }
}
