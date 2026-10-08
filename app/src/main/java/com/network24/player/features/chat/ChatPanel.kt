package com.network24.player.features.chat

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.speech.RecognizerIntent
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.network24.player.R
import com.network24.player.core.api.Web24Api
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.mapper.toLiveChannel
import com.network24.player.features.dashboard.home.HomeFont
import com.network24.player.features.discover.Game
import com.network24.player.features.discover.GameChannels
import com.network24.player.features.player.activity.PlayerActivity
import com.network24.player.features.player.manager.PlayerManager
import com.network24.player.features.player.state.PlayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Live Chat in the full-screen player: a panel on the right (the picture moves left so nothing is covered), like
 * YouTube's live chat. The room follows what is on: a live game (everyone watching that game, on any channel,
 * chats together), else the channel. In a Watch Party the party's private room is used instead, and when the host
 * switches channel the guests' TVs follow (can be turned off).
 *
 * On a TV: one-press reactions (they also float up over the picture), voice typing, or "Type on phone" (QR).
 * Everyone chats under a chat name; the login name is never shown.
 */
class ChatPanel(private val activity: AppCompatActivity, button: View) {
    private val api = ChatApi(activity)
    private val db by lazy { DatabaseProvider.get(activity) }
    private val res = activity.resources
    private val uiScale = res.displayMetrics.let { m -> minOf(1f, m.widthPixels / m.density / 960f, m.heightPixels / m.density / 400f) }
    private val d = res.displayMetrics.density * uiScale * 0.92f
    private fun dp(v: Int) = (v * d).toInt()
    private fun dpf(v: Float) = v * d

    private val bg = 0xEB0C0D12.toInt()
    private val surface = Color.parseColor("#1A1C23")
    private val line = Color.parseColor("#1FFFFFFF")
    private val textMain = Color.parseColor("#F2F3F5")
    private val textSub = Color.parseColor("#9BA1AD")
    private val accent = Color.parseColor("#7C5CFF")
    private val accentSoft = Color.parseColor("#A894FF")
    private val gold = Color.parseColor("#F5B841")
    private val live = Color.parseColor("#E5484D")

    private val reactions = listOf("🔥", "👏", "😮", "😂", "❤️", "GOAL!", "What a play!", "Let's go!")

    private var frame: FrameLayout? = null
    private lateinit var panel: LinearLayout
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var partyBar: LinearLayout
    private lateinit var partyText: TextView
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var mic: ImageView
    private lateinit var status: TextView
    private lateinit var tabChat: TextView
    private lateinit var tabParty: TextView
    private lateinit var chatHint: TextView
    private lateinit var partyView: ScrollView
    private lateinit var partyBox: LinearLayout
    private lateinit var reactsRow: View
    private lateinit var typeRow: View
    private var onPartyTab = false
    private var partyInfo: ChatApi.Room? = null
    private var partyCode = ""
    private var membersText: TextView? = null
    private var emptyHint: TextView? = null
    private lateinit var helpBar: TextView
    private val helpDefault = "Use the arrows to move and OK to select"
    private val panelW by lazy { (res.displayMetrics.widthPixels * 0.30f).toInt().coerceIn(dp(320), dp(460)) }

    var isOpen = false
        private set
    var onOpen: () -> Unit = {}

    private var nick = ""
    private var room = ""
    private var roomTitle = ""
    private var isHost = false
    private val seen = HashSet<String>()
    private var streamSeen = 0
    private var partyChannelSeen = 0
    private var loop: Job? = null
    private var lastBeat = 0L
    private var lastCount = 0L
    private var msgReg: com.google.firebase.firestore.ListenerRegistration? = null
    private var roomReg: com.google.firebase.firestore.ListenerRegistration? = null

    private val back = object : OnBackPressedCallback(false) { override fun handleOnBackPressed() = close() }

    private val voice = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { send(it, "m") }
    }

    init {
        button.setOnClickListener { if (isOpen) close() else open() }
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { detach(); if (room.isNotEmpty()) { val r = room; activity.lifecycleScope.launch { api.leavePresence(r); api.leave(r) } } }
            override fun onStart(owner: LifecycleOwner) { if (isOpen && room.isNotEmpty()) { attach(); lastBeat = 0L; startLoop() } }
        })
        if (openOnNextPlayer) { openOnNextPlayer = false; button.post { open() } }
    }

    // ------------------------------------------------------------------------------------------------ open / close
    fun open() {
        onOpen()
        val f = frame ?: build().also { frame = it }
        f.visibility = View.VISIBLE
        isOpen = true
        panel.translationX = panelW.toFloat(); panel.alpha = 0f
        panel.animate().translationX(0f).alpha(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        squeezeVideo(true)
        f.post { (if (onPartyTab) tabParty else tabChat).requestFocus() }
        back.remove(); activity.onBackPressedDispatcher.addCallback(activity, back); back.isEnabled = true
        activity.lifecycleScope.launch { if (ensureNick()) { enterRoom(); startLoop(); (if (onPartyTab) tabParty else tabChat).requestFocus() } else close() }
    }

    fun close() {
        // never opened yet: nothing built to close (the AI / guide panels call this when they open)
        if (frame == null) { isOpen = false; return }
        isOpen = false
        back.isEnabled = false
        loop?.cancel()
        detach()
        hideKeyboard()
        squeezeVideo(false)
        frame?.let { f -> panel.animate().translationX(panelW.toFloat()).alpha(0f).setDuration(200).withEndAction { if (!isOpen) f.visibility = View.GONE }.start() }
        if (room.isNotEmpty()) { val r = room; activity.lifecycleScope.launch { api.leavePresence(r); api.leave(r) } }
    }

    /** Remote keys the open panel keeps for itself (the player would change channel / show its controls). */
    fun handlesKey(keyCode: Int) = isOpen && keyCode in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)

    /** The picture keeps its full size and ratio; the chat is a glass panel over its right edge (owner: never shrink or crop it). */
    private fun squeezeVideo(on: Boolean) {
        activity.findViewById<View>(R.id.playerView)?.let { v -> (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp -> if (lp.marginEnd != 0) { lp.marginEnd = 0; v.layoutParams = lp } } }
    }

    // ------------------------------------------------------------------------------------------------ building
    private fun text(s: String, size: Float, color: Int = textMain, weight: Int = 500, lines: Int = 1) = TextView(activity).apply {
        text = s; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * d); setTextColor(color); typeface = HomeFont.of(activity, weight)
        maxLines = lines; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
    }

    private fun shape(fill: Int, radius: Float, stroke: Int = 0) = GradientDrawable().apply { setColor(fill); cornerRadius = dpf(radius); if (stroke != 0) setStroke(dp(1), stroke) }

    /** The focused button's job, shown in the help line at the bottom of the panel. */
    private fun help(v: View, words: String) {
        val old = v.onFocusChangeListener
        v.setOnFocusChangeListener { view, has -> old?.onFocusChange(view, has); if (has) helpBar.text = words else if (helpBar.text == words) helpBar.text = helpDefault }
    }

    private fun focusRing(v: View, radius: Float) {
        v.isFocusable = true; v.isFocusableInTouchMode = true; v.isClickable = true
        val ring = GradientDrawable().apply { cornerRadius = dpf(radius); setStroke(dp(2), Color.WHITE); setColor(Color.TRANSPARENT) }
        v.setOnFocusChangeListener { view, has -> view.foreground = if (has) ring else null }
    }

    private fun chip(label: String, hint: String = "", onClick: () -> Unit) = text(label, 13f, textMain, 700).apply {
        setPadding(dp(12), dp(7), dp(12), dp(7)); background = shape(0x1FFFFFFF, 16f); gravity = Gravity.CENTER
        setOnClickListener { onClick() }; focusRing(this, 16f)
        if (hint.isNotEmpty()) help(this, hint)
    }

    private fun icon(resId: Int, label: String, above: Boolean = false, onClick: () -> Unit) = ImageView(activity).apply {
        setImageResource(resId); setColorFilter(textMain); contentDescription = label; setPadding(dp(8), dp(8), dp(8), dp(8))
        background = shape(0x1AFFFFFF, 18f); setOnClickListener { onClick() }; focusRing(this, 18f)
        help(this, label)
    }

    private fun isInside(group: ViewGroup, view: View): Boolean {
        var v: View? = view
        while (v != null) { if (v === group) return true; v = v.parent as? View }
        return false
    }

    private fun build(): FrameLayout {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        // the remote's focus stays inside the panel (the player's hidden controls sit underneath)
        val f = object : FrameLayout(activity) {
            override fun focusSearch(focused: View?, direction: Int): View? =
                android.view.FocusFinder.getInstance().findNextFocus(this, focused, direction) ?: focused
        }.apply { elevation = 90f; clipChildren = false }

        panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(18), dp(16), dp(14)); clipChildren = false; clipToPadding = false
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xD60C0D12.toInt(), 0xF40C0D12.toInt()))
            isClickable = true
        }
        f.addView(panel, FrameLayout.LayoutParams(panelW, -1, Gravity.END))

        // header: room, people in it, Watch Party, phone, close
        val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val words = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        title = text("Live Chat", 16f, textMain, 800)
        sub = text("Connecting…", 11f, textSub, 600).apply { setPadding(0, dp(4), 0, 0) }
        words.addView(title); words.addView(sub)
        head.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(icon(R.drawable.ic_phone_type, "Type on your phone - scan a QR code") { phone() }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(6) })
        head.addView(icon(R.drawable.ic_cu_close, "Close the chat") { close() }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(6) })
        panel.addView(head)

        // two clearly named tabs: the chat, and Watch Party (what it is + how to use it)
        val tabs = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        tabChat = tab("Live chat", "Chat with everyone watching this channel right now") { setTab(false) }
        tabParty = tab("Watch Party", "Watch the same channel together with friends, with a private chat") { setTab(true) }
        tabs.addView(tabChat, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        tabs.addView(tabParty, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        chatHint = text("", 11f, textSub, 500, lines = 2).apply { setPadding(dp(2), dp(8), dp(2), 0) }
        panel.addView(chatHint)

        partyBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(8)); visibility = View.GONE
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x553A2C7A, 0x331E1A3A)).apply { cornerRadius = dpf(10f); setStroke(dp(1), 0x557C5CFF) }
        }
        partyText = text("", 12f, textMain, 600, lines = 2)
        partyBar.addView(partyText, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(partyBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // messages
        list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false; isFocusable = false; addView(list); isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(24)) }
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        status = text("", 11f, gold, 600, lines = 2).apply { visibility = View.GONE; setPadding(0, dp(2), 0, dp(4)) }
        panel.addView(status)

        // one-press reactions
        val reacts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        reactions.chunked(5).forEach { part ->
            val r1 = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            part.forEach { r -> r1.addView(chip(r, "Send $r to everyone watching") { send(r, "r") }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6); bottomMargin = dp(6) }) }
            reacts.addView(r1)
        }
        reactsRow = reacts
        panel.addView(reactsRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        // typing row
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(activity).apply {
            hint = "Say something…"; setHintTextColor(textSub); setTextColor(textMain); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 14f * d)
            typeface = HomeFont.of(activity, 500); background = shape(surface, 20f, line); setPadding(dp(14), dp(9), dp(14), dp(9))
            isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEND; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(android.text.InputFilter.LengthFilter(200))
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false }
        }
        help(input, "Type a message - press OK to open the keyboard")
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        mic = icon(R.drawable.ic_mic_chat, "Speak your message", above = true) { speak() }
        val canSpeak = activity.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
        mic.visibility = if (canSpeak) View.VISIBLE else View.GONE
        row.addView(mic, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(6) })
        row.addView(chip("Send", "Send your message") { sendTyped() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        typeRow = row
        panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        // the Watch Party tab replaces the messages, reactions and typing row
        partyBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(10), dp(6), dp(10)) }
        partyView = ScrollView(activity).apply { isVerticalScrollBarEnabled = false; isFocusable = false; visibility = View.GONE; clipChildren = false; clipToPadding = false; addView(partyBox) }
        panel.addView(partyView, LinearLayout.LayoutParams(-1, 0, 1f))

        // what the focused button does (a TV remote has no tooltips)
        helpBar = text(helpDefault, 11f, textSub, 600, lines = 2).apply { setPadding(dp(10), dp(7), dp(10), dp(7)); background = shape(0x14FFFFFF, 10f) }
        panel.addView(helpBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        styleTabs()

        root.addView(f, ViewGroup.LayoutParams(-1, -1))
        return f
    }

    // ------------------------------------------------------------------------------------------------ chat name
    private suspend fun ensureNick(): Boolean {
        val me = runCatching { api.me() }.getOrElse { Toast.makeText(activity, it.message, Toast.LENGTH_LONG).show(); return false }
        if (me.optBoolean("banned")) { Toast.makeText(activity, "You cannot use the chat right now.", Toast.LENGTH_LONG).show(); return false }
        nick = me.optString("nick").takeIf { !me.isNull("nick") && it.isNotBlank() } ?: (askNick() ?: return false)
        ChatApi.setUsed(activity)
        return true
    }

    /** First time: the customer picks the name everyone sees (never the login name). */
    private suspend fun askNick(): String? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), 0) }
        val hint = TextView(activity).apply { text = "Everyone in the chat sees this name. Please don't use your login name - it keeps your account safe."; setPadding(0, 0, 0, dp(10)) }
        val et = EditText(activity).apply { this.hint = "e.g. HockeyFan22"; isSingleLine = true; filters = arrayOf(android.text.InputFilter.LengthFilter(20)) }
        val err = TextView(activity).apply { setTextColor(live); setPadding(0, dp(6), 0, 0) }
        box.addView(hint); box.addView(et); box.addView(err)
        val dlg = AlertDialog.Builder(activity).setTitle("Choose your chat name").setView(box)
            .setPositiveButton("Save", null).setNegativeButton("Cancel") { _, _ -> if (cont.isActive) cont.resumeWith(Result.success(null)) }
            .setOnCancelListener { if (cont.isActive) cont.resumeWith(Result.success(null)) }.create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                activity.lifecycleScope.launch {
                    runCatching { api.setNick(et.text.toString()) }
                        .onSuccess { n -> dlg.dismiss(); if (cont.isActive) cont.resumeWith(Result.success(n)) }
                        .onFailure { err.text = it.message }
                }
            }
            et.requestFocus()
        }
        dlg.show()
    }

    // ------------------------------------------------------------------------------------------------ rooms
    /** The party's room, else the live game on this channel, else the channel's own room. */
    private suspend fun pickRoom(): Pair<String, String> {
        ChatApi.party(activity)?.let { return it to "Watch Party" }
        val id = PlayerManager.currentStreamId
        val ch = withContext(Dispatchers.IO) { runCatching { db.channelDao().getByStreamIds(listOf(id)).firstOrNull() }.getOrNull() }
        val name = ch?.name.orEmpty().replace(Regex("^[A-Z]{2,3}\\s*\\|\\s*"), "").trim()
        val now = withContext(Dispatchers.IO) {
            ch?.epgChannelId?.takeIf { it.isNotBlank() }?.let { e -> runCatching { db.epgDao().getNowByEpgChannelIdsChunked(listOf(e), System.currentTimeMillis()).firstOrNull()?.title }.getOrNull() }.orEmpty()
        }
        val game = liveGames().firstOrNull { g -> GameChannels.hits(ch?.name.orEmpty(), g) == 2 || GameChannels.hits(now, g) == 2 }
        if (game != null) {
            val espnId = JSONObject(game.json).optString("id")
            val lg = game.league.uppercase().filter { it.isLetterOrDigit() }.take(12)
            if (espnId.isNotBlank() && lg.length >= 2) return "g_${lg}_$espnId" to game.title
        }
        return "c_$id" to name.ifBlank { "Live Chat" }
    }

    private suspend fun enterRoom() {
        streamSeen = PlayerManager.currentStreamId
        val (r, t) = pickRoom()
        if (r == "c_0") { showStatus("Start a channel to chat about it."); return }
        runCatching { api.join(r, t) }.onSuccess { j ->
            if (room.isNotEmpty() && room != r) { detach(); api.leavePresence(room); api.leave(room) }
            room = j.optString("room"); roomTitle = j.optString("title").ifBlank { t }; isHost = j.optBoolean("host")
            nick = j.optString("nick").ifBlank { nick }
            seen.clear(); list.removeAllViews(); partyChannelSeen = 0; partyInfo = null
            title.text = if (room.startsWith("p_")) "Watch Party" else roomTitle
            chatHint.text = when {
                room.startsWith("p_") -> "Private chat - only the people in your Watch Party can see it."
                room.startsWith("g_") -> "Chat with everyone watching this game right now, on any channel."
                else -> "Chat with everyone watching this channel right now. Want to watch with friends? Open Watch Party."
            }
            emptyHint = text("No messages yet.\nSay hello, or press a reaction below.", 13f, textSub, 500, lines = 3).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(28), dp(8), 0) }
            list.addView(emptyHint, LinearLayout.LayoutParams(-1, -2))
            if (onPartyTab) renderParty()
            showStatus(null)
            updateWatching(1)
            attach()
            lastBeat = 0L; lastCount = 0L
        }.onFailure { e ->
            // a party this account left elsewhere: back to the normal chat
            if (room.startsWith("p_") || r.startsWith("p_")) { ChatApi.setParty(activity, null); room = ""; enterRoom() } else showStatus(e.message)
        }
    }

    private fun startLoop() {
        loop?.cancel()
        loop = activity.lifecycleScope.launch {
            while (isActive && isOpen) {
                // another channel without a party: its own chat
                if (PlayerManager.currentStreamId != streamSeen && streamSeen != 0) {
                    val id = PlayerManager.currentStreamId
                    streamSeen = id
                    if (room.startsWith("p_")) { if (isHost) hostSwitched(id) } else enterRoom()
                }
                // "I am here" every 45 s, the head count every 20 s (the lines themselves arrive by listener)
                val now = System.currentTimeMillis()
                if (room.isNotEmpty() && now - lastBeat > 45_000) { lastBeat = now; api.heartbeat(room, nick) }
                if (room.isNotEmpty() && now - lastCount > 20_000) { lastCount = now; updateWatching(api.watching(room)) }
                delay(2_000)
            }
        }
    }

    /** Live listeners on the room: lines the moment they are written, and the party document (code, members, the host's channel). */
    private fun attach() {
        detach()
        if (room.isEmpty()) return
        msgReg = api.listenMessages(room) { onMessages(it) }
        if (room.startsWith("p_")) roomReg = api.listenRoom(room) { r ->
            if (r != null) showParty(r)
            else if (room.startsWith("p_")) { ChatApi.setParty(activity, null); room = ""; activity.lifecycleScope.launch { enterRoom() } }
        }
    }

    private fun detach() { msgReg?.remove(); msgReg = null; roomReg?.remove(); roomReg = null }

    private fun updateWatching(n: Int) {
        sub.text = SpannableStringBuilder().apply {
            append("●  ", ForegroundColorSpan(live), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("${maxOf(n, 1)} in chat  ·  you are ")
            append(nick, StyleSpan(android.graphics.Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun onMessages(msgs: List<ChatApi.Msg>) {
        if (!isOpen) return
        val atBottom = scroll.getChildAt(0).height - scroll.scrollY - scroll.height < dp(80)
        val first = seen.isEmpty()
        var floated = 0
        if (msgs.isNotEmpty()) emptyHint?.let { list.removeView(it); emptyHint = null }
        msgs.filter { it.id !in seen }.forEach { m ->
            seen += m.id
            addMessage(m)
            // reactions from others float over the picture (a few at a time)
            if (m.kind == "r" && !first && m.nick != nick && floated < 4) { floatReaction(m.text); floated++ }
        }
        while (list.childCount > 150) list.removeViewAt(0)
        if (atBottom || first) scroll.post { scroll.scrollTo(0, list.height) }
    }

    private fun addMessage(m: ChatApi.Msg) {
        val v = if (m.kind == "s") text(m.text, 12f, textSub, 600, lines = 3).apply { gravity = Gravity.CENTER; setPadding(dp(6), dp(6), dp(6), dp(6)) }
        else text("", if (m.kind == "r") 18f else 14f, textMain, 500, lines = 6).apply {
            text = SpannableStringBuilder().apply {
                append(m.nick + "  ", ForegroundColorSpan(if (m.nick == nick) gold else accentSoft), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append(m.text)
            }
            setLineSpacing(0f, 1.15f); setPadding(dp(8), dp(5), dp(8), dp(5))
            // the remote can reach a message to report it
            val ring = GradientDrawable().apply { cornerRadius = dpf(8f); setColor(0x1FFFFFFF) }
            isFocusable = true; isClickable = true
            setOnFocusChangeListener { view, has -> view.background = if (has) ring else null }
            if (m.nick != nick) {
                setOnClickListener { report(m) }
                setOnLongClickListener { report(m); true }
            }
        }
        list.addView(v, LinearLayout.LayoutParams(-1, -2))
    }

    private fun report(m: ChatApi.Msg) {
        AlertDialog.Builder(activity).setTitle("Report this message?")
            .setMessage("${m.nick}: ${m.text}\n\nMessages reported by several viewers are hidden at once, and our team checks every report.")
            .setPositiveButton("Report") { _, _ -> activity.lifecycleScope.launch { runCatching { api.report(m.id) }; Toast.makeText(activity, "Thanks - reported.", Toast.LENGTH_SHORT).show() } }
            .setNegativeButton("Cancel", null).show()
    }

    /** A reaction rises over the picture and fades, like on YouTube. */
    private fun floatReaction(t: String) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val w = res.displayMetrics.widthPixels - panelW
        val v = text(t, if (t.length <= 2) 34f else 18f, Color.WHITE, 800).apply {
            setShadowLayer(dpf(8f), 0f, dpf(2f), 0xCC000000.toInt())
            if (t.length > 2) { background = shape(0x887C5CFF.toInt(), 16f); setPadding(dp(12), dp(6), dp(12), dp(6)) }
            elevation = 80f
        }
        val h = res.displayMetrics.heightPixels
        root.addView(v, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = (w * (0.70f + Math.random().toFloat() * 0.22f)).toInt() - dp(40); topMargin = (h * 0.78f).toInt()
        })
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(200).withEndAction {
            v.animate().translationY(-h * 0.45f).alpha(0f).setDuration(2600).setInterpolator(DecelerateInterpolator()).withEndAction { root.removeView(v) }.start()
        }.start()
    }

    private fun showStatus(t: String?) { status.text = t.orEmpty(); status.visibility = if (t.isNullOrBlank()) View.GONE else View.VISIBLE }

    // ------------------------------------------------------------------------------------------------ sending
    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        hideKeyboard()
        send(t, "m")
    }

    private fun send(t: String, kind: String) {
        if (room.isEmpty()) return
        activity.lifecycleScope.launch {
            runCatching { api.send(room, nick, t, kind) }
                .onSuccess { showStatus(null); if (kind == "r") floatReaction(t); scroll.post { scroll.scrollTo(0, list.height) } }
                .onFailure { showStatus(it.message) }
        }
    }

    private fun speak() {
        runCatching {
            voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your message"))
        }.onFailure { Toast.makeText(activity, "Voice typing is not available on this device.", Toast.LENGTH_SHORT).show() }
    }

    private fun hideKeyboard() { (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0) }

    /** "Type on phone": a QR that opens this chat on the phone for 2 hours, signed in as this account. */
    private fun phone() {
        if (room.isEmpty()) return
        activity.lifecycleScope.launch {
            val url = runCatching { api.pair(room) }.getOrElse { Toast.makeText(activity, it.message, Toast.LENGTH_LONG).show(); return@launch }
            val size = 600
            val m = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, size, size)
            val px = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
            val bmp = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
            val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(20), dp(10), dp(20), dp(6)) }
            box.addView(TextView(activity).apply { text = "Point your phone's camera at the code and open the link. You chat as $nick; the link works for 2 hours."; gravity = Gravity.CENTER })
            box.addView(ImageView(activity).apply { setImageBitmap(bmp); adjustViewBounds = true }, LinearLayout.LayoutParams(dp(220), dp(220)).apply { topMargin = dp(10) })
            AlertDialog.Builder(activity).setTitle("Type on your phone").setView(box).setPositiveButton("Done", null).show()
        }
    }

    // ------------------------------------------------------------------------------------------------ Watch Party
    private fun showParty(r: ChatApi.Room) {
        val firstInfo = partyInfo == null
        partyInfo = r; if (r.code.isNotBlank()) partyCode = r.code
        partyBar.visibility = if (onPartyTab) View.GONE else View.VISIBLE
        val who = if (r.members.size <= 3) r.members.joinToString(", ") else r.members.take(3).joinToString(", ") + " +${r.members.size - 3}"
        partyText.text = "Code ${r.code}  ·  Host ${r.host}\n$who" + if (!isHost && r.channelName.isNotBlank()) "  ·  on ${r.channelName}" else ""
        if (onPartyTab) { if (firstInfo) renderParty() else membersText?.text = membersLine(r) }
        // guests follow the host's channel (once per change)
        if (!isHost && r.channelId > 0 && r.channelId != partyChannelSeen) {
            val first = partyChannelSeen == 0
            partyChannelSeen = r.channelId
            if (r.channelId != PlayerManager.currentStreamId && ChatApi.followHost(activity) && (!first || PlayerManager.currentStreamId != r.channelId)) followTo(r.channelId, r.channelName)
        }
    }

    private suspend fun hostSwitched(id: Int) {
        val name = withContext(Dispatchers.IO) { runCatching { db.channelDao().getByStreamIds(listOf(id)).firstOrNull()?.name }.getOrNull() }.orEmpty()
        api.partyChannel(room, id, name.replace(Regex("^[A-Z]{2,3}\\s*\\|\\s*"), ""))
    }

    private fun followTo(id: Int, name: String) {
        activity.lifecycleScope.launch {
            val ch = withContext(Dispatchers.IO) { runCatching { db.channelDao().getByStreamIds(listOf(id)).firstOrNull() }.getOrNull() }
            if (ch == null) { showStatus("The host is watching $name - it is not in your package."); return@launch }
            val all = withContext(Dispatchers.IO) { runCatching { db.channelDao().getByCategory(ch.categoryId.orEmpty()) }.getOrDefault(emptyList()) }.ifEmpty { listOf(ch) }
            PlayerState.channels.clear(); PlayerState.channels.addAll(all.map { it.toLiveChannel() })
            PlayerState.currentPosition = all.indexOfFirst { it.streamId == id }.coerceAtLeast(0)
            streamSeen = id
            Toast.makeText(activity, "Following the host to $name", Toast.LENGTH_SHORT).show()
            (activity as? PlayerActivity)?.playSelectedChannel()
        }
    }

    // ------------------------------------------------------------------------------------------------ tabs
    private fun tab(label: String, hint: String, onClick: () -> Unit) = text(label, 14f, textMain, 800).apply {
        gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(10)); setOnClickListener { onClick() }; focusRing(this, 14f)
        help(this, hint)
    }

    private fun styleTabs() {
        tabChat.background = if (!onPartyTab) shape(accent, 14f) else shape(0x1FFFFFFF, 14f)
        tabParty.background = if (onPartyTab) shape(accent, 14f) else shape(0x1FFFFFFF, 14f)
    }

    private fun setTab(party: Boolean) {
        onPartyTab = party
        styleTabs()
        val chatVis = if (party) View.GONE else View.VISIBLE
        scroll.visibility = chatVis; reactsRow.visibility = chatVis; typeRow.visibility = chatVis; chatHint.visibility = chatVis
        status.visibility = if (party || status.text.isNullOrBlank()) View.GONE else View.VISIBLE
        partyView.visibility = if (party) View.VISIBLE else View.GONE
        partyBar.visibility = View.GONE
        if (party) { hideKeyboard(); renderParty() } else if (room.startsWith("p_")) partyBar.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------------------------------------ Watch Party
    private fun big(label: String, primary: Boolean, hint: String, onClick: () -> Unit) = text(label, 14f, textMain, 800, lines = 2).apply {
        gravity = Gravity.CENTER; setPadding(dp(14), dp(12), dp(14), dp(12)); background = shape(if (primary) accent else 0x1FFFFFFF, 14f)
        setOnClickListener { onClick() }; focusRing(this, 14f)
        help(this, hint)
    }

    private fun step(n: String, words: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, dp(5))
        addView(text(n, 13f, textMain, 800).apply { gravity = Gravity.CENTER; background = shape(0x337C5CFF, 14f) }, LinearLayout.LayoutParams(dp(28), dp(28)))
        addView(text(words, 13f, textMain, 500, lines = 3), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
    }

    private fun membersLine(r: ChatApi.Room) =
        if (r.members.isEmpty()) "Waiting for friends…" else "Watching: " + r.members.joinToString(", ")

    private fun renderParty(focusFirst: Boolean = false) {
        partyBox.removeAllViews(); membersText = null
        val inParty = room.startsWith("p_")
        fun gap(h: Int) { partyBox.addView(View(activity), LinearLayout.LayoutParams(-1, dp(h))) }
        if (!inParty) {
            partyBox.addView(text("Watch together with friends", 18f, textMain, 800, lines = 2))
            partyBox.addView(text("Everyone you invite watches the same channel at the same time and chats in a private room. When you change channel, their TVs follow yours.", 13f, textSub, 500, lines = 6).apply { setPadding(0, dp(6), 0, dp(10)) })
            partyBox.addView(step("1", "Press Start a Watch Party - you get a 6-letter code."))
            partyBox.addView(step("2", "Friends open this tab on their TV, press Join with a code and type it. Or you invite them by their chat name."))
            partyBox.addView(step("3", "Watch and chat together."))
            gap(12)
            val invites = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            partyBox.addView(invites)
            partyBox.addView(big("Start a Watch Party", true, "Creates a private room and gives you a 6-letter code to share") { startParty() }, LinearLayout.LayoutParams(-1, -2))
            gap(8)
            partyBox.addView(big("Join with a code", false, "Type the 6-letter code a friend shared with you") { askText("Join a Watch Party", "Party code (6 letters)") { c -> joinParty { api.partyJoinCode(c) } } }, LinearLayout.LayoutParams(-1, -2))
            activity.lifecycleScope.launch {
                val found = runCatching { api.me() }.getOrNull()?.optJSONArray("invites") ?: return@launch
                for (i in 0 until found.length()) {
                    val inv = found.getJSONObject(i)
                    val on = inv.optString("channel").takeIf { it.isNotBlank() }?.let { " (on $it)" }.orEmpty()
                    invites.addView(big("You're invited: join ${inv.optString("from")}'s party$on", true, "Join your friend's party right now") { joinParty { api.partyJoinRoom(inv.optString("room")) } },
                        LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                }
            }
        } else {
            partyBox.addView(text(if (isHost) "You are hosting" else "You joined ${partyInfo?.host.orEmpty().ifBlank { "a" }}'s party", 12f, textSub, 700))
            partyBox.addView(text("Your party code", 12f, textSub, 600).apply { setPadding(0, dp(8), 0, dp(4)) })
            partyBox.addView(text(partyCode.ifBlank { "······" }.uppercase().toCharArray().joinToString(" "), 30f, accentSoft, 800).apply {
                gravity = Gravity.CENTER; setPadding(dp(8), dp(14), dp(8), dp(14)); background = shape(0x337C5CFF, 14f, 0x557C5CFF)
            }, LinearLayout.LayoutParams(-1, -2))
            partyBox.addView(text("Friends: open Watch Party on their TV, press Join with a code, and type this code.", 12f, textSub, 500, lines = 3).apply { setPadding(0, dp(8), 0, dp(8)) })
            membersText = text(partyInfo?.let { membersLine(it) } ?: "Connecting…", 13f, textMain, 600, lines = 3)
            partyBox.addView(membersText)
            gap(12)
            partyBox.addView(big("Invite a friend by chat name", true, "Sends an invite - they see it in their chat on any TV") {
                askText("Invite a friend", "Their chat name") { n ->
                    activity.lifecycleScope.launch {
                        runCatching { api.invite(room, n) }
                            .onSuccess { Toast.makeText(activity, "Invite sent to $it", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(activity, it.message, Toast.LENGTH_LONG).show() }
                    }
                }
            }, LinearLayout.LayoutParams(-1, -2))
            if (!isHost) {
                gap(8)
                val follow = big("", false, "When ON, your TV changes channel together with the host") {}
                fun label() { follow.text = if (ChatApi.followHost(activity)) "My TV follows the host's channel: ON\n(press to turn off)" else "My TV follows the host's channel: OFF\n(press to turn on)" }
                label()
                follow.setOnClickListener { ChatApi.setFollowHost(activity, !ChatApi.followHost(activity)); label() }
                partyBox.addView(follow, LinearLayout.LayoutParams(-1, -2))
            } else {
                partyBox.addView(text("When you change channel, everyone's TV changes with you.", 12f, textSub, 500, lines = 2).apply { setPadding(0, dp(8), 0, 0) })
            }
            gap(8)
            partyBox.addView(big("Leave the Watch Party", false, "Goes back to the normal channel chat") { leaveParty() }, LinearLayout.LayoutParams(-1, -2))
        }
        if (focusFirst) partyBox.post { firstFocusable(partyBox)?.requestFocus() }
    }

    private fun firstFocusable(g: ViewGroup): View? {
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c.isFocusable && c.visibility == View.VISIBLE) return c
            if (c is ViewGroup) firstFocusable(c)?.let { return it }
        }
        return null
    }

    private fun startParty() {
        activity.lifecycleScope.launch {
            val id = PlayerManager.currentStreamId
            val name = withContext(Dispatchers.IO) { runCatching { db.channelDao().getByStreamIds(listOf(id)).firstOrNull()?.name }.getOrNull() }.orEmpty().replace(Regex("^[A-Z]{2,3}\\s*\\|\\s*"), "")
            runCatching { api.partyCreate(id, name) }.onSuccess { j ->
                ChatApi.setParty(activity, j.optString("room"))
                partyCode = j.optString("code"); partyInfo = null
                enterRoom()
                renderParty(true)
                Toast.makeText(activity, "Your Watch Party is on - share the code with your friends", Toast.LENGTH_LONG).show()
            }.onFailure { Toast.makeText(activity, it.message, Toast.LENGTH_LONG).show() }
        }
    }

    private fun joinParty(call: suspend () -> JSONObject) {
        activity.lifecycleScope.launch {
            runCatching { call() }.onSuccess { j ->
                ChatApi.setParty(activity, j.optString("room"))
                partyCode = j.optString("code").ifBlank { partyCode }; partyInfo = null
                enterRoom()
                renderParty(true)
                j.optJSONObject("channel")?.let { c -> if (c.optInt("id") != PlayerManager.currentStreamId && ChatApi.followHost(activity)) followTo(c.optInt("id"), c.optString("name")) }
            }.onFailure { Toast.makeText(activity, it.message, Toast.LENGTH_LONG).show() }
        }
    }

    private fun leaveParty() {
        val r = room
        ChatApi.setParty(activity, null)
        partyBar.visibility = View.GONE
        activity.lifecycleScope.launch { api.leave(r, party = true); room = ""; partyInfo = null; partyCode = ""; enterRoom(); if (onPartyTab) renderParty(true) }
    }

    private fun askText(t: String, hint: String, onOk: (String) -> Unit) {
        val et = EditText(activity).apply { this.hint = hint; isSingleLine = true }
        val box = FrameLayout(activity).apply { setPadding(dp(22), dp(8), dp(22), 0); addView(et) }
        AlertDialog.Builder(activity).setTitle(t).setView(box)
            .setPositiveButton("OK") { _, _ -> et.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk) }
            .setNegativeButton("Cancel", null).show()
        et.requestFocus()
    }

    // ------------------------------------------------------------------------------------------------ live games
    private suspend fun liveGames(): List<Game> {
        val now = System.currentTimeMillis()
        if (now - gamesAt > 120_000) {
            runCatching { Web24Api(activity).support("scores") }.onSuccess { r ->
                games = Web24Api.objects(r.optJSONArray("games")).filter { it.optString("state") == "in" }.mapNotNull { Game.parse(it) }
                gamesAt = now
            }
        }
        return games
    }

    companion object {
        private var games: List<Game> = emptyList()
        private var gamesAt = 0L
        /** Set when a Watch Party invite was accepted: the next player opens the chat by itself. */
        var openOnNextPlayer = false
    }
}
