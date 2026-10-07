package com.network24.player.features.guide

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.firebase.firestore.FirebaseFirestore
import com.network24.player.R
import com.network24.player.common.models.FavoriteItemType
import com.network24.player.core.api.Web24Api
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.entity.CategoryType
import com.network24.player.core.database.entity.ChannelEntity
import com.network24.player.core.database.entity.EpgEntity
import com.network24.player.core.database.repository.FavoritesRepository
import com.network24.player.core.parental.ParentalLock
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.features.catchup.CatchupActivity
import com.network24.player.features.catchup.CatchupPlayerActivity
import com.network24.player.features.dashboard.home.HomeFont
import com.network24.player.core.database.mapper.toLiveChannel
import com.network24.player.features.discover.ChannelLauncher
import com.network24.player.features.discover.CinemaPro
import com.network24.player.features.discover.Fmt
import com.network24.player.features.reminders.Reminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * TV Guide in the app's look: the home's top bar (TV Guide selected), a billboard with the programme in focus
 * (artwork, channel, live / upcoming / aired, time, progress, description), category chips, a time ruler with a
 * NOW line and the grid - one row per channel, programmes as blocks as wide as they are long.
 *
 * Remote: UP / DOWN change channel and keep the time, LEFT / RIGHT walk through the programmes and at the edge
 * move the guide an hour earlier / later. OK: on now = watch live, aired on a catch-up channel = watch it from
 * the start, still to come = remind me. Hold OK (or Menu): every option, also favourites.
 * Locked and adult categories are not shown here (the TV is shared at home).
 */
class TvGuideActivity : BaseActivity() {
    private val uiScale by lazy { resources.displayMetrics.let { m -> minOf(1f, m.widthPixels / m.density / 960f, m.heightPixels / m.density / 400f) } }
    private val d by lazy { resources.displayMetrics.density * uiScale }
    private val fontD by lazy { d * resources.configuration.fontScale.coerceIn(0.85f, 1.15f) }
    private fun dp(v: Int) = (v * d).toInt()
    private fun dpf(v: Float) = v * d
    private val screenW by lazy { resources.displayMetrics.widthPixels }
    private val screenH by lazy { resources.displayMetrics.heightPixels }
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { PreferenceManager(this) }
    private val db by lazy { DatabaseProvider.get(this) }

    private val bg = Color.parseColor("#08090C")
    private val surface = Color.parseColor("#14161B")
    private val line = Color.parseColor("#1FFFFFFF")
    private val textMain = Color.parseColor("#F2F3F5")
    private val textSub = Color.parseColor("#9BA1AD")
    private val accent = Color.parseColor("#7C5CFF")
    private val live = Color.parseColor("#E5484D")
    private val gold = Color.parseColor("#F5B841")

    private val hour = 3_600_000L
    private val span = 3 * hour
    private var windowStart = 0L
    private val windowEnd get() = windowStart + span

    private class Row(val ch: ChannelEntity, val progs: List<EpgEntity>)
    private class Block(val row: Int, val ch: ChannelEntity, val p: EpgEntity?, val start: Long, val end: Long)

    private var cats: List<Pair<String, String>> = emptyList()
    private var allChannels: List<ChannelEntity> = emptyList()
    private var favIds: Set<Int> = emptySet()
    private var catKey = ""
    private var channels: List<ChannelEntity> = emptyList()
    private var rows: List<Row> = emptyList()
    private var recordable: Set<Int>? = null
    private var focusTime = 0L

    private lateinit var root: FrameLayout
    private lateinit var backdrop: ImageView
    private lateinit var heroLogo: ImageView
    private lateinit var heroTag: TextView
    private lateinit var heroChannel: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroDesc: TextView
    private lateinit var heroBar: ProgressBar
    private lateinit var chipRow: LinearLayout
    private lateinit var ruler: FrameLayout
    private lateinit var dayLabel: TextView
    private lateinit var gridBox: FrameLayout
    private lateinit var nowLine: View
    private lateinit var rv: RecyclerView
    private lateinit var status: TextView
    private val adapter = GuideAdapter()
    private val chanW by lazy { dp(230) }
    private val laneW by lazy { screenW - dp(48) * 2 - chanW }
    private val rowH by lazy { dp(58) }
    private fun xOf(t: Long) = ((t - windowStart).toDouble() / span * laneW).toInt()

    /** This page's tab in the top bar: UP into the bar lands there. */
    private var hereTab: android.view.View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val now = System.currentTimeMillis()
        windowStart = now / (30 * 60_000L) * (30 * 60_000L) - 30 * 60_000L
        focusTime = now
        build()
        load()
        handler.postDelayed(object : Runnable {
            override fun run() { placeNowLine(); handler.postDelayed(this, 30_000) }
        }, 30_000)
    }

    // ------------------------------------------------------------------------------------------------ building blocks
    private fun text(s: String, size: Float, color: Int = textMain, weight: Int = 500, lines: Int = 1) = TextView(this).apply {
        text = s; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * fontD); setTextColor(color); typeface = HomeFont.of(this@TvGuideActivity, weight)
        maxLines = lines; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
    }

    private fun shape(fill: Int, radius: Float, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dpf(radius); if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun focusable(v: View, radius: Float, scale: Float = 1.06f, ringColor: Int = Color.WHITE, onFocus: (() -> Unit)? = null) {
        v.isFocusable = true; v.isClickable = true
        val stroke = dp(3)
        val ring = GradientDrawable().apply { cornerRadius = (dpf(radius) - stroke / 2f).coerceAtLeast(0f); setStroke(stroke, ringColor); setColor(Color.TRANSPARENT) }
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).translationZ(if (has) dpf(10f) else 0f).setDuration(130).start()
            view.foreground = if (has) ring else null
            if (has) onFocus?.invoke()
        }
    }

    private fun iconButton(icon: Int, label: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon); setColorFilter(textMain); setPadding(dp(10), dp(10), dp(10), dp(10)); contentDescription = label
        background = shape(0x1AFFFFFF, 21f); setOnClickListener { onClick() }
        focusable(this, 21f, 1.08f)
        com.network24.player.core.ui.IconHint.attach(this, label)
    }

    private val prefix = Regex("^[A-Z]{2,3}\\s*\\|\\s*")
    private fun cleanName(n: String?) = n.orEmpty().replace(prefix, "").trim()
    private val marks = Regex("[ʰ-˿ᴀ-ᶿ⁰-₟]+")
    private fun cleanTitle(t: String?) = t.orEmpty().replace(marks, "").replace(Regex("\\s+"), " ").trim()
    private fun niceName(n: String) = n.trim().split(" ").joinToString(" ") { w ->
        if (w.length <= 3 && w.all { it.isUpperCase() || !it.isLetter() }) w else w.lowercase().replaceFirstChar { it.uppercase() }
    }

    // ------------------------------------------------------------------------------------------------ page
    private fun build() {
        root = FrameLayout(this).apply { setBackgroundColor(bg) }
        val heroH = (screenH * 0.52f).toInt()
        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        root.addView(backdrop, FrameLayout.LayoutParams((screenW * 0.62f).toInt(), heroH, Gravity.TOP or Gravity.END))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(bg, 0xCC08090C.toInt(), 0x3308090C))
        }, FrameLayout.LayoutParams((screenW * 0.62f).toInt(), heroH, Gravity.TOP or Gravity.END))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x0008090C, bg))
        }, FrameLayout.LayoutParams(-1, (heroH * 0.45f).toInt(), Gravity.TOP).apply { topMargin = (heroH * 0.55f).toInt() + 1 })

        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(48), dp(24), dp(48), dp(12)); clipChildren = false; clipToPadding = false }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        val menu = iconButton(R.drawable.ic_more_vert, "More") {}
        page.addView(topBar(menu), LinearLayout.LayoutParams(-1, -2))
        page.addView(hero(), LinearLayout.LayoutParams(-1, (screenH * 0.2f).toInt()).apply { topMargin = dp(8) })

        chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        page.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; setPadding(dp(8), dp(8), dp(8), dp(8)); addView(chipRow)
        }, LinearLayout.LayoutParams(-1, -2).apply { marginStart = -dp(8); topMargin = dp(6) })

        // time ruler: the day over the channel column, then a label every 30 minutes
        val rulerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        dayLabel = text("", 13f, textMain, 800).apply { letterSpacing = 0.06f }
        rulerRow.addView(dayLabel, LinearLayout.LayoutParams(chanW, -2))
        ruler = FrameLayout(this)
        rulerRow.addView(ruler, LinearLayout.LayoutParams(laneW, dp(24)))
        page.addView(rulerRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        gridBox = FrameLayout(this).apply { clipChildren = true }
        rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TvGuideActivity); adapter = this@TvGuideActivity.adapter
            itemAnimator = null; clipChildren = false; clipToPadding = false; setPadding(0, dp(6), 0, dp(6)); isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        gridBox.addView(rv, FrameLayout.LayoutParams(-1, -1))
        nowLine = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(live, 0x66E5484D)); elevation = dpf(20f)
        }
        gridBox.addView(nowLine, FrameLayout.LayoutParams(dp(2), -1))
        status = text("Loading the guide…", 15f, textSub)
        gridBox.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        page.addView(gridBox, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(2) })
        if (packageManager.hasSystemFeature("android.software.leanback")) page.addView(text("OK  watch · remind      Hold OK  more options      ◀ ▶ at the edge  earlier · later", 12f, textSub, 600).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        setContentView(setupGlobalRightDrawer(root, menu))
    }

    private fun topBar(menu: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        val mark = ImageView(this@TvGuideActivity).apply { setImageResource(R.drawable.app_mark); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
        val words = LinearLayout(this@TvGuideActivity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(24), 0)
            addView(text("NETWORK24", 16f, weight = 800).apply { letterSpacing = 0.02f }, LinearLayout.LayoutParams(-2, -2))
            addView(text("FUTURE", 10f, weight = 800).apply {
                letterSpacing = 0.08f; setPadding(dp(1), 0, 0, 0)
                paint.shader = android.graphics.LinearGradient(0f, 0f, dpf(70f), 0f, accent, Color.parseColor("#22D3EE"), android.graphics.Shader.TileMode.CLAMP)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = -dp(3) })
        }
        addView(mark, LinearLayout.LayoutParams(-2, dp(28)))
        addView(words)
        words.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h > 0 && mark.layoutParams.height != h) mark.post { mark.layoutParams = mark.layoutParams.apply { height = h }; mark.requestLayout() }
        }
        fun go(cls: Class<*>, extra: (Intent.() -> Unit)? = null) { startActivity(Intent(this@TvGuideActivity, cls).apply { extra?.invoke(this) }); finish() }
        val tabs = listOf<Pair<String, () -> Unit>>(
            "Home" to { finish() },
            "Live TV" to { go(com.network24.player.features.livetv.LiveTvActivity::class.java) },
            "Movies" to { CinemaPro.open(this@TvGuideActivity) },
            "Sports" to { go(com.network24.player.features.sports.SportsActivity::class.java) },
            "TV Guide" to { rv.post { focusAt(0, System.currentTimeMillis()) } },
            "Catch-up" to { go(CatchupActivity::class.java) },
        )
        val tabRow = LinearLayout(this@TvGuideActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tabs.forEachIndexed { i, (label, onClick) ->
            val here = label == "TV Guide"
            val t = text(label, 14f, if (here) textMain else textSub, if (here) 700 else 600).apply {
                setPadding(dp(9), dp(9), dp(9), dp(9)); background = if (here) shape(0x1FFFFFFF, 18f) else null
                setOnClickListener { onClick() }
            }
            focusable(t, 18f, 1.04f)
            if (here) hereTab = t
            tabRow.addView(t, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(2) })
        }
        addView(HorizontalScrollView(this@TvGuideActivity).apply { isHorizontalScrollBarEnabled = false; addView(tabRow) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(iconButton(R.drawable.ic_h_search, "Search") {
            com.network24.player.features.search.SearchOverlay.show(this@TvGuideActivity)
        }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(12) })
        addView(iconButton(R.drawable.ic_live_chat, "Live Support") {
            com.network24.player.features.help.HelpCenter.show(this@TvGuideActivity)
        }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(10) })
        addView(iconButton(R.drawable.ic_h_account, "Account") {
            com.network24.player.features.account.AccountCenter.show(this@TvGuideActivity)
        }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(10) })
        addView(iconButton(R.drawable.ic_settings, "Settings") { context.startActivity(android.content.Intent(context, com.network24.player.features.settings.activity.SettingsActivity::class.java)) }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(10) })
        addView(menu, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(10) })
    }

    /** Billboard: the programme in focus. */
    private fun hero() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM
        val tagRow = LinearLayout(this@TvGuideActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heroTag = text("", 11f, Color.WHITE, 800).apply { letterSpacing = 0.12f; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        tagRow.addView(heroTag)
        heroLogo = ImageView(this@TvGuideActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        tagRow.addView(heroLogo, LinearLayout.LayoutParams(dp(48), dp(26)).apply { marginStart = dp(12) })
        heroChannel = text("", 12f, Color.parseColor("#D9F2F3F5"), 700).apply { letterSpacing = 0.12f; setPadding(dp(10), 0, 0, 0) }
        tagRow.addView(heroChannel)
        addView(tagRow)
        heroTitle = text("TV Guide", 28f, weight = 800).apply { letterSpacing = -0.02f; setPadding(0, dp(10), 0, 0); setShadowLayer(dpf(10f), 0f, dpf(2f), 0x99000000.toInt()) }
        addView(heroTitle, LinearLayout.LayoutParams((screenW * 0.6f).toInt(), -2))
        val metaRow = LinearLayout(this@TvGuideActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        heroMeta = text("", 14f, textSub, 600).apply { fontFeatureSettings = "tnum" }
        metaRow.addView(heroMeta)
        heroBar = ProgressBar(this@TvGuideActivity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progressDrawable = getDrawable(R.drawable.home_progress); visibility = View.GONE }
        metaRow.addView(heroBar, LinearLayout.LayoutParams(dp(180), dp(4)).apply { marginStart = dp(14) })
        addView(metaRow)
        heroDesc = text("Everything on now and next, on every channel.", 14f, Color.parseColor("#CCF2F3F5"), 500, lines = 2).apply { setPadding(0, dp(8), 0, 0); setLineSpacing(0f, 1.15f) }
        addView(heroDesc, LinearLayout.LayoutParams((screenW * 0.55f).toInt(), -2))
    }

    // ------------------------------------------------------------------------------------------------ data
    private fun hiddenCategories(all: List<Pair<String, String>>): Set<String> {
        val locked = if (ParentalLock.isEnabled(this)) ParentalLock.lockedIds(this) else emptySet()
        val adult = Regex("ADULT|XXX|18\\+", RegexOption.IGNORE_CASE)
        return locked + all.filter { adult.containsMatchIn(it.second) }.map { it.first }
    }

    private fun load() = lifecycleScope.launch {
        val (c, ch, fav) = withContext(Dispatchers.IO) {
            val allCats = db.categoryDao().getByType(CategoryType.LIVE).sortedBy { it.position }.map { it.categoryId to it.name }
            val hidden = hiddenCategories(allCats)
            val off = prefs.getDisabledLiveCategoryIds().orEmpty()
            val chans = db.channelDao().getAll().filter { it.categoryId == null || (it.categoryId !in hidden && it.categoryId !in off) }
            val favs = db.favoritesDao().getByType(FavoriteItemType.LIVE_CHANNEL).mapNotNull { it.itemId.toIntOrNull() }.toSet()
            val withChannels = chans.mapNotNull { it.categoryId }.toSet()
            Triple(allCats.filter { it.first in withChannels && it.first !in hidden && it.first !in off }, chans, favs)
        }
        cats = c; allChannels = ch; favIds = fav
        buildChips()
        selectCategory(if (favIds.isNotEmpty()) "fav" else cats.firstOrNull()?.first ?: "all", focusGrid = true)
        launch { recordable = runCatching { Web24Api(this@TvGuideActivity).support("catchup").optJSONArray("streams") }.getOrNull()?.let { a -> (0 until a.length()).map { a.optInt(it) }.toSet() } }
    }

    private fun buildChips() {
        chipRow.removeAllViews()
        val list = (if (favIds.isNotEmpty()) listOf("fav" to "★  Favorites") else emptyList()) + cats.map { it.first to niceName(it.second) }
        list.forEachIndexed { i, (key, label) ->
            val t = text(label, 14f, textMain, 700).apply {
                setPadding(dp(16), dp(9), dp(16), dp(9)); tag = "chip:$key"
                setOnClickListener { selectCategory(key, focusGrid = true) }
            }
            focusable(t, 18f, 1.05f)
            chipRow.addView(t, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(8) })
        }
    }

    private fun paintChips() {
        for (i in 0 until chipRow.childCount) {
            val t = chipRow.getChildAt(i) as TextView
            val on = t.tag == "chip:$catKey"
            t.background = shape(if (on) Color.WHITE else 0x1AFFFFFF, 18f)
            focusable(t, 18f, 1.05f, if (on) accent else Color.WHITE)
            t.setTextColor(if (on) bg else textMain)
        }
    }

    private fun selectCategory(key: String, focusGrid: Boolean) {
        catKey = key
        paintChips()
        channels = when (key) {
            "fav" -> allChannels.filter { it.streamId in favIds }
            else -> allChannels.filter { it.categoryId == key }
        }.sortedWith(compareBy({ it.num ?: Int.MAX_VALUE }, { it.name }))
        reloadGuide(focusGrid)
    }

    /** The programmes of the shown channels inside the window, then the grid is drawn again. */
    private fun reloadGuide(focusGrid: Boolean, focusRow: Int = 0) {
        val list = channels
        drawRuler()
        lifecycleScope.launch {
            val byEpg = withContext(Dispatchers.IO) {
                val ids = list.mapNotNull { it.epgChannelId?.takeIf { s -> s.isNotBlank() } }.distinct()
                if (ids.isEmpty()) emptyMap() else db.epgDao().getByEpgChannelIdsChunked(ids, windowStart, windowEnd).groupBy { it.epgChannelId }
            }
            if (list !== channels) return@launch
            rows = list.map { ch -> Row(ch, byEpg[ch.epgChannelId].orEmpty().filter { (it.stopTimestamp ?: 0) > windowStart && (it.startTimestamp ?: 0) < windowEnd }) }
            status.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            status.text = "No channels here yet."
            adapter.notifyDataSetChanged()
            placeNowLine()
            // touch screens never focus a block: show the first channel's programme at the guide time
            rows.getOrNull(focusRow.coerceAtLeast(0))?.let { r ->
                val t = focusTime.coerceIn(windowStart, windowEnd - 1)
                val p = r.progs.firstOrNull { t >= (it.startTimestamp ?: 0) && t < (it.stopTimestamp ?: 0) }
                showHero(Block(focusRow, r.ch, p, p?.startTimestamp ?: windowStart, p?.stopTimestamp ?: windowEnd))
            }
            if (focusGrid && rows.isNotEmpty()) rv.post { focusAt(focusRow.coerceIn(0, rows.size - 1), focusTime) }
        }
    }

    private fun drawRuler() {
        ruler.removeAllViews()
        var t = windowStart
        while (t < windowEnd) {
            ruler.addView(text(Fmt.clock(t), 12f, textSub, 700).apply { fontFeatureSettings = "tnum" },
                FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM).apply { leftMargin = xOf(t) + dp(6) })
            ruler.addView(View(this).apply { setBackgroundColor(line) }, FrameLayout.LayoutParams(dp(1), dp(10), Gravity.BOTTOM).apply { leftMargin = xOf(t) })
            t += 30 * 60_000L
        }
        dayLabel.text = Fmt.day(windowStart).uppercase()
    }

    private fun placeNowLine() {
        val now = System.currentTimeMillis()
        val inside = now in windowStart until windowEnd
        nowLine.visibility = if (inside && rows.isNotEmpty()) View.VISIBLE else View.GONE
        if (inside) nowLine.translationX = (chanW + xOf(now)).toFloat()
    }

    // ------------------------------------------------------------------------------------------------ grid
    private inner class GuideAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false
                layoutParams = RecyclerView.LayoutParams(-1, rowH)
            }
            val cell = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), 0, dp(12), 0)
            }
            cell.addView(ImageView(parent.context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(64), dp(38)))
            cell.addView(text("", 13f, textMain, 700, lines = 2).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(cell, LinearLayout.LayoutParams(chanW, -1))
            row.addView(FrameLayout(parent.context).apply { clipChildren = false; clipToPadding = false }, LinearLayout.LayoutParams(laneW, -1))
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val r = rows[position]
            val row = holder.itemView as LinearLayout
            val cell = row.getChildAt(0) as LinearLayout
            (cell.getChildAt(0) as ImageView).load(r.ch.icon?.takeIf { it.isNotBlank() }) { placeholder(R.drawable.app_logo); error(R.drawable.app_logo) }
            (cell.getChildAt(1) as TextView).text = cleanName(r.ch.name)
            val lane = row.getChildAt(1) as FrameLayout
            lane.removeAllViews()
            if (r.progs.isEmpty()) lane.addView(blockView(Block(position, r.ch, null, windowStart, windowEnd)))
            else r.progs.forEach { p -> lane.addView(blockView(Block(position, r.ch, p, p.startTimestamp ?: windowStart, p.stopTimestamp ?: windowEnd))) }
        }
    }

    private fun catchupOk(ch: ChannelEntity, start: Long): Boolean {
        val now = System.currentTimeMillis()
        val keepMs = (ch.tvArchiveDuration ?: 0) * 86_400_000L
        return (ch.tvArchive ?: 0) == 1 && (recordable?.contains(ch.streamId) ?: true) && start < now && now - start < keepMs
    }

    /** One programme block, as wide as its time in the window: on now (violet, progress), aired (dim), to come. */
    private fun blockView(b: Block): View {
        val now = System.currentTimeMillis()
        val isLive = b.p != null && now in b.start until b.end
        val past = b.p != null && b.end <= now
        val reminder = b.p != null && !past && !isLive && Reminders.has(this, b.ch.streamId, b.start)
        val x0 = xOf(maxOf(b.start, windowStart)); val x1 = xOf(minOf(b.end, windowEnd))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(10), dp(6)); tag = b
            background = when {
                b.p == null -> shape(0x0DFFFFFF, 10f, line)
                isLive -> GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#3A2C7A"), Color.parseColor("#1E1A3A"))).apply { cornerRadius = dpf(10f); setStroke(dp(1), 0x667C5CFF) }
                past -> shape(0x0AFFFFFF, 10f)
                else -> shape(surface, 10f, line)
            }
            clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        val title = when { b.p == null -> "No guide information"; else -> cleanTitle(b.p.title).ifBlank { "Untitled" } }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (isLive) titleRow.addView(text("LIVE", 9f, Color.WHITE, 800).apply { setPadding(dp(5), dp(2), dp(5), dp(2)); background = shape(live, 4f) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        if (reminder) titleRow.addView(text("⏰", 11f, gold, 700), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(5) })
        titleRow.addView(text(title, 14f, if (past || b.p == null) textSub else textMain, 700))
        box.addView(titleRow)
        val sub = when {
            b.p == null -> "Watch live"
            past && catchupOk(b.ch, b.start) -> "▶ Catch-up · ${Fmt.clock(b.start)}"
            else -> "${Fmt.clock(b.start)} – ${Fmt.clock(b.end)}"
        }
        box.addView(text(sub, 11f, if (past && catchupOk(b.ch, b.start)) Color.parseColor("#A894FF") else textSub, 600).apply { setPadding(0, dp(3), 0, 0); fontFeatureSettings = "tnum" })
        if (isLive) box.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = ((now - b.start) * 1000 / (b.end - b.start)).toInt(); progressDrawable = getDrawable(R.drawable.home_progress)
        }, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(5) })
        box.layoutParams = FrameLayout.LayoutParams((x1 - x0 - dp(4)).coerceAtLeast(dp(8)), rowH - dp(8), Gravity.CENTER_VERTICAL).apply { leftMargin = x0 + dp(2) }
        box.setOnClickListener { act(b) }
        box.setOnLongClickListener { options(b); true }
        focusable(box, 10f, 1.03f, if (isLive) Color.parseColor("#A894FF") else Color.WHITE) {
            heroPending?.let { handler.removeCallbacks(it) }
            heroPending = Runnable { showHero(b) }.also { handler.postDelayed(it, 120) }
        }
        return box
    }

    private var heroPending: Runnable? = null

    private fun blocksOf(pos: Int): List<View>? {
        val vh = rv.findViewHolderForAdapterPosition(pos) ?: return null
        val lane = (vh.itemView as LinearLayout).getChildAt(1) as FrameLayout
        return (0 until lane.childCount).map { lane.getChildAt(it) }.sortedBy { (it.tag as Block).start }
    }

    /** Focus the block of row [pos] that is on at [time] (or the nearest one), scrolling the row into view first. */
    private fun focusAt(pos: Int, time: Long, tries: Int = 0) {
        if (pos !in rows.indices) return
        val blocks = blocksOf(pos)
        val lm = rv.layoutManager as LinearLayoutManager
        val first = lm.findFirstCompletelyVisibleItemPosition(); val last = lm.findLastCompletelyVisibleItemPosition()
        if (blocks.isNullOrEmpty() || !rv.isLaidOut || first < 0 || pos < first || pos > last) {
            if (tries > 20) { blocks?.firstOrNull()?.requestFocus(); return }
            if (first >= 0 && (pos < first || pos > last)) rv.scrollToPosition(pos)
            handler.postDelayed({ focusAt(pos, time, tries + 1) }, 40)
            return
        }
        val t = time.coerceIn(windowStart, windowEnd - 1)
        val target = blocks.firstOrNull { val b = it.tag as Block; t >= b.start && t < b.end } ?: blocks.minByOrNull { val b = it.tag as Block; minOf(Math.abs(b.start - t), Math.abs(b.end - t)) }
        target?.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (com.network24.player.core.ui.TopBarFocus.up(this, hereTab, event)) return true
        val focus = currentFocus
        val b = focus?.tag as? Block
        if (event.action == KeyEvent.ACTION_DOWN && b != null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (b.row == 0) { (chipRow.findViewWithTag<View>("chip:$catKey") ?: chipRow.getChildAt(0))?.requestFocus() } else focusAt(b.row - 1, focusTime)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> { focusAt(b.row + 1, focusTime); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val right = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    val blocks = blocksOf(b.row).orEmpty()
                    val i = blocks.indexOf(focus)
                    val next = blocks.getOrNull(if (right) i + 1 else i - 1)
                    if (next != null) {
                        val nb = next.tag as Block
                        focusTime = maxOf(nb.start, windowStart) + 60_000L
                        next.requestFocus()
                    } else shiftWindow(if (right) hour else -hour, b.row, if (right) b.end else b.start - 60_000L)
                    return true
                }
                KeyEvent.KEYCODE_MENU -> { options(b); return true }
            }
        }
        // from the chips DOWN goes into the guide at the current time
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && focus != null && (focus.tag as? String)?.startsWith("chip:") == true) {
            focusAt(0, focusTime); return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Moves the guide an hour earlier / later (from one day back to two days ahead). */
    private fun shiftWindow(by: Long, row: Int, time: Long) {
        val now = System.currentTimeMillis()
        val next = windowStart + by
        if (next < now - 24 * hour || next > now + 48 * hour) return
        windowStart = next
        focusTime = time
        reloadGuide(focusGrid = true, focusRow = row)
    }

    // ------------------------------------------------------------------------------------------------ billboard
    private fun showHero(b: Block) {
        val now = System.currentTimeMillis()
        heroLogo.load(b.ch.icon?.takeIf { it.isNotBlank() })
        heroChannel.text = cleanName(b.ch.name).uppercase()
        val p = b.p
        val isLive = p != null && now in b.start until b.end
        val past = p != null && b.end <= now
        heroTag.text = when { p == null -> "CHANNEL"; isLive -> "LIVE"; past -> if (catchupOk(b.ch, b.start)) "CATCH-UP" else "AIRED"; else -> "UPCOMING" }
        heroTag.background = shape(when { isLive -> live; past -> accent; p == null -> 0x33FFFFFF; else -> 0x33FFFFFF }, 4f)
        heroTitle.text = if (p == null) cleanName(b.ch.name) else cleanTitle(p.title).ifBlank { "Untitled" }
        val mins = ((b.end - b.start) / 60_000).toInt()
        heroMeta.text = when {
            p == null -> "No guide information for this channel"
            isLive -> "${Fmt.clock(b.start)} – ${Fmt.clock(b.end)}  ·  ${if (b.end - now < 60_000) "ending now" else "${(b.end - now) / 60_000} min left"}"
            past -> "${Fmt.day(b.start)} ${Fmt.clock(b.start)} – ${Fmt.clock(b.end)}  ·  $mins min"
            else -> "${Fmt.day(b.start)} ${Fmt.clock(b.start)} – ${Fmt.clock(b.end)}  ·  starts in ${untilText(b.start - now)}" + if (Reminders.has(this, b.ch.streamId, b.start)) "  ·  ⏰ reminder set" else ""
        }
        heroBar.visibility = if (isLive) View.VISIBLE else View.GONE
        if (isLive) heroBar.progress = ((now - b.start) * 1000 / (b.end - b.start)).toInt()
        heroDesc.text = p?.description?.takeIf { it.isNotBlank() } ?: when {
            p == null -> "Press OK to watch this channel live."
            isLive -> "On now. Press OK to watch."
            past -> if (catchupOk(b.ch, b.start)) "Recorded. Press OK to watch it from the start." else "This show has ended."
            else -> "Press OK to get a reminder 2 minutes before it starts."
        }
        backdrop.animate().alpha(0f).setDuration(150).start()
        val title = p?.let { cleanTitle(it.title) }.orEmpty()
        if (title.isNotBlank()) art(title) { a ->
            if (heroTitle.text.toString() != title) return@art
            a.optString("backdrop").takeIf { it.isNotBlank() }?.let { u ->
                backdrop.load(u) { crossfade(true); listener(onSuccess = { _, _ -> if (heroTitle.text.toString() == title) backdrop.animate().alpha(0.9f).setDuration(400).start() }) }
            }
        }
    }

    private fun untilText(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        return if (m >= 60) "${m / 60} h ${m % 60} min".replace(" 0 min", "") else "$m min"
    }

    // ------------------------------------------------------------------------------------------------ actions
    private fun act(b: Block) {
        val now = System.currentTimeMillis()
        when {
            b.p == null || now in b.start until b.end -> watchLive(b.ch)
            b.end <= now -> if (catchupOk(b.ch, b.start)) watchCatchup(b) else Toast.makeText(this, "This show was not recorded.", Toast.LENGTH_SHORT).show()
            else -> toggleReminder(b)
        }
    }

    private fun options(b: Block) {
        val now = System.currentTimeMillis()
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += "Watch ${cleanName(b.ch.name)} live" to { watchLive(b.ch) }
        items += "Watch in MultiView" to { multiView(b.ch) }
        if (b.p != null && b.start < now && catchupOk(b.ch, b.start)) items += "Watch from the start" to { watchCatchup(b) }
        if (b.p != null && b.start > now) items += (if (Reminders.has(this, b.ch.streamId, b.start)) "Remove reminder" else "Remind me") to { toggleReminder(b) }
        items += (if (b.ch.streamId in favIds) "Remove from Favorites" else "Add to Favorites") to { toggleFavorite(b.ch) }
        val title = b.p?.let { cleanTitle(it.title) }?.ifBlank { null } ?: cleanName(b.ch.name)
        showChoiceDialog(title = title, items = items.map { it.first }, selectedIndex = -1, focusIndex = 0) { which -> items[which].second() }
    }

    private fun watchLive(ch: ChannelEntity) = ChannelLauncher.play(this, channels.ifEmpty { listOf(ch) }, ch)

    private fun multiView(ch: ChannelEntity) {
        val list = channels.ifEmpty { listOf(ch) }
        com.network24.player.features.player.state.PlayerState.channels.clear()
        com.network24.player.features.player.state.PlayerState.channels.addAll(list.map { it.toLiveChannel() })
        com.network24.player.features.player.state.PlayerState.currentPosition = list.indexOfFirst { it.streamId == ch.streamId }.coerceAtLeast(0)
        startActivity(Intent(this, com.network24.player.features.player.multiview.MultiViewActivity::class.java))
    }

    private fun watchCatchup(b: Block) {
        val p = b.p ?: return
        val now = System.currentTimeMillis()
        val end = minOf(b.end, now - 60_000L)
        fun item(t: String?, desc: String?, s: Long, e: Long) = JSONObject().put("title", cleanTitle(t)).put("desc", desc.orEmpty()).put("start", s).put("end", e)
        val shows = JSONArray().put(item(p.title, p.description, b.start, end))
        rows.getOrNull(b.row)?.progs?.filter { (it.startTimestamp ?: 0) >= b.end && (it.stopTimestamp ?: 0) <= now }?.forEach {
            shows.put(item(it.title, it.description, it.startTimestamp ?: 0, it.stopTimestamp ?: 0))
        }
        startActivity(Intent(this, CatchupPlayerActivity::class.java)
            .putExtra(CatchupPlayerActivity.EXTRA_STREAM, b.ch.streamId)
            .putExtra(CatchupPlayerActivity.EXTRA_CHANNEL, cleanName(b.ch.name))
            .putExtra(CatchupPlayerActivity.EXTRA_LOGO, b.ch.icon.orEmpty())
            .putExtra(CatchupPlayerActivity.EXTRA_SHOWS, shows.toString()))
    }

    private fun toggleReminder(b: Block) {
        val p = b.p ?: return
        Reminders.toggle(this, b.ch.streamId, b.start, cleanTitle(p.title), cleanName(b.ch.name))
        adapter.notifyItemChanged(b.row)
        rv.post { focusAt(b.row, focusTime) }
        showHero(b)
    }

    private fun toggleFavorite(ch: ChannelEntity) {
        val repo = FavoritesRepository(db.favoritesDao(), FirebaseFirestore.getInstance())
        val isFav = ch.streamId in favIds
        lifecycleScope.launch {
            runCatching {
                if (isFav) repo.removeFavorite(prefs.getUsername(), FavoriteItemType.LIVE_CHANNEL, ch.streamId.toString())
                else repo.addFavorite(prefs.getUsername(), FavoriteItemType.LIVE_CHANNEL, ch.streamId.toString())
            }.onSuccess {
                favIds = if (isFav) favIds - ch.streamId else favIds + ch.streamId
                Toast.makeText(this@TvGuideActivity, "${cleanName(ch.name)} ${if (isFav) "removed from" else "added to"} Favorites", Toast.LENGTH_SHORT).show()
                val had = chipRow.findViewWithTag<View>("chip:fav") != null
                if (had != favIds.isNotEmpty()) { buildChips(); paintChips() }
            }.onFailure { Toast.makeText(this@TvGuideActivity, "Could not update Favorites", Toast.LENGTH_SHORT).show() }
        }
    }

    // ------------------------------------------------------------------------------------------------ artwork
    private val artDone = HashMap<String, JSONObject?>()
    private val artWait = HashMap<String, MutableList<(JSONObject) -> Unit>>()
    private val artQueue = LinkedHashSet<String>()
    private var artFlushing = false
    private val artFlush = Runnable { flushArt() }

    private fun art(title: String, onArt: (JSONObject) -> Unit) {
        when {
            artDone.containsKey(title) -> artDone[title]?.let(onArt)
            artWait.containsKey(title) -> artWait[title]!!.add(onArt)
            else -> { artWait[title] = mutableListOf(onArt); artQueue += title }
        }
        if (artQueue.isNotEmpty()) { handler.removeCallbacks(artFlush); handler.postDelayed(artFlush, 250) }
    }

    private fun flushArt() {
        if (artFlushing || artQueue.isEmpty()) return
        artFlushing = true
        val batch = artQueue.take(24); artQueue.removeAll(batch.toSet())
        lifecycleScope.launch {
            val r = runCatching { Web24Api(this@TvGuideActivity).support("art", "titles" to batch.joinToString("\n")) }.getOrNull()
            val art = r?.optJSONObject("art")
            if (r == null || art == null || r.optBoolean("limited")) batch.forEach { artWait.remove(it) }
            else batch.forEach { t -> val a = art.optJSONObject(t); artDone[t] = a; val cbs = artWait.remove(t).orEmpty(); if (a != null) cbs.forEach { it(a) } }
            artFlushing = false
            if (artQueue.isNotEmpty()) handler.postDelayed(artFlush, 50)
        }
    }

    override fun onResume() {
        super.onResume()
        // back from the player or a reminder change: reminders and the LIVE block may have changed
        if (rows.isNotEmpty()) { adapter.notifyDataSetChanged(); placeNowLine() }
    }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
