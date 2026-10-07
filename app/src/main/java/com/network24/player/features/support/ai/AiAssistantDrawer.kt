package com.network24.player.features.support.ai

import com.network24.player.core.database.DatabaseProvider
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Gravity
import android.view.KeyEvent
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import com.network24.player.R
import com.network24.player.common.models.AiChannel
import com.network24.player.common.models.AiMessage
import com.network24.player.core.database.entity.ChannelEntity
import com.network24.player.core.database.mapper.toLiveChannel
import com.network24.player.features.player.activity.PlayerActivity
import com.network24.player.features.player.state.PlayerState
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.features.player.manager.PlayerManager
import com.network24.player.features.support.adapter.SupportMessageAdapter
import com.network24.player.features.support.repository.SupportRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The full-screen player's private chat with the N24 Support AI (the same AI
 * that answers in #ai-actions-and-chat, running on the GX10 server). Opens
 * as a see-through panel on the right, so the channel keeps playing behind
 * it. Ready-made buttons fix the channel being watched straight away; typed
 * questions go to the AI. Polls only while the panel is open.
 */
class AiAssistantDrawer(
    private val activity: AppCompatActivity,
    private val button: View
) {

    private class Quick(val label: String, val text: String, val mode: String)

    private val quicks = listOf(
        Quick("Channel not working", "This channel is not working", "down"),
        Quick("Buffering", "This channel keeps buffering", "buffering"),
        Quick("Wrong channel / loop", "This channel is showing the wrong content or looping", "loop"),
        Quick("No sound", "This channel has no sound", "audio"),
        Quick("TV guide wrong", "The TV guide is missing or wrong for this channel", "epg"),
        Quick("My plan", "", PLAN),
    )

    private val prefs = PreferenceManager(activity)
    private val repo = SupportRepository(prefs)
    private var host: FrameLayout? = null
    private var pollJob: Job? = null
    private var lastId = 0
    private var sending = false
    var isOpen = false
        private set

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = close()
    }

    // Channel that was playing on this screen before "go to channel" opened
    // the full-screen player on top; put back when the customer returns.
    private var backUrl: String? = null
    private var backStreamId = 0

    init {
        button.setOnClickListener { if (isOpen) close() else open() }
        applyEnabled()
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                applyEnabled()
                restoreAfterJump()
            }
        })
    }

    /** Called when the panel opens (the screen closes the "What's playing" panel). */
    var onOpen: () -> Unit = {}

    fun open() {
        onOpen()
        val panel = host ?: build().also { host = it }
        panel.visibility = View.VISIBLE
        slideIn(panel)
        isOpen = true
        // Re-add so this callback wins over the player's own back handling.
        back.remove()
        activity.onBackPressedDispatcher.addCallback(activity, back)
        back.isEnabled = true
        if (!panel.isInTouchMode) panel.post { focusNewest() }
        startPolling()
    }

    /** Remote keys the open panel handles itself instead of the player screen. */
    fun handlesKey(keyCode: Int): Boolean = isOpen && keyCode in NAV_KEYS

    /** TV remote: put focus on the newest answer's buttons, else on the first quick button. */
    private fun focusNewest() {
        val panel = host ?: return
        val list = panel.findViewById<LinearLayout>(R.id.aiMessages)
        for (i in list.childCount - 1 downTo 0) {
            val box = list.getChildAt(i).findViewWithTag<ViewGroup>(CHOICES_TAG)
            if (box != null && box.visibility == View.VISIBLE && box.childCount > 0) {
                box.getChildAt(0).requestFocus()
                return
            }
        }
        panel.findViewById<LinearLayout>(R.id.aiChips).getChildAt(0)?.requestFocus()
    }

    private fun isInside(frame: ViewGroup, view: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === frame) return true
            v = v.parent as? View
        }
        return false
    }

    fun close() {
        isOpen = false
        back.isEnabled = false
        pollJob?.cancel()
        host?.let { panel ->
            panel.findViewById<EditText>(R.id.aiInput).let { input ->
                ContextCompat.getSystemService(activity, InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(input.windowToken, 0)
            }
            slideOut(panel)
        }
    }

    // ---------------------------------------------------------------- animation

    /** The see-through panel slides in from the right edge and fades in. */
    private fun slideIn(frame: FrameLayout) {
        val panel = frame.getChildAt(0) ?: return
        panel.animate().cancel()
        val distance = panel.width.takeIf { it > 0 } ?: panel.layoutParams.width
        panel.translationX = distance.toFloat()
        panel.alpha = 0f
        panel.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(ANIM_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction(null)
            .start()
    }

    /** Slides back out to the right, then hides; reopening mid-way just reverses it. */
    private fun slideOut(frame: FrameLayout) {
        val panel = frame.getChildAt(0) ?: run { frame.visibility = View.GONE; return }
        panel.animate().cancel()
        panel.animate()
            .translationX(panel.width.toFloat())
            .alpha(0f)
            .setDuration(ANIM_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { if (!isOpen) frame.visibility = View.GONE }
            .start()
    }

    private fun build(): FrameLayout {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val density = activity.resources.displayMetrics.density
        val screen = activity.resources.displayMetrics.widthPixels
        val width = (screen * 0.42f).toInt().coerceAtLeast((360 * density).toInt()).coerceAtMost(screen)

        // Pass-through frame: only the panel itself takes touches, the rest of
        // the screen still reaches the player.
        // Keeps D-pad focus inside the panel: the player's own (hidden) controls
        // sit underneath and would otherwise take it.
        val frame = object : FrameLayout(activity) {
            override fun focusSearch(focused: View?, direction: Int): View? {
                // Chat buttons sit in one column: LEFT/RIGHT must not drop down into the
                // quick buttons (an OK press there would start a fix by mistake).
                val messages = findViewById<View>(R.id.aiMessages)
                if (focused != null && messages != null && isInside(messages as ViewGroup, focused)
                    && (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT)
                ) {
                    return focused
                }
                val next = super.focusSearch(focused, direction)
                return if (next == null || isInside(this, next)) next else focused
            }
        }.apply { elevation = 100f }
        val panel = LayoutInflater.from(activity).inflate(R.layout.layout_ai_drawer, frame, false)
        frame.addView(panel, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        root.addView(frame, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        panel.findViewById<View>(R.id.aiClose).apply {
            setOnClickListener { close() }
            nextFocusRightId = R.id.aiClose     // nothing to its right; don't jump into the chat
        }
        panel.findViewById<View>(R.id.aiClear).setOnClickListener { clearChat() }
        val input = panel.findViewById<EditText>(R.id.aiInput)
        panel.findViewById<View>(R.id.aiSend).setOnClickListener { sendTyped(input) }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) {
                sendTyped(input)
                true
            } else {
                false
            }
        }

        val chips = panel.findViewById<LinearLayout>(R.id.aiChips)
        quicks.forEach { q -> chips.addView(chip(q.label) { onQuick(q) }) }

        host = frame   // addBubble() draws into host
        addBubble(welcome())
        return frame
    }

    private fun welcome() = AiMessage(
        0, "bot", "Hi! I'm the N24 Support AI. Tap a button below if the channel you're watching " +
            "has a problem, or ask me anything about your account or the app.", null, null
    )

    /** Header "Clear": wipes this customer's chat on the server and on screen. */
    private fun clearChat() {
        activity.lifecycleScope.launch {
            repo.aiClear()
                .onSuccess {
                    host?.findViewById<LinearLayout>(R.id.aiMessages)?.removeAllViews()
                    addBubble(welcome())
                }
                .onFailure { Toast.makeText(activity, it.message, Toast.LENGTH_LONG).show() }
        }
    }

    /** Settings > Special features > AI Support Assistant (off by default) shows or hides the button. */
    private fun applyEnabled() {
        val enabled = prefs.isAiAssistantEnabled()
        button.visibility = if (enabled) View.VISIBLE else View.GONE
        if (!enabled && isOpen) close()
    }

    // ---------------------------------------------------------------- go to channel

    /**
     * One button per channel the AI suggested. Only channels in this
     * customer's package (the app's channel list) are shown.
     */
    private fun channelBox(channels: List<AiChannel>, focusFirst: Boolean): LinearLayout {
        val density = activity.resources.displayMetrics.density
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        activity.lifecycleScope.launch {
            val owned = runCatching {
                DatabaseProvider.get(activity).channelDao().getByStreamIds(channels.map { it.id })
            }.getOrDefault(emptyList()).associateBy { it.streamId }
            // not the channels of categories under the parental lock
            val locked = com.network24.player.core.parental.ParentalLock.activeLockedIds(activity)
            val shown = channels.filter { it.id in owned && owned[it.id]?.categoryId !in locked }
            if (shown.isEmpty()) {
                box.addView(TextView(activity).apply {
                    text = "None of these channels are in your package."
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(activity, R.color.text_hint))
                    setPadding(0, (6 * density).toInt(), 0, 0)
                })
                return@launch
            }
            shown.forEach { c ->
                box.addView(channelButton(c) { goToChannel(shown.mapNotNull { owned[it.id] }, c.id) })
            }
            // TV remote: a new answer with channels puts focus on its first channel
            // (not while typing, not for older messages loaded when the panel opens).
            val row = box.parent as? View
            val list = row?.parent as? ViewGroup
            val newest = list != null && list.indexOfChild(row) == list.childCount - 1
            if (focusFirst && isOpen && newest && !box.isInTouchMode && activity.currentFocus !is EditText) {
                box.post { box.getChildAt(0)?.requestFocus() }
            } else {
                host?.findViewById<ScrollView>(R.id.aiScroll)?.let { s -> s.post { s.scrollTo(0, s.getChildAt(0).height) } }
            }
        }
        return box
    }

    private fun channelButton(c: AiChannel, onClick: () -> Unit): TextView {
        val density = activity.resources.displayMetrics.density
        val label = SpannableStringBuilder("▶  " + (c.name ?: "Channel ${c.id}"))
        label.setSpan(StyleSpan(Typeface.BOLD), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (!c.info.isNullOrBlank()) {
            val start = label.length
            label.append("\n").append(c.info)
            label.setSpan(RelativeSizeSpan(0.85f), start, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            label.setSpan(ForegroundColorSpan(ContextCompat.getColor(activity, R.color.text_secondary)),
                start, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return chip("", onClick).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * density).toInt(); marginEnd = (36 * density).toInt() }
        }
    }

    /**
     * Plays the chosen channel full screen; the other suggested channels become
     * the up/down list. On the full-screen player itself it just switches.
     */
    private fun goToChannel(all: List<ChannelEntity>, streamId: Int) {
        val list = all.map { it.toLiveChannel() }
        val position = list.indexOfFirst { it.stream_id == streamId }
        if (position < 0) return
        PlayerState.channels.clear()
        PlayerState.channels.addAll(list)
        PlayerState.currentPosition = position
        close()
        if (activity is PlayerActivity) {
            activity.playSelectedChannel()
            return
        }
        backUrl = PlayerManager.currentStreamUrl()
        backStreamId = PlayerManager.currentStreamId
        activity.startActivity(
            Intent(activity, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_PLAY_SELECTED_CHANNEL, true)
        )
    }

    /** Back from a channel the AI opened: this screen's own channel plays again. */
    private fun restoreAfterJump() {
        val url = backUrl ?: return
        backUrl = null
        if (PlayerManager.currentStreamUrl() == url) return
        val view = activity.findViewById<androidx.media3.ui.PlayerView>(R.id.playerView) ?: return
        PlayerManager.play(activity, view, url, backStreamId.toString())
    }

    private fun chip(label: String, onClick: () -> Unit): TextView {
        val density = activity.resources.displayMetrics.density
        return TextView(activity).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_ai_chip)
            isFocusable = true
            isClickable = true
            setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * density).toInt() }
            setOnClickListener { onClick() }
        }
    }

    // ---------------------------------------------------------------- sending

    private fun sendTyped(input: EditText) {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        ask(text, "")
    }

    private fun onQuick(q: Quick) {
        if (q.mode == PLAN) {
            // Answered from the account details the app already has - instant.
            addBubble(AiMessage(0, "you", "What's my plan?", null, null))
            addBubble(AiMessage(0, "bot", planText(), null, null))
            return
        }
        ask(q.text, if (PlayerManager.currentStreamId > 0) q.mode else "")
    }

    private fun ask(text: String, mode: String) {
        if (sending) return
        sending = true
        showStatus("Sending…")
        activity.lifecycleScope.launch {
            val streamId = PlayerManager.currentStreamId
            // The name lets the AI server find the channel (Main searches by name).
            val streamName = if (streamId > 0) {
                try {
                    DatabaseProvider.get(activity).channelDao().getByStreamIds(listOf(streamId)).firstOrNull()?.name
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
            repo.aiAsk(text, mode, streamId, streamName.orEmpty())
                .onSuccess { r ->
                    r.message?.let { addBubble(it); lastId = maxOf(lastId, it.id) }
                    if (!r.online) showOffline()
                }
                .onFailure {
                    showStatus(null)
                    Toast.makeText(activity, it.message, Toast.LENGTH_LONG).show()
                }
            sending = false
            poll()
        }
    }

    private fun planText(): String {
        val expiry = prefs.getExpiry()
        val days = if (expiry > 0L) com.network24.player.core.util.ExpiryDays.from(expiry * 1000L) else null
        val date = if (expiry > 0L) {
            java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(expiry * 1000L))
        } else {
            null
        }
        val plan = if (prefs.isTrial()) "a trial" else "a Premium"
        return buildString {
            append("You're on $plan account (status: ${prefs.getStatus()}).\n")
            append(
                when {
                    date == null -> "Your plan has no end date."
                    days != null && days < 0 -> "It expired on $date. To keep watching, please contact the provider you got your account from."
                    else -> "It runs until $date ($days day${if (days == 1L) "" else "s"} left)."
                }
            )
            append("\nConnections in use: ${prefs.getActiveConnections()} of ${prefs.getMaxConnections()}.")
        }
    }

    // ---------------------------------------------------------------- receiving

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = activity.lifecycleScope.launch {
            while (isActive && isOpen) {
                poll()
                delay(POLL_MS)
            }
        }
    }

    private suspend fun poll() {
        val firstLoad = lastId == 0
        repo.aiPoll(lastId).onSuccess { r ->
            var answered = false
            r.messages.orEmpty().forEach { m ->
                if (m.id > lastId) {
                    // After the first load, the customer's own questions are
                    // already on screen (added when sent) - only add answers.
                    // Only answers that arrive now may take remote focus - not the old
                    // chat loaded when the panel opens (its channel buttons stole focus).
                    if (m.fromBot || firstLoad) addBubble(m, live = !firstLoad)
                    if (m.fromBot && !firstLoad) answered = true
                    lastId = m.id
                }
            }
            if (answered) reloadIfStuck()
            setOnline(r.online)
            if (!r.online && r.state != "idle") showOffline() else showStatus(statusText(r.state))
        }
    }

    /**
     * A fix can bring a dead channel back while the player has already given up
     * (reconnect attempts used up, "Unable to play this stream" on screen). Load
     * it again then, so the customer sees it working without reopening it.
     * A channel that is playing is left alone.
     */
    private fun reloadIfStuck() {
        val player = PlayerManager.getExoPlayerOrNull() ?: return
        if (player.playerError != null || player.playbackState == Player.STATE_IDLE) {
            PlayerManager.retryCurrent()
        }
    }

    private fun statusText(state: String?): String? = when (state) {
        "queued" -> "Sent. Waiting for N24 Support…"
        "reading" -> "N24 Support is reading your message…"
        "typing" -> "N24 Support is typing…"
        else -> null
    }

    private fun setOnline(online: Boolean) {
        host?.findViewById<TextView>(R.id.aiOnline)?.apply {
            text = if (online) "● Online" else "● Offline"
            setTextColor(ContextCompat.getColor(activity, if (online) R.color.success else R.color.error))
        }
    }

    private fun showOffline() {
        setOnline(false)
        showStatus("The AI assistant is offline right now. Please try again later or use Live Support.")
    }

    private fun showStatus(text: String?) {
        host?.findViewById<TextView>(R.id.aiStatus)?.apply {
            visibility = if (text == null) View.GONE else View.VISIBLE
            this.text = text.orEmpty()
        }
    }

    private fun addBubble(m: AiMessage, live: Boolean = true) {
        val panel = host ?: return
        val list = panel.findViewById<LinearLayout>(R.id.aiMessages)
        val density = activity.resources.displayMetrics.density
        // Only the newest bot message keeps its answer buttons.
        for (i in 0 until list.childCount) list.getChildAt(i).findViewWithTag<View>(CHOICES_TAG)?.visibility = View.GONE

        val bubble = TextView(activity).apply {
            text = SupportMessageAdapter.styled(m.text.orEmpty())
            textSize = 14f
            setTextColor(Color.WHITE)
            setLineSpacing(2 * density, 1f)
            setBackgroundResource(if (m.fromBot) R.drawable.bg_ai_bubble_bot else R.drawable.bg_ai_bubble_you)
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            autoLinkMask = Linkify.WEB_URLS
            movementMethod = LinkMovementMethod.getInstance()
            setLinkTextColor(ContextCompat.getColor(activity, R.color.primary_light))
            // LinkMovementMethod makes the text focusable; on a TV remote that is an
            // invisible stop. Without it UP/DOWN scroll a long answer instead.
            isFocusable = false
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (m.fromBot) Gravity.START else Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
        }
        row.addView(bubble, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { if (m.fromBot) marginEnd = (36 * density).toInt() else marginStart = (36 * density).toInt() })

        val channels = m.channels.orEmpty()
        if (m.fromBot && channels.isNotEmpty()) row.addView(channelBox(channels, focusFirst = live))

        val choices = m.choices.orEmpty()
        if (m.fromBot && choices.isNotEmpty()) {
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                tag = CHOICES_TAG
            }
            choices.forEach { c ->
                box.addView(chip(c.replace("**", "")) { ask(c.replace("**", ""), "") }.apply {
                    (layoutParams as LinearLayout.LayoutParams).topMargin = (6 * density).toInt()
                })
            }
            row.addView(box)
        }
        list.addView(row)
        // Remote: move to the new answer's buttons, but never away from the text box while typing.
        if (live && m.fromBot && choices.isNotEmpty() && !row.isInTouchMode
            && activity.currentFocus !is EditText
        ) {
            row.post { row.findViewWithTag<ViewGroup>(CHOICES_TAG)?.getChildAt(0)?.requestFocus() }
        }
        panel.findViewById<ScrollView>(R.id.aiScroll).post {
            // scrollTo, not fullScroll(FOCUS_DOWN): fullScroll also moves focus to the last button.
            panel.findViewById<ScrollView>(R.id.aiScroll).let { it.scrollTo(0, it.getChildAt(0).height) }
        }
    }

    companion object {
        private const val POLL_MS = 2_000L
        private const val PLAN = "plan"
        private const val CHOICES_TAG = "ai_choices"
        private const val ANIM_MS = 220L
        private val NAV_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER
        )
    }
}
