package com.network24.player.features.settings.activity

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.network24.player.R
import com.network24.player.features.dashboard.home.HomeFont
import com.network24.player.features.live.models.LiveCategory

/**
 * The building blocks of the Settings pages in the app's look (dark glass, violet accents, white focus ring):
 * a page with back / title / menu and a hero card on the left, cards, pick-one option rows, switch rows,
 * PIN fields, buttons and a category switch list.
 */
class SettingsKit(private val act: AppCompatActivity) {
    private val res = act.resources
    private val uiScale = res.displayMetrics.let { m -> minOf(1f, m.widthPixels / m.density / 960f, m.heightPixels / m.density / 400f) }
    val d = res.displayMetrics.density * uiScale
    fun dp(v: Int) = (v * d).toInt()
    fun dpf(v: Float) = v * d

    val bg = Color.parseColor("#08090C")
    val surface = Color.parseColor("#14161B")
    val line = Color.parseColor("#1FFFFFFF")
    val textMain = Color.parseColor("#F2F3F5")
    val textSub = Color.parseColor("#9BA1AD")
    val accent = Color.parseColor("#7C5CFF")
    val accentSoft = Color.parseColor("#A894FF")
    val cyan = Color.parseColor("#22D3EE")
    val good = Color.parseColor("#3DD68C")
    val bad = Color.parseColor("#E5484D")

    fun text(s: CharSequence, size: Float, color: Int = textMain, weight: Int = 500, lines: Int = 1) = TextView(act).apply {
        text = s; setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * d); setTextColor(color); typeface = HomeFont.of(act, weight)
        maxLines = lines; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
    }

    fun shape(fill: Int, radius: Float, stroke: Int = 0) = GradientDrawable().apply { setColor(fill); cornerRadius = dpf(radius); if (stroke != 0) setStroke(dp(1), stroke) }

    fun focusable(v: View, radius: Float, scale: Float = 1.02f, ring: Int = Color.WHITE) {
        v.isFocusable = true; v.isClickable = true
        val stroke = dp(3)
        val r = GradientDrawable().apply { cornerRadius = (dpf(radius) - stroke / 2f).coerceAtLeast(0f); setStroke(stroke, ring); setColor(Color.TRANSPARENT) }
        v.setOnFocusChangeListener { view, has ->
            view.foreground = if (has) r else null
            view.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).setDuration(120).start()
            if (has) view.post { view.requestRectangleOnScreen(android.graphics.Rect(-dp(20), -dp(20), view.width + dp(20), view.height + dp(20)), false) }
        }
    }

    fun iconTile(icon: Int, color: Int, size: Int = 42) = FrameLayout(act).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf((color and 0x00FFFFFF) or 0x44000000, (color and 0x00FFFFFF) or 0x14000000)).apply { cornerRadius = dpf(size / 4f) }
        addView(ImageView(act).apply { setImageResource(icon); setColorFilter(color) }, FrameLayout.LayoutParams(dp(size / 2 + 1), dp(size / 2 + 1), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    class Page(val root: ViewGroup, val menu: View, val back: View, val hero: LinearLayout, val content: LinearLayout)

    /** Back, title, menu; a hero card on the left ([heroWidth] dp, 0 = none) and a scrolling column on the right. */
    fun page(title: String, subtitle: String, heroWidth: Int = 300, scrolls: Boolean = true): Page {
        val root = FrameLayout(act).apply { background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#FF15112E"), bg, bg)) }
        val page = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(48), dp(24), dp(48), 0) }
        root.addView(page, FrameLayout.LayoutParams(-1, -1))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val b = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            page.setPadding(dp(48) + b.left, dp(24) + b.top, dp(48) + b.right, b.bottom); ins
        }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        val back = roundIcon(R.drawable.ic_back, "Back") { act.finish() }
        head.addView(back, LinearLayout.LayoutParams(dp(42), dp(42)))
        val words = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
        words.addView(text(title, 22f, textMain, 800))
        words.addView(text(subtitle, 12f, textSub, 600).apply { setPadding(0, dp(4), 0, 0) })
        head.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        val menu = roundIcon(R.drawable.ic_more_vert, "Menu") {}
        head.addView(menu, LinearLayout.LayoutParams(dp(42), dp(42)))
        page.addView(head)

        val body = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = true }  // a scrolled list never draws over the title
        val hero = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(22), dp(22), dp(20))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#FF241D4A"), surface)).apply { cornerRadius = dpf(20f); setStroke(dp(1), 0x337C5CFF) }
            visibility = if (heroWidth > 0) View.VISIBLE else View.GONE
        }
        // the hero scrolls on its own when a small screen cannot show all of it
        val heroScroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; clipChildren = false; clipToPadding = false; addView(hero); visibility = hero.visibility }
        body.addView(heroScroll, LinearLayout.LayoutParams(dp(heroWidth.coerceAtLeast(1)), -1).apply { topMargin = dp(18); bottomMargin = dp(18) })
        val content = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(8), dp(10), dp(8), dp(40)) }
        val scroll: View = if (scrolls) ScrollView(act).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(24)); addView(content) } else content
        body.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = if (heroWidth > 0) dp(20) else 0 })
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        return Page(root, menu, back, hero, content)
    }

    fun roundIcon(icon: Int, label: String, onClick: () -> Unit) = ImageView(act).apply {
        setImageResource(icon); setColorFilter(textMain); setPadding(dp(10), dp(10), dp(10), dp(10)); contentDescription = label
        background = shape(0x1AFFFFFF, 21f); setOnClickListener { onClick() }; focusable(this, 21f, 1.08f)
    }

    /** The big circle of a hero card (icon in a violet-cyan orb). */
    fun orb(icon: Int, size: Int = 62) = FrameLayout(act).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(accent, cyan)).apply { shape = GradientDrawable.OVAL }
        addView(ImageView(act).apply { setImageResource(icon); setColorFilter(Color.WHITE) }, FrameLayout.LayoutParams(dp(size / 2), dp(size / 2), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    fun label(s: String) = text(s.uppercase(), 11f, accentSoft, 800).apply { letterSpacing = 0.16f; setPadding(dp(4), dp(18), 0, dp(8)) }

    fun spacer() = View(act).apply { layoutParams = LinearLayout.LayoutParams(1, 0, 1f) }

    /** A pick-one row: title, line, and a violet tick when chosen. */
    fun option(title: String, sub: String, chosen: Boolean, badge: String? = null, onClick: () -> Unit) = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(13), dp(16), dp(13))
        background = if (chosen) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#3A2C7A"), Color.parseColor("#1E1A3A"))).apply { cornerRadius = dpf(14f); setStroke(dp(1), 0x887C5CFF.toInt()) }
            else shape(surface, 14f, line)
        val dot = FrameLayout(act).apply { background = shape(Color.TRANSPARENT, 12f, if (chosen) accent else 0x40FFFFFF) .apply { setStroke(dp(2), if (chosen) accentSoft else 0x55FFFFFF) } }
        if (chosen) dot.addView(View(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accentSoft) } }, FrameLayout.LayoutParams(dp(12), dp(12), Gravity.CENTER))
        addView(dot, LinearLayout.LayoutParams(dp(24), dp(24)))
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(10), 0) }
        val tr = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tr.addView(text(title, 15f, textMain, 700))
        if (badge != null) tr.addView(text(badge, 10f, Color.parseColor("#08090C"), 800).apply { letterSpacing = 0.08f; setPadding(dp(7), dp(3), dp(7), dp(3)); background = shape(accentSoft, 6f) },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        col.addView(tr)
        col.addView(text(sub, 12f, textSub, 500, lines = 2).apply { setPadding(0, dp(4), 0, 0) })
        addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        setOnClickListener { onClick() }
        focusable(this, 14f)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }

    fun button(label: String, primary: Boolean, danger: Boolean = false, onClick: () -> Unit) = text(label, 14f, if (primary) bg else if (danger) Color.parseColor("#FF8A8E") else textMain, 800).apply {
        gravity = Gravity.CENTER; setPadding(dp(20), dp(11), dp(20), dp(11))
        background = shape(if (primary) Color.WHITE else if (danger) 0x26E5484D else 0x26FFFFFF, 12f)
        setOnClickListener { onClick() }
        focusable(this, 12f, 1.05f, if (primary) accent else Color.WHITE)
    }

    fun pinField(hint: String) = EditText(act).apply {
        this.hint = hint; setHintTextColor(textSub); setTextColor(textMain); setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 15f * d); typeface = HomeFont.of(act, 600)
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; filters = arrayOf(android.text.InputFilter.LengthFilter(8))
        isSingleLine = true; setPadding(dp(16), dp(12), dp(16), dp(12))
        // the number keyboard's password type switches to a monospace font: the app font again, the dots spaced only once typed
        typeface = HomeFont.of(act, 600)
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { letterSpacing = if (s.isNullOrEmpty()) 0f else 0.3f }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        background = shape(Color.parseColor("#1A1C23"), 12f, line)
        setOnFocusChangeListener { v, has -> v.background = shape(Color.parseColor("#1A1C23"), 12f, line).apply { if (has) setStroke(dp(2), accent) } }
    }

    fun styleSwitch(s: SwitchMaterial) = s.apply {
        thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.WHITE, Color.parseColor("#C9CDD4")))
        trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, 0x40FFFFFF))
    }

    fun progressBar() = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(accent) }

    /**
     * Categories with a switch each (switch on = [onLabel]). [disabled] holds the ids whose switch is off.
     * Same use as the old list: updateList / setEnabled / onChanged.
     */
    inner class CategorySwitches(private val onLabel: String, private val offLabel: String, private val onChanged: (LiveCategory, Boolean) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<LiveCategory> = emptyList()
        private val disabled = HashSet<String>()
        var filter: String = ""
            set(v) { field = v; notifyDataSetChanged() }
        private val shown get() = if (filter.isBlank()) items else items.filter { it.category_name.contains(filter, true) }

        fun updateList(list: List<LiveCategory>, off: Set<String>) { items = list; disabled.clear(); disabled.addAll(off); notifyDataSetChanged() }
        fun setEnabled(id: String, on: Boolean) { if (on) disabled.remove(id) else disabled.add(id); notifyDataSetChanged() }
        fun onCount() = items.count { it.category_id !in disabled }

        override fun getItemCount() = shown.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(14), dp(10))
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }
                focusable(this, 12f, 1.01f)
            }
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text("", 14f, textMain, 700))
            col.addView(text("", 11f, textSub, 600).apply { setPadding(0, dp(3), 0, 0) })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(styleSwitch(SwitchMaterial(act).apply { isFocusable = false; isClickable = false }))
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val c = shown[position]
            val row = holder.itemView as LinearLayout
            val on = c.category_id !in disabled
            val col = row.getChildAt(0) as LinearLayout
            (col.getChildAt(0) as TextView).text = c.category_name.removePrefix("🔒 ")
            (col.getChildAt(1) as TextView).apply { text = if (on) onLabel else offLabel; setTextColor(if (on) accentSoft else textSub) }
            (row.getChildAt(1) as SwitchMaterial).isChecked = on
            row.background = if (on) shape(Color.parseColor("#1B1830"), 12f, 0x447C5CFF) else shape(surface, 12f, line)
            row.setOnClickListener {
                val now = c.category_id in disabled
                if (now) disabled.remove(c.category_id) else disabled.add(c.category_id)
                notifyItemChanged(holder.bindingAdapterPosition)
                onChanged(c, now)
            }
        }
    }
}
