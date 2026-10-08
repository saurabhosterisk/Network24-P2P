package com.network24.player.features.search

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.network24.player.R
import com.network24.player.core.api.Web24Api
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.entity.CategoryType
import com.network24.player.core.database.entity.ChannelEntity
import com.network24.player.core.database.entity.EpgEntity
import com.network24.player.core.parental.ParentalLock
import com.network24.player.features.catchup.CatchupPlayerActivity
import com.network24.player.features.dashboard.home.HomeFont
import com.network24.player.features.discover.ChannelLauncher
import com.network24.player.features.discover.EventCenter
import com.network24.player.features.discover.Fmt
import com.network24.player.features.discover.GameCenter
import com.network24.player.features.reminders.Reminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Search right where the customer is: the search icon opens a dark glass layer over the page (no new screen) and
 * results come in while typing - channels, shows on now, sports, shows coming up and shows to watch in Catch-up,
 * the matching letters in violet. On a TV the layer has its own keyboard on the left (the system keyboard covered
 * half the screen); phones use their own keyboard (and the microphone). Empty box: recent searches and what most
 * viewers are watching now. Locked categories are never searched.
 */
class SearchOverlay private constructor(private val act: AppCompatActivity, private val initial: String? = null, private val onClose: (() -> Unit)? = null) : Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    companion object {
        fun show(act: AppCompatActivity, query: String? = null, onClose: (() -> Unit)? = null) = SearchOverlay(act, query, onClose).show()
        private const val PREFS = "n24_search"
    }

    private val res = act.resources
    private val uiScale = res.displayMetrics.let { m -> minOf(1f, m.widthPixels / m.density / 960f, m.heightPixels / m.density / 400f) }
    private val d = res.displayMetrics.density * uiScale
    private fun dp(v: Int) = (v * d).toInt()
    private fun dpf(v: Float) = v * d
    private val isTv = act.packageManager.hasSystemFeature("android.software.leanback")
    private val db = DatabaseProvider.get(act)

    private val bg = Color.parseColor("#08090C")
    private val surface = Color.parseColor("#14161B")
    private val line = Color.parseColor("#1FFFFFFF")
    private val textMain = Color.parseColor("#F2F3F5")
    private val textSub = Color.parseColor("#9BA1AD")
    private val accent = Color.parseColor("#7C5CFF")
    private val accentSoft = Color.parseColor("#A894FF")
    private val live = Color.parseColor("#E5484D")
    private val gold = Color.parseColor("#F5B841")

    /** One line of the results: a section title, or something to open. */
    private class Hit(
        val header: String? = null, val title: String = "", val sub: String = "", val badge: String = "", val badgeColor: Int = 0,
        val icon: String? = null, val glyph: String = "", val progress: Int = -1, val key: String = "", val onClick: (() -> Unit)? = null,
    )

    private var query = ""
    private var hits: List<Hit> = emptyList()
    private var job: Job? = null
    private lateinit var queryView: TextView
    private var editText: EditText? = null
    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private var keyboard: LinearLayout? = null
    private var lastKey: View? = null
    private val adapter = HitAdapter()

    // what is searched (read once when the layer opens)
    private var channels: List<ChannelEntity> = emptyList()
    private var byGuide: Map<String, List<ChannelEntity>> = emptyMap()
    private var catNames: Map<String, String> = emptyMap()
    private var sports: List<JSONObject> = emptyList()
    private var leagueNames: Map<String, String> = emptyMap()
    private var trending: List<Hit> = emptyList()
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setSoftInputMode(if (isTv) WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        setContentView(build())
        window?.decorView?.alpha = 0f
        window?.decorView?.animate()?.alpha(1f)?.setDuration(180)?.start()
        act.lifecycleScope.launch { loadData(); render() }
    }

    // ------------------------------------------------------------------------------------------------ building
    private fun text(s: CharSequence, size: Float, color: Int = textMain, weight: Int = 500, lines: Int = 1) = TextView(act).apply {
        text = s; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * d); setTextColor(color); typeface = HomeFont.of(act, weight)
        maxLines = lines; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
    }

    private fun shape(fill: Int, radius: Float, stroke: Int = 0) = GradientDrawable().apply { setColor(fill); cornerRadius = dpf(radius); if (stroke != 0) setStroke(dp(1), stroke) }

    private fun focusable(v: View, radius: Float, scale: Float = 1.06f, ring: Int = Color.WHITE) {
        v.isFocusable = true; v.isClickable = true
        val r = GradientDrawable().apply { cornerRadius = dpf(radius); setStroke(dp(2), ring); setColor(Color.TRANSPARENT) }
        v.setOnFocusChangeListener { view, has ->
            view.foreground = if (has) r else null
            view.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).setDuration(110).start()
        }
    }

    private fun build(): View {
        val root = FrameLayout(act).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#FF1A1438"), Color.parseColor("#FF08090C"), Color.parseColor("#FF08090C")))
        }
        val page = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(48), dp(26), dp(48), 0) }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        // phones: keep clear of the status / navigation bars (the close button sat under the navigation bar)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val b = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            page.setPadding(dp(48) + b.left, dp(26) + b.top, dp(48) + b.right, 0 + b.bottom)
            ins
        }

        // the search line: magnifier, what is typed, microphone (phones), close
        val head = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(4), dp(8), dp(4))
            background = shape(0x14FFFFFF, 26f, 0x337C5CFF)
        }
        head.addView(ImageView(act).apply { setImageResource(R.drawable.ic_h_search); setColorFilter(accentSoft) }, LinearLayout.LayoutParams(dp(24), dp(24)))
        if (isTv) {
            queryView = text("", 22f, textMain, 700).apply { setPadding(dp(14), dp(12), dp(8), dp(12)) }
            head.addView(queryView, LinearLayout.LayoutParams(0, -2, 1f))
            // a real (invisible) text field, so the system keyboard - Gboard or the Fire TV keyboard, with the remote's
            // voice key - types into the app's own search; testers wanted a mic that never leaves the app
            val et = EditText(act).apply {
                alpha = 0f; isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEARCH; inputType = android.text.InputType.TYPE_CLASS_TEXT
                isFocusable = false; isFocusableInTouchMode = false
                addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) { if (hasFocus()) setQuery(s?.toString().orEmpty(), fromField = true) }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                })
                setOnEditorActionListener { _, _, _ -> leaveVoice(); true }
            }
            editText = et
            head.addView(et, LinearLayout.LayoutParams(1, 1))
            head.addView(roundIcon(R.drawable.ic_mic_chat, "Speak") { voiceKeyboard() }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) })
        } else {
            val et = EditText(act).apply {
                hint = "Search channels, shows, teams…"; setHintTextColor(textSub); setTextColor(textMain); background = null
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 18f * d); typeface = HomeFont.of(act, 600); isSingleLine = true
                imeOptions = EditorInfo.IME_ACTION_SEARCH; setPadding(dp(14), dp(10), dp(8), dp(10))
                addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) { setQuery(s?.toString().orEmpty(), fromField = true) }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                })
                setOnEditorActionListener { v, id, _ -> if (id == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(v); true } else false }
            }
            editText = et
            queryView = et
            head.addView(et, LinearLayout.LayoutParams(0, -2, 1f))
            val canSpeak = act.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
            if (canSpeak) head.addView(roundIcon(R.drawable.ic_mic_chat, "Speak") { speak() }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) })
        }
        head.addView(roundIcon(R.drawable.ic_cu_close, "Close search") { dismiss() }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) })
        page.addView(head, LinearLayout.LayoutParams(-1, -2))

        status = text("", 12f, textSub, 600).apply { setPadding(dp(6), dp(10), 0, dp(4)) }
        page.addView(status)

        val body = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        if (isTv) {
            val kb = keyboardView()
            keyboard = kb
            body.addView(kb, LinearLayout.LayoutParams(dp(300), -2).apply { topMargin = dp(6) })
        }
        list = RecyclerView(act).apply {
            layoutManager = LinearLayoutManager(act); adapter = this@SearchOverlay.adapter; itemAnimator = null
            clipToPadding = false; setPadding(dp(if (isTv) 26 else 0), dp(4), dp(6), dp(30)); isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(24)); descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS; isFocusable = false
        }
        body.addView(list, LinearLayout.LayoutParams(0, -1, 1f))
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun roundIcon(icon: Int, label: String, onClick: () -> Unit) = ImageView(act).apply {
        setImageResource(icon); setColorFilter(textMain); setPadding(dp(9), dp(9), dp(9), dp(9)); contentDescription = label
        background = shape(0x1AFFFFFF, 20f); setOnClickListener { onClick() }; focusable(this, 20f, 1.08f)
    }

    /** TV: letters and numbers to pick with the remote, Space, Delete and Clear. */
    private fun keyboardView() = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10)); background = shape(0x0FFFFFFF, 18f, line)
        val rows = listOf("abcdefg", "hijklmn", "opqrstu", "vwxyz12", "3456789")
        rows.forEach { r ->
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            r.forEach { c -> row.addView(key(c.uppercase()) { type(c.toString()) }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }) }
            addView(row)
        }
        val last = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        last.addView(key("0") { type("0") }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        last.addView(key("Space") { type(" ") }, LinearLayout.LayoutParams(0, dp(38), 2.4f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        last.addView(key("⌫") { setQuery(query.dropLast(1)) }, LinearLayout.LayoutParams(0, dp(38), 1.3f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        last.addView(key("Clear") { setQuery("") }, LinearLayout.LayoutParams(0, dp(38), 2.3f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        addView(last)
        addView(text("Results appear as you type. Press ► to go to them.", 11f, textSub, 600, lines = 2).apply { setPadding(dp(4), dp(10), dp(4), 0) })
    }

    private fun key(label: String, onClick: () -> Unit) = text(label, 15f, textMain, 700).apply {
        gravity = Gravity.CENTER; background = shape(0x14FFFFFF, 9f)
        setOnClickListener { lastKey = this; onClick() }
        focusable(this, 9f, 1.12f, accent)
        setOnFocusChangeListener { v, has ->
            v.background = shape(if (has) accent else 0x14FFFFFF, 9f)
            v.animate().scaleX(if (has) 1.12f else 1f).scaleY(if (has) 1.12f else 1f).setDuration(100).start()
            if (has) lastKey = v
        }
    }

    // ------------------------------------------------------------------------------------------------ typing
    private fun type(s: String) { if (query.length < 40) setQuery(query + s) }

    private fun setQuery(q: String, fromField: Boolean = false) {
        query = q
        if (isTv) queryView.text = if (q.isEmpty()) SpannableStringBuilder().apply { append("Search channels, shows, teams…", ForegroundColorSpan(textSub), 0) } else "$q▏"
        else if (!fromField) editText?.setText(q)
        job?.cancel()
        job = act.lifecycleScope.launch { delay(if (q.isBlank()) 0 else 220); render() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isTv && event.action == KeyEvent.ACTION_DOWN && editText?.hasFocus() == true
            && event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK)) { leaveVoice(); return true }
        val f = currentFocus
        if (isTv && event.action == KeyEvent.ACTION_DOWN && f != null) {
            val inKb = keyboard?.let { isInside(f, it) } == true
            val inList = isInside(f, list)
            when {
                // a remote with a keyboard / number keys types straight into the search
                event.unicodeChar in 'a'.code..'z'.code || event.unicodeChar in 'A'.code..'Z'.code || event.unicodeChar in '0'.code..'9'.code || event.unicodeChar == ' '.code -> {
                    type(event.unicodeChar.toChar().lowercase()); return true
                }
                event.keyCode == KeyEvent.KEYCODE_DEL -> { setQuery(query.dropLast(1)); return true }
                inKb && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && f.parent is LinearLayout && (f.parent as LinearLayout).let { it.indexOfChild(f) == it.childCount - 1 } -> {
                    firstResult()?.requestFocus(); return true
                }
                inList && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> { (lastKey ?: keyboard?.let { firstKey(it) })?.requestFocus(); return true }
                inList && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun firstKey(kb: LinearLayout): View? = ((kb.getChildAt(0) as? LinearLayout)?.getChildAt(0))
    private fun firstResult(): View? = (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.isFocusable }

    private fun isInside(v: View, group: View): Boolean {
        var p: Any? = v
        while (p is View) { if (p === group) return true; p = p.parent }
        return false
    }

    override fun onStart() {
        super.onStart()
        if (isTv) keyboard?.post { keyboard?.let { firstKey(it) }?.requestFocus() } else editText?.requestFocus()
        setQuery(initial.orEmpty())
        setOnDismissListener { editText?.let { hideKeyboard(it) }; onClose?.invoke() }
    }

    private fun hideKeyboard(v: View) { (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0) }

    /** The mic on a TV: the system keyboard with its voice key, inside the app (no global Alexa / Assistant search). */
    private fun voiceKeyboard() {
        val et = editText ?: return
        et.isFocusable = true; et.isFocusableInTouchMode = true
        et.setText(query); et.setSelection(et.length())
        et.requestFocus()
        (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(et, InputMethodManager.SHOW_FORCED)
        status.text = "Press the microphone button on your remote and say a channel, a show or a team."
    }

    private fun leaveVoice() {
        val et = editText ?: return
        hideKeyboard(et)
        et.clearFocus()
        et.isFocusable = false; et.isFocusableInTouchMode = false
        // the keyboard goes away a moment later; only then can the results take the focus
        list.postDelayed({ (firstResult() ?: keyboard?.let { firstKey(it) })?.requestFocus() }, 200)
    }

    private fun speak() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        // a one-off result hook (the layer is a dialog, not an activity)
        var launcher: androidx.activity.result.ActivityResultLauncher<Intent>? = null
        launcher = act.activityResultRegistry.register("n24_search_voice", androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { setQuery(it) }
            launcher?.unregister()
        }
        runCatching { launcher.launch(i) }.onFailure { launcher.unregister(); Toast.makeText(act, "Voice search is not available on this device.", Toast.LENGTH_SHORT).show() }
    }

    // ------------------------------------------------------------------------------------------------ data
    private val prefix = Regex("^[A-Z]{2,3}\\s*\\|\\s*")
    private fun clean(n: String?) = n.orEmpty().replace(prefix, "").trim()
    private val marks = Regex("[ʰ-˿ᴀ-ᶿ⁰-₟]+")
    private fun cleanTitle(t: String?) = t.orEmpty().replace(marks, "").replace(Regex("\\s+"), " ").trim()

    private suspend fun loadData() {
        withContext(Dispatchers.IO) {
            val locked = ParentalLock.activeLockedIds(act)
            channels = runCatching { db.channelDao().getAll() }.getOrDefault(emptyList()).filter { it.categoryId == null || it.categoryId !in locked }
            byGuide = channels.filter { !it.epgChannelId.isNullOrBlank() }.groupBy { it.epgChannelId!!.lowercase() }
            catNames = runCatching { db.categoryDao().getByType(CategoryType.LIVE).associate { it.categoryId to it.name.orEmpty() } }.getOrDefault(emptyMap())
        }
        loaded = true
        // sports and what is popular arrive a moment later
        act.lifecycleScope.launch {
            runCatching { Web24Api(act).support("scores") }.onSuccess { r ->
                sports = Web24Api.objects(r.optJSONArray("games")) + Web24Api.objects(r.optJSONArray("events"))
                leagueNames = Web24Api.objects(r.optJSONArray("leagues")).associate { it.optString("code") to it.optString("name") }
                if (query.isNotBlank()) render()
            }
        }
        act.lifecycleScope.launch {
            val pop = runCatching { Web24Api.objects(Web24Api(act).support("popular").optJSONArray("streams")).map { it.optInt("stream_id") to it.optInt("viewers") } }.getOrDefault(emptyList())
            val byId = channels.associateBy { it.streamId }
            val list = pop.mapNotNull { (id, _) -> byId[id] }.take(12)
            val viewers = pop.toMap()
            val nows = nowOf(list)
            trending = list.mapIndexed { i, ch -> channelHit(ch, nows[ch.epgChannelId?.lowercase()], list, "#${i + 1}", "${viewers[ch.streamId] ?: 0} watching") }
            if (query.isBlank()) render()
        }
    }

    private suspend fun nowOf(list: List<ChannelEntity>): Map<String, EpgEntity> = withContext(Dispatchers.IO) {
        val ids = list.mapNotNull { it.epgChannelId?.takeIf { e -> e.isNotBlank() } }.distinct()
        if (ids.isEmpty()) emptyMap() else runCatching { db.epgDao().getNowByEpgChannelIdsChunked(ids, System.currentTimeMillis()) }.getOrDefault(emptyList())
            .filter { it.epgChannelId != null }.associateBy { it.epgChannelId!!.lowercase() }
    }

    private fun recent(): List<String> = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("recent", "").orEmpty().split('\n').filter { it.isNotBlank() }
    private fun remember(q: String) {
        val t = q.trim(); if (t.length < 2) return
        val l = (listOf(t) + recent().filterNot { it.equals(t, true) }).take(8)
        act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("recent", l.joinToString("\n")).apply()
    }

    // ------------------------------------------------------------------------------------------------ results
    private suspend fun render() {
        val q = query.trim()
        if (q.length < 2) {
            val out = mutableListOf<Hit>()
            val rec = recent()
            if (rec.isNotEmpty()) {
                out += Hit(header = "Recent searches")
                rec.forEach { r -> out += Hit(title = r, glyph = "↺", key = "r:$r", onClick = { setQuery(r) }) }
            }
            if (trending.isNotEmpty()) { out += Hit(header = "Trending now"); out += trending }
            status.text = if (q.isEmpty()) (if (isTv) "Pick letters on the left - or try a channel, a show or a team." else "Try a channel, a show or a team.") else "Keep typing…"
            show(out)
            return
        }
        if (!loaded) { status.text = "Getting ready…"; return }
        status.text = "Searching…"
        val now = System.currentTimeMillis()
        val ql = q.lowercase()
        val out = mutableListOf<Hit>()

        // channels: names that start with the words first
        val chans = withContext(Dispatchers.Default) {
            channels.filter { clean(it.name).lowercase().contains(ql) }
                .sortedWith(compareBy({ if (clean(it.name).lowercase().startsWith(ql)) 0 else 1 }, { clean(it.name).length })).take(25)
        }
        val chanNow = nowOf(chans)
        if (chans.isNotEmpty()) { out += Hit(header = "Channels"); chans.forEach { out += channelHit(it, chanNow[it.epgChannelId?.lowercase()], chans) } }

        // shows: on now, coming up (two days), and aired in the last week on catch-up channels
        val upcoming = withContext(Dispatchers.IO) { runCatching { db.epgDao().searchProgramsInWindow(q, now, now + 2 * 86_400_000L) }.getOrDefault(emptyList()) }.take(120)
        val ended = withContext(Dispatchers.IO) { runCatching { db.epgDao().searchProgramsEnded(q, now - 7 * 86_400_000L, now) }.getOrDefault(emptyList()) }
        fun chOf(p: EpgEntity) = if (p.streamId > 0) channels.firstOrNull { it.streamId == p.streamId } else byGuide[p.epgChannelId?.lowercase()]?.firstOrNull()
        val onNow = upcoming.filter { (it.startTimestamp ?: 0) <= now }.mapNotNull { p -> chOf(p)?.let { p to it } }.distinctBy { it.second.streamId }.take(15)
        val later = upcoming.filter { (it.startTimestamp ?: 0) > now }.mapNotNull { p -> chOf(p)?.let { p to it } }.distinctBy { it.first.title + it.first.startTimestamp }.take(15)
        val catchup = ended.mapNotNull { p -> chOf(p)?.takeIf { ch -> (ch.tvArchive ?: 0) == 1 && now - (p.startTimestamp ?: 0) < (ch.tvArchiveDuration ?: 0) * 86_400_000L }?.let { p to it } }
            .distinctBy { it.first.title + it.first.startTimestamp }.take(15)

        if (onNow.isNotEmpty()) {
            out += Hit(header = "On now")
            val list = onNow.map { it.second }
            onNow.forEach { (p, ch) ->
                val s = p.startTimestamp ?: now; val e = p.stopTimestamp ?: now
                out += Hit(title = cleanTitle(p.title), sub = "${clean(ch.name)}  ·  ${((e - now) / 60_000).coerceAtLeast(0)} min left", badge = "LIVE", badgeColor = live, icon = ch.icon,
                    progress = if (e > s) ((now - s) * 1000 / (e - s)).toInt() else -1, key = "n:${ch.streamId}", onClick = { go { ChannelLauncher.play(act, list, ch) } })
            }
        }
        // sports: teams, leagues and events with this name
        val games = sports.filter { g ->
            val names = if (g.has("kind")) listOf(g.optString("name"), g.optString("short")) else GameCenter.teams(g).let { (a, h) -> listOf(a.optString("name"), a.optString("short"), a.optString("abbr"), h.optString("name"), h.optString("short"), h.optString("abbr")) }
            (names + listOf(g.optString("league"), leagueNames[g.optString("league")].orEmpty())).any { it.isNotBlank() && it.lowercase().contains(ql) }
        }.sortedWith(compareBy({ when (it.optString("state")) { "in" -> 0; "pre" -> 1; else -> 2 } }, { if (it.optString("state") == "post") -it.optLong("start") else it.optLong("start") })).take(12)
        if (games.isNotEmpty()) { out += Hit(header = "Sports"); games.forEach { out += sportHit(it) } }

        if (later.isNotEmpty()) {
            out += Hit(header = "Coming up")
            later.forEach { (p, ch) ->
                val s = p.startTimestamp ?: now
                val set = Reminders.has(act, ch.streamId, s)
                out += Hit(title = cleanTitle(p.title), sub = "${clean(ch.name)}  ·  ${Fmt.day(s)} ${Fmt.clock(s)}", badge = if (set) "⏰ Reminder set" else "Remind me", badgeColor = if (set) gold else 0,
                    icon = ch.icon, key = "l:${ch.streamId}:$s", onClick = {
                        val on = Reminders.toggle(act, ch.streamId, s, cleanTitle(p.title), clean(ch.name))
                        remember(query)
                        Toast.makeText(act, if (on) "We'll remind you 2 minutes before ${cleanTitle(p.title)}" else "Reminder removed", Toast.LENGTH_SHORT).show()
                        act.lifecycleScope.launch { render() }
                    })
            }
        }
        if (catchup.isNotEmpty()) {
            out += Hit(header = "Catch-up")
            catchup.forEach { (p, ch) ->
                val s = p.startTimestamp ?: now
                out += Hit(title = cleanTitle(p.title), sub = "${clean(ch.name)}  ·  aired ${Fmt.day(s)} ${Fmt.clock(s)}", badge = "▶ Watch", badgeColor = accentSoft, icon = ch.icon,
                    key = "c:${ch.streamId}:$s", onClick = { go { watchCatchup(p, ch) } })
            }
        }
        val total = out.count { it.header == null }
        status.text = if (total == 0) "Nothing found for \"$q\". Try another word." else "$total results for \"$q\""
        show(out)
    }

    private fun channelHit(ch: ChannelEntity, now: EpgEntity?, list: List<ChannelEntity>, lead: String = "", badge: String? = null): Hit {
        val t = System.currentTimeMillis()
        val s = now?.startTimestamp ?: 0; val e = now?.stopTimestamp ?: 0
        return Hit(title = (if (lead.isNotEmpty()) "$lead  " else "") + clean(ch.name),
            sub = listOfNotNull(now?.let { cleanTitle(it.title) }?.ifBlank { null }, catNames[ch.categoryId]?.takeIf { it.isNotBlank() }).joinToString("  ·  "),
            icon = ch.icon, progress = if (e > s && s > 0) ((t - s) * 1000 / (e - s)).toInt() else -1, key = "ch:${ch.streamId}",
            badge = badge ?: (ch.num?.let { "CH $it" } ?: ""), onClick = { go { ChannelLauncher.play(act, list, ch) } })
    }

    private fun sportHit(g: JSONObject): Hit {
        val st = g.optString("state")
        val lg = leagueNames[g.optString("league")].orEmpty().ifBlank { g.optString("league") }
        if (g.has("kind")) {
            return Hit(title = g.optString("short").ifBlank { g.optString("name") }, sub = "$lg  ·  ${EventCenter.dates(g)}", glyph = "🏆",
                badge = if (st == "in") "LIVE" else if (st == "post") "Final" else "Details", badgeColor = if (st == "in") live else 0, key = "e:" + g.optString("id"),
                onClick = { go { EventCenter.open(act, g, lg) } })
        }
        val (a, h) = GameCenter.teams(g)
        val startMs = g.optLong("start") * 1000
        val title = if (st == "pre") "${GameCenter.short(a)} @ ${GameCenter.short(h)}" else "${GameCenter.short(a)} ${a.optString("score")}  @  ${GameCenter.short(h)} ${h.optString("score")}"
        return Hit(title = title, sub = "$lg  ·  " + when (st) { "in" -> g.optString("detail").ifBlank { "Live" }; "post" -> g.optString("detail").ifBlank { "Final" }; else -> "${Fmt.day(startMs)} ${Fmt.clock(startMs)}" },
            icon = a.optString("logo"), badge = if (st == "in") "LIVE" else if (st == "post") "Final" else "Game Center", badgeColor = if (st == "in") live else 0, key = "g:" + g.optString("id"),
            onClick = { go { GameCenter.open(act, JSONObject(g.toString()).put("league_name", lg)) } })
    }

    /** Opens what was picked; the search is remembered and the layer closes. */
    private fun go(action: () -> Unit) { remember(query); dismiss(); action() }

    private fun watchCatchup(p: EpgEntity, ch: ChannelEntity) {
        val shows = JSONArray().put(JSONObject().put("title", cleanTitle(p.title)).put("desc", p.description.orEmpty()).put("start", p.startTimestamp ?: 0).put("end", p.stopTimestamp ?: 0))
        act.startActivity(Intent(act, CatchupPlayerActivity::class.java)
            .putExtra(CatchupPlayerActivity.EXTRA_STREAM, ch.streamId).putExtra(CatchupPlayerActivity.EXTRA_CHANNEL, clean(ch.name))
            .putExtra(CatchupPlayerActivity.EXTRA_LOGO, ch.icon.orEmpty()).putExtra(CatchupPlayerActivity.EXTRA_SHOWS, shows.toString()))
    }

    private fun show(out: List<Hit>) {
        val keepFocus = list.hasFocus()
        hits = out
        adapter.notifyDataSetChanged()
        if (keepFocus) list.post { firstResult()?.requestFocus() }
    }

    // ------------------------------------------------------------------------------------------------ rows
    private fun highlight(t: String): CharSequence {
        val q = query.trim()
        val i = if (q.length >= 2) t.lowercase().indexOf(q.lowercase()) else -1
        if (i < 0) return t
        return SpannableStringBuilder(t).apply {
            setSpan(ForegroundColorSpan(accentSoft), i, i + q.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), i, i + q.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private inner class HitAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = hits.size
        override fun getItemViewType(position: Int) = if (hits[position].header != null) 0 else 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v: View = if (viewType == 0) text("", 13f, accentSoft, 800).apply { letterSpacing = 0.14f; setPadding(dp(4), dp(18), 0, dp(8)); layoutParams = RecyclerView.LayoutParams(-1, -2) }
            else LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(16), dp(8))
                layoutParams = RecyclerView.LayoutParams(-1, dp(62)).apply { bottomMargin = dp(6) }
                background = shape(surface, 12f, line)
                addView(FrameLayout(act).apply {
                    addView(ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, FrameLayout.LayoutParams(-1, -1))
                    addView(text("", 18f, accentSoft, 700).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
                }, LinearLayout.LayoutParams(dp(60), dp(38)))
                val mid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(10), 0) }
                mid.addView(text("", 15f, textMain, 700))
                mid.addView(text("", 12f, textSub, 600).apply { setPadding(0, dp(4), 0, 0) })
                mid.addView(ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progressDrawable = act.getDrawable(R.drawable.home_progress) },
                    LinearLayout.LayoutParams(dp(120), dp(3)).apply { topMargin = dp(5) })
                addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
                addView(text("", 12f, textSub, 700).apply { setPadding(dp(10), dp(4), dp(10), dp(4)) })
                focusable(this, 12f, 1.02f)
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val h = hits[position]
            if (h.header != null) { (holder.itemView as TextView).text = h.header.uppercase(); return }
            val row = holder.itemView as LinearLayout
            val box = row.getChildAt(0) as FrameLayout
            val img = box.getChildAt(0) as ImageView
            val glyph = box.getChildAt(1) as TextView
            if (h.glyph.isNotEmpty()) { img.setImageDrawable(null); img.visibility = View.GONE; glyph.visibility = View.VISIBLE; glyph.text = h.glyph }
            else { glyph.visibility = View.GONE; img.visibility = View.VISIBLE; img.load(h.icon?.takeIf { it.isNotBlank() }) { placeholder(R.drawable.app_logo); error(R.drawable.app_logo) } }
            val mid = row.getChildAt(1) as LinearLayout
            (mid.getChildAt(0) as TextView).text = highlight(h.title)
            (mid.getChildAt(1) as TextView).apply { text = h.sub; visibility = if (h.sub.isBlank()) View.GONE else View.VISIBLE }
            (mid.getChildAt(2) as ProgressBar).apply { visibility = if (h.progress >= 0) View.VISIBLE else View.GONE; progress = h.progress.coerceAtLeast(0) }
            (row.getChildAt(2) as TextView).apply {
                text = h.badge; visibility = if (h.badge.isBlank()) View.GONE else View.VISIBLE
                setTextColor(if (h.badgeColor == live) Color.WHITE else if (h.badgeColor != 0) h.badgeColor else textSub)
                background = if (h.badgeColor == live) shape(live, 4f) else null
            }
            row.setOnClickListener { h.onClick?.invoke() }
        }
    }
}
