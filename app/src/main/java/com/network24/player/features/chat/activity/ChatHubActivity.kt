package com.network24.player.features.chat.activity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.PopupWindow
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.network24.player.R
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.mapper.toLiveChannel
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.databinding.ActivityChatHubBinding
import com.network24.player.features.chat.adapter.ChatMessagesAdapter
import com.network24.player.features.chat.adapter.ChatRoomsAdapter
import com.network24.player.features.chat.adapter.MentionAdapter
import com.network24.player.features.chat.model.ChatRoom
import com.network24.player.features.chat.repo.ChatMessage
import com.network24.player.features.chat.repo.ChatRepository
import com.network24.player.features.player.activity.PlayerActivity
import com.network24.player.features.player.state.PlayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatHubActivity : BaseActivity() {
    private lateinit var binding: ActivityChatHubBinding
    private lateinit var prefs: PreferenceManager
    private lateinit var senderName: String
    private lateinit var senderId: String
    private val repo = ChatRepository()
    private var roomMessagesListener: ListenerRegistration? = null
    private lateinit var roomsAdapter: ChatRoomsAdapter
    private lateinit var messagesAdapter: ChatMessagesAdapter
    private val roomLastMsgListeners = mutableMapOf<String, ListenerRegistration>()
    private var selectedRoom: ChatRoom? = null
    private var replyToMessage: ChatMessage? = null
    private var mentionPopup: PopupWindow? = null
    private var mentionAdapter: MentionAdapter? = null
    private var mentionTokenStart = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatHubBinding.inflate(layoutInflater)
        setContentView(setupGlobalRightDrawer(binding.root, binding.btnMore))
        prefs = PreferenceManager(this)
        senderName = (prefs.getUsername() ?: "guest").trim().ifEmpty { "guest" }
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "device"
        senderId = senderName.lowercase().replace(" ", "_") + "_" + deviceId.takeLast(6)

        roomsAdapter = ChatRoomsAdapter { room -> selectRoom(room) }
        binding.rvRooms.layoutManager = LinearLayoutManager(this)
        binding.rvRooms.adapter = roomsAdapter
        val rooms = defaultRooms()
        roomsAdapter.submit(rooms)

        messagesAdapter = ChatMessagesAdapter(
            mySenderId = senderId,
            onReply = { beginReply(it) },
            onMessageMenu = { showMessageActions(it) },
            onReportedChannelClick = { message -> openReportedChannel(message) }
        )
        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvMessages.adapter = messagesAdapter
        binding.btnSend?.setOnClickListener { sendCurrent() }
        binding.btnCancelReply?.setOnClickListener { clearReply() }
        setupMentionAutocomplete()

        startRoomUnreadWatchers(rooms)
        val lastId = prefs.getLastChatRoomId()
        val initial = rooms.firstOrNull { it.id == lastId } ?: rooms.first()
        selectRoom(initial)
        binding.rvMessages.nextFocusDownId = binding.etMessage?.id ?: View.NO_ID
        binding.etMessage?.nextFocusUpId = binding.rvMessages.id
        binding.btnSend?.nextFocusUpId = binding.rvMessages.id
        binding.btnCancelReply?.nextFocusUpId = binding.rvMessages.id
        focusRoom(initial)
    }

    private fun showMessageActions(message: ChatMessage) {
        val room = selectedRoom ?: return
        val mine = message.senderId == senderId && !message.deleted
        val actions = mutableListOf<String>()
        if (!message.deleted && canSendToRoom(room.id, room.readOnly)) actions += "Reply"
        actions += "Copy"
        if (mine) { actions += "Edit"; actions += "Delete" }
        if (!message.deleted) actions += "Report"
        AlertDialog.Builder(this).setTitle(message.senderName.ifBlank { "Message" }).setItems(actions.toTypedArray()) { _, which ->
            when (actions[which]) {
                "Reply" -> beginReply(message)
                "Copy" -> copyMessage(message)
                "Edit" -> editMessage(message)
                "Delete" -> confirmDeleteMessage(message)
                "Report" -> reportMessage(room, message)
            }
        }.show()
    }

    private fun editMessage(message: ChatMessage) {
        val room = selectedRoom ?: return
        if (message.senderId != senderId || message.deleted) return
        val input = EditText(this).apply {
            setText(message.text)
            setSelection(text.length)
            setSingleLine(false)
            minLines = 2
            maxLines = 5
            setPadding(24, 12, 24, 12)
        }
        AlertDialog.Builder(this)
            .setTitle("Edit message")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) { Toast.makeText(this, "Message cannot be empty", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                repo.editMessage(room.id, message.id, text,
                    onOk = { Toast.makeText(this, "Message updated", Toast.LENGTH_SHORT).show() },
                    onError = { Toast.makeText(this, "Edit failed: ${it.message}", Toast.LENGTH_LONG).show() })
            }.show()
    }

    private fun confirmDeleteMessage(message: ChatMessage) {
        val room = selectedRoom ?: return
        if (message.senderId != senderId || message.deleted) return
        AlertDialog.Builder(this)
            .setTitle("Delete message?")
            .setMessage("This message will be replaced with a deleted-message notice.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                repo.deleteMessage(room.id, message.id,
                    onOk = { Toast.makeText(this, "Message deleted", Toast.LENGTH_SHORT).show() },
                    onError = { Toast.makeText(this, "Delete failed: ${it.message}", Toast.LENGTH_LONG).show() })
            }.show()
    }

    private fun copyMessage(message: ChatMessage) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Chat message", message.text))
        Toast.makeText(this, "Message copied", Toast.LENGTH_SHORT).show()
    }

    private fun openReportedChannel(message: ChatMessage) {
        val reportedName = message.reportedChannelName?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: Regex("""channel ['\"](.+?)['\"]""", RegexOption.IGNORE_CASE)
                .find(message.text)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                .orEmpty()
        if (message.reportedChannelStreamId == null && reportedName.isEmpty()) return

        lifecycleScope.launch {
            val channel = withContext(Dispatchers.IO) {
                val channelDao = DatabaseProvider.get(this@ChatHubActivity).channelDao()
                val byStreamId = message.reportedChannelStreamId
                    ?.let { streamId -> channelDao.getByStreamIds(listOf(streamId)).firstOrNull() }

                (byStreamId ?: reportedName.takeIf { it.isNotEmpty() }?.let { name ->
                    channelDao.getAll().firstOrNull { it.name?.trim().equals(name, ignoreCase = true) }
                })?.toLiveChannel()
            }

            if (channel == null) {
                Toast.makeText(
                    this@ChatHubActivity,
                    "This channel is no longer available in the current channel list.",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            PlayerState.channels.clear()
            PlayerState.channels.add(channel)
            PlayerState.currentPosition = 0

            startActivity(
                Intent(this@ChatHubActivity, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_PLAY_SELECTED_CHANNEL, true)
            )
        }
    }

    private fun reportMessage(room: ChatRoom, message: ChatMessage) {
        AlertDialog.Builder(this).setTitle("Report message?").setMessage("Report this message to the support team?")
            .setNegativeButton("Cancel", null).setPositiveButton("Report") { _, _ ->
                repo.reportMessage(room.id, message, senderId, senderName,
                    { Toast.makeText(this, "Message reported", Toast.LENGTH_SHORT).show() },
                    { Toast.makeText(this, "Report failed: ${it.message}", Toast.LENGTH_LONG).show() })
            }.show()
    }

    private fun setupMentionAutocomplete() {
        val edit = binding.etMessage ?: return
        edit.doAfterTextChanged { text ->
            val value = text?.toString().orEmpty()
            val cursor = edit.selectionStart.coerceAtLeast(0)
            val at = value.lastIndexOf('@', (cursor - 1).coerceAtLeast(0))
            if (at < 0 || at > cursor - 1) { hideMentionPopup(); return@doAfterTextChanged }
            val token = value.substring(at + 1, cursor)
            if (token.contains(Regex("\\s")) || token.length > 32) { hideMentionPopup(); return@doAfterTextChanged }
            mentionTokenStart = at
            loadMentionSuggestions(token)
        }
    }

    private fun loadMentionSuggestions(query: String) {
        val normalized = query.lowercase()
        FirebaseFirestore.getInstance().collection("users").orderBy("username", Query.Direction.ASCENDING)
            .startAt(normalized).endAt(normalized + "\uf8ff").limit(8).get()
            .addOnSuccessListener { snap ->
                val names = snap.documents.mapNotNull { it.getString("username")?.trim()?.takeIf { n -> n.isNotEmpty() } }
                    .filter { it.lowercase().startsWith(normalized) }.distinctBy { it.lowercase() }
                if (names.isEmpty()) hideMentionPopup() else showMentionPopup(names)
            }.addOnFailureListener { hideMentionPopup() }
    }

    private fun showMentionPopup(names: List<String>) {
        val edit = binding.etMessage ?: return
        val recycler = (mentionPopup?.contentView as? RecyclerView) ?: RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ChatHubActivity); overScrollMode = View.OVER_SCROLL_NEVER; setPadding(4, 4, 4, 4)
            background = GradientDrawable().apply { setColor(Color.parseColor("#151B2C")); cornerRadius = 12f }
            mentionAdapter = MentionAdapter { insertMention(it) }; adapter = mentionAdapter
        }
        if (mentionPopup == null) mentionPopup = PopupWindow(recycler, dp(300), dp(8 + 48 * names.size.coerceAtMost(4)), true).apply {
            elevation = dp(8).toFloat(); isOutsideTouchable = true
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.parseColor("#151B2C")); cornerRadius = 12f })
        }
        mentionAdapter?.submit(names); mentionPopup?.height = dp(8 + 48 * names.size.coerceAtMost(4))
        if (mentionPopup?.isShowing != true) mentionPopup?.showAsDropDown(edit, 0, -edit.height - mentionPopup!!.height - dp(6))
    }

    private fun insertMention(username: String) {
        val edit = binding.etMessage ?: return
        val text = edit.text?.toString().orEmpty(); val cursor = edit.selectionStart.coerceAtLeast(0)
        if (mentionTokenStart < 0 || mentionTokenStart > cursor || mentionTokenStart > text.length) { hideMentionPopup(); return }
        val replacement = "@$username "; val updated = text.substring(0, mentionTokenStart) + replacement + text.substring(cursor)
        edit.setText(updated); edit.setSelection((mentionTokenStart + replacement.length).coerceAtMost(updated.length)); hideMentionPopup(); edit.requestFocus()
    }

    private fun hideMentionPopup() { mentionPopup?.dismiss(); mentionTokenStart = -1 }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun focusRoom(room: ChatRoom) {
        val index = roomsAdapter.getPositionOf(room.id)
        if (index != -1) binding.rvRooms.post { binding.rvRooms.scrollToPosition(index); binding.rvRooms.post { binding.rvRooms.layoutManager?.findViewByPosition(index)?.requestFocus() } }
    }

    private fun selectRoom(room: ChatRoom) {
        selectedRoom = room; clearReply(); hideMentionPopup(); binding.tvRoomTitle?.text = "# ${room.id}"
        roomsAdapter.setSelectedRoom(room.id); prefs.setLastChatRoomId(room.id)
        val canSend = canSendToRoom(room.id, room.readOnly)
        binding.etMessage?.isEnabled = canSend; binding.btnSend?.isEnabled = canSend
        binding.etMessage?.hint = if (canSend) "Type a message (use @username to mention)" else "Read-only channel"
        binding.etMessage?.visibility = if (canSend) View.VISIBLE else View.GONE; binding.btnSend?.visibility = if (canSend) View.VISIBLE else View.GONE
        if (!canSend) binding.replyBar?.visibility = View.GONE
        roomsAdapter.setUnread(room.id, false); roomMessagesListener?.remove()
        roomMessagesListener = repo.listenMessages(room.id, onUpdate = { list ->
            messagesAdapter.submit(list)
            if (list.isNotEmpty()) { binding.rvMessages.scrollToPosition(list.size - 1); prefs.setChatLastSeen(room.id, list.last().ts?.toDate()?.time ?: System.currentTimeMillis()) } else prefs.setChatLastSeen(room.id, System.currentTimeMillis())
            roomsAdapter.setUnread(room.id, false)
        }, onError = { Toast.makeText(this, "Listen failed: ${it.message}", Toast.LENGTH_SHORT).show() })
    }

    private fun beginReply(message: ChatMessage) {
        val room = selectedRoom ?: return; if (!canSendToRoom(room.id, room.readOnly)) return
        replyToMessage = message; binding.tvReplyPreview?.text = "↩ Replying to ${message.senderName.ifBlank { "Unknown" }}\n${message.text.take(160)}"; binding.replyBar?.visibility = View.VISIBLE; binding.etMessage?.requestFocus()
    }

    private fun clearReply() { replyToMessage = null; if (::binding.isInitialized) { binding.replyBar?.visibility = View.GONE; binding.tvReplyPreview?.text = "" } }

    private fun sendCurrent() {
        val room = selectedRoom ?: return; if (!canSendToRoom(room.id, room.readOnly)) return
        val text = binding.etMessage?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) { Toast.makeText(this, "Empty message", Toast.LENGTH_SHORT).show(); return }
        repo.sendMessage(room.id, text, senderId, senderName, replyToMessage, extractMentions(text), { binding.etMessage?.setText(""); clearReply() }, { Toast.makeText(this, "Send failed: ${it.message}", Toast.LENGTH_LONG).show() })
    }

    private fun extractMentions(text: String): List<String> = Regex("(?<![A-Za-z0-9_])@([A-Za-z0-9_.-]{2,32})").findAll(text).map { it.groupValues[1].lowercase() }.distinct().toList()
    private fun canSendToRoom(roomId: String, isReadOnly: Boolean): Boolean = !isReadOnly

    private fun startRoomUnreadWatchers(rooms: List<ChatRoom>) {
        roomLastMsgListeners.values.forEach { it.remove() }; roomLastMsgListeners.clear(); val db = FirebaseFirestore.getInstance()
        rooms.forEach { room -> roomLastMsgListeners[room.id] = db.collection("rooms").document(room.id).collection("messages").orderBy("ts", Query.Direction.DESCENDING).limit(1).addSnapshotListener { snap, err ->
            if (err != null) return@addSnapshotListener; val doc = snap?.documents?.firstOrNull() ?: return@addSnapshotListener; val ts = doc.getTimestamp("ts")?.toDate()?.time ?: return@addSnapshotListener
            roomsAdapter.setUnread(room.id, selectedRoom?.id != room.id && ts > prefs.getChatLastSeen(room.id))
        } }
    }

    private fun defaultRooms(): List<ChatRoom> = listOf(
        ChatRoom("announcements", "Announcements", "📢", 1, true), ChatRoom("pinned_posts", "Pinned Posts", "📌", 2, true), ChatRoom("channel_down", "Channel Down", "🚨", 3, true),
        ChatRoom("buffering_issues", "Buffering Issues", "⏳", 4, false), ChatRoom("questions_and_help", "Questions & Help", "❓", 5, false), ChatRoom("channel_requests", "Channel Requests", "📡", 6, false),
        ChatRoom("general_discussions", "General Discussions", "💬", 7, false), ChatRoom("live_events", "Live Events", "🏆", 8, true), ChatRoom("development_desk", "Development Desk", "💻", 9, false)
    ).sortedBy { it.order }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val readOnly = binding.etMessage?.visibility == View.GONE; val right = binding.rvMessages.hasFocus() || binding.etMessage?.hasFocus() == true || binding.btnSend?.hasFocus() == true || binding.btnCancelReply?.hasFocus() == true
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (binding.rvRooms.hasFocus()) { if (messagesAdapter.itemCount > 0) focusLastVisibleMessage() else if (!readOnly) binding.etMessage?.requestFocus(); return true }; if (binding.etMessage?.hasFocus() == true || binding.btnCancelReply?.hasFocus() == true) { binding.btnSend?.requestFocus(); return true }; if (binding.btnSend?.hasFocus() == true) return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (binding.btnSend?.hasFocus() == true || binding.btnCancelReply?.hasFocus() == true) { binding.etMessage?.requestFocus(); return true }; if (right) { restoreRoomFocus(); return true } }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (right && binding.rvMessages.hasFocus()) { val child = binding.rvMessages.focusedChild; if (child != null) { val pos = binding.rvMessages.getChildAdapterPosition(child); val tv = child.findViewById<android.widget.TextView>(R.id.tvText); if (tv != null && tv.hasFocus() && tv.canScrollVertically(1)) return super.dispatchKeyEvent(event); if (pos == messagesAdapter.itemCount - 1) { if (!readOnly) binding.etMessage?.requestFocus(); return true } } } else if (right && (binding.etMessage?.hasFocus() == true || binding.btnSend?.hasFocus() == true || binding.btnCancelReply?.hasFocus() == true)) return true }
                KeyEvent.KEYCODE_DPAD_UP -> { if (right && (binding.etMessage?.hasFocus() == true || binding.btnSend?.hasFocus() == true || binding.btnCancelReply?.hasFocus() == true)) { focusLastVisibleMessage(); return true }; if (right && binding.rvMessages.hasFocus()) { val child = binding.rvMessages.focusedChild; if (child != null) { val pos = binding.rvMessages.getChildAdapterPosition(child); val tv = child.findViewById<android.widget.TextView>(R.id.tvText); if (tv != null && tv.hasFocus() && tv.canScrollVertically(-1)) return super.dispatchKeyEvent(event); if (pos == 0) return true } } }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun focusLastVisibleMessage() { if (messagesAdapter.itemCount == 0) return; val lm = binding.rvMessages.layoutManager as LinearLayoutManager; val pos = lm.findLastVisibleItemPosition(); if (pos >= 0) lm.findViewByPosition(pos)?.findViewById<View>(R.id.tvText)?.requestFocus() }
    private fun restoreRoomFocus() { selectedRoom?.let { focusRoom(it) } }
    override fun onDestroy() { hideMentionPopup(); roomMessagesListener?.remove(); roomLastMsgListeners.values.forEach { it.remove() }; roomLastMsgListeners.clear(); super.onDestroy() }
}
