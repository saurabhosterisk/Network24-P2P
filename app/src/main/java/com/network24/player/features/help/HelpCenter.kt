package com.network24.player.features.help

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.CircleCropTransformation
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.network24.player.R
import com.network24.player.common.models.AiMessage
import com.network24.player.common.models.SupportChannel
import com.network24.player.common.models.SupportMessage
import com.network24.player.core.database.repository.LiveHistoryRepository
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.features.dashboard.home.HomeFont
import com.network24.player.features.player.manager.PlayerManager
import com.network24.player.features.support.adapter.SupportMessageAdapter
import com.network24.player.features.support.repository.SupportRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Help Center: the support icon opens it right over the page (no new screen), in the app's look.
 *  - Community: the Discord support channels (unread dots, STAFF badges), writing here or on the phone (QR);
 *  - Answers: short how-tos for the app's features.
 */
class HelpCenter private constructor(private val act: AppCompatActivity) : Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    companion object {
        fun show(act: AppCompatActivity) = HelpCenter(act).show()
        private const val PREFS = "n24_help"
        private const val WEB = "https://play.web24.live/"
        // the most useful channels first; the rest follow in Discord's order
        private val TOP = listOf("announcements", "questions-and-help", "channel-down", "buffering-issues-fix", "channel-requests")
    }

    private val res = act.resources
    private val uiScale = res.displayMetrics.let { m -> minOf(1f, m.widthPixels / m.density / 960f, m.heightPixels / m.density / 400f) }
    private val d = res.displayMetrics.density * uiScale
    private fun dp(v: Int) = (v * d).toInt()
    private fun dpf(v: Float) = v * d
    private val isTv = act.packageManager.hasSystemFeature("android.software.leanback")
    private val prefs = PreferenceManager(act)
    private val repo = SupportRepository(prefs)

    private val surface = Color.parseColor("#14161B")
    private val line = Color.parseColor("#1FFFFFFF")
    private val textMain = Color.parseColor("#F2F3F5")
    private val textSub = Color.parseColor("#9BA1AD")
    private val accent = Color.parseColor("#7C5CFF")
    private val accentSoft = Color.parseColor("#A894FF")
    private val live = Color.parseColor("#E5484D")
    private val gold = Color.parseColor("#F5B841")
    private val good = Color.parseColor("#3DD68C")

    private var tab = "community"
    private lateinit var tabRow: LinearLayout
    private lateinit var content: FrameLayout
    private var poll: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        }
        setContentView(build())
        window?.decorView?.alpha = 0f
        window?.decorView?.animate()?.alpha(1f)?.setDuration(180)?.start()
        showTab("community")
        setOnDismissListener { poll?.cancel() }
    }

    // ------------------------------------------------------------------------------------------------ building blocks
    private fun text(s: CharSequence, size: Float, color: Int = textMain, weight: Int = 500, lines: Int = 1) = TextView(act).apply {
        text = s; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * d); setTextColor(color); typeface = HomeFont.of(act, weight)
        maxLines = lines; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
    }

    private fun shape(fill: Int, radius: Float, stroke: Int = 0) = GradientDrawable().apply { setColor(fill); cornerRadius = dpf(radius); if (stroke != 0) setStroke(dp(1), stroke) }

    private fun focusable(v: View, radius: Float, scale: Float = 1.04f, ring: Int = Color.WHITE) {
        v.isFocusable = true; v.isClickable = true
        val r = GradientDrawable().apply { cornerRadius = dpf(radius); setStroke(dp(2), ring); setColor(Color.TRANSPARENT) }
        v.setOnFocusChangeListener { view, has ->
            view.foreground = if (has) r else null
            view.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).setDuration(110).start()
        }
    }

    private fun pill(label: String, primary: Boolean = false, onClick: () -> Unit) = text(label, 13f, if (primary) Color.parseColor("#08090C") else textMain, 700).apply {
        gravity = Gravity.CENTER; setPadding(dp(16), dp(9), dp(16), dp(9))
        background = shape(if (primary) Color.WHITE else 0x1FFFFFFF, 18f)
        setOnClickListener { onClick() }; focusable(this, 18f, 1.06f, if (primary) accent else Color.WHITE)
    }

    private fun roundIcon(icon: Int, label: String, onClick: () -> Unit) = ImageView(act).apply {
        setImageResource(icon); setColorFilter(textMain); setPadding(dp(9), dp(9), dp(9), dp(9)); contentDescription = label
        background = shape(0x1AFFFFFF, 20f); setOnClickListener { onClick() }; focusable(this, 20f, 1.08f)
    }

    private fun card() = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#221E1A3A"), surface)).apply { cornerRadius = dpf(14f); setStroke(dp(1), line) }
    }

    // ------------------------------------------------------------------------------------------------ page
    private fun build(): View {
        val root = FrameLayout(act).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#FF1A1438"), Color.parseColor("#FF08090C"), Color.parseColor("#FF08090C")))
        }
        val page = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(48), dp(22), dp(48), dp(12)) }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        // phones: keep clear of the status / navigation bars (the close button sat under the navigation bar)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val b = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            page.setPadding(dp(48) + b.left, dp(22) + b.top, dp(48) + b.right, dp(12) + b.bottom)
            ins
        }

        // title
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val words = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        words.addView(text("Help Center", 24f, textMain, 800))
        words.addView(text("Ask our team and the community, or read a quick answer.", 13f, textSub, 600).apply { setPadding(0, dp(5), 0, 0) })
        head.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(roundIcon(R.drawable.ic_cu_close, "Close help") { dismiss() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        page.addView(head)

        // tabs
        tabRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        page.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; setPadding(dp(14), dp(10), dp(14), dp(8)); addView(tabRow) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); marginStart = -dp(14) })
        content = FrameLayout(act)
        page.addView(content, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4) })
        return root
    }

    // ------------------------------------------------------------------------------------------------ tabs
    private fun showTab(t: String) {
        tab = t
        poll?.cancel()
        tabRow.removeAllViews()
        listOf("community" to "💬  Community", "answers" to "❓  Answers").forEach { (k, label) ->
            val on = k == tab
            tabRow.addView(text(label, 14f, if (on) Color.parseColor("#08090C") else textMain, 700).apply {
                setPadding(dp(18), dp(9), dp(18), dp(9)); background = shape(if (on) Color.WHITE else 0x1AFFFFFF, 18f); this.tag = "tab:$k"
                setOnClickListener { if (tab != k) showTab(k) }
                focusable(this, 18f, 1.05f, if (on) accent else Color.WHITE)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        // Remote Help: support sees the screen and presses the remote for the customer (core/remote)
        val helping = com.network24.player.core.remote.HelpSession.state.let { it == "live" || it == "waiting" }
        tabRow.addView(text(if (helping) "■  Stop remote help" else "🎧  Get remote help", 14f, textMain, 700).apply {
            setPadding(dp(18), dp(9), dp(18), dp(9)); background = shape(if (helping) 0x40E5484D else 0x403DD68C, 18f); this.tag = "tab:remote"
            setOnClickListener { dismiss(); if (helping) com.network24.player.core.remote.HelpSession.stop(act) else com.network24.player.core.remote.HelpSession.request(act) }
            focusable(this, 18f, 1.05f)
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        content.removeAllViews()
        when (t) {
            "community" -> communityView()
            else -> answersView()
        }
        // the tab keeps the focus (it landed on the close button, so RIGHT + OK closed the help)
        tabRow.postDelayed({ tabRow.findViewWithTag<View>("tab:$tab")?.requestFocus() }, 120)
    }

    // ------------------------------------------------------------------------------------------------ community
    private var channels: List<SupportChannel> = emptyList()
    private var current: SupportChannel? = null
    private lateinit var chanList: LinearLayout
    private lateinit var msgs: LinearLayout
    private lateinit var msgScroll: ScrollView
    private lateinit var chanTitle: TextView
    private lateinit var composer: LinearLayout
    private lateinit var input: EditText
    private var shownIds = HashSet<String>()
    // newest message shown so far: a refresh adds only messages newer than this (it used to add the 35 older ones
    // of the page too, and the 15-message limit then dropped the newest)
    private var newestShown: java.math.BigInteger? = null

    private fun seenPrefs() = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun seen(id: String) = seenPrefs().getString("seen:$id", null)
    private fun markSeen(c: SupportChannel) { c.lastMessageId?.let { seenPrefs().edit().putString("seen:${c.id}", it).apply() } }
    private fun unread(c: SupportChannel): Boolean {
        val last = c.lastMessageId ?: return false
        val s = seen(c.id) ?: run { seenPrefs().edit().putString("seen:${c.id}", last).apply(); return false }
        return (last.toBigIntegerOrNull() ?: return false) > (s.toBigIntegerOrNull() ?: return false)
    }

    private fun communityView() {
        val box = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        chanList = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        box.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(22)); addView(chanList) }, LinearLayout.LayoutParams(dp(250), -1))
        val right = card()
        chanTitle = text("", 15f, textMain, 800)
        right.addView(chanTitle)
        msgs = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        msgScroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(msgs); isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(20)) }
        // newest first, like a feed: the message box on top, the newest message right under it, older ones below.
        // Nothing depends on scrolling to the end any more (pictures and long texts finishing their layout later
        // pushed the newest messages out of sight)
        composer = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(act).apply {
            setHintTextColor(textSub); setTextColor(textMain); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 14f * d); typeface = HomeFont.of(act, 500)
            background = shape(Color.parseColor("#1A1C23"), 20f, line); setPadding(dp(14), dp(9), dp(14), dp(9)); isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
            // the box itself shows the remote's focus (only a blinking cursor showed before)
            setOnFocusChangeListener { v, has -> v.background = shape(Color.parseColor("#1A1C23"), 20f, if (has) accent else line).apply { if (has) setStroke(dp(2), accent) } }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        composer.addView(roundIcon(R.drawable.ic_phone_type, "Write on your phone") { qr("Write on your phone", "Open this on your phone, sign in and tap Help - typing is easier there.", WEB) },
            LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginStart = dp(6) })
        composer.addView(pill("Send", primary = true) { send() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        right.addView(composer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        right.addView(msgScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
        box.addView(right, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(14) })
        content.addView(box, FrameLayout.LayoutParams(-1, -1))

        act.lifecycleScope.launch {
            repo.channels().onSuccess { list ->
                channels = list.sortedBy { c -> TOP.indexOfFirst { c.name.contains(it, true) }.let { if (it < 0) 99 else it } }
                drawChannels()
                val last = seenPrefs().getString("last", null)
                (channels.firstOrNull { it.id == last } ?: channels.firstOrNull())?.let { open(it) }
            }.onFailure { chanTitle.text = it.message }
        }
        var n = 0
        poll = act.lifecycleScope.launch {
            while (isActive) {
                delay(4_000)
                current?.let { refresh(it) }
                if (++n % 2 == 0) repo.channels().onSuccess { list -> channels = channels.map { c -> list.firstOrNull { it.id == c.id } ?: c }; drawChannels() }
            }
        }
    }

    private fun drawChannels() {
        val had = chanList.findFocus()?.tag
        chanList.removeAllViews()
        channels.forEach { c ->
            val on = c.id == current?.id
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(12), dp(10)); tag = "ch:${c.id}"
                background = if (on) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#3A2C7A"), Color.parseColor("#1E1A3A"))).apply { cornerRadius = dpf(12f); setStroke(dp(1), 0x667C5CFF) } else null
                setOnClickListener { open(c) }; focusable(this, 12f, 1.0f)  // no zoom: a bigger row was cut by the list edges
            }
            val u = unread(c) && !on
            row.addView(text("# " + c.name, 14f, if (on || u) textMain else textSub, if (u || on) 800 else 600), LinearLayout.LayoutParams(0, -2, 1f))
            if (u) row.addView(View(act).apply { background = shape(accent, 4f) }, LinearLayout.LayoutParams(dp(8), dp(8)))
            chanList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(3) })
        }
        if (had != null) chanList.findViewWithTag<View>(had)?.requestFocus()
    }

    private fun open(c: SupportChannel) {
        current = c
        seenPrefs().edit().putString("last", c.id).apply()
        chanTitle.text = "# " + c.name
        composer.visibility = if (c.writable) View.VISIBLE else View.GONE
        input.hint = "Message #" + c.name
        msgs.removeAllViews(); shownIds.clear(); newestShown = null
        drawChannels()
        // the chosen channel is shown in the list (it can sit far down)
        chanList.post { chanList.findViewWithTag<View>("ch:${c.id}")?.let { v -> (chanList.parent as? ScrollView)?.smoothScrollTo(0, (v.top - dp(60)).coerceAtLeast(0)) } }
        act.lifecycleScope.launch { refresh(c) }
    }

    private suspend fun refresh(c: SupportChannel) {
        val page = repo.latest(c.id).getOrNull() ?: return
        if (current?.id != c.id) return
        // the 15 most recent; afterwards only messages newer than the newest one shown, each put on top
        val fresh = if (newestShown == null) page.messages.takeLast(15) else page.messages.filter { (it.id.toBigIntegerOrNull() ?: return@filter false) > newestShown!! }
        val atTop = msgScroll.scrollY < dp(40)
        fresh.forEach { m -> shownIds += m.id; msgs.addView(messageView(m), 0); m.id.toBigIntegerOrNull()?.let { id -> if (newestShown == null || id > newestShown!!) newestShown = id } }
        while (msgs.childCount > 15) msgs.removeViewAt(msgs.childCount - 1)
        markSeen(c.copy(lastMessageId = page.messages.lastOrNull()?.id ?: c.lastMessageId))
        if (fresh.isNotEmpty() && atTop) msgScroll.post { msgScroll.scrollTo(0, 0) }
    }

    private fun messageView(m: SupportMessage) = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(8), dp(4), dp(8))
        addView(ImageView(act).apply { load(m.author.avatar?.takeIf { it.isNotBlank() }) { transformations(CircleCropTransformation()); placeholder(R.drawable.app_logo); error(R.drawable.app_logo) } },
            LinearLayout.LayoutParams(dp(34), dp(34)))
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text(m.author.name.orEmpty(), 13f, if (m.author.staff) accentSoft else textMain, 800))
        if (m.author.staff) top.addView(text("STAFF", 9f, Color.WHITE, 800).apply { setPadding(dp(6), dp(2), dp(6), dp(2)); background = shape(accent, 4f) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        top.addView(text(SupportMessageAdapter.friendlyTime(m.time), 11f, textSub, 600).apply { setPadding(dp(8), 0, 0, 0) })
        col.addView(top)
        m.reply?.text?.takeIf { it.isNotBlank() }?.let { col.addView(text("↪ ${m.reply.author.orEmpty()}: $it", 11f, textSub, 500).apply { setPadding(0, dp(4), 0, 0) }) }
        m.text?.takeIf { it.isNotBlank() }?.let { col.addView(text(SupportMessageAdapter.styled(it), 14f, textMain, 500, lines = 30).apply { setPadding(0, dp(4), 0, 0); setLineSpacing(0f, 1.15f) }) }
        m.attachments.orEmpty().filter { it.isImage && !it.url.isNullOrBlank() }.take(2).forEach { a ->
            col.addView(ImageView(act).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START; load(a.url) }, LinearLayout.LayoutParams(dp(260), -2).apply { topMargin = dp(6) })
        }
        // link previews and files (posts with only these showed as empty messages)
        m.embeds.orEmpty().filter { !it.title.isNullOrBlank() || !it.description.isNullOrBlank() }.take(2).forEach { e ->
            val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); background = shape(0x14FFFFFF, 8f); }
            e.title?.takeIf { it.isNotBlank() }?.let { box.addView(text(it, 13f, accentSoft, 700, lines = 2)) }
            e.description?.takeIf { it.isNotBlank() }?.let { box.addView(text(SupportMessageAdapter.styled(it), 12f, textSub, 500, lines = 4).apply { setPadding(0, dp(4), 0, 0) }) }
            e.image?.takeIf { it.isNotBlank() }?.let { box.addView(ImageView(act).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START; load(it) }, LinearLayout.LayoutParams(dp(240), -2).apply { topMargin = dp(6) }) }
            col.addView(box, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        }
        m.attachments.orEmpty().filter { !it.isImage }.take(3).forEach { a -> col.addView(text((if (a.type?.startsWith("video/") == true) "🎬  " else if (a.type?.contains("pdf") == true) "📄  " else "📎  ") + (a.name ?: "File"), 13f, textSub, 600).apply { setPadding(0, dp(5), 0, 0) }) }
        addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        // the remote can scroll through the messages
        isFocusable = isTv
        val r = GradientDrawable().apply { cornerRadius = dpf(10f); setColor(0x1F7C5CFF); setStroke(dp(1), 0x997C5CFF.toInt()) }
        setOnFocusChangeListener { v, has -> v.background = if (has) r else null }
    }

    private fun send() {
        val c = current ?: return
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        act.lifecycleScope.launch {
            repo.send(act, c.id, t, null, null).onSuccess { refresh(c) }.onFailure { Toast.makeText(act, it.message, Toast.LENGTH_LONG).show() }
        }
    }

    // ------------------------------------------------------------------------------------------------ answers
    private val faq = listOf(
        "How do I watch a channel?" to "Open Live TV, pick a category on the left and a channel on the right. The channel plays in the preview above - press OK for full screen. In the full-screen player, UP / DOWN change the channel.",
        "How does Catch-up work?" to "Catch-up has shows from the last days on recorded channels. Open Catch-up, pick a show and press Watch from start. You can also find them in the TV Guide (aired shows marked ▶ Catch-up) and in Search.",
        "How do I set a reminder?" to "In the TV Guide or in Search, select a show that has not started and press OK. You get a notice 2 minutes before it starts.",
        "What is MultiView?" to "MultiView plays several channels at once (as many as your plan allows). Press MultiView on Home, or hold OK on a channel in Live TV and choose Watch in MultiView.",
        "How do I chat during a game?" to "While a channel plays full screen, show the controls and press the chat button. Everyone watching the same game chats together. For a private chat with friends, start a Watch Party in the chat and share its code.",
        "Where are live games and scores?" to "Open Sports: live games first, then today and the coming days. Select a game for the score, stats and the channels showing it. Follow your teams in My Teams to get an alert before each game.",
        "How many devices can I use?" to "Your plan allows ${prefs.getMaxConnections()} screen${if (prefs.getMaxConnections() == 1) "" else "s"} at the same time. Stop watching on one device to free a screen for another.",
        "A channel is not working - what do I do?" to "Tell us in Help Center → Community → #channel-down with the channel name. Our team checks it and answers there - usually within minutes.",
    )

    private fun answersView() {
        // side padding: a focused (slightly bigger) answer is not cut by the scroll edges
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(6), dp(14), dp(20)) }
        faq.forEach { (q, a) ->
            val item = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); background = shape(surface, 12f, line) }
            val qv = text("›  $q", 15f, textMain, 700)
            val av = text(a, 14f, Color.parseColor("#D9F2F3F5"), 500, lines = 10).apply { setPadding(dp(18), dp(8), 0, 0); setLineSpacing(0f, 1.2f); visibility = View.GONE }
            item.addView(qv); item.addView(av)
            item.setOnClickListener {
                val open = av.visibility != View.VISIBLE
                av.visibility = if (open) View.VISIBLE else View.GONE
                qv.text = (if (open) "▾  " else "›  ") + q
                qv.setTextColor(if (open) accentSoft else textMain)
            }
            focusable(item, 12f, 1.02f)
            list.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        content.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; addView(list) }, FrameLayout.LayoutParams(-1, -1))
    }

    // ------------------------------------------------------------------------------------------------ helpers
    private fun qr(title: String, hint: String, url: String) {
        val size = 600
        val m = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, size, size)
        val px = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
        val bmp = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(20), dp(10), dp(20), dp(6)) }
        box.addView(TextView(act).apply { text = hint; gravity = Gravity.CENTER })
        box.addView(ImageView(act).apply { setImageBitmap(bmp); adjustViewBounds = true }, LinearLayout.LayoutParams(dp(220), dp(220)).apply { topMargin = dp(10) })
        box.addView(TextView(act).apply { text = url; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
        AlertDialog.Builder(act).setTitle(title).setView(box).setPositiveButton("Done", null)
            .apply { if (!isTv) setNeutralButton("Open here") { _, _ -> runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } } }.show()
    }

    /**
     * The remote's way, set by hand: tabs DOWN = the open channel; channel list RIGHT = the message box (it went to
     * a random message), first channel UP = the tab; message box DOWN = the newest message (older ones below),
     * newest message UP = the message box; LEFT anywhere on the right = back to the open channel.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val f = currentFocus
        if (event.action != KeyEvent.ACTION_DOWN || f == null) return super.dispatchKeyEvent(event)
        val k = event.keyCode
        val onTab = (f.tag as? String)?.startsWith("tab:") == true
        if (onTab && k == KeyEvent.KEYCODE_DPAD_DOWN) {
            val target = when (tab) { "community" -> currentRow(); else -> firstFocusable(content) }
            if (target?.requestFocus() == true) return true
        }
        if (tab != "community" || !::chanList.isInitialized) return super.dispatchKeyEvent(event)
        val inList = isInside(f, chanList)
        val inMsgs = isInside(f, msgs)
        val onInput = f === input
        val inComposer = isInside(f, composer)
        when {
            inList && k == KeyEvent.KEYCODE_DPAD_RIGHT -> { (if (composer.visibility == View.VISIBLE) input else firstMessage())?.requestFocus(); return true }
            inList && k == KeyEvent.KEYCODE_DPAD_LEFT -> return true
            inList && k == KeyEvent.KEYCODE_DPAD_UP && f === chanList.getChildAt(0) -> { tabRow.findViewWithTag<View>("tab:$tab")?.requestFocus(); return true }
            (inMsgs || (onInput && input.text.isEmpty())) && k == KeyEvent.KEYCODE_DPAD_LEFT -> { currentRow()?.requestFocus(); return true }
            inComposer && !onInput && k == KeyEvent.KEYCODE_DPAD_LEFT && f === composer.getChildAt(1) -> { input.requestFocus(); return true }
            inComposer && k == KeyEvent.KEYCODE_DPAD_UP -> { tabRow.findViewWithTag<View>("tab:$tab")?.requestFocus(); return true }
            inComposer && k == KeyEvent.KEYCODE_DPAD_DOWN -> { firstMessage()?.let { it.requestFocus(); msgScroll.scrollTo(0, 0) }; return true }
            inMsgs && k == KeyEvent.KEYCODE_DPAD_UP && f === firstMessage() -> { (if (composer.visibility == View.VISIBLE) input else tabRow.findViewWithTag<View>("tab:$tab"))?.requestFocus(); return true }
            inMsgs && k == KeyEvent.KEYCODE_DPAD_DOWN && f === lastMessage() -> return true
            inMsgs && k == KeyEvent.KEYCODE_DPAD_RIGHT -> return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun currentRow(): View? = chanList.findViewWithTag("ch:${current?.id}") ?: chanList.getChildAt(0)
    private fun lastMessage(): View? = if (msgs.childCount > 0) msgs.getChildAt(msgs.childCount - 1) else null
    private fun firstMessage(): View? = if (msgs.childCount > 0) msgs.getChildAt(0) else null
    private fun isInside(v: View, group: View): Boolean { var p: Any? = v; while (p is View) { if (p === group) return true; p = p.parent }; return false }

    private fun firstFocusable(v: View): View? {
        if (v.isFocusable && v.visibility == View.VISIBLE && v !is ViewGroup) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
        return if (v.isFocusable) v else null
    }
}
