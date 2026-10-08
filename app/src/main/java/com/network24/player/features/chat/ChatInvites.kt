package com.network24.player.features.chat

import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.firebase.firestore.ListenerRegistration
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.mapper.toLiveChannel
import com.network24.player.features.player.activity.PlayerActivity
import com.network24.player.features.player.state.PlayerState
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Watch Party invites reach the customer on whatever screen is open, the moment a friend sends one: "SkipperFan
 * invited you to a Watch Party on ESPN - Join?". Only accounts that use the chat (have a chat name) listen. Join
 * plays the party's channel with the chat open.
 */
object ChatInvites {
    private val shown = HashSet<String>()
    private var lastMe = 0L

    fun attach(activity: AppCompatActivity) {
        var reg: ListenerRegistration? = null
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                if (!ChatApi.used(activity)) return
                activity.lifecycleScope.launch {
                    val api = ChatApi(activity)
                    // the Firebase session comes from Main's token (once, then it lives on)
                    if (api.uid.isEmpty() || System.currentTimeMillis() - lastMe > 50 * 60_000L) { lastMe = System.currentTimeMillis(); runCatching { api.me() } }
                    reg?.remove()
                    reg = api.listenInvites { list -> offer(activity, api, list) }
                }
            }
            override fun onPause(owner: LifecycleOwner) { reg?.remove(); reg = null }
        })
    }

    private fun offer(activity: AppCompatActivity, api: ChatApi, invites: List<JSONObject>) {
        for (o in invites) {
            val room = o.optString("room")
            if (room in shown || room == ChatApi.party(activity)) continue
            shown += room
            if (activity.isFinishing || activity.isDestroyed) return
            val on = o.optString("channel").takeIf { it.isNotBlank() }?.let { " on $it" } ?: ""
            com.network24.player.core.remote.RemoteUi.popup(activity,
                title = "${o.optString("from")} invited you",
                body = "Watch together$on and chat during the game.",
                buttons = listOf("Join" to { join(activity, room) }, "Not now" to {}, "Decline" to { activity.lifecycleScope.launch { api.declineInvite(room) }; Unit }),
                chip = "Watch Party", chipColor = android.graphics.Color.parseColor("#A894FF"))
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
