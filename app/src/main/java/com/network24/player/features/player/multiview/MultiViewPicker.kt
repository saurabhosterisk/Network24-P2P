package com.network24.player.features.player.multiview

import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import coil.load
import com.network24.player.R
import com.network24.player.common.models.FavoriteItemType
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.entity.CategoryType
import com.network24.player.core.database.entity.ChannelEntity
import com.network24.player.core.database.mapper.toLiveChannel
import com.network24.player.core.database.repository.LiveHistoryRepository
import com.network24.player.core.parental.ParentalLock
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.features.dashboard.home.HomeFont
import com.network24.player.features.live.models.LiveChannel
import android.graphics.drawable.GradientDrawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Channel picker of MultiView: every live category (Favorites and Recently watched first) on the left, its channels
 * on the right, the four windows on top. Selecting a channel plays it in the chosen window and moves on to the next
 * empty one, so four channels are set up in one go; selecting a channel that is already playing takes it out again.
 * Categories under the parental lock and the ones switched off in Manage Categories are left out.
 */
class MultiViewPicker(
    private val activity: AppCompatActivity,
    private val root: ViewGroup,
    private val windows: () -> Array<LiveChannel?>,
    private val onPick: (slot: Int, channel: LiveChannel?) -> Unit,
    private val onClosed: (slot: Int) -> Unit,
) {
    private data class Cat(val id: String, val name: String, val count: Int)

    private val d = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private fun col(id: Int) = ContextCompat.getColor(activity, id)
    private val db by lazy { DatabaseProvider.get(activity) }
    private val handler = Handler(Looper.getMainLooper())
    private val touch by lazy { activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN) }

    private var panel: FrameLayout? = null
    private lateinit var chips: LinearLayout
    private lateinit var hint: TextView
    private lateinit var catList: RecyclerView
    private lateinit var chList: RecyclerView
    private lateinit var chTitle: TextView
    private lateinit var progress: ProgressBar
    private val catAdapter = CatAdapter()
    private val chAdapter = ChAdapter()

    private var cats: List<Cat> = emptyList()
    private var catIndex = 0
    private var target = 0
    private var loadJob: Job? = null
    private var pendingCat: Runnable? = null

    val isOpen get() = panel != null

    fun open(slot: Int, preferCategory: String?) {
        target = slot
        if (panel == null) build()
        refreshChips()
        activity.lifecycleScope.launch {
            if (cats.isEmpty()) cats = loadCats()
            catAdapter.notifyDataSetChanged()
            val start = cats.indexOfFirst { it.id == preferCategory }.takeIf { it >= 0 } ?: 0
            selectCat(start, focus = true)
        }
    }

    fun close() {
        val p = panel ?: return
        loadJob?.cancel()
        pendingCat?.let { handler.removeCallbacks(it) }
        root.removeView(p)
        panel = null
        onClosed(target)
    }

    // ------------------------------------------------------------------------------------------------ layout
    // the app look: Manrope, dark glass rows with a white focus ring, violet accent
    private val surface = Color.parseColor("#14161B")
    private val line = Color.parseColor("#1FFFFFFF")
    private val accent = Color.parseColor("#7C5CFF")
    private val accentSoft = Color.parseColor("#A894FF")
    private val textSub = Color.parseColor("#9BA1AD")

    private fun text(s: String, size: Float, color: Int = Color.parseColor("#F2F3F5"), bold: Boolean = false) = TextView(activity).apply {
        text = s; textSize = size; setTextColor(color); typeface = HomeFont.of(activity, if (bold) 800 else 500); includeFontPadding = false
    }

    private fun shape(fill: Int, radius: Float, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius.toInt()).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
    }

    /** Rounded row with a 3 dp white ring while it has the focus (the same ring as the rest of the app). */
    private fun glass(v: View, radius: Int, selected: () -> Boolean = { false }) {
        val ring = GradientDrawable().apply { cornerRadius = dp(radius) - dp(3) / 2f; setStroke(dp(3), Color.WHITE); setColor(Color.TRANSPARENT) }
        fun paint() { v.background = if (selected()) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#3A2C7A"), Color.parseColor("#1E1A3A"))).apply { cornerRadius = dp(radius).toFloat(); setStroke(dp(1), 0x667C5CFF) } else shape(surface, radius.toFloat(), line) }
        paint()
        val old = v.onFocusChangeListener
        v.setOnFocusChangeListener { x, has -> x.foreground = if (has) ring else null; x.animate().scaleX(if (has) 1.02f else 1f).scaleY(if (has) 1.02f else 1f).setDuration(120).start(); old?.onFocusChange(x, has) }
        v.tag = (v.tag as? Runnable) ?: Runnable { paint() }
    }

    private fun build() {
        val p = FrameLayout(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#FF15112E"), Color.parseColor("#FF08090C"), Color.parseColor("#FF08090C"))); isClickable = true; elevation = dp(30).toFloat()
        }
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(48), dp(24), dp(48), dp(16)) }
        p.addView(col, FrameLayout.LayoutParams(-1, -1))

        val head = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("MultiView · choose channels", 22f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(activity).apply {
            text = "Done"; textSize = 15f; typeface = HomeFont.of(activity, 800); setTextColor(Color.parseColor("#08090C")); gravity = Gravity.CENTER
            setPadding(dp(28), dp(11), dp(28), dp(11)); background = shape(Color.WHITE, 22f)
            isFocusable = true; isClickable = true; setOnClickListener { close() }
            glass(this, 22)
            background = shape(Color.WHITE, 22f)
        })
        col.addView(head)

        chips = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(chips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        hint = text("", 13f, textSub).apply { setPadding(dp(2), dp(8), 0, dp(8)) }
        col.addView(hint)

        val body = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        val left = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        left.addView(text("CATEGORIES", 12f, accentSoft, true).apply { letterSpacing = 0.12f; setPadding(dp(4), 0, 0, dp(6)) })
        catList = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity); adapter = catAdapter; id = View.generateViewId(); clipToPadding = false
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        left.addView(catList, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(left, LinearLayout.LayoutParams(0, -1, 0.34f))

        val right = FrameLayout(activity)
        val rightCol = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        chTitle = text("", 12f, accentSoft, true).apply { letterSpacing = 0.12f; setPadding(dp(4), 0, 0, dp(6)) }
        rightCol.addView(chTitle)
        chList = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity); adapter = chAdapter; id = View.generateViewId(); clipToPadding = false
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        rightCol.addView(chList, LinearLayout.LayoutParams(-1, 0, 1f))
        right.addView(rightCol, FrameLayout.LayoutParams(-1, -1))
        progress = ProgressBar(activity).apply { visibility = View.GONE }
        right.addView(progress, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        body.addView(right, LinearLayout.LayoutParams(0, -1, 0.66f).apply { marginStart = dp(16) })

        root.addView(p, ViewGroup.LayoutParams(-1, -1))
        panel = p
    }

    /** The four windows: number, what plays there; the target window is highlighted. */
    private fun refreshChips() {
        val w = windows()
        val hadFocus = chips.hasFocus()
        chips.removeAllViews()
        for (i in 0 until 4) {
            val ch = w[i]
            val v = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8))
                isFocusable = true; isClickable = true; isLongClickable = true
                isSelected = i == target; glass(this, 14) { i == target }
                addView(text("WINDOW ${i + 1}" + if (i == target) "  ◀ next" else "", 11f, if (i == target) accentSoft else textSub, true))
                addView(text(ch?.name ?: "Empty", 14f, if (ch == null) textSub else Color.WHITE, ch != null).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                setOnClickListener { target = i; refreshChips(); chAdapter.refresh() }
                setOnLongClickListener { if (windows()[i] != null) { onPick(i, null); refreshChips(); chAdapter.refresh() }; true }
            }
            chips.addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(10) })
            if (hadFocus && i == target) v.post { v.requestFocus() }
        }
        hint.text = "Select a channel to play it in Window ${target + 1}. Select a window above to change it · " +
            (if (touch) "hold a window to empty it" else "hold OK on a window to empty it") + " · select a playing channel to take it out."
    }

    // ------------------------------------------------------------------------------------------------ data
    private suspend fun loadCats(): List<Cat> = withContext(Dispatchers.IO) {
        val locked = ParentalLock.activeLockedIds(activity)
        val off = PreferenceManager(activity).getDisabledLiveCategoryIds().orEmpty()
        val counts = db.channelDao().getAll().groupingBy { it.categoryId }.eachCount()
        val out = mutableListOf<Cat>()
        val favs = db.favoritesDao().getByType(FavoriteItemType.LIVE_CHANNEL).size
        if (favs > 0) out += Cat(FAV, "★  Favorites", favs)
        out += Cat(RECENT, "Recently watched", -1)
        db.categoryDao().getByType(CategoryType.LIVE).sortedBy { it.position }.forEach { c ->
            if (c.categoryId in locked || c.categoryId in off) return@forEach
            val n = counts[c.categoryId] ?: 0
            if (n > 0) out += Cat(c.categoryId, c.name, n)
        }
        out
    }

    private suspend fun channelsOf(cat: Cat): List<ChannelEntity> = withContext(Dispatchers.IO) {
        val locked = ParentalLock.activeLockedIds(activity)
        fun ok(c: ChannelEntity) = c.categoryId == null || c.categoryId !in locked
        when (cat.id) {
            FAV -> {
                val ids = db.favoritesDao().getByType(FavoriteItemType.LIVE_CHANNEL).mapNotNull { it.itemId.toIntOrNull() }
                val by = db.channelDao().getByStreamIds(ids).associateBy { it.streamId }
                ids.mapNotNull { by[it] }.filter { ok(it) }
            }
            RECENT -> {
                val ids = LiveHistoryRepository(activity).getRecentlyWatched().mapNotNull { it.stream_id }
                val by = db.channelDao().getByStreamIds(ids).associateBy { it.streamId }
                ids.mapNotNull { by[it] }
            }
            else -> db.channelDao().getByCategory(cat.id)
        }
    }

    /** What is on now, from the TV guide in the app. */
    private suspend fun nowPlaying(list: List<ChannelEntity>): Map<String, String> = withContext(Dispatchers.IO) {
        val ids = list.mapNotNull { it.epgChannelId?.takeIf { e -> e.isNotBlank() } }.distinct()
        val now = System.currentTimeMillis()
        val out = HashMap<String, String>()
        ids.chunked(400).forEach { part ->
            runCatching { db.epgDao().getNowByEpgChannelIds(part, now) }.getOrNull()?.forEach { e ->
                val k = e.epgChannelId ?: return@forEach
                if (k !in out) out[k] = e.title.orEmpty()
            }
        }
        out
    }

    private fun selectCat(i: Int, focus: Boolean, focusChannels: Boolean = false) {
        if (cats.isEmpty()) return
        val old = catIndex
        catIndex = i.coerceIn(0, cats.lastIndex)
        catAdapter.notifyItemChanged(old); catAdapter.notifyItemChanged(catIndex)
        val cat = cats[catIndex]
        chTitle.text = cat.name.removePrefix("★  ").uppercase()
        loadJob?.cancel()
        progress.visibility = View.VISIBLE
        loadJob = activity.lifecycleScope.launch {
            val list = channelsOf(cat)
            chAdapter.submit(list, emptyMap())
            progress.visibility = View.GONE
            chList.scrollToPosition(0)
            if (focusChannels && list.isNotEmpty()) chList.post { chList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
            if (focus) {
                catList.scrollToPosition(catIndex)
                catList.post { catList.findViewHolderForAdapterPosition(catIndex)?.itemView?.requestFocus() }
            }
            if (list.isEmpty()) chTitle.text = "${chTitle.text} · nothing here yet"
            chAdapter.setNow(nowPlaying(list))
        }
    }

    private fun pick(ch: ChannelEntity) {
        val w = windows()
        val already = w.indexOfFirst { it?.stream_id == ch.streamId }
        if (already >= 0) {
            onPick(already, null)
            target = already
        } else {
            onPick(target, ch.toLiveChannel())
            // next empty window, else stay (the next pick replaces this one)
            val w2 = windows()
            val next = (1..4).map { (target + it) % 4 }.firstOrNull { w2[it] == null }
            if (next != null) target = next
        }
        refreshChips()
        chAdapter.refresh()
    }

    // ------------------------------------------------------------------------------------------------ lists
    private inner class CatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = cats.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                isFocusable = true; isClickable = true; glass(this, 14) { this.isSelected }
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
                addView(text("", 15f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(text("", 12f, textSub))
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val c = cats[position]
            val v = h.itemView as LinearLayout
            val sel = position == catIndex
            (v.getChildAt(0) as TextView).apply { text = c.name; setTextColor(if (sel) accentSoft else Color.WHITE) }
            (v.getChildAt(1) as TextView).text = if (c.count >= 0) "${c.count}" else ""
            v.isSelected = sel; (v.tag as? Runnable)?.run()
            v.setOnClickListener { selectCat(h.bindingAdapterPosition, focus = false); chList.post { chList.getChildAt(0)?.requestFocus() } }
            // moving through the categories shows their channels (after a short pause, not on every step)
            v.setOnFocusChangeListener { _, has ->
                if (!has) return@setOnFocusChangeListener
                val pos = h.bindingAdapterPosition
                if (pos == catIndex || pos < 0) return@setOnFocusChangeListener
                pendingCat?.let { handler.removeCallbacks(it) }
                pendingCat = Runnable { selectCat(pos, focus = false) }.also { handler.postDelayed(it, 300) }
            }
            v.setOnKeyListener { _, code, e ->
                if (e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    val pos = h.bindingAdapterPosition
                    pendingCat?.let { handler.removeCallbacks(it) }
                    pendingCat = null
                    // a category not shown yet: load it, then go into its channels
                    if (pos >= 0 && pos != catIndex) selectCat(pos, focus = false, focusChannels = true)
                    else (chList.findViewHolderForAdapterPosition(0)?.itemView ?: chList.getChildAt(0))?.requestFocus()
                    true
                } else false
            }
        }
    }

    private inner class ChAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var list: List<ChannelEntity> = emptyList()
        private var now: Map<String, String> = emptyMap()
        init { setHasStableIds(true) }
        fun submit(l: List<ChannelEntity>, n: Map<String, String>) { list = l; now = n; notifyDataSetChanged() }
        fun setNow(n: Map<String, String>) { now = n; refresh() }
        // rebinding in place keeps the remote on the same row
        fun refresh() = notifyItemRangeChanged(0, list.size)
        override fun getItemId(position: Int) = list[position].streamId.toLong()
        override fun getItemCount() = list.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(9), dp(16), dp(9))
                isFocusable = true; isClickable = true; glass(this, 14)
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
                addView(ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(60), dp(40)))
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0)
                    addView(text("", 16f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(text("", 13f, textSub).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(text("", 13f, Color.WHITE, true).apply { background = shape(0x26FFFFFF, 14f); setPadding(dp(14), dp(6), dp(14), dp(6)) })
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val c = list[position]
            val v = h.itemView as LinearLayout
            (v.getChildAt(0) as ImageView).load(c.icon?.takeIf { it.isNotBlank() }) { placeholder(R.drawable.app_logo); error(R.drawable.app_logo) }
            val texts = v.getChildAt(1) as LinearLayout
            (texts.getChildAt(0) as TextView).text = c.name.orEmpty()
            val prog = c.epgChannelId?.let { now[it] }.orEmpty()
            (texts.getChildAt(1) as TextView).apply { text = if (prog.isNotBlank()) "Now: $prog" else ""; visibility = if (prog.isBlank()) View.GONE else View.VISIBLE }
            val inWin = windows().indexOfFirst { it?.stream_id == c.streamId }
            (v.getChildAt(2) as TextView).apply {
                text = if (inWin >= 0) "▶ Window ${inWin + 1}" else "+ Window ${target + 1}"
                setTextColor(if (inWin >= 0) accentSoft else textSub)
            }
            v.setOnClickListener { pick(c) }
            v.setOnKeyListener { _, code, e ->
                if (e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    catList.post { (catList.findViewHolderForAdapterPosition(catIndex)?.itemView ?: catList.getChildAt(0))?.requestFocus() }
                    true
                } else false
            }
        }
    }

    companion object { private const val FAV = "@fav"; private const val RECENT = "@recent" }
}
