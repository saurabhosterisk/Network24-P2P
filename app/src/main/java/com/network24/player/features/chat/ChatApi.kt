package com.network24.player.features.chat

import android.content.Context
import com.network24.player.core.api.Web24Api
import com.network24.player.core.preferences.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Live Chat / Watch Party on Main (support_api.php chat_* actions). A room's last 80 messages are a plain file
 * that nginx serves (/chat/r/<room>.json); polling it with If-Modified-Since costs Main almost nothing.
 */
class ChatApi(private val context: Context) {
    private val api = Web24Api(context)

    data class Msg(val id: Long, val nick: String, val kind: String, val text: String, val at: Long)
    data class Room(
        val room: String, val kind: String, val title: String, val watching: Int, val msgs: List<Msg>,
        val host: String = "", val code: String = "", val channelId: Int = 0, val channelName: String = "", val members: List<String> = emptyList(),
    )

    suspend fun me(): JSONObject = api.support("chat_me")
    suspend fun setNick(nick: String): String = api.support("chat_nick", "nick" to nick).optString("nick")
    suspend fun join(room: String, title: String): JSONObject = api.support("chat_join", "room" to room, "title" to title)
    suspend fun ping(room: String) { api.support("chat_ping", "room" to room) }
    suspend fun leave(room: String, party: Boolean = false) { runCatching { api.support("chat_leave", "room" to room, "party" to if (party) "1" else "") } }
    suspend fun post(room: String, text: String, kind: String = "m") { api.support("chat_post", "room" to room, "text" to text, "kind" to kind) }
    suspend fun report(id: Long) { api.support("chat_report", "id" to id.toString()) }
    suspend fun partyCreate(streamId: Int, channel: String): JSONObject = api.support("chat_party_create", "stream_id" to streamId.toString(), "channel_name" to channel)
    suspend fun partyJoinCode(code: String): JSONObject = api.support("chat_party_join", "code" to code)
    suspend fun partyJoinRoom(room: String): JSONObject = api.support("chat_party_join", "room" to room)
    suspend fun declineInvite(room: String) { runCatching { api.support("chat_invite_decline", "room" to room) } }
    suspend fun invite(room: String, nick: String): String = api.support("chat_party_invite", "room" to room, "nick" to nick).optString("nick")
    suspend fun partyChannel(room: String, streamId: Int, name: String) { runCatching { api.support("chat_party_channel", "room" to room, "stream_id" to streamId.toString(), "channel_name" to name) } }
    suspend fun pair(room: String): String = api.support("chat_pair", "room" to room).optString("url")

    private var lastModified: String? = null
    private var lastFile: String? = null

    /** The room file; null when it did not change since the last read (304) or could not be read. */
    suspend fun read(file: String): Room? = withContext(Dispatchers.IO) {
        if (file != lastFile) { lastFile = file; lastModified = null }
        val b = Request.Builder().url(PreferenceManager.SERVER_URL + file).header("User-Agent", "Web24Chat")
        lastModified?.let { b.header("If-Modified-Since", it) }
        runCatching {
            client.newCall(b.build()).execute().use { r ->
                if (r.code == 304 || !r.isSuccessful) return@use null
                lastModified = r.header("Last-Modified")
                parse(JSONObject(r.body?.string().orEmpty()))
            }
        }.getOrNull()
    }

    private fun parse(j: JSONObject): Room {
        val a = j.optJSONArray("msgs")
        val msgs = (0 until (a?.length() ?: 0)).mapNotNull { i -> a?.optJSONObject(i) }.map {
            Msg(it.optLong("id"), it.optString("n"), it.optString("k"), it.optString("t"), it.optLong("at"))
        }
        val ch = j.optJSONObject("channel")
        val mem = j.optJSONArray("members")
        return Room(j.optString("room"), j.optString("kind"), j.optString("title"), j.optInt("watching"), msgs,
            j.optString("host"), j.optString("code"), ch?.optInt("id") ?: 0, ch?.optString("name").orEmpty(),
            (0 until (mem?.length() ?: 0)).map { mem!!.optString(it) })
    }

    companion object {
        private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()

        // the Watch Party this device is in (survives screens; cleared on Leave)
        private const val PREFS = "n24_chat"
        fun party(c: Context): String? = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("party:" + user(c), null)
        fun setParty(c: Context, room: String?) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("party:" + user(c), room).apply() }
        fun followHost(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("follow:" + user(c), true)
        fun setFollowHost(c: Context, on: Boolean) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("follow:" + user(c), on).apply() }
        /** true once this account has a chat name: only then the app looks for Watch Party invites. */
        fun used(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("used:" + user(c), false)
        fun setUsed(c: Context) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("used:" + user(c), true).apply() }
        private fun user(c: Context) = PreferenceManager(c.applicationContext).getUsername().trim().lowercase()
    }
}
