package com.network24.player.features.livetv

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
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
import com.network24.player.core.database.mapper.toLiveChannel
import com.network24.player.core.database.repository.FavoritesRepository
import com.network24.player.core.database.repository.FavoritesOrder
import com.network24.player.core.net.StreamDataSourceFactory
import com.network24.player.core.parental.ParentalLock
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.features.catchup.CatchupActivity
import com.network24.player.features.dashboard.home.HomeFont
import com.network24.player.features.discover.ChannelLauncher
import com.network24.player.features.discover.CinemaPro
import com.network24.player.features.discover.Fmt
import com.network24.player.features.guide.TvGuideActivity
import com.network24.player.features.live.repository.CategorySettingsRepository
import com.network24.player.features.live.repository.LiveRepository
import com.network24.player.features.live.repository.SyncCallback
import com.network24.player.features.parental.PinPrompt
import com.network24.player.features.parental.WebStateRepository
import com.network24.player.features.player.multiview.MultiViewActivity
import com.network24.player.features.player.state.PlayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Live TV in the app's look, on one page: the home's top bar (Live TV selected), the categories down the left
 * (Favorites first, favourite categories marked, locked ones with a padlock), the channels of the chosen category
 * on the right with what is on now, and above them the channel in focus playing live (sound fades in, like the
 * home billboard) with its programme, time left and what is next.
 *
 * Remote: UP / DOWN walk the list, LEFT / RIGHT between categories and channels, OK on a channel = full screen,
 * hold OK (or Menu) = options (MultiView, favourite). Hold OK on a category = favourite category.
 * Touch: the first tap previews a channel, the second plays it.
 * The preview frees its connection after 20 s without a key or touch, and before the full player opens.
 */
@OptIn(UnstableApi::class)
class LiveTvActivity : BaseActivity() {
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
    private val favRepo by lazy { FavoritesRepository(db.favoritesDao(), FirebaseFirestore.getInstance()) }

    private val bg = Color.parseColor("#08090C")
    private val surface = Color.parseColor("#14161B")
    private val line = Color.parseColor("#1FFFFFFF")
    private val textMain = Color.parseColor("#F2F3F5")
    private val textSub = Color.parseColor("#9BA1AD")
    private val accent = Color.parseColor("#7C5CFF")
    private val accentSoft = Color.parseColor("#A894FF")
    private val live = Color.parseColor("#E5484D")

    private class Cat(val id: String, val name: String, val count: Int, val fav: Boolean, val locked: Boolean)
    private class Now(val now: EpgEntity?, val next: EpgEntity?)

    private var cats: List<Cat> = emptyList()
    private var allChannels: List<ChannelEntity> = emptyList()
    private var favChannelIds: Set<Int> = emptySet()
    // favorites in the viewer's own order (the DAO's order)
    private var favOrder: List<Int> = emptyList()
    private var favCatIds: Set<String> = emptySet()
    private var catKey = ""
    private var channels: List<ChannelEntity> = emptyList()
    private var guide: Map<String, Now> = emptyMap()
    // every guide read so far: the billboard keeps its programme while another category is shown
    private val seen = HashMap<String, Now>()
    private var heroCh: ChannelEntity? = null
    private var lastChannelPos = 0
    private val pickIds by lazy { intent.getIntArrayExtra(EXTRA_STREAM_IDS)?.toList() }
    private val pickTitle by lazy { intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Channels" } }
    private var pickChannels: List<ChannelEntity> = emptyList()

    private lateinit var root: FrameLayout
    private lateinit var video: PlayerView
    private lateinit var backdrop: ImageView
    private lateinit var poster: ImageView
    private lateinit var heroTag: TextView
    private lateinit var heroLogo: ImageView
    private lateinit var heroChannel: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroBar: ProgressBar
    private lateinit var heroNext: TextView
    private lateinit var listTitle: TextView
    private lateinit var listCount: TextView
    private lateinit var rail: RecyclerView
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private val railAdapter = RailAdapter()
    private val listAdapter = ChannelAdapter()
    private val railW by lazy { dp(250) }
    private val heroH by lazy { (screenH * 0.50f).toInt() }

    /** This page's tab in the top bar: UP into the bar lands there. */
    private var hereTab: android.view.View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
        load()
    }

    // ------------------------------------------------------------------------------------------------ building blocks
    private fun text(s: String, size: Float, color: Int = textMain, weight: Int = 500, lines: Int = 1) = TextView(this).apply {
        text = s; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * fontD); setTextColor(color); typeface = HomeFont.of(this@LiveTvActivity, weight)
        maxLines = lines; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
    }

    private fun shape(fill: Int, radius: Float, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dpf(radius); if (stroke != 0) setStroke(dp(1), stroke)
    }

    private fun focusable(v: View, radius: Float, scale: Float = 1.04f, onFocus: ((Boolean) -> Unit)? = null) {
        v.isFocusable = true; v.isClickable = true
        val stroke = dp(3)
        val ring = GradientDrawable().apply { cornerRadius = (dpf(radius) - stroke / 2f).coerceAtLeast(0f); setStroke(stroke, Color.WHITE); setColor(Color.TRANSPARENT) }
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).translationZ(if (has) dpf(10f) else 0f).setDuration(130).start()
            view.foreground = if (has) ring else null
            onFocus?.invoke(has)
        }
    }

    private fun iconButton(icon: Int, label: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon); setColorFilter(textMain); setPadding(dp(8), dp(8), dp(8), dp(8)); contentDescription = label
        background = shape(0x14FFFFFF, 18f, 0x1FFFFFFF); setOnClickListener { onClick() }
        focusable(this, 18f, 1.08f)
        com.network24.player.core.ui.IconHint.attach(this, label)
    }

    private val prefix = Regex("^[A-Z]{2,3}\\s*\\|\\s*")
    private fun cleanName(n: String?) = n.orEmpty().replace(prefix, "").trim()
    private val marks = Regex("[ʰ-˿ᴀ-ᶿ⁰-₟]+")
    private fun cleanTitle(t: String?) = t.orEmpty().replace(marks, "").replace(Regex("\\s+"), " ").trim()
    private fun niceName(n: String) = n.trim().split(" ").joinToString(" ") { w ->
        if (w.length <= 3 && w.all { it.isUpperCase() || !it.isLetter() }) w else w.lowercase().replaceFirstChar { it.uppercase() }
    }
    private fun progress(p: EpgEntity, now: Long): Int {
        val s = p.startTimestamp ?: return 0; val e = p.stopTimestamp ?: return 0
        return if (e <= s) 0 else ((now - s) * 1000 / (e - s)).toInt().coerceIn(0, 1000)
    }

    // ------------------------------------------------------------------------------------------------ page
    private fun build() {
        root = FrameLayout(this).apply { setBackgroundColor(bg) }
        // the channel in focus plays behind the right side's top half; its artwork / logo stand in until it starts
        val heroLeft = dp(48) + railW + dp(24)
        val heroW = screenW - heroLeft
        backdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        root.addView(backdrop, FrameLayout.LayoutParams(heroW, heroH, Gravity.TOP or Gravity.END))
        video = PlayerView(this).apply {
            useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM; setShutterBackgroundColor(Color.TRANSPARENT); alpha = 0f
            isFocusable = false
        }
        root.addView(video, FrameLayout.LayoutParams(heroW, heroH, Gravity.TOP or Gravity.END))
        poster = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; alpha = 0f }
        root.addView(poster, FrameLayout.LayoutParams((heroW * 0.32f).toInt(), (heroH * 0.36f).toInt(), Gravity.TOP or Gravity.END).apply {
            topMargin = (heroH * 0.24f).toInt(); marginEnd = (heroW * 0.08f).toInt()
        })
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(bg, 0xD908090C.toInt(), 0x5908090C, 0x0008090C))
        }, FrameLayout.LayoutParams(heroW, heroH, Gravity.TOP or Gravity.END))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xB308090C.toInt(), 0x0008090C, 0x0008090C, bg))
        }, FrameLayout.LayoutParams(heroW, heroH + 2, Gravity.TOP or Gravity.END))

        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(48), dp(24), dp(48), 0); clipChildren = false; clipToPadding = false }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        val menu = iconButton(R.drawable.ic_more_vert, "More") {}
        page.addView(topBar(menu), LinearLayout.LayoutParams(-1, -2))

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(26) })

        // left: categories
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        left.addView(text("CATEGORIES", 11f, textSub, 800).apply { letterSpacing = 0.16f; setPadding(dp(14), 0, 0, dp(8)) })
        rail = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LiveTvActivity); adapter = railAdapter; itemAnimator = null
            clipChildren = false; clipToPadding = false; setPadding(dp(4), dp(4), dp(4), dp(24)); isVerticalScrollBarEnabled = false
            isFocusable = false; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(24))
        }
        left.addView(rail, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(left, LinearLayout.LayoutParams(railW, -1))

        // right: the channel in focus, then the channels
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), 0, 0, 0); clipChildren = false; clipToPadding = false }
        // at least the picture area; taller when the text needs it (phones: "Up next" was cut by the list title)
        right.addView(hero().apply { minimumHeight = heroH - dp(24) - dp(36) - dp(26) - dp(30) }, LinearLayout.LayoutParams(-1, -2))
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; setPadding(dp(4), dp(6), 0, dp(8)) }
        listTitle = text("", 18f, textMain, 800)
        head.addView(listTitle)
        listCount = text("", 13f, textSub, 600).apply { setPadding(dp(10), 0, 0, dp(1)) }
        head.addView(listCount)
        right.addView(head, LinearLayout.LayoutParams(-1, -2))
        val listBox = FrameLayout(this)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LiveTvActivity); adapter = listAdapter; itemAnimator = null
            clipChildren = false; clipToPadding = false; setPadding(dp(4), dp(4), dp(8), dp(24)); isVerticalScrollBarEnabled = false
            isFocusable = false; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(24))
        }
        listBox.addView(list, FrameLayout.LayoutParams(-1, -1))
        status = text("Loading channels…", 15f, textSub, 600, lines = 3).apply { gravity = Gravity.CENTER }
        listBox.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        right.addView(listBox, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(right, LinearLayout.LayoutParams(0, -1, 1f))

        setContentView(setupGlobalRightDrawer(root, menu))
    }

    private fun topBar(menu: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        // brand: the Network24 logo - mark and wordmark in one image (4:1)
        addView(ImageView(this@LiveTvActivity).apply { setImageResource(R.drawable.app_logo_wide); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START }, LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(16) })
        fun go(cls: Class<*>) { stopPreview(); startActivity(Intent(this@LiveTvActivity, cls)); finish() }
        val tabs = listOf<Pair<String, () -> Unit>>(
            "Home" to { finish() },
            "Live TV" to { focusChannel(lastChannelPos) },
            "Cinema" to { stopPreview(); CinemaPro.open(this@LiveTvActivity) },
            "Sports" to { go(com.network24.player.features.sports.SportsActivity::class.java) },
            "TV Guide" to { go(TvGuideActivity::class.java) },
            "Catch-up" to { go(CatchupActivity::class.java) },
        )
        val tabRow = LinearLayout(this@LiveTvActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(3), dp(3), dp(3), dp(3)); background = shape(0x14FFFFFF, 21f, 0x1FFFFFFF) }
        tabs.forEachIndexed { i, (label, onClick) ->
            val here = label == "Live TV"
            val t = text(label, 13.5f, if (here) Color.parseColor("#08090C") else textSub, if (here) 700 else 600).apply {
                setPadding(dp(12), dp(7), dp(12), dp(7)); background = if (here) shape(Color.WHITE, 18f) else null
                setOnClickListener { onClick() }
            }
            focusable(t, 18f, 1.04f)
            if (here) hereTab = t
            tabRow.addView(t, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(2) })
        }
        addView(HorizontalScrollView(this@LiveTvActivity).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true; addView(android.widget.FrameLayout(this@LiveTvActivity).apply { addView(tabRow, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)) }) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(iconButton(R.drawable.ic_h_search, "Search") {
            com.network24.player.features.search.SearchOverlay.show(this@LiveTvActivity)
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(12) })
        addView(iconButton(R.drawable.ic_live_chat, "Live Support") {
            com.network24.player.features.help.HelpCenter.show(this@LiveTvActivity)
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(8) })
        // account and settings are in the More menu (the owner wants a shorter bar)
        addView(menu, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(8) })
    }

    /** The channel in focus: LIVE, channel, programme, time left, what is next. */
    private fun hero() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM; setPadding(dp(4), 0, 0, 0)
        val tagRow = LinearLayout(this@LiveTvActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heroTag = text("LIVE", 11f, Color.WHITE, 800).apply { letterSpacing = 0.12f; setPadding(dp(8), dp(4), dp(8), dp(4)); background = shape(live, 4f); visibility = View.INVISIBLE }
        tagRow.addView(heroTag)
        heroLogo = ImageView(this@LiveTvActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        tagRow.addView(heroLogo, LinearLayout.LayoutParams(dp(48), dp(26)).apply { marginStart = dp(12) })
        heroChannel = text("", 12f, Color.parseColor("#D9F2F3F5"), 700).apply { letterSpacing = 0.12f; setPadding(dp(10), 0, 0, 0) }
        tagRow.addView(heroChannel)
        addView(tagRow)
        heroTitle = text("Live TV", 30f, weight = 800).apply { letterSpacing = -0.02f; setPadding(0, dp(10), 0, 0); setShadowLayer(dpf(10f), 0f, dpf(2f), 0x99000000.toInt()) }
        addView(heroTitle, LinearLayout.LayoutParams((screenW * 0.5f).toInt(), -2))
        val metaRow = LinearLayout(this@LiveTvActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        heroMeta = text("Every channel, live.", 14f, textSub, 600).apply { fontFeatureSettings = "tnum" }
        metaRow.addView(heroMeta)
        heroBar = ProgressBar(this@LiveTvActivity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progressDrawable = getDrawable(R.drawable.home_progress); visibility = View.GONE }
        metaRow.addView(heroBar, LinearLayout.LayoutParams(dp(160), dp(4)).apply { marginStart = dp(14) })
        addView(metaRow)
        heroNext = text("", 13f, Color.parseColor("#CCF2F3F5"), 600).apply { setPadding(0, dp(8), 0, 0) }
        addView(heroNext, LinearLayout.LayoutParams((screenW * 0.5f).toInt(), -2))
        setOnClickListener { heroCh?.let { watch(it) } }
    }

    // ------------------------------------------------------------------------------------------------ data
    private fun load() = lifecycleScope.launch {
        val empty = withContext(Dispatchers.IO) { db.channelDao().getAll().isEmpty() }
        if (empty) { firstSync(); return@launch }
        reloadAll(keepCat = false)
        // the lock may have been changed on play.web24.live or another device
        if (WebStateRepository(this@LiveTvActivity).sync()) reloadAll(keepCat = true)
    }

    /** First login: nothing saved yet, download categories and channels with the progress on screen. */
    private fun firstSync() {
        val repo = LiveRepository(applicationContext)
        val msg = "Downloading channels for the first time…"
        runCallbackSyncWithLoader(loadingMessage = msg, successMessage = "Channels ready") { onSuccess, onError ->
            repo.syncAllData(server = prefs.getServer(), username = prefs.getUsername(), password = prefs.getPassword(), callback = object : SyncCallback {
                override fun onSuccess() { lifecycleScope.launch(Dispatchers.Main) { prefs.setLastSyncTime(System.currentTimeMillis()); onSuccess(); reloadAll(keepCat = false) } }
                override fun onError(message: String) { lifecycleScope.launch(Dispatchers.Main) { onError("Could not download the channels: $message"); status.text = "Could not load the channels.\nUse ⋮ → Refresh to try again." } }
                override fun onProgress(percent: Int) { showLoader("$msg $percent%") }
            })
        }
    }

    private suspend fun reloadAll(keepCat: Boolean) {
        val user = prefs.getUsername()
        val (c, ch, favCh) = withContext(Dispatchers.IO) {
            val disabled = runCatching { CategorySettingsRepository(FirebaseFirestore.getInstance(), prefs).getDisabledCategoryIds(user) }.getOrDefault(emptySet())
            val favCats = runCatching { favRepo.getFavoriteItemIds("LIVE_CATEGORY") }.getOrDefault(emptySet())
            val locked = ParentalLock.activeLockedIds(this@LiveTvActivity)
            val raw = db.channelDao().getAll()
            pickIds?.let { ids -> val byId = raw.associateBy { it.streamId }; pickChannels = ids.mapNotNull { byId[it] } }
            val chans = raw.filter { it.categoryId !in disabled }
            val counts = chans.groupingBy { it.categoryId }.eachCount()
            val all = db.categoryDao().getByType(CategoryType.LIVE).sortedBy { it.position }
                .filter { it.categoryId !in disabled && (counts[it.categoryId] ?: 0) > 0 }
                .map { Cat(it.categoryId, it.name, counts[it.categoryId] ?: 0, it.categoryId in favCats, it.categoryId in locked) }
            val favList = db.favoritesDao().getByType(FavoriteItemType.LIVE_CHANNEL).mapNotNull { it.itemId.toIntOrNull() }
            favOrder = favList
            val favs = favList.toSet()
            favCatIds = favCats
            // favourite categories first, then the provider's order
            Triple(all.filter { it.fav } + all.filterNot { it.fav }, chans, favs)
        }
        allChannels = ch; favChannelIds = favCh
        val favCount = ch.count { it.streamId in favCh && !ParentalLock.isLocked(this, it.categoryId) }
        cats = (if (pickIds != null) listOf(Cat("pick", pickTitle, pickChannels.size, false, false)) else emptyList()) +
            (if (favCount > 0) listOf(Cat("fav", "Favorite channels", favCount, false, false)) else emptyList()) + c
        railAdapter.notifyDataSetChanged()
        val asked = (if (pickIds != null) "pick" else intent.getStringExtra(EXTRA_CATEGORY_ID))?.takeIf { k(it) >= 0 }
        val start = catKey.takeIf { keepCat && k(it) >= 0 } ?: asked ?: cats.firstOrNull { !it.locked }?.id ?: return
        selectCategory(start, focusList = !keepCat)
    }

    private fun k(id: String) = cats.indexOfFirst { it.id == id }

    private fun selectCategory(id: String, focusList: Boolean) {
        val cat = cats.getOrNull(k(id)) ?: return
        val changed = catKey != id
        catKey = id
        repaintRail()
        listTitle.text = when (id) { "fav" -> "★  Favorites  ·  " + FavoritesOrder.label(FavoritesOrder.mode(this)); "pick" -> cat.name; else -> niceName(cat.name) }
        listCount.text = "${cat.count} channels"
        if (cat.locked) {
            channels = emptyList(); guide = emptyMap(); listAdapter.notifyDataSetChanged()
            status.visibility = View.VISIBLE; status.text = "🔒  This category is locked.\nSelect it and enter your PIN to open it."
            return
        }
        val chans = when (id) {
            "pick" -> pickChannels.filterNot { ParentalLock.isLocked(this, it.categoryId) }
            // favorites: the viewer's own order, or the sort they picked
            "fav" -> allChannels.associateBy { it.streamId }.let { by -> FavoritesOrder.apply(this, favOrder.mapNotNull { by[it] }.filterNot { ParentalLock.isLocked(this, it.categoryId) }, { it.name.orEmpty() }, { it.num ?: 0 }) }
            else -> allChannels.filter { it.categoryId == id }.sortedWith(compareBy({ it.num ?: Int.MAX_VALUE }, { it.name }))
        }
        if (!changed && chans.size == channels.size) { if (focusList) focusChannel(lastChannelPos); return }
        channels = chans; guide = emptyMap(); lastChannelPos = 0
        status.visibility = if (chans.isEmpty()) View.VISIBLE else View.GONE
        status.text = "No channels here yet."
        listAdapter.notifyDataSetChanged(); list.scrollToPosition(0)
        loadGuide(chans)
        if (focusList && chans.isNotEmpty()) focusChannel(0)
        if (chans.isNotEmpty() && (heroCh == null || focusList)) schedulePreview(chans[0])
    }

    /** What is on now and next on the shown channels. */
    private fun loadGuide(chans: List<ChannelEntity>) = lifecycleScope.launch {
        val now = System.currentTimeMillis()
        val map = withContext(Dispatchers.IO) {
            val ids = chans.mapNotNull { it.epgChannelId?.takeIf { s -> s.isNotBlank() } }.distinct()
            if (ids.isEmpty()) emptyMap() else db.epgDao().getByEpgChannelIdsChunked(ids, now - 6 * 3_600_000L, now + 6 * 3_600_000L)
                .groupBy { it.epgChannelId.orEmpty() }
                .mapValues { (_, progs) ->
                    val sorted = progs.sortedBy { it.startTimestamp ?: 0 }
                    val cur = sorted.firstOrNull { (it.startTimestamp ?: 0) <= now && (it.stopTimestamp ?: 0) > now }
                    Now(cur, sorted.firstOrNull { (it.startTimestamp ?: 0) >= (cur?.stopTimestamp ?: now) })
                }
        }
        if (chans !== channels) return@launch
        guide = map; seen.putAll(map)
        listAdapter.notifyItemRangeChanged(0, channels.size, TICK)
        heroCh?.let { showHero(it) }
    }

    /** "12 min left"; the last minute reads "ending now" (it showed "0 min left"). */
    private fun leftText(ms: Long) = if (ms < 60_000) "ending now" else "${ms / 60_000} min left"

    private fun nowOf(ch: ChannelEntity) = ch.epgChannelId?.let { guide[it] ?: seen[it] }

    // ------------------------------------------------------------------------------------------------ categories
    /** Only the highlight moves when the shown category changes; rebuilding the rows would throw the focus around. */
    private fun repaintRail() {
        for (i in 0 until rail.childCount) {
            val row = rail.getChildAt(i) as LinearLayout
            cats.getOrNull(rail.getChildAdapterPosition(row))?.let { paintCat(row, it) }
        }
    }

    private fun paintCat(row: LinearLayout, c: Cat) {
        val on = c.id == catKey
        (row.getChildAt(0) as TextView).setTextColor(if (on) Color.WHITE else if (c.locked) textSub else Color.parseColor("#D9F2F3F5"))
        (row.getChildAt(1) as TextView).setTextColor(if (on) accentSoft else textSub)
        row.background = if (on) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#3A2C7A"), Color.parseColor("#1E1A3A"))).apply {
            cornerRadius = dpf(12f); setStroke(dp(1), 0x667C5CFF)
        } else null
    }

    private inner class RailAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = cats[position].id.hashCode().toLong()
        override fun getItemCount() = cats.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(11), dp(12), dp(11))
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
            }
            row.addView(text("", 14f, textMain, 700), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text("", 12f, textSub, 700).apply { fontFeatureSettings = "tnum"; setPadding(dp(8), 0, 0, 0) })
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val c = cats[position]
            val row = holder.itemView as LinearLayout
            (row.getChildAt(0) as TextView).text = when {
                c.id == "fav" -> "★  Favorites"
                c.locked -> "🔒  " + niceName(c.name)
                c.fav -> "♥  " + niceName(c.name)
                else -> niceName(c.name)
            }
            (row.getChildAt(1) as TextView).text = c.count.toString()
            paintCat(row, c)
            row.tag = "cat:${c.id}"
            focusable(row, 12f, 1.03f) { has -> if (has) { pendingCat?.let { handler.removeCallbacks(it) }; if (!c.locked) pendingCat = Runnable { selectCategory(c.id, false) }.also { handler.postDelayed(it, 350) } } }
            row.setOnClickListener { openCategory(c) }
            row.setOnLongClickListener { toggleFavoriteCategory(c); true }
        }
    }

    private var pendingCat: Runnable? = null

    private fun openCategory(c: Cat) {
        if (c.locked) {
            PinPrompt.ask(this) { lifecycleScope.launch { reloadAll(keepCat = true); selectCategory(c.id, focusList = true) } }
            return
        }
        pendingCat?.let { handler.removeCallbacks(it) }
        selectCategory(c.id, focusList = true)
    }

    private fun toggleFavoriteCategory(c: Cat) {
        if (c.id == "fav") return
        lifecycleScope.launch {
            runCatching {
                if (c.fav) favRepo.removeFavorite(prefs.getUsername(), "LIVE_CATEGORY", c.id) else favRepo.addFavorite(prefs.getUsername(), "LIVE_CATEGORY", c.id)
            }.onSuccess {
                Toast.makeText(this@LiveTvActivity, "${niceName(c.name)} ${if (c.fav) "removed from" else "added to"} favourite categories", Toast.LENGTH_SHORT).show()
                reloadAll(keepCat = true)
                rail.post { rail.findViewWithTag<View>("cat:${c.id}")?.requestFocus() }
            }.onFailure { Toast.makeText(this@LiveTvActivity, "Could not update favourites", Toast.LENGTH_SHORT).show() }
        }
    }

    // ------------------------------------------------------------------------------------------------ channels
    private inner class ChannelAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        init { setHasStableIds(true) }
        override fun getItemId(position: Int) = channels[position].streamId.toLong()
        override fun getItemCount() = channels.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(8), dp(16), dp(8))
                layoutParams = RecyclerView.LayoutParams(-1, dp(64)).apply { bottomMargin = dp(6) }
                background = shape(surface, 12f, line)
            }
            row.addView(text("", 13f, textSub, 700).apply { fontFeatureSettings = "tnum"; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(44), -2))
            row.addView(ImageView(parent.context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(68), dp(40)).apply { marginStart = dp(6) })
            val mid = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(12), 0) }
            val nameRow = LinearLayout(parent.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            nameRow.addView(text("", 15f, textMain, 700), LinearLayout.LayoutParams(-2, -2).apply { weight = 0f })
            nameRow.addView(text("★", 12f, Color.parseColor("#F5B841"), 700).apply { setPadding(dp(8), 0, 0, 0) })
            mid.addView(nameRow)
            mid.addView(text("", 12f, textSub, 600).apply { setPadding(0, dp(4), 0, 0) })
            row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
            val end = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
            end.addView(text("", 11f, textSub, 600).apply { fontFeatureSettings = "tnum"; gravity = Gravity.END })
            end.addView(ProgressBar(parent.context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progressDrawable = getDrawable(R.drawable.home_progress) },
                LinearLayout.LayoutParams(dp(110), dp(3)).apply { topMargin = dp(6) })
            row.addView(end, LinearLayout.LayoutParams(-2, -2))
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty()) bindGuide(holder.itemView as LinearLayout, channels[position]) else onBindViewHolder(holder, position)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val ch = channels[position]
            val row = holder.itemView as LinearLayout
            (row.getChildAt(0) as TextView).text = (ch.num ?: (position + 1)).toString()
            (row.getChildAt(1) as ImageView).load(ch.icon?.takeIf { it.isNotBlank() }) { placeholder(R.drawable.app_logo); error(R.drawable.app_logo) }
            val nameRow = (row.getChildAt(2) as LinearLayout).getChildAt(0) as LinearLayout
            (nameRow.getChildAt(0) as TextView).text = cleanName(ch.name)
            nameRow.getChildAt(1).visibility = if (ch.streamId in favChannelIds) View.VISIBLE else View.GONE
            bindGuide(row, ch)
            row.tag = position
            focusable(row, 12f, 1.02f) { has ->
                if (has) {
                    lastChannelPos = position
                    schedulePreview(ch)
                }
                paintRow(row, ch, has)
            }
            paintRow(row, ch, row.hasFocus())
            row.setOnClickListener { if (row.isInTouchMode && heroCh?.streamId != ch.streamId) { schedulePreview(ch, 0) } else watch(ch) }
            row.setOnLongClickListener { options(ch); true }
        }
    }

    private fun paintRow(row: View, ch: ChannelEntity, focused: Boolean) {
        val previewing = heroCh?.streamId == ch.streamId
        row.background = when {
            focused || previewing -> GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#3A2C7A"), Color.parseColor("#1E1A3A"))).apply {
                cornerRadius = dpf(12f); setStroke(dp(1), 0x667C5CFF)
            }
            else -> shape(surface, 12f, line)
        }
    }

    private fun bindGuide(row: LinearLayout, ch: ChannelEntity) {
        val now = System.currentTimeMillis()
        val n = nowOf(ch)
        val sub = ((row.getChildAt(2) as LinearLayout).getChildAt(1) as TextView)
        val end = row.getChildAt(3) as LinearLayout
        val time = end.getChildAt(0) as TextView
        val bar = end.getChildAt(1) as ProgressBar
        val cur = n?.now
        if (cur == null) {
            sub.text = if (guide.isEmpty()) "" else "Live"
            time.text = ""; bar.visibility = View.INVISIBLE
        } else {
            sub.text = cleanTitle(cur.title).ifBlank { "Live" }
            time.text = leftText((cur.stopTimestamp ?: now) - now)
            bar.visibility = View.VISIBLE; bar.progress = progress(cur, now)
        }
    }

    // the rail row a held key is heading for (focus itself lags a few frames behind the key events)
    private var railPos = -1
    private var railPending = false

    private fun focusCat(pos: Int, tries: Int = 0) {
        val p = pos.coerceIn(0, cats.size - 1)
        val v = rail.findViewHolderForAdapterPosition(p)?.itemView
        if (v == null || !rail.isLaidOut) {
            if (tries > 20) { railPending = false; return }
            if (tries == 0) rail.scrollToPosition(p)
            handler.postDelayed({ focusCat(p, tries + 1) }, 16)
            return
        }
        // keep the row away from the edges so the next press has somewhere to go without a scroll first
        val lm = rail.layoutManager as LinearLayoutManager
        if (p >= lm.findLastCompletelyVisibleItemPosition() && p < cats.size - 1) rail.scrollBy(0, v.height + dp(4))
        else if (p <= lm.findFirstCompletelyVisibleItemPosition() && p > 0) rail.scrollBy(0, -(v.height + dp(4)))
        railPending = false
        v.requestFocus()
    }

    private fun focusChannel(pos: Int, tries: Int = 0) {
        if (channels.isEmpty()) return
        val p = pos.coerceIn(0, channels.size - 1)
        val v = list.findViewHolderForAdapterPosition(p)?.itemView
        if (v == null || !list.isLaidOut) {
            if (tries > 20) return
            if (tries == 0) list.scrollToPosition(p)
            handler.postDelayed({ focusChannel(p, tries + 1) }, 40)
            return
        }
        v.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (com.network24.player.core.ui.TopBarFocus.up(this, hereTab, event)) return true
        userActive()
        val focus = currentFocus
        if (event.action == KeyEvent.ACTION_DOWN && focus != null) {
            val inList = focus.parent === list
            val inRail = focus.parent === rail
            when {
                inList && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val i = k(catKey).coerceAtLeast(0)
                    rail.findViewWithTag<View>("cat:$catKey")?.requestFocus() ?: run { rail.scrollToPosition(i); rail.post { rail.findViewWithTag<View>("cat:$catKey")?.requestFocus() } }
                    return true
                }
                inList && event.keyCode == KeyEvent.KEYCODE_MENU -> { channels.getOrNull(focus.tag as? Int ?: -1)?.let { options(it) }; return true }
                inList && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                inRail && event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val c = cats.getOrNull(k(((focus.tag as? String) ?: "").removePrefix("cat:")))
                    if (c != null && !c.locked) { pendingCat?.let { handler.removeCallbacks(it) }; if (c.id != catKey) selectCategory(c.id, true) else focusChannel(lastChannelPos) }
                    return true
                }
                inRail && event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> return true
                // UP / DOWN on the rail are moved by hand, like the TV guide rows: RecyclerView's own focus search gives up while
                // it scrolls, so a held key (repeat events every ~50 ms) lost the focus to the top bar or stuck on one row
                inRail && (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -> {
                    val cur = k(((focus.tag as? String) ?: "").removePrefix("cat:")).let { if (railPos in cats.indices && railPending) railPos else it }
                    val next = cur + (if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1)
                    if (next < 0) return super.dispatchKeyEvent(event)   // above the first category: the top bar, as before
                    if (next >= cats.size) return true
                    railPos = next; railPending = true
                    focusCat(next)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean { userActive(); return super.dispatchTouchEvent(ev) }

    // ------------------------------------------------------------------------------------------------ preview
    private var pendingPreview: Runnable? = null

    private fun schedulePreview(ch: ChannelEntity, delay: Long = 450) {
        pendingPreview?.let { handler.removeCallbacks(it) }
        pendingPreview = Runnable { preview(ch) }.also { handler.postDelayed(it, delay) }
    }

    private fun preview(ch: ChannelEntity) {
        val old = heroCh
        heroCh = ch
        showHero(ch)
        // the previously previewed row loses its highlight
        if (old != null && old.streamId != ch.streamId) channels.indexOfFirst { it.streamId == old.streamId }.takeIf { it >= 0 }?.let { i ->
            list.findViewHolderForAdapterPosition(i)?.itemView?.let { paintRow(it, old, it.hasFocus()) }
        }
        channels.indexOfFirst { it.streamId == ch.streamId }.takeIf { it >= 0 }?.let { i -> list.findViewHolderForAdapterPosition(i)?.itemView?.let { paintRow(it, ch, it.hasFocus()) } }
        startVideo(ch)
    }

    private fun showHero(ch: ChannelEntity) {
        val now = System.currentTimeMillis()
        val n = nowOf(ch)
        heroTag.visibility = View.VISIBLE
        heroLogo.load(ch.icon?.takeIf { it.isNotBlank() })
        heroChannel.text = ((ch.num?.let { "$it  ·  " } ?: "") + cleanName(ch.name)).uppercase()
        val cur = n?.now
        heroTitle.text = cur?.let { cleanTitle(it.title) }?.ifBlank { null } ?: cleanName(ch.name)
        if (cur != null) {
            val s = cur.startTimestamp ?: now; val e = cur.stopTimestamp ?: now
            heroMeta.text = "${Fmt.clock(s)} – ${Fmt.clock(e)}  ·  ${leftText(e - now)}"
            heroBar.visibility = View.VISIBLE; heroBar.progress = progress(cur, now)
        } else { heroMeta.text = if (guide.isEmpty()) "" else "Live now"; heroBar.visibility = View.GONE }
        heroNext.text = n?.next?.let { "Up next  ${Fmt.clock(it.startTimestamp ?: now)}   ${cleanTitle(it.title)}" } ?: ""
        val title = cur?.let { cleanTitle(it.title) }.orEmpty()
        if (heroArtFor != title) {
            heroArtFor = title
            backdrop.animate().alpha(0f).setDuration(150).start()
            if (title.isNotBlank()) art(title) { a ->
                if (heroArtFor != title) return@art
                a.optString("backdrop").takeIf { it.isNotBlank() }?.let { u ->
                    backdrop.load(u) { crossfade(true); listener(onSuccess = { _, _ -> if (heroArtFor == title) backdrop.animate().alpha(0.85f).setDuration(400).start() }) }
                }
            }
        }
        if (playingId != ch.streamId || video.alpha < 0.5f) {
            poster.load(ch.icon?.takeIf { it.isNotBlank() }) { listener(onSuccess = { _, _ -> if (heroCh?.streamId == ch.streamId && video.alpha < 0.5f) poster.animate().alpha(0.9f).setDuration(300).start() }) }
        }
    }
    private var heroArtFor = ""

    private var player: ExoPlayer? = null
    private var playingId = -1
    private var started = false

    private fun startVideo(ch: ChannelEntity) {
        if (!started || playingId == ch.streamId) return
        val p = player ?: ExoPlayer.Builder(applicationContext, StreamDataSourceFactory.createRenderersFactory(this))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(8000, 20000, 1000, 2000).build())
            .setMediaSourceFactory(StreamDataSourceFactory.createMediaSourceFactory())
            .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .build().also { pl ->
                player = pl; pl.volume = 0f; video.player = pl
                pl.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        video.animate().alpha(1f).setDuration(500).start(); poster.animate().alpha(0f).setDuration(300).start()
                        fadeSound(1f)
                    }
                    override fun onPlayerError(error: PlaybackException) { video.animate().alpha(0f).setDuration(300).start(); playingId = -1 }
                })
            }
        playingId = ch.streamId
        // a new channel starts silent and its sound rises once the picture is up
        volumeAnim?.cancel(); p.volume = 0f
        video.animate().alpha(0f).setDuration(150).start()
        val server = prefs.getServer().trim().trimEnd('/')
        p.setMediaItem(MediaItem.fromUri("$server/live/${prefs.getUsername().trim()}/${prefs.getPassword().trim()}/${ch.streamId}.m3u8"))
        p.prepare(); p.playWhenReady = true
        idle = false; armIdle()
    }

    private var volumeAnim: ValueAnimator? = null
    private fun fadeSound(target: Float) {
        val p = player ?: return
        volumeAnim?.cancel()
        volumeAnim = ValueAnimator.ofFloat(p.volume, target).apply {
            duration = if (target > 0f) 900 else 300
            addUpdateListener { player?.volume = it.animatedValue as Float }
            start()
        }
    }

    // the preview must not hold one of the account's connections while nobody uses the app
    private var idle = false
    private val idleStop = Runnable { stopForIdle() }
    private fun armIdle() { handler.removeCallbacks(idleStop); handler.postDelayed(idleStop, IDLE_MS) }
    private fun userActive() { if (!started) return; if (idle) heroCh?.let { playingId = -1; startVideo(it) } else if (playingId != -1) armIdle() }
    private fun stopForIdle() {
        if (!started || playingId == -1) return
        idle = true; playingId = -1
        fadeSound(0f)
        if (backdrop.alpha < 0.5f) poster.animate().alpha(0.9f).setDuration(600).start()
        video.animate().alpha(0f).setDuration(600).withEndAction { if (idle) player?.stop() }.start()
    }

    private fun stopPreview() {
        handler.removeCallbacks(idleStop); pendingPreview?.let { handler.removeCallbacks(it) }
        volumeAnim?.cancel(); player?.volume = 0f; player?.stop(); playingId = -1; video.alpha = 0f
    }

    // ------------------------------------------------------------------------------------------------ actions
    private fun watch(ch: ChannelEntity) {
        stopPreview() // free the connection before the full player takes one
        ChannelLauncher.play(this, channels.ifEmpty { listOf(ch) }, ch)
    }

    private fun options(ch: ChannelEntity) {
        val fav = ch.streamId in favChannelIds
        val items = listOf<Pair<String, () -> Unit>>(
            "Watch full screen" to { watch(ch) },
            "Watch in MultiView" to { multiView(ch) },
            (if (fav) "Remove from Favorites" else "Add to Favorites") to { toggleFavorite(ch) },
            "TV Guide" to { stopPreview(); startActivity(Intent(this, TvGuideActivity::class.java)) },
        ).let { base ->
            // in Favorites the list can be arranged like a cable line-up
            if (catKey != "fav") base else listOf<Pair<String, () -> Unit>>(
                "Move up" to { moveFav(ch, -1) }, "Move down" to { moveFav(ch, 1) },
                "Move to top" to { moveFav(ch, Int.MIN_VALUE) }, "Move to bottom" to { moveFav(ch, Int.MAX_VALUE) },
                "Sort favorites: " + FavoritesOrder.label(FavoritesOrder.mode(this)) + "…" to { sortFavs() },
            ) + base
        }
        showChoiceDialog(title = cleanName(ch.name), items = items.map { it.first }, selectedIndex = -1, focusIndex = 0) { which -> items[which].second() }
    }

    /** [step]: -1 / +1 one place, Int.MIN_VALUE = top, Int.MAX_VALUE = bottom. Arranging switches the sort to My order. */
    private fun moveFav(ch: ChannelEntity, step: Int) {
        val order = channels.map { it.streamId }.toMutableList()
        val from = order.indexOf(ch.streamId)
        if (from < 0) return
        val to = when (step) { Int.MIN_VALUE -> 0; Int.MAX_VALUE -> order.size - 1; else -> (from + step).coerceIn(0, order.size - 1) }
        if (to == from) return
        order.removeAt(from); order.add(to, ch.streamId)
        // favorites hidden by the parental lock keep a place after the visible ones
        val full = order + favOrder.filter { it !in order }
        if (FavoritesOrder.mode(this) != FavoritesOrder.MY) FavoritesOrder.setMode(this, FavoritesOrder.MY)
        favOrder = full
        channels = emptyList(); selectCategory("fav", focusList = false); focusChannel(to)
        lifecycleScope.launch { runCatching { favRepo.saveOrder(prefs.getUsername(), FavoriteItemType.LIVE_CHANNEL, full.map { it.toString() }) } }
    }

    private fun sortFavs() {
        val modes = listOf(FavoritesOrder.MY, FavoritesOrder.NAME, FavoritesOrder.NUM)
        showChoiceDialog(title = "Sort favorites", items = modes.map { FavoritesOrder.label(it) }, selectedIndex = modes.indexOf(FavoritesOrder.mode(this))) { i ->
            FavoritesOrder.setMode(this, modes[i])
            channels = emptyList(); selectCategory("fav", focusList = true)
        }
    }

    private fun multiView(ch: ChannelEntity) {
        stopPreview()
        val list = channels.ifEmpty { listOf(ch) }
        PlayerState.channels.clear(); PlayerState.channels.addAll(list.map { it.toLiveChannel() })
        PlayerState.currentPosition = list.indexOfFirst { it.streamId == ch.streamId }.coerceAtLeast(0)
        startActivity(Intent(this, MultiViewActivity::class.java))
    }

    private fun toggleFavorite(ch: ChannelEntity) {
        val isFav = ch.streamId in favChannelIds
        lifecycleScope.launch {
            runCatching {
                if (isFav) favRepo.removeFavorite(prefs.getUsername(), FavoriteItemType.LIVE_CHANNEL, ch.streamId.toString())
                else favRepo.addFavorite(prefs.getUsername(), FavoriteItemType.LIVE_CHANNEL, ch.streamId.toString())
            }.onSuccess {
                favChannelIds = if (isFav) favChannelIds - ch.streamId else favChannelIds + ch.streamId
                Toast.makeText(this@LiveTvActivity, "${cleanName(ch.name)} ${if (isFav) "removed from" else "added to"} Favorites", Toast.LENGTH_SHORT).show()
                val pos = channels.indexOfFirst { it.streamId == ch.streamId }
                // the Favorites entry in the list of categories appears / disappears / changes its count
                val focusPos = lastChannelPos
                reloadAll(keepCat = true)
                if (catKey != "fav" && pos >= 0) { listAdapter.notifyItemChanged(pos); focusChannel(focusPos) }
            }.onFailure { Toast.makeText(this@LiveTvActivity, "Could not update Favorites", Toast.LENGTH_SHORT).show() }
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
            val r = runCatching { Web24Api(this@LiveTvActivity).support("art", "titles" to batch.joinToString("\n")) }.getOrNull()
            val art = r?.optJSONObject("art")
            if (r == null || art == null || r.optBoolean("limited")) batch.forEach { artWait.remove(it) }
            else batch.forEach { t -> val a = art.optJSONObject(t); artDone[t] = a; val cbs = artWait.remove(t).orEmpty(); if (a != null) cbs.forEach { it(a) } }
            artFlushing = false
            if (artQueue.isNotEmpty()) handler.postDelayed(artFlush, 50)
        }
    }

    // ------------------------------------------------------------------------------------------------ lifecycle
    private val tick = object : Runnable {
        override fun run() {
            // shows end: once a minute the list and the billboard move on (and the guide is read again at a change)
            val now = System.currentTimeMillis()
            if (guide.values.any { (it.now?.stopTimestamp ?: Long.MAX_VALUE) <= now }) loadGuide(channels)
            else { listAdapter.notifyItemRangeChanged(0, channels.size, TICK); heroCh?.let { showHero(it) } }
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        handler.postDelayed(tick, 60_000)
        heroCh?.let { playingId = -1; startVideo(it) }
    }

    override fun onStop() {
        started = false
        handler.removeCallbacks(tick)
        stopPreview(); idle = false
        super.onStop()
    }

    override fun onRestart() {
        super.onRestart()
        // back from the player, Settings or a PIN change: favourites / lock / hidden categories may have changed
        lifecycleScope.launch { reloadAll(keepCat = true) }
    }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); player?.release(); player = null; super.onDestroy() }

    companion object {
        private const val IDLE_MS = 20_000L
        private const val TICK = "tick"
        /** Open on this category (Home "All …" rows). */
        const val EXTRA_CATEGORY_ID = "category_id"
        /** A list of channels (an event or game) shown first, under EXTRA_TITLE, in the given order. */
        const val EXTRA_STREAM_IDS = "stream_ids"
        const val EXTRA_TITLE = "category_name"
    }
}
