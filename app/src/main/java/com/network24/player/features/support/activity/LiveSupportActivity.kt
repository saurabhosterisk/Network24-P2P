package com.network24.player.features.support.activity

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.network24.player.R
import com.network24.player.common.models.SupportChannel
import com.network24.player.common.models.SupportMessage
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.features.support.adapter.SupportChannelAdapter
import com.network24.player.features.support.adapter.SupportMessageAdapter
import com.network24.player.features.support.repository.SupportRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Live Support: the N24 Discord support channels inside the app. Customers
 * read and post with their N24 login - no Discord account needed. Posts go
 * to Discord under their N24 username (support_api.php on Main).
 */
class LiveSupportActivity : BaseActivity() {

    companion object {
        private const val POLL_MS = 4_000L
        private const val PREF_LAST_CHANNEL = "live_support_last_channel"
        private const val SEEN_PREFS = "live_support_seen"
        private const val CHANNELS_EVERY = 2   // channel list (unread dots) every 2nd poll = 8 s
    }

    private lateinit var repo: SupportRepository
    private lateinit var channelAdapter: SupportChannelAdapter
    private lateinit var messageAdapter: SupportMessageAdapter
    private lateinit var messageList: RecyclerView
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var input: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var composer: View
    private lateinit var readOnlyNote: View
    private lateinit var channelTitle: TextView
    private lateinit var replyBar: View
    private lateinit var replyText: TextView
    private lateinit var imageBar: View
    private lateinit var imagePreview: ImageView

    private var channels: List<SupportChannel> = emptyList()
    private var current: SupportChannel? = null
    private var replyTo: SupportMessage? = null
    private var pickedImage: Uri? = null
    private var pollJob: Job? = null
    private var loadingOlder = false
    private var noMoreOlder = false
    private var sending = false
    private val seenPrefs by lazy { getSharedPreferences(SEEN_PREFS, MODE_PRIVATE) }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pickedImage = uri
            imagePreview.load(uri)
            imageBar.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val contentRoot = layoutInflater.inflate(R.layout.activity_live_support, null, false) as ViewGroup
        setContentView(setupGlobalRightDrawer(contentRoot, contentRoot.findViewById(R.id.btnMore)))

        // On touch screens the message box took the first focus and popped
        // the keyboard open. Park focus on the screen itself instead; a TV
        // remote (not in touch mode) still starts on the channel list.
        if (contentRoot.isInTouchMode) {
            contentRoot.isFocusableInTouchMode = true
            contentRoot.requestFocus()
        }

        repo = SupportRepository(PreferenceManager(this))
        messageList = findViewById(R.id.messageList)
        status = findViewById(R.id.messageStatus)
        progress = findViewById(R.id.messageProgress)
        input = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.btnSend)
        composer = findViewById(R.id.composer)
        readOnlyNote = findViewById(R.id.readOnlyNote)
        channelTitle = findViewById(R.id.channelTitle)
        replyBar = findViewById(R.id.replyBar)
        replyText = findViewById(R.id.replyText)
        imageBar = findViewById(R.id.imageBar)
        imagePreview = findViewById(R.id.imagePreview)

        findViewById<View>(R.id.supportBack).setOnClickListener { finish() }

        channelAdapter = SupportChannelAdapter { openChannel(it) }
        findViewById<RecyclerView>(R.id.channelList).apply {
            layoutManager = LinearLayoutManager(this@LiveSupportActivity)
            adapter = channelAdapter
        }

        messageAdapter = SupportMessageAdapter(
            onMessage = { showMessageOptions(it) },
            onImage = { showImage(it) }
        )
        layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messageList.layoutManager = layoutManager
        messageList.adapter = messageAdapter
        messageList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy < 0 && layoutManager.findFirstVisibleItemPosition() <= 2) loadOlder()
            }
        })

        sendButton.setOnClickListener { send() }
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                send()
                true
            } else {
                false
            }
        }
        findViewById<View>(R.id.btnPickImage).setOnClickListener {
            try {
                pickImage.launch("image/*")
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "Pictures can't be picked on this device.", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<View>(R.id.replyCancel).setOnClickListener { setReply(null) }
        findViewById<View>(R.id.imageCancel).setOnClickListener {
            pickedImage = null
            imageBar.visibility = View.GONE
        }

        loadChannels()
    }

    override fun onResume() {
        super.onResume()
        startPolling()
    }

    override fun onPause() {
        pollJob?.cancel()
        super.onPause()
    }

    private fun loadChannels() {
        showStatus(null, loading = true)
        lifecycleScope.launch {
            repo.channels()
                .onSuccess { list ->
                    channels = list
                    channelAdapter.submit(list)
                    updateUnread()
                    if (list.isEmpty()) {
                        showStatus("Live Support is not available right now.")
                        return@onSuccess
                    }
                    val lastId = getPreferences(MODE_PRIVATE).getString(PREF_LAST_CHANNEL, null)
                    // Start where most customers need help, unless they were elsewhere last time.
                    val start = list.firstOrNull { it.id == lastId }
                        ?: list.firstOrNull { it.name == "questions-and-help" }
                        ?: list.first()
                    openChannel(start)
                    val channelList = findViewById<RecyclerView>(R.id.channelList)
                    if (!channelList.isInTouchMode) {
                        channelList.post {
                            channelList.findViewHolderForAdapterPosition(list.indexOf(start))
                                ?.itemView?.requestFocus()
                        }
                    }
                }
                .onFailure { showStatus(it.message) }
        }
    }

    private fun openChannel(channel: SupportChannel) {
        if (current?.id == channel.id && messageAdapter.itemCount > 0) return
        current = channel
        channelAdapter.selectedId = channel.id
        markSeen(channel.id, channel.lastMessageId)
        updateUnread()
        getPreferences(MODE_PRIVATE).edit().putString(PREF_LAST_CHANNEL, channel.id).apply()
        channelTitle.text = "# " + channel.name
        channelAdapter.rightTargetId = if (channel.writable) R.id.messageInput else R.id.messageList
        composer.visibility = if (channel.writable) View.VISIBLE else View.GONE
        readOnlyNote.visibility = if (channel.writable) View.GONE else View.VISIBLE
        input.hint = "Message #" + channel.name
        setReply(null)
        noMoreOlder = false
        messageAdapter.submit(emptyList())
        showStatus(null, loading = true)
        refresh(scrollToEnd = true)
        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            var tick = 0
            while (isActive) {
                delay(POLL_MS)
                refresh(scrollToEnd = false)
                if (++tick % CHANNELS_EVERY == 0) refreshChannels()
            }
        }
    }

    /**
     * Pulls the channel's latest page and merges it over what is shown:
     * older pages the customer scrolled up to are kept, the latest window
     * is replaced (so edits and staff deletions show up too).
     */
    private fun refresh(scrollToEnd: Boolean) {
        val channel = current ?: return
        lifecycleScope.launch {
            val result = repo.latest(channel.id)
            if (current?.id != channel.id) return@launch
            result
                .onSuccess { page ->
                    messageAdapter.you = page.you
                    markSeen(channel.id, page.messages.lastOrNull()?.id)
                    val latest = page.messages
                    val oldestLatest = latest.firstOrNull()?.id
                    val kept = if (oldestLatest == null) emptyList() else
                        messageAdapter.items.filter { idLess(it.id, oldestLatest) }
                    val merged = kept + latest
                    val before = messageAdapter.items
                    if (merged.map { it.id to it.text } == before.map { it.id to it.text } &&
                        merged.size == before.size
                    ) {
                        showStatus(if (merged.isEmpty()) "No messages yet. Say hello!" else null)
                        return@onSuccess
                    }
                    val atBottom = !messageList.canScrollVertically(1)
                    val newAtEnd = merged.lastOrNull()?.id != before.lastOrNull()?.id
                    messageAdapter.submit(merged)
                    showStatus(if (merged.isEmpty()) "No messages yet. Say hello!" else null)
                    if (merged.isNotEmpty() && (scrollToEnd || (atBottom && newAtEnd))) {
                        scrollToEnd()
                    }
                }
                .onFailure {
                    // Keep what is on screen during a short network blip.
                    if (messageAdapter.itemCount == 0) showStatus(it.message)
                }
        }
    }

    /**
     * Pictures load after the first layout and make their rows taller, which
     * left the newest messages hidden below the fold - scroll again once
     * they have had time to arrive.
     */
    private fun scrollToEnd() {
        val last = messageAdapter.itemCount - 1
        if (last < 0) return
        messageList.scrollToPosition(last)
        for (delayMs in longArrayOf(250L, 900L, 2000L)) {
            messageList.postDelayed({
                val end = messageAdapter.itemCount - 1
                if (end >= 0 && layoutManager.findLastVisibleItemPosition() >= end - 2) {
                    messageList.scrollToPosition(end)
                    messageList.post { messageList.scrollBy(0, Int.MAX_VALUE / 4) }
                }
            }, delayMs)
        }
    }

    /** Newer channel list: new-message dots, and writable changes. */
    private fun refreshChannels() {
        lifecycleScope.launch {
            repo.channels().onSuccess { list ->
                if (list.isEmpty()) return@onSuccess
                // Rebinding every row every few seconds can move TV remote
                // focus, so only redraw when the list itself changed.
                if (list.map { it.id to it.writable } != channels.map { it.id to it.writable }) {
                    channelAdapter.submit(list)
                }
                channels = list
                current?.let { open -> list.firstOrNull { it.id == open.id }?.let { markSeenIfOpen(it) } }
                updateUnread()
            }
        }
    }

    private fun markSeenIfOpen(channel: SupportChannel) {
        // The open channel's own newest message is on screen, so it is read.
        if (messageAdapter.items.lastOrNull()?.id == channel.lastMessageId) {
            markSeen(channel.id, channel.lastMessageId)
        }
    }

    private fun markSeen(channelId: String, messageId: String?) {
        if (messageId.isNullOrBlank()) return
        val seen = seenPrefs.getString(channelId, null)
        if (seen == null || idLess(seen, messageId)) {
            seenPrefs.edit().putString(channelId, messageId).apply()
        }
    }

    /**
     * A channel is unread when its newest message is newer than the last one
     * this customer saw there. The very first time a channel is listed it
     * counts as read, so a fresh install does not light up every channel.
     */
    private fun updateUnread() {
        val unread = mutableSetOf<String>()
        for (ch in channels) {
            val last = ch.lastMessageId?.takeIf { it.isNotBlank() } ?: continue
            val seen = seenPrefs.getString(ch.id, null)
            if (seen == null) {
                seenPrefs.edit().putString(ch.id, last).apply()
            } else if (ch.id != current?.id && idLess(seen, last)) {
                unread.add(ch.id)
            }
        }
        channelAdapter.unread = unread
    }

    private fun loadOlder() {
        val channel = current ?: return
        val first = messageAdapter.items.firstOrNull()?.id ?: return
        if (loadingOlder || noMoreOlder) return
        loadingOlder = true
        lifecycleScope.launch {
            repo.older(channel.id, first)
                .onSuccess { older ->
                    if (current?.id != channel.id) return@onSuccess
                    if (older.isEmpty()) {
                        noMoreOlder = true
                        return@onSuccess
                    }
                    val known = messageAdapter.items.map { it.id }.toSet()
                    val fresh = older.filter { it.id !in known }
                    val anchor = layoutManager.findFirstVisibleItemPosition()
                    val offset = layoutManager.findViewByPosition(anchor)?.top ?: 0
                    messageAdapter.submit(fresh + messageAdapter.items)
                    layoutManager.scrollToPositionWithOffset(anchor + fresh.size, offset)
                }
            loadingOlder = false
        }
    }

    private fun send() {
        val channel = current ?: return
        if (sending || !channel.writable) return
        val text = input.text.toString().trim()
        val image = pickedImage
        if (text.isEmpty() && image == null) return
        sending = true
        sendButton.isEnabled = false
        lifecycleScope.launch {
            repo.send(this@LiveSupportActivity, channel.id, text, replyTo?.id, image)
                .onSuccess {
                    input.setText("")
                    setReply(null)
                    pickedImage = null
                    imageBar.visibility = View.GONE
                    refresh(scrollToEnd = true)
                }
                .onFailure {
                    Toast.makeText(this@LiveSupportActivity, it.message, Toast.LENGTH_LONG).show()
                }
            sending = false
            sendButton.isEnabled = true
        }
    }

    private fun setReply(message: SupportMessage?) {
        replyTo = message
        replyBar.visibility = if (message == null) View.GONE else View.VISIBLE
        if (message != null) {
            val snippet = message.text.orEmpty().replace('\n', ' ').ifBlank { "picture" }
            replyText.text = "Replying to ${message.author.name}: $snippet"
            input.requestFocus()
        }
    }

    private fun showMessageOptions(message: SupportMessage) {
        val options = mutableListOf<String>()
        if (current?.writable == true) options.add("Reply")
        if (!message.text.isNullOrBlank()) options.add("Copy text")
        if (options.isEmpty()) return
        showChoiceDialog(
            title = message.author.name ?: "Message",
            items = options,
            selectedIndex = -1
        ) { which ->
            when (options[which]) {
                "Reply" -> setReply(message)
                "Copy text" -> {
                    val clipboard = getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("message", message.text))
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showImage(url: String) {
        val image = ImageView(this).apply {
            adjustViewBounds = true
            load(url)
        }
        val frame = LinearLayout(this).apply {
            setPadding(8, 8, 8, 8)
            addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setView(frame)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showStatus(message: String?, loading: Boolean = false) {
        progress.visibility = if (loading) View.VISIBLE else View.GONE
        status.visibility = if (!loading && message != null) View.VISIBLE else View.GONE
        status.text = message.orEmpty()
    }

    /** Discord ids are numbers too long for Long in some cases - compare as text. */
    private fun idLess(a: String, b: String): Boolean =
        if (a.length != b.length) a.length < b.length else a < b
}
