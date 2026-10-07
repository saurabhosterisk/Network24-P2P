package com.network24.player.features.dashboard.home

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.load
import com.network24.player.R
import com.network24.player.common.models.FavoriteItemType
import com.network24.player.core.api.Web24Api
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.entity.ChannelEntity
import com.network24.player.core.database.entity.EpgEntity
import com.network24.player.core.database.mapper.toLiveChannel
import com.network24.player.core.database.repository.LiveHistoryRepository
import com.network24.player.core.net.StreamDataSourceFactory
import com.network24.player.core.parental.ParentalLock
import com.network24.player.core.preferences.PreferenceManager
import com.network24.player.features.discover.ChannelLauncher
import com.network24.player.features.discover.EventCenter
import com.network24.player.features.discover.Fmt
import com.network24.player.features.discover.GameCenter
import com.network24.player.features.player.multiview.MultiViewActivity
import com.network24.player.features.player.state.PlayerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Manrope (bundled, SIL OFL) at the weights the home uses; the system font on Android 7 and older. */
object HomeFont {
    private val cache = HashMap<Int, Typeface>()
    fun of(c: Context, weight: Int): Typeface = cache.getOrPut(weight) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) runCatching {
            Typeface.Builder(c.assets, "fonts/manrope.ttf").setFontVariationSettings("'wght' $weight").build()
        }.getOrNull() ?: fallback(weight) else fallback(weight)
    }
    private fun fallback(w: Int) = if (w >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
}

/**
 * The home screen.
 *
 * A live billboard fills the top of the screen: the last channel watched plays muted behind its name, the
 * programme on now (time, minutes left, progress) and the next one. Resting the remote on any channel card puts
 * that channel on the billboard, live. Text tabs on top lead to the sections; rows below show Continue watching,
 * Live sports (real team logos from ESPN), Your favorites and Trending. Everything shown is real content.
 */
@OptIn(UnstableApi::class)
class HomeScreen(
    private val act: AppCompatActivity,
    private val openMenu: () -> Unit,
    private val openAccount: () -> Unit,
    private val tabs: List<Pair<String, () -> Unit>>,
    private val support: () -> Unit,
) {
    // the page is laid out for a screen at least 960 x 400 dp (TV, big phones in landscape); smaller screens
    // (16:9 phones, small tablets) get the same page scaled down evenly, so the top bar and billboard never
    // squeeze or run off the edge. Text follows the same scale; the system font size counts up to +15%.
    private val uiScale = act.resources.displayMetrics.let { m -> minOf(1f, m.widthPixels / m.density / 960f, m.heightPixels / m.density / 400f) }
    private val d = act.resources.displayMetrics.density * uiScale
    private val fontD = d * act.resources.configuration.fontScale.coerceIn(0.85f, 1.15f)
    private fun dp(v: Int) = (v * d).toInt()
    private fun dpf(v: Float) = v * d
    private fun TextView.size(sp: Float) = setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sp * fontD)
    private val prefs = PreferenceManager(act)
    private val db by lazy { DatabaseProvider.get(act) }
    private val handler = Handler(Looper.getMainLooper())
    private val touch = act.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
    private val screenH = act.resources.displayMetrics.heightPixels
    private val screenW = act.resources.displayMetrics.widthPixels

    // palette: neutral near-black, one accent, red only for LIVE
    private val bg = Color.parseColor("#08090C")
    private val surface = Color.parseColor("#14161B")
    private val line = Color.parseColor("#1FFFFFFF")
    private val textMain = Color.parseColor("#F2F3F5")
    private val textSub = Color.parseColor("#9BA1AD")
    private val accent = Color.parseColor("#A894FF")
    private val live = Color.parseColor("#E5484D")
    private val gold = Color.parseColor("#F5B841")

    val root = FrameLayout(act).apply { setBackgroundColor(bg) }

    private lateinit var video: PlayerView
    private lateinit var backdrop: ImageView
    private lateinit var poster: ImageView
    private lateinit var heroArtLogo: ImageView
    private lateinit var heroChLogo: ImageView
    private lateinit var heroBox: LinearLayout
    private lateinit var heroLive: TextView
    private lateinit var heroTag: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroProgram: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroBar: ProgressBar
    private lateinit var heroNext: TextView
    private lateinit var btnWatch: View
    private lateinit var btnMulti: View
    private lateinit var clock: TextView
    private lateinit var chip: ImageView
    private lateinit var rows: LinearLayout
    private lateinit var scroll: ScrollView

    private var player: ExoPlayer? = null
    private var heroChannel: ChannelEntity? = null
    private var heroList: List<ChannelEntity> = emptyList()
    private var recentFirst = -1
    private var playingId = -1
    private var started = false
    private var pendingHero: Runnable? = null

    private val tick = object : Runnable {
        override fun run() {
            heroChannel?.let { refreshEpg(it) }
            handler.postDelayed(this, 30_000)
        }
    }

    init { build() }

    // ------------------------------------------------------------------------------------------------ building blocks
    private fun text(s: String, size: Float, color: Int = textMain, weight: Int = 500) = TextView(act).apply {
        text = s; size(size); setTextColor(color); typeface = HomeFont.of(act, weight)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
    }

    private fun shape(fill: Int, radius: Float, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dpf(radius); if (stroke != 0) setStroke(dp(1), stroke)
    }

    /** Focus: slightly larger, a white ring, lifted. Unfocused: back in line. */
    private fun focusable(v: View, radius: Float, scale: Float = 1.06f, ringColor: Int = Color.WHITE, onFocus: (() -> Unit)? = null) {
        v.isFocusable = true; v.isClickable = true
        // the stroke is drawn half a stroke inside the edge: a smaller corner radius makes its outer edge follow the
        // view's own rounded corners exactly (with the same radius the white corners showed outside the ring)
        val stroke = dp(3)
        val ring = GradientDrawable().apply { cornerRadius = (dpf(radius) - stroke / 2f).coerceAtLeast(0f); setStroke(stroke, ringColor); setColor(Color.TRANSPARENT) }
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).translationZ(if (has) dpf(10f) else 0f).setDuration(150).start()
            view.foreground = if (has) ring else null
            if (has) { keepInView(view); onFocus?.invoke() }
        }
    }

    private fun keepInView(v: View) {
        val loc = IntArray(2); v.getLocationInWindow(loc)
        when {
            loc[1] + v.height > screenH * 0.94f -> scroll.smoothScrollBy(0, (loc[1] + v.height - screenH * 0.86f).toInt())
            loc[1] < screenH * 0.1f && scroll.scrollY > 0 -> scroll.smoothScrollBy(0, (loc[1] - screenH * 0.25f).toInt())
        }
    }

    private fun build() {
        // the picture's height follows the screen shape: 70% on 16:9 (TV) and narrower screens, growing to the full
        // height on 20:9 phones and wider, so the billboard text and buttons always sit on the picture and a 16:9
        // picture is never cut into a thin strip; in between it grows smoothly
        val tall = ((screenW.toFloat() / screenH - 16f / 9f) / (20f / 9f - 16f / 9f)).coerceIn(0f, 1f)
        val heroH = (screenH * (0.70f + 0.30f * tall)).toInt()
        video = PlayerView(act).apply {
            useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            setShutterBackgroundColor(Color.TRANSPARENT); alpha = 0f
        }
        // the programme's artwork (TMDB) fills the billboard; the live picture fades in over it
        backdrop = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, heroH))
        root.addView(video, FrameLayout.LayoutParams(-1, heroH))
        // until the picture starts: the channel's own logo, large and quiet, where the picture will be
        poster = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_CENTER; alpha = 0f }
        root.addView(poster, FrameLayout.LayoutParams((screenW * 0.30f).toInt(), (heroH * 0.40f).toInt(), Gravity.TOP or Gravity.END).apply {
            topMargin = (heroH * 0.22f).toInt(); marginEnd = (screenW * 0.10f).toInt()
        })
        root.addView(View(act).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF508090C.toInt(), 0xCC08090C.toInt(), 0x4008090C, 0x0008090C))
        }, FrameLayout.LayoutParams((screenW * 0.75f).toInt(), heroH))
        // bottom fade; the more of the picture lies behind the buttons (a taller picture, or a scaled-down page
        // whose buttons sit higher), the higher and darker it starts, so the channel's own captions never show
        // through the buttons
        val under = maxOf(tall, ((1f - uiScale) * 3f).coerceIn(0f, 1f))
        val fadeTop = 0.55f - 0.15f * under
        val fadeMid = 0xB3 + ((0xE0 - 0xB3) * under).toInt()
        root.addView(View(act).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x0008090C, (fadeMid shl 24) or 0x08090C, bg))
        }, FrameLayout.LayoutParams(-1, (heroH * (1f - fadeTop)).toInt(), Gravity.TOP).apply { topMargin = (heroH * fadeTop).toInt() + 1 })
        root.addView(View(act).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xD908090C.toInt(), 0x0008090C))
        }, FrameLayout.LayoutParams(-1, dp(120)))

        scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; isFocusable = false }
        scroll.viewTreeObserver.addOnScrollChangedListener { nearBottom(); fadeSound() }
        // nothing clips: a focused (enlarged) button or card must show whole, also at the left edge
        val page = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(48), dp(24), 0, dp(40)); clipChildren = false; clipToPadding = false }
        scroll.addView(page)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        page.addView(topBar(), LinearLayout.LayoutParams(-1, -2).apply { marginEnd = dp(48) })
        // the billboard text sits low in the picture; the first row starts where the picture fades out
        heroBox = hero()
        val heroArea = FrameLayout(act).apply { clipChildren = false; clipToPadding = false; minimumHeight = (screenH * 0.66f).toInt() - dp(24 + 42 + 36) }
        heroArea.addView(heroBox, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        page.addView(heroArea, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(36) })
        rows = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        page.addView(rows, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
    }

    private fun topBar() = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        // brand: the N mark exactly as tall as the two-line wordmark next to it (NETWORK24 / FUTURE)
        val mark = ImageView(act).apply { setImageResource(R.drawable.app_mark); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
        val words = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(24), 0)
            addView(text("NETWORK24", 16f, weight = 800).apply { letterSpacing = 0.02f }, LinearLayout.LayoutParams(-2, -2))
            addView(text("FUTURE", 10f, weight = 800).apply {
                letterSpacing = 0.08f; setPadding(dp(1), 0, 0, 0)
                paint.shader = android.graphics.LinearGradient(0f, 0f, dpf(70f), 0f, Color.parseColor("#7C5CFF"), Color.parseColor("#22D3EE"), android.graphics.Shader.TileMode.CLAMP)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = -dp(3) })
        }
        addView(mark, LinearLayout.LayoutParams(-2, dp(28)))
        addView(words)
        words.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h > 0 && mark.layoutParams.height != h) mark.post { mark.layoutParams = mark.layoutParams.apply { height = h }; mark.requestLayout() }
        }
        val tabRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tabs.forEachIndexed { i, (label, onClick) ->
            val home = i == 0
            val t = text(label, 14f, if (home) textMain else textSub, if (home) 700 else 600).apply {
                setPadding(dp(11), dp(9), dp(11), dp(9))
                background = if (home) shape(0x1FFFFFFF, 18f) else null
                setOnClickListener { if (!home) onClick() }
            }
            focusable(t, 18f, 1.04f)
            tabRow.addView(t, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(4) })
        }
        addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(tabRow) }, LinearLayout.LayoutParams(0, -2, 1f))
        clock = text("", 1f)
        addView(iconButton(R.drawable.ic_h_search, "Search") {
            com.network24.player.features.search.SearchOverlay.show(act)
        })
        addView(iconButton(R.drawable.ic_live_chat, "Live Support") { support() }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(10) })
        // account: a round icon like its neighbours (keeps room for the tabs); opens the user info page
        chip = iconButton(R.drawable.ic_h_account, "Account") { openAccount() }
        addView(chip, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(10) })
        addView(iconButton(R.drawable.ic_more_vert, "Menu") { openMenu() }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(10) })
    }

    private fun iconButton(icon: Int, label: String, onClick: () -> Unit) = ImageView(act).apply {
        setImageResource(icon); setColorFilter(textMain); setPadding(dp(10), dp(10), dp(10), dp(10)); contentDescription = label
        background = shape(0x1AFFFFFF, 21f); setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        focusable(this, 21f, 1.08f)
    }

    private fun hero() = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
        val tagRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heroLive = text("LIVE", 11f, Color.WHITE, 800).apply {
            letterSpacing = 0.12f; setPadding(dp(8), dp(4), dp(8), dp(4)); background = shape(live, 4f)
        }
        tagRow.addView(heroLive)
        heroChLogo = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        tagRow.addView(heroChLogo, LinearLayout.LayoutParams(dp(44), dp(24)).apply { marginStart = dp(12) })
        heroTag = text("", 12f, Color.parseColor("#D9F2F3F5"), 700).apply { letterSpacing = 0.12f; setPadding(dp(10), 0, 0, 0) }
        tagRow.addView(heroTag)
        addView(tagRow)
        heroArtLogo = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_START; adjustViewBounds = true; visibility = View.GONE; maxHeight = dp(120); maxWidth = (screenW * 0.42f).toInt() }
        addView(heroArtLogo, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        heroTitle = text("", 42f, weight = 800).apply { letterSpacing = -0.025f; setPadding(0, dp(12), 0, 0); setShadowLayer(dpf(12f), 0f, dpf(2f), 0x99000000.toInt()) }
        addView(heroTitle, LinearLayout.LayoutParams((screenW * 0.55f).toInt(), -2))
        heroProgram = text("", 16f, Color.parseColor("#CCF2F3F5"), 500).apply { setPadding(0, dp(8), 0, 0); visibility = View.GONE }
        addView(heroProgram, LinearLayout.LayoutParams((screenW * 0.5f).toInt(), -2))
        val metaRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, 0) }
        heroMeta = text("", 14f, textSub, 600).apply { fontFeatureSettings = "tnum" }
        metaRow.addView(heroMeta)
        heroBar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progressDrawable = act.getDrawable(R.drawable.home_progress) }
        metaRow.addView(heroBar, LinearLayout.LayoutParams(dp(200), dp(4)).apply { marginStart = dp(14) })
        addView(metaRow)
        heroNext = text("", 13f, textSub, 500).apply { setPadding(0, dp(8), 0, 0) }
        addView(heroNext)
        val buttons = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        btnWatch = button("Watch now", R.drawable.ic_play, true) { heroChannel?.let { ChannelLauncher.play(act, heroList.ifEmpty { listOf(it) }, it) } }
        buttons.addView(btnWatch)
        btnMulti = button("MultiView", R.drawable.ic_grid, false) { multiView() }
        buttons.addView(btnMulti, LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(12) })
        addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
    }

    private fun button(label: String, icon: Int, primary: Boolean, onClick: () -> Unit): View = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), 0, dp(24), 0)
        background = shape(if (primary) Color.WHITE else 0x26FFFFFF, 10f)
        layoutParams = LinearLayout.LayoutParams(-2, dp(48))
        addView(ImageView(act).apply { setImageResource(icon); setColorFilter(if (primary) bg else textMain) }, LinearLayout.LayoutParams(dp(20), dp(20)))
        addView(text(label, 15f, if (primary) bg else textMain, 700).apply { setPadding(dp(10), 0, 0, 0) })
        setOnClickListener { onClick() }
        // the white button gets a brand-violet ring (a white ring would not show on it)
        focusable(this, 10f, 1.06f, if (primary) Color.parseColor("#7C5CFF") else Color.WHITE)
    }

    // ------------------------------------------------------------------------------------------------ rows
    private fun addRow(key: String, title: String, sub: String, items: List<View>, seeAll: Pair<String, () -> Unit>? = null) {
        rows.findViewWithTag<View>(key)?.let { rows.removeView(it) }
        if (items.isEmpty()) return
        val views = items.take(MAX_ROW) + listOfNotNull(seeAll?.let { (label, open) -> seeAllCard(label, open) })
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; tag = key }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        head.addView(text(title, 19f, weight = 700).apply { letterSpacing = -0.01f })
        if (sub.isNotBlank()) head.addView(text(sub, 13f, textSub, 500).apply { setPadding(dp(12), 0, 0, dp(1)) })
        box.addView(head)
        val strip = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        views.forEachIndexed { i, v -> strip.addView(v, (v.layoutParams as LinearLayout.LayoutParams).apply { if (i > 0) marginStart = dp(16) }) }
        // RIGHT on the last card / LEFT on the first stays in the row (it jumped to the row above)
        views.forEach { if (it.id == View.NO_ID) it.id = View.generateViewId() }
        views.last().nextFocusRightId = views.last().id
        views.first().nextFocusLeftId = views.first().id
        // room on the left so the focused (enlarged) first card is not cut off at the screen edge
        box.addView(HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false; clipChildren = false
            setPadding(dp(16), dp(14), dp(48), dp(18)); addView(strip)
        }.also { hs ->
            // a rebuilt row starts at its first card (after an update it opened scrolled, the first card cut at the edge)
            hs.post { if (!hs.hasFocus()) hs.scrollTo(0, 0) }
        }, LinearLayout.LayoutParams(-1, -2).apply { marginStart = -dp(16) })
        var at = rows.childCount
        for (i in 0 until rows.childCount) if (rank(rows.getChildAt(i).tag as? String) > rank(key)) { at = i; break }
        rows.addView(box, at, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private val fixedRows = listOf("continue", "sports", "favorites", "trending")
    private fun rank(key: String?): Int = fixedRows.indexOf(key).takeIf { it >= 0 }
        ?: key?.removePrefix("cat:")?.let { id -> 100 + catPlan.indexOfFirst { it.categoryId == id } } ?: 999

    /** Last card of a row: opens the full list behind it. */
    private fun seeAllCard(label: String, open: () -> Unit) = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        background = shape(0x14FFFFFF, 14f, line)
        layoutParams = LinearLayout.LayoutParams(dp(170), dp(162))
        addView(TextView(act).apply {
            text = "→"; size(26f); setTextColor(textMain); gravity = Gravity.CENTER; typeface = HomeFont.of(act, 600)
            background = shape(0x1FFFFFFF, 26f)
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        addView(text("See all", 15f, weight = 700).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) })
        addView(text(label, 12f, textSub, 500).apply { gravity = Gravity.CENTER; setPadding(dp(10), dp(4), dp(10), 0) })
        setOnClickListener { open() }
        focusable(this, 14f)
    }

    private fun openAct(cls: Class<*>) = act.startActivity(Intent(act, cls))

    private val prefix = Regex("^[A-Z]{2,3}\\s*\\|\\s*")
    private fun cleanName(n: String?) = n.orEmpty().replace(prefix, "").trim()

    // TV guides tag titles with superscript letters ("Good Morning America ᴺᵉʷ"): drop them for display and artwork search
    private val marks = Regex("[\u02b0-\u02ff\u1d00-\u1dbf\u2070-\u209f]+")
    private fun cleanProgram(t: String?) = t.orEmpty().replace(marks, "").replace(Regex("\\s+"), " ").trim()

    /**
     * Channel card, 16:9: the artwork of the programme on now (TMDB) with the channel's logo as a badge, the
     * programme name and how far it is. Until the artwork arrives (or when there is none): the channel's logo.
     */
    private fun channelCard(ch: ChannelEntity, now: EpgEntity?, list: List<ChannelEntity>) = FrameLayout(act).apply {
        background = shape(surface, 14f, line)
        clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        layoutParams = LinearLayout.LayoutParams(dp(288), dp(162))
        val logo = ImageView(act).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            load(ch.icon?.takeIf { it.isNotBlank() }) { placeholder(R.drawable.app_logo); error(R.drawable.app_logo) }
        }
        addView(logo, FrameLayout.LayoutParams(dp(132), dp(62), Gravity.CENTER).apply { bottomMargin = dp(26) })
        val pic = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        addView(pic, FrameLayout.LayoutParams(-1, -1))
        addView(View(act).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0x33000000, 0xF0000000.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))
        val badge = ImageView(act).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(6), dp(4), dp(6), dp(4)); background = shape(0xB3000000.toInt(), 8f); visibility = View.GONE
            load(ch.icon?.takeIf { it.isNotBlank() })
        }
        addView(badge, FrameLayout.LayoutParams(dp(62), dp(34), Gravity.TOP or Gravity.START).apply { setMargins(dp(10), dp(10), 0, 0) })
        val prog = cleanProgram(now?.title).takeIf { it.isNotBlank() }
        val info = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(14), dp(16)) }
        info.addView(text(prog ?: cleanName(ch.name), 15f, weight = 700))
        info.addView(text(if (prog != null) cleanName(ch.name) else "Live", 12f, textSub, 600).apply { setPadding(0, dp(4), 0, 0) })
        addView(info, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        progressOf(now).takeIf { it >= 0 }?.let { p ->
            addView(ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; progress = p; progressDrawable = act.getDrawable(R.drawable.home_progress) },
                FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM).apply { setMargins(dp(14), 0, dp(14), dp(8)) })
        }
        if (prog != null) art(listOf(prog)) { _, a ->
            val url = a.optString("backdrop").ifBlank { return@art }.replace("/w1280/", "/w500/")
            pic.load(url) { crossfade(true); listener(onSuccess = { _, _ -> pic.animate().alpha(1f).setDuration(350).start(); logo.visibility = View.GONE; badge.visibility = View.VISIBLE }) }
        }
        setOnClickListener { ChannelLauncher.play(act, list, ch) }
        focusable(this, 14f) {
            pendingHero?.let { handler.removeCallbacks(it) }
            pendingHero = Runnable { showHero(ch, list, null) }.also { handler.postDelayed(it, 650) }
        }
    }

    // ------------------------------------------------------------------------------------------------ artwork
    private val artDone = HashMap<String, JSONObject?>()
    private val artWait = HashMap<String, MutableList<(String, JSONObject) -> Unit>>()

    private val artQueue = LinkedHashSet<String>()
    private var artFlushing = false
    private val artFlush = Runnable { flushArt() }

    /**
     * Programme artwork from Main (support_api "art": TMDB backdrop + title logo, cached there 30 days). Titles
     * asked for by many cards are collected for a moment and sent 24 per request (one request per card ran into
     * the server's per-minute limit and most cards stayed without a picture).
     */
    private fun art(titles: Collection<String>, onArt: (String, JSONObject) -> Unit) {
        titles.filter { it.isNotBlank() }.distinct().forEach { t ->
            when {
                artDone.containsKey(t) -> artDone[t]?.let { onArt(t, it) }
                artWait.containsKey(t) -> artWait[t]!!.add(onArt)
                else -> { artWait[t] = mutableListOf(onArt); artQueue += t }
            }
        }
        if (artQueue.isNotEmpty()) { handler.removeCallbacks(artFlush); handler.postDelayed(artFlush, 120) }
    }

    private fun flushArt() {
        if (artFlushing || artQueue.isEmpty()) return
        artFlushing = true
        val batch = artQueue.take(24); artQueue.removeAll(batch.toSet())
        act.lifecycleScope.launch {
            val r = runCatching { Web24Api(act).support("art", "titles" to batch.joinToString("\n")) }.getOrNull()
            val art = r?.optJSONObject("art")
            if (r == null || art == null || r.optBoolean("limited")) {
                // not answered (or the server's limit): ask again later, nothing is remembered as "no artwork"
                batch.forEach { artWait.remove(it) }
            } else batch.forEach { t ->
                val a = art.optJSONObject(t)
                artDone[t] = a
                val cbs = artWait.remove(t).orEmpty()
                if (a != null) cbs.forEach { it(t, a) }
            }
            artFlushing = false
            if (artQueue.isNotEmpty()) handler.postDelayed(artFlush, if (r?.optBoolean("limited") == true) 20_000 else 50)
        }
    }

    private fun heroArt(streamId: Int, title: String) = art(listOf(title)) { _, a ->
        if (heroChannel?.streamId != streamId) return@art
        a.optString("backdrop").takeIf { it.isNotBlank() }?.let { u ->
            backdrop.load(u) { crossfade(true); listener(onSuccess = { _, _ ->
                if (heroChannel?.streamId == streamId) { backdrop.animate().alpha(1f).setDuration(500).start(); poster.animate().alpha(0f).setDuration(300).start() }
            }) }
        }
        a.optString("logo").takeIf { it.isNotBlank() }?.let { u ->
            heroArtLogo.load(u) { listener(onSuccess = { _, _ ->
                if (heroChannel?.streamId == streamId) { heroArtLogo.visibility = View.VISIBLE; heroTitle.visibility = View.GONE }
            }) }
        }
    }

    /** Game / event card from Live Sports: league, LIVE or start time, both teams with their real logos and score. */
    private fun gameCard(g: JSONObject) = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
        background = shape(surface, 12f, line)
        layoutParams = LinearLayout.LayoutParams(dp(288), dp(162))
        val state = g.optString("state")
        val start = g.optLong("start") * 1000
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text((if (g.optBoolean("mine")) "★  " else "") + g.optString("league_name").uppercase(), 11f, if (g.optBoolean("mine")) gold else textSub, 700).apply { letterSpacing = 0.1f }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(if (state == "in") text("LIVE", 10f, Color.WHITE, 800).apply { letterSpacing = 0.1f; setPadding(dp(6), dp(3), dp(6), dp(3)); background = shape(live, 4f) }
            else text("${Fmt.day(start)} · ${Fmt.clock(start)}", 11f, textSub, 600))
        addView(head)
        val teams = Web24Api.objects(g.optJSONArray("teams"))
        if (!g.has("kind") && teams.size == 2) {
            val away = teams.firstOrNull { !it.optBoolean("home") } ?: teams[0]
            val home = teams.firstOrNull { it !== away } ?: teams[1]
            val a = away.optString("score").toIntOrNull(); val h = home.optString("score").toIntOrNull()
            listOf(away to (a != null && h != null && a < h), home to (a != null && h != null && h < a)).forEach { (t, behind) ->
                val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
                r.addView(ImageView(act).apply { GameCenter.logo(this, t.optString("logo")) }, LinearLayout.LayoutParams(dp(28), dp(28)))
                r.addView(text(GameCenter.short(t), 15f, textMain, 700).apply { setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                if (state != "pre") r.addView(text(t.optString("score"), 18f, if (behind) textSub else textMain, 800).apply { fontFeatureSettings = "tnum" })
                addView(r)
            }
        } else {
            addView(text(g.optString("name"), 15f, weight = 700).apply { maxLines = 2; setPadding(0, dp(12), 0, 0) })
            val sub = g.optString("headline").ifBlank { EventCenter.list(g.optJSONArray("live")).firstOrNull().orEmpty() }
            if (sub.isNotBlank()) addView(text(sub, 12f, textSub, 500).apply { maxLines = 2; setPadding(0, dp(6), 0, 0) })
        }
        setOnClickListener { if (g.has("kind")) EventCenter.open(act, g, g.optString("league_name")) else GameCenter.open(act, g) }
        focusable(this, 12f)
    }

    // ------------------------------------------------------------------------------------------------ data
    fun load() {
        act.lifecycleScope.launch {
            val locked = hiddenCategories()
            val recent = withContext(Dispatchers.IO) {
                val ids = runCatching { LiveHistoryRepository(act).getRecentlyWatched().mapNotNull { it.stream_id } }.getOrDefault(emptyList())
                val by = db.channelDao().getByStreamIds(ids).associateBy { it.streamId }
                ids.mapNotNull { by[it] }.filter { it.categoryId == null || it.categoryId !in locked }.take(MAX_ROW)
            }
            val favs = withContext(Dispatchers.IO) {
                val ids = db.favoritesDao().getByType(FavoriteItemType.LIVE_CHANNEL).mapNotNull { it.itemId.toIntOrNull() }
                val by = db.channelDao().getByStreamIds(ids).associateBy { it.streamId }
                ids.mapNotNull { by[it] }.filter { it.categoryId == null || it.categoryId !in locked }.take(MAX_ROW)
            }
            // the billboard was on a channel that is hidden now (category locked / adult): it is taken off
            if (heroChannel?.categoryId?.let { it in locked } == true) {
                player?.stop(); playingId = -1; video.alpha = 0f; heroChannel = null
            }
            val newest = recent.firstOrNull()?.streamId ?: -1
            val watchedSomethingNew = newest != -1 && newest != recentFirst && recentFirst != -1
            recentFirst = newest
            val now = nowPlaying(recent + favs)
            // back from watching another channel: it takes over the billboard
            if (watchedSomethingNew) recent.firstOrNull()?.let { showHero(it, recent, now[it.epgChannelId]) }
            if (heroChannel == null) {
                val first = recent.firstOrNull() ?: favs.firstOrNull()
                if (first != null) showHero(first, recent.ifEmpty { favs }, now[first.epgChannelId]) else welcome()
            }
            addRow("continue", "Continue watching", "live now on the channels you watched", recent.map { channelCard(it, now[it.epgChannelId], recent) },
                "Recently watched" to { openAct(com.network24.player.features.live.activity.RecentlyWatchedActivity::class.java) })
            addRow("favorites", "Your favorites", "", favs.map { channelCard(it, now[it.epgChannelId], favs) },
                "All favorites" to { openAct(com.network24.player.features.live.activity.FavoriteChannelsActivity::class.java) })
            focusStart()
            loadSports()
            loadTrending(locked)
            planCategories(recent, locked)
        }
    }

    /**
     * Categories the home never shows or plays (the TV is shared at home): the ones under the parental lock - also
     * while it is opened with the PIN - and every adult category (ADULT / XXX / 18+ in its name).
     */
    private suspend fun hiddenCategories(): Set<String> {
        val locked = if (ParentalLock.isEnabled(act)) ParentalLock.lockedIds(act) else emptySet()
        val adult = Regex("ADULT|XXX|18\\+", RegexOption.IGNORE_CASE)
        val adultIds = withContext(Dispatchers.IO) {
            db.categoryDao().getByType(com.network24.player.core.database.entity.CategoryType.LIVE).filter { adult.containsMatchIn(it.name) }.map { it.categoryId }
        }
        return locked + adultIds
    }

    private var focusedOnce = false
    private fun focusStart() {
        if (touch) return
        val target = if (btnWatch.visibility == View.VISIBLE) btnWatch else rows.getChildAt(0)
        // first time: Watch now (Android puts the focus on the first tab); later only when nothing has it
        target?.post { if (!focusedOnce || !root.hasFocus()) { target.requestFocus(); focusedOnce = true } }
    }

    private fun loadTrending(locked: Set<String>) = act.lifecycleScope.launch {
        val pop = runCatching { Web24Api.objects(Web24Api(act).support("popular").optJSONArray("streams")).map { it.optInt("stream_id") } }.getOrDefault(emptyList())
        if (pop.isEmpty()) return@launch
        val list = withContext(Dispatchers.IO) {
            val by = db.channelDao().getByStreamIds(pop).associateBy { it.streamId }
            pop.mapNotNull { by[it] }.filter { it.categoryId == null || it.categoryId !in locked }.take(MAX_ROW)
        }
        val now = nowPlaying(list)
        addRow("trending", "Trending on Network24", "what most viewers are watching right now", list.map { channelCard(it, now[it.epgChannelId], list) },
            "Top 40 now" to { openAct(com.network24.player.features.discover.TrendingActivity::class.java) })
        if (heroChannel == null && list.isNotEmpty()) showHero(list[0], list, now[list[0].epgChannelId])
    }

    /**
     * Live sports: games of the teams you follow first (next 7 days), then everything live, then what starts in
     * the next 48 hours. Each card says the day (Today / Tomorrow / Sat).
     */
    private fun loadSports() = act.lifecycleScope.launch {
        val r = runCatching { Web24Api(act).support("scores") }.getOrNull() ?: return@launch
        val names = Web24Api.objects(r.optJSONArray("leagues")).associate { it.optString("code") to it.optString("name") }
        val keys = com.network24.player.features.discover.Teams.keys(act)
        val nowS = System.currentTimeMillis() / 1000
        fun mine(g: JSONObject) = Web24Api.objects(g.optJSONArray("teams")).any { "${g.optString("league")}:${it.optString("id")}" in keys }
        val all = (Web24Api.objects(r.optJSONArray("games")) + Web24Api.objects(r.optJSONArray("events"))).filter { g ->
            val st = g.optString("state"); val t = g.optLong("start")
            st == "in" || (st == "pre" && t >= nowS - 1800 && t <= nowS + (if (mine(g)) 7 * 86400 else 48 * 3600))
        }.sortedWith(compareBy({ if (mine(it)) 0 else 1 }, { if (it.optString("state") == "in") 0 else 1 }, { it.optLong("start") }))
            .take(MAX_ROW)
            .map { JSONObject(it.toString()).put("league_name", names[it.optString("league")] ?: it.optString("league")).put("mine", mine(it)) }
        val n = all.count { it.optString("state") == "in" }
        val yours = all.count { it.optBoolean("mine") }
        val sub = listOfNotNull(if (n > 0) "$n live now" else null, if (yours > 0) "$yours with your teams" else null).joinToString("  ·  ").ifBlank { "today and tomorrow" }
        addRow("sports", "Live sports", sub, all.map { gameCard(it) },
            "Games, results, teams" to { openAct(com.network24.player.features.sports.SportsActivity::class.java) })
    }

    // ------------------------------------------------------------------------------------------------ category rows
    private var catPlan: List<com.network24.player.core.database.entity.CategoryEntity> = emptyList()
    private var catBuilt = 0
    private var catBusy = false

    /**
     * Up to 10 category rows: the categories this viewer watches most first, then Live TV's own order. Categories
     * under the parental lock, switched off in Manage Categories, or for adults are left out.
     */
    private fun planCategories(recent: List<ChannelEntity>, locked: Set<String>) = act.lifecycleScope.launch {
        val off = prefs.getDisabledLiveCategoryIds().orEmpty()
        val adult = Regex("ADULT|XXX|18\\+", RegexOption.IGNORE_CASE)
        val cats = withContext(Dispatchers.IO) { db.categoryDao().getByType(com.network24.player.core.database.entity.CategoryType.LIVE).sortedBy { it.position } }
            .filter { it.categoryId !in locked && it.categoryId !in off && !adult.containsMatchIn(it.name) }
        val watched = recent.mapNotNull { it.categoryId }.groupingBy { it }.eachCount()
        catPlan = (cats.filter { it.categoryId in watched }.sortedByDescending { watched[it.categoryId] } + cats.filter { it.categoryId !in watched }).take(10)
        catBuilt = 0
        rows.toList().filter { (it.tag as? String)?.startsWith("cat:") == true }.forEach { rows.removeView(it) }
        buildMoreCategories(2)
    }

    /** "USA SPORTS NETWORKS" -> "USA Sports Networks", "NBA / WNBA PACKAGE" -> "NBA / WNBA Package": short words stay capitals. */
    private fun niceName(n: String) = n.trim().split(" ").joinToString(" ") { w ->
        w.split("/").joinToString("/") { p -> if (p.length <= 4 || p.any { it.isDigit() }) p else p.lowercase().replaceFirstChar { it.uppercase() } }
    }

    private fun LinearLayout.toList() = (0 until childCount).map { getChildAt(it) }

    private fun buildMoreCategories(n: Int) {
        if (catBusy || catBuilt >= catPlan.size) return
        catBusy = true
        act.lifecycleScope.launch {
            repeat(n) {
                val c = catPlan.getOrNull(catBuilt) ?: return@repeat
                catBuilt++
                val list = withContext(Dispatchers.IO) { db.channelDao().getByCategory(c.categoryId) }.take(15)
                if (list.isEmpty()) return@repeat
                val now = nowPlaying(list)
                addRow("cat:" + c.categoryId, niceName(c.name), "", list.map { channelCard(it, now[it.epgChannelId], list) },
                    "All ${c.name.lowercase()}" to {
                        act.startActivity(Intent(act, com.network24.player.features.live.activity.ChannelListActivity::class.java)
                            .putExtra("category_id", c.categoryId).putExtra("category_name", c.name))
                    })
            }
            catBusy = false
        }
    }

    /** More category rows when the viewer gets near the bottom. */
    private fun nearBottom() {
        val content = scroll.getChildAt(0) ?: return
        if (scroll.scrollY + scroll.height > content.height - screenH * 0.9f) buildMoreCategories(2)
    }

    private suspend fun nowPlaying(list: List<ChannelEntity>): Map<String?, EpgEntity> = withContext(Dispatchers.IO) {
        val ids = list.mapNotNull { it.epgChannelId?.takeIf { e -> e.isNotBlank() } }.distinct()
        val now = System.currentTimeMillis()
        val out = HashMap<String?, EpgEntity>()
        ids.chunked(400).forEach { part -> runCatching { db.epgDao().getNowByEpgChannelIds(part, now) }.getOrNull()?.forEach { if (it.epgChannelId !in out) out[it.epgChannelId] = it } }
        out
    }

    private fun progressOf(e: EpgEntity?): Int {
        val s = e?.startTimestamp ?: return -1
        val t = e.stopTimestamp ?: return -1
        if (t <= s) return -1
        return ((System.currentTimeMillis() - s) * 1000 / (t - s)).toInt().coerceIn(0, 1000)
    }

    // ------------------------------------------------------------------------------------------------ billboard
    private fun showHero(ch: ChannelEntity, list: List<ChannelEntity>, now: EpgEntity?) {
        if (heroChannel?.streamId == ch.streamId) return
        heroChannel = ch; heroList = list
        heroLive.visibility = View.VISIBLE
        heroTag.text = (if (ch.streamId == recentFirst) "CONTINUE WATCHING  ·  " else "ON NOW  ·  ") + cleanName(ch.name).uppercase()
        heroChLogo.load(ch.icon?.takeIf { it.isNotBlank() })
        heroTitle.text = cleanName(ch.name)
        heroArtLogo.visibility = View.GONE; heroTitle.visibility = View.VISIBLE
        backdrop.animate().alpha(0f).setDuration(250).start()
        btnWatch.visibility = View.VISIBLE; btnMulti.visibility = View.VISIBLE
        poster.alpha = 0f
        poster.load(ch.icon?.takeIf { it.isNotBlank() }) { listener(onSuccess = { _, _ -> if (video.alpha < 0.5f) poster.animate().alpha(0.9f).setDuration(250).start() }) }
        heroBox.alpha = 0.4f; heroBox.animate().alpha(1f).setDuration(300).start()
        if (now != null) paint(now, null) else refreshEpg(ch)
        startVideo(ch)
    }

    private fun refreshEpg(ch: ChannelEntity) {
        // no guide id: nothing to look up (asking again from paint() looped until the app crashed)
        val epg = ch.epgChannelId?.takeIf { it.isNotBlank() } ?: run { paint(null, NO_NEXT); return }
        act.lifecycleScope.launch {
            val t = System.currentTimeMillis()
            val cur = withContext(Dispatchers.IO) { runCatching { db.epgDao().getNowByEpgChannelId(epg, t) }.getOrNull() }
            val next = withContext(Dispatchers.IO) { runCatching { db.epgDao().getNextByEpgChannelId(epg, t) }.getOrNull() }
            if (heroChannel?.streamId == ch.streamId) paint(cur, next ?: NO_NEXT)
        }
    }

    private fun paint(cur: EpgEntity?, next: EpgEntity?) {
        val hm = SimpleDateFormat("h:mm", Locale.US)
        val hma = SimpleDateFormat("h:mm a", Locale.US)
        if (cur != null) {
            val s = cur.startTimestamp ?: 0
            val e = cur.stopTimestamp ?: 0
            heroTitle.text = cleanProgram(cur.title).ifBlank { heroChannel?.let { cleanName(it.name) }.orEmpty() }
            heroChannel?.let { ch -> cleanProgram(cur.title).takeIf { it.isNotBlank() }?.let { heroArt(ch.streamId, it) } }
            heroMeta.text = "${hm.format(Date(s))} – ${hma.format(Date(e))}  ·  ${((e - System.currentTimeMillis()) / 60000).coerceAtLeast(0)} min left"
            heroBar.visibility = View.VISIBLE
            ValueAnimator.ofInt(heroBar.progress, progressOf(cur)).apply { duration = 450; addUpdateListener { heroBar.progress = it.animatedValue as Int } }.start()
        } else {
            heroMeta.text = "Live now"; heroBar.visibility = View.GONE
        }
        when {
            next === NO_NEXT -> heroNext.text = ""
            next != null -> heroNext.text = "Next  ${hma.format(Date(next.startTimestamp ?: 0))}   ${cleanProgram(next.title)}"
            // came with the row's "now" only: ask the guide once for what follows
            cur != null -> heroChannel?.takeIf { !it.epgChannelId.isNullOrBlank() }?.let { refreshEpg(it) }
            else -> heroNext.text = ""
        }
    }

    private fun welcome() {
        heroLive.visibility = View.GONE
        heroTag.text = "WELCOME TO NETWORK24"
        heroTitle.text = "Live TV, sports and movies"
        heroProgram.text = "Open Live TV to start watching. Your channels appear here once you have watched them."
        heroMeta.text = ""; heroBar.visibility = View.GONE; heroNext.text = ""
        btnWatch.visibility = View.GONE; btnMulti.visibility = View.GONE
    }

    private fun startVideo(ch: ChannelEntity) {
        if (!started || playingId == ch.streamId) return
        val p = player ?: ExoPlayer.Builder(act.applicationContext, StreamDataSourceFactory.createRenderersFactory(act))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(8000, 20000, 1000, 2000).build())
            .setMediaSourceFactory(StreamDataSourceFactory.createMediaSourceFactory())
            // the billboard plays with sound; it takes audio focus like any player (other apps pause)
            .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .build().also { pl ->
                player = pl; pl.volume = 0f; video.player = pl
                pl.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        video.animate().alpha(1f).setDuration(600).start(); poster.animate().alpha(0f).setDuration(400).start()
                        soundOn = true; fadeSound()
                    }
                    override fun onPlayerError(error: PlaybackException) { video.animate().alpha(0f).setDuration(300).start(); playingId = -1 }
                })
            }
        playingId = ch.streamId
        // a new channel starts silent and its sound rises once the picture is up
        soundOn = false; volumeAnim?.cancel(); volumeTarget = 0f; p.volume = 0f
        video.animate().alpha(0f).setDuration(200).start()
        val server = prefs.getServer().trim().trimEnd('/')
        p.setMediaItem(MediaItem.fromUri("$server/live/${prefs.getUsername().trim()}/${prefs.getPassword().trim()}/${ch.streamId}.m3u8"))
        p.prepare(); p.playWhenReady = true
        idle = false; armIdle()
    }

    // The preview must not hold one of the account's connections while nobody uses the app: after 20 s without
    // a key or touch it stops (the connection is freed) and the programme's banner stays; any key or touch
    // starts it again.
    private var idle = false
    private val idleStop = Runnable { stopForIdle() }
    private fun armIdle() { handler.removeCallbacks(idleStop); handler.postDelayed(idleStop, IDLE_MS) }

    fun userActive() {
        if (!started) return
        if (idle) heroChannel?.let { playingId = -1; startVideo(it) } else if (playingId != -1) armIdle()
    }

    private fun stopForIdle() {
        if (!started || playingId == -1) return
        idle = true; playingId = -1
        soundOn = false; fadeSound()
        // the banner (programme artwork) shows through as the picture fades; no artwork -> the channel's logo
        if (backdrop.alpha < 0.5f) poster.animate().alpha(0.9f).setDuration(600).start()
        video.animate().alpha(0f).setDuration(600).withEndAction { if (idle) player?.stop() }.start()
    }

    // billboard sound: rises softly when the picture starts, fades out while the page is scrolled down to the rows
    // (the billboard is then mostly covered) and comes back at the top
    private var soundOn = false
    private var volumeAnim: ValueAnimator? = null
    private var volumeTarget = 0f
    private fun fadeSound() {
        val p = player ?: return
        val target = if (soundOn && scroll.scrollY < screenH * 0.35f) 1f else 0f
        if (target == volumeTarget && (volumeAnim?.isRunning == true || p.volume == target)) return
        volumeTarget = target; volumeAnim?.cancel()
        volumeAnim = ValueAnimator.ofFloat(p.volume, target).apply {
            duration = if (target > 0f) 900 else 400
            addUpdateListener { player?.volume = it.animatedValue as Float }
            start()
        }
    }

    private fun multiView() {
        val ch = heroChannel ?: return
        PlayerState.channels.clear(); PlayerState.channels.addAll(heroList.ifEmpty { listOf(ch) }.map { it.toLiveChannel() })
        PlayerState.currentPosition = heroList.indexOfFirst { it.streamId == ch.streamId }.coerceAtLeast(0)
        act.startActivity(Intent(act, MultiViewActivity::class.java))
    }

    // ------------------------------------------------------------------------------------------------ account / lifecycle
    private fun daysLeft(): Long? = prefs.getExpiry().takeIf { it > 0 }?.let { com.network24.player.core.util.ExpiryDays.from(it * 1000) }

    /** Account icon: plain normally, gold from 15 days before the end, red once it has ended (details on its page). */
    fun refreshAccount() {
        val left = daysLeft()
        val expired = prefs.getExpiry().let { it > 0 && it * 1000 <= System.currentTimeMillis() }
        when {
            expired -> { chip.setColorFilter(Color.WHITE); chip.background = shape(live, 21f); chip.contentDescription = "Account, expired" }
            left != null && left <= 15 -> {
                chip.setColorFilter(bg); chip.background = shape(gold, 21f)
                chip.contentDescription = "Account, " + when (left) { 0L -> "ends today"; 1L -> "1 day left"; else -> "$left days left" }
            }
            else -> {
                chip.setColorFilter(textMain); chip.background = shape(0x1AFFFFFF, 21f)
                chip.contentDescription = "Account, " + prefs.getUsername() + (left?.let { ", $it days left" } ?: "")
            }
        }
    }

    fun onStart() {
        started = true
        handler.post(tick)
        refreshAccount()
        heroChannel?.let { playingId = -1; startVideo(it) }
    }

    fun onStop() {
        started = false
        handler.removeCallbacks(tick); pendingHero?.let { handler.removeCallbacks(it) }
        handler.removeCallbacks(idleStop); idle = false
        soundOn = false; volumeAnim?.cancel(); volumeTarget = 0f; player?.volume = 0f
        player?.stop(); playingId = -1; video.alpha = 0f
    }

    fun onDestroy() { player?.release(); player = null }

    /**
     * BACK, the way TV apps do it: inside a row that is scrolled sideways -> back to the row's first card;
     * anywhere further down -> straight back to the billboard (no competing scroll animations); at the top -> leave.
     */
    /** The remote back on Watch now (after a dialog over the home closes). */
    fun focusWatch() {
        scroll.scrollTo(0, 0)
        val t = if (btnWatch.visibility == View.VISIBLE) btnWatch else rows.getChildAt(0)
        t?.post { t.requestFocus() }
    }

    fun handleBack(): Boolean {
        val f = act.currentFocus
        var p = f?.parent
        while (p != null && p !is HorizontalScrollView) p = p.parent
        val hs = p as? HorizontalScrollView
        val strip = hs?.getChildAt(0) as? LinearLayout
        if (hs != null && strip != null && strip.childCount > 0 && rows.indexOfChild(hs.parent as? View) >= 0 && f !== strip.getChildAt(0)) {
            hs.scrollTo(0, 0)
            strip.getChildAt(0).requestFocus()
            return true
        }
        if (scroll.scrollY <= 0 && (f == null || !rows.hasFocus())) return false
        val target = if (btnWatch.visibility == View.VISIBLE) btnWatch else rows.getChildAt(0)
        scroll.scrollTo(0, 0)
        target?.post { target.requestFocus() }
        return true
    }

    private companion object { val NO_NEXT = EpgEntity(id = "", streamId = 0); const val MAX_ROW = 20; const val IDLE_MS = 20_000L }
}
