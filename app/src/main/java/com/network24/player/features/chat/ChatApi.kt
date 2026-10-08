package com.network24.player.features.chat

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.network24.player.core.api.Web24Api
import com.network24.player.core.preferences.PreferenceManager
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.util.Date
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Live Chat / Watch Party. Main (support_api.php chat_* actions) keeps the chat name, bans, parties, invites and
 * reports and signs a Firebase custom token; the messages, presence and party state live in Firestore (project
 * network24), so every TV sees a line the moment it is sent and Main does nothing per message.
 */
class ChatApi(private val context: Context) {
    private val api = Web24Api(context)
    private val db get() = FirebaseFirestore.getInstance()

    /** [id] is the message's Firestore path (chat_rooms/<room>/msgs/<id>) - what a report names. */
    data class Msg(val id: String, val nick: String, val kind: String, val text: String, val at: Long)
    data class Room(
        val room: String, val kind: String, val title: String, val host: String = "", val hostUid: String = "", val code: String = "",
        val channelId: Int = 0, val channelName: String = "", val members: List<String> = emptyList(),
    )

    // ------------------------------------------------------------------------------------------------ Main
    /** Who I am in the chat; also signs this device in to Firestore with the token Main put in the reply. */
    suspend fun me(): JSONObject = api.support("chat_me").also { signIn(it) }
    suspend fun setNick(nick: String): String = api.support("chat_nick", "nick" to nick).optString("nick")
    suspend fun join(room: String, title: String): JSONObject = api.support("chat_join", "room" to room, "title" to title)
    suspend fun leave(room: String, party: Boolean = false) { runCatching { api.support("chat_leave", "room" to room, "party" to if (party) "1" else "") } }
    suspend fun report(id: String) { api.support("chat_report", "id" to id) }
    suspend fun partyCreate(streamId: Int, channel: String): JSONObject = api.support("chat_party_create", "stream_id" to streamId.toString(), "channel_name" to channel)
    suspend fun partyJoinCode(code: String): JSONObject = api.support("chat_party_join", "code" to code)
    suspend fun partyJoinRoom(room: String): JSONObject = api.support("chat_party_join", "room" to room)
    suspend fun declineInvite(room: String) { runCatching { api.support("chat_invite_decline", "room" to room) } }
    suspend fun invite(room: String, nick: String): String = api.support("chat_party_invite", "room" to room, "nick" to nick).optString("nick")
    suspend fun partyChannel(room: String, streamId: Int, name: String) { runCatching { api.support("chat_party_channel", "room" to room, "stream_id" to streamId.toString(), "channel_name" to name) } }
    suspend fun pair(room: String): String = api.support("chat_pair", "room" to room).optString("url")

    /** Firebase sign-in with Main's 1-hour custom token (skipped while this account's session is fresh). */
    suspend fun signIn(me: JSONObject) {
        val token = me.optString("token"); val uid = me.optString("uid"); val nick = me.optString("nick")
        if (token.isBlank() || uid.isBlank()) return
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser?.uid == uid && signedNick == nick && System.currentTimeMillis() - signedAt < 50 * 60_000L) return
        auth.signInWithCustomToken(token).await()
        signedNick = nick; signedAt = System.currentTimeMillis()
    }

    val uid: String get() = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()

    // ------------------------------------------------------------------------------------------------ Firestore
    private fun roomDoc(room: String) = db.collection("chat_rooms").document(room)

    /** The room's last 80 lines, again every time one is added (hidden ones are left out). */
    fun listenMessages(room: String, onChange: (List<Msg>) -> Unit): ListenerRegistration =
        roomDoc(room).collection("msgs").orderBy("at", Query.Direction.DESCENDING).limit(80).addSnapshotListener { s, _ ->
            if (s == null) return@addSnapshotListener
            onChange(s.documents.filter { it.getBoolean("hidden") != true }.map { d ->
                Msg(d.reference.path, d.getString("n").orEmpty(), d.getString("k") ?: "m", d.getString("t").orEmpty(),
                    d.getTimestamp("at")?.toDate()?.time ?: System.currentTimeMillis())
            }.asReversed())
        }

    /** Writes a line straight to the room (the rules check the name, length, links and the ban). */
    suspend fun send(room: String, nick: String, text: String, kind: String) {
        val k = if (kind == "r") "r" else "m"
        val t = if (k == "r") text else clean(text)
        if (t.isBlank() || t == "[link removed]") throw IllegalStateException("Nothing to send.")
        val now = System.currentTimeMillis()
        if (now - lastSend < 2_000) throw IllegalStateException("Slow down a little - one message every few seconds.")
        lastSend = now
        try {
            roomDoc(room).collection("msgs").add(mapOf("n" to nick, "uid" to uid, "k" to k, "t" to t, "at" to FieldValue.serverTimestamp())).await()
        } catch (e: FirebaseFirestoreException) {
            throw IllegalStateException(if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) "You cannot chat right now." else "Not sent - check the connection.")
        }
    }

    /** "I am here": the viewer's own presence line, refreshed while the chat is open. */
    suspend fun heartbeat(room: String, nick: String) {
        runCatching { roomDoc(room).collection("presence").document(uid).set(mapOf("n" to nick, "at" to FieldValue.serverTimestamp())).await() }
    }

    suspend fun leavePresence(room: String) {
        if (uid.isEmpty()) return
        runCatching { roomDoc(room).collection("presence").document(uid).delete().await() }
    }

    /** How many are in the chat right now (presence lines of the last 90 s). */
    suspend fun watching(room: String): Int = runCatching {
        roomDoc(room).collection("presence").whereGreaterThan("at", Timestamp(Date(System.currentTimeMillis() - 90_000)))
            .count().get(AggregateSource.SERVER).await().count.toInt()
    }.getOrDefault(0)

    /** A Watch Party's document: members, code and the host's channel (null once the party is gone). */
    fun listenRoom(room: String, onChange: (Room?) -> Unit): ListenerRegistration =
        roomDoc(room).addSnapshotListener { d, _ -> onChange(d?.takeIf { it.exists() }?.let { parseRoom(room, it) }) }

    private fun parseRoom(room: String, d: DocumentSnapshot): Room {
        val members = (d.get("members") as? Map<*, *>)?.values?.map { it.toString() } ?: emptyList()
        return Room(room, d.getString("kind") ?: "p", d.getString("title").orEmpty(), d.getString("host").orEmpty(), d.getString("hostUid").orEmpty(),
            d.getString("code").orEmpty(), (d.getLong("channelId") ?: 0L).toInt(), d.getString("channelName").orEmpty(), members)
    }

    /** The account's own document: Watch Party invites arrive here the moment a friend sends them. */
    fun listenInvites(onChange: (List<JSONObject>) -> Unit): ListenerRegistration? {
        val u = uid
        if (u.isEmpty()) return null
        return db.collection("chat_users").document(u).addSnapshotListener { d, _ ->
            val inv = d?.get("invites") as? Map<*, *> ?: emptyMap<Any, Any>()
            onChange(inv.entries.map { (k, v) ->
                val m = v as? Map<*, *>
                JSONObject().put("room", k.toString()).put("from", m?.get("from")?.toString().orEmpty())
                    .put("title", m?.get("title")?.toString().orEmpty()).put("channel", m?.get("channel")?.toString().orEmpty())
            })
        }
    }

    companion object {
        private var signedNick = ""
        private var signedAt = 0L
        private var lastSend = 0L

        private val words = listOf("fuck", "shit", "bitch", "cunt", "nigger", "nigga", "faggot", "retard", "whore", "slut", "dick", "pussy", "asshole", "bastard", "motherfucker", "chutiya", "madarchod", "behenchod", "bhenchod", "randi", "gandu")

        /** Same clean-up Main applies: no control characters, no links, swear words starred, 200 characters. */
        fun clean(text: String): String {
            var t = text.replace(Regex("[\\x00-\\x1F\\x7F]+"), " ").replace(Regex("\\s+"), " ").trim()
            t = t.replace(Regex("\\b(?:https?://|www\\.)\\S+|\\b[a-z0-9-]+\\.(?:com|net|org|io|tv|live|xyz|me|ru|biz|info)\\b\\S*", RegexOption.IGNORE_CASE), "[link removed]")
            for (w in words) t = t.replace(Regex(Regex.escape(w), RegexOption.IGNORE_CASE), "*".repeat(w.length))
            return t.take(200)
        }

        suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
            addOnSuccessListener { if (c.isActive) c.resume(it) }
            addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
        }

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
