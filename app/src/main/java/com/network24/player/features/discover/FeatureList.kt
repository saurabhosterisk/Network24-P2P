package com.network24.player.features.discover

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.network24.player.R
import com.network24.player.core.base.BaseActivity
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.core.database.entity.ChannelEntity
import com.network24.player.core.database.mapper.toLiveChannel
import com.network24.player.databinding.ActivityFeatureListBinding
import com.network24.player.databinding.ItemFeatureRowBinding
import com.network24.player.features.player.activity.PlayerActivity
import com.network24.player.features.player.state.PlayerState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One row of a list page. */
data class Row(
    val key: String,
    val title: String,
    val sub: String = "",
    val sub2: String = "",
    val icon: String? = null,
    val lead: String = "",
    val badge: String = "",
    val badgeColor: Int = 0,
    val progress: Int = -1,
    val showIcon: Boolean = true,
    val onLong: (() -> Unit)? = null,
    val onClick: (() -> Unit)? = null,
)

class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
    class VH(val b: ItemFeatureRowBinding) : RecyclerView.ViewHolder(b.root)
    private var rows: List<Row> = emptyList()
    fun submit(list: List<Row>) { rows = list; notifyDataSetChanged() }
    override fun getItemCount() = rows.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(ItemFeatureRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(h: VH, position: Int) {
        val r = rows[position]
        h.b.txtTitle.text = r.title
        h.b.txtSub.text = r.sub; h.b.txtSub.visibility = if (r.sub.isBlank()) View.GONE else View.VISIBLE
        h.b.txtSub2.text = r.sub2; h.b.txtSub2.visibility = if (r.sub2.isBlank()) View.GONE else View.VISIBLE
        h.b.txtLead.text = r.lead; h.b.txtLead.visibility = if (r.lead.isBlank()) View.GONE else View.VISIBLE
        h.b.imgIcon.visibility = if (r.showIcon) View.VISIBLE else View.GONE
        if (r.showIcon) h.b.imgIcon.load(r.icon?.takeIf { it.isNotBlank() }) { placeholder(R.drawable.app_logo); error(R.drawable.app_logo) }
        h.b.txtBadge.text = r.badge; h.b.txtBadge.visibility = if (r.badge.isBlank()) View.GONE else View.VISIBLE
        if (r.badgeColor != 0) h.b.txtBadge.setTextColor(r.badgeColor) else h.b.txtBadge.setTextColor(ContextCompat.getColor(h.itemView.context, R.color.text_primary))
        h.b.progress.visibility = if (r.progress >= 0) View.VISIBLE else View.GONE
        if (r.progress >= 0) h.b.progress.progress = r.progress
        h.b.cardRoot.isClickable = r.onClick != null || r.onLong != null
        h.b.cardRoot.setOnClickListener { r.onClick?.invoke() }
        h.b.cardRoot.setOnLongClickListener { if (r.onLong != null) { r.onLong.invoke(); true } else false }
    }
}

/** Base for the newer list pages: header card, optional search bar with voice, optional chip row, one list. */
abstract class FeatureListActivity : BaseActivity() {
    protected lateinit var b: ActivityFeatureListBinding
    protected val adapter = RowAdapter()
    protected val db by lazy { DatabaseProvider.get(this) }
    private var onQuery: ((String) -> Unit)? = null

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { b.editSearch.setText(it); b.editSearch.setSelection(it.length); onQuery?.invoke(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityFeatureListBinding.inflate(layoutInflater)
        setContentView(setupGlobalRightDrawer(b.root, b.btnMore))
        b.btnBack.setOnClickListener { finish() }
        b.rvItems.layoutManager = LinearLayoutManager(this)
        b.rvItems.adapter = adapter
    }

    protected fun title(t: String, sub: String = "") { b.txtTitle.text = t; b.txtSubtitle.text = sub; b.txtSubtitle.visibility = if (sub.isBlank()) View.GONE else View.VISIBLE }

    protected fun action(label: String?, onClick: () -> Unit = {}) {
        b.btnAction.visibility = if (label == null) View.GONE else View.VISIBLE
        b.btnAction.text = label ?: ""; b.btnAction.setOnClickListener { onClick() }
    }

    protected fun enableSearch(hint: String, live: Boolean, cb: (String) -> Unit) {
        onQuery = cb
        b.searchRow.visibility = View.VISIBLE
        b.editSearch.hint = hint
        b.btnVoice.setOnClickListener {
            runCatching { voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PROMPT, hint)) }
                .onFailure { toast("Voice search is not available on this device.") }
        }
        b.editSearch.setOnEditorActionListener { v, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) {
                cb(b.editSearch.text.toString())
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
                focusFirstRow()
                true
            } else false
        }
        // TV remote: DOWN leaves the search box for the results (Fire TV kept the focus inside the box)
        b.editSearch.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                val chip = selectedChip(b.chipRow).takeIf { b.chipScroll.visibility == View.VISIBLE && b.chipRow.childCount > 0 }
                if (chip != null) chip.requestFocus() else focusFirstRow()
                true
            } else false
        }
        if (live) b.editSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { cb(s?.toString().orEmpty()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = Unit
        })
    }

    /** UP on the first row goes to the selected chip (Android picked whichever chip sat right above, e.g. "MLS"). */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
            val row = currentFocus?.let { f -> generateSequence(f) { it.parent as? View }.firstOrNull { it.parent === b.rvItems } }
            if (row != null && b.rvItems.getChildAdapterPosition(row) == 0) {
                val target = selectedChip(b.chipRow2).takeIf { b.chipScroll2.visibility == View.VISIBLE }
                    ?: selectedChip(b.chipRow).takeIf { b.chipScroll.visibility == View.VISIBLE }
                    ?: b.editSearch.takeIf { b.searchRow.visibility == View.VISIBLE }
                if (target != null && target.requestFocus()) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    protected fun focusFirstRow() {
        b.rvItems.post { b.rvItems.getChildAt(0)?.let { (it.findViewById<View>(R.id.cardRoot) ?: it).requestFocus() } }
    }

    /** Chip row (same chips as the rest of the app); returns nothing, calls [onPick] with the chosen key. */
    protected fun chips(items: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit): Unit =
        chipsIn(b.chipScroll, b.chipRow, items, selected, onPick) { k -> chips(items, k, onPick) }

    /** Second chip row under the first (e.g. a filter under the tabs); an empty list hides it. */
    protected fun chips2(items: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit): Unit =
        chipsIn(b.chipScroll2, b.chipRow2, items, selected, onPick) { k -> chips2(items, k, onPick) }

    private fun chipsIn(scroll: View, row: LinearLayout, items: List<Pair<String, String>>, selected: String,
                        onPick: (String) -> Unit, redraw: (String) -> Unit) {
        // rebuilding the chips must not throw the remote's focus away (it jumped to the Back button). A pick can
        // rebuild the same row twice (its own redraw, then the screen's render) before the first focus request lands.
        val hadFocus = row.hasFocus() || pendingChipFocus === row
        scroll.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        row.removeAllViews()
        val d = resources.displayMetrics.density
        items.forEach { (k, label) ->
            val t = TextView(this).apply {
                id = View.generateViewId()
                text = label; textSize = 14f; isFocusable = true; isClickable = true
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setBackgroundResource(R.drawable.bg_interactive_chip)
                setPadding((16 * d).toInt(), (9 * d).toInt(), (16 * d).toInt(), (9 * d).toInt())
                isSelected = k == selected
                if (k == selected) { setTextColor(ContextCompat.getColor(context, R.color.primary_light)); setTypeface(typeface, android.graphics.Typeface.BOLD) }
                setOnClickListener { pendingChipFocus = row; redraw(k); onPick(k) }
            }
            row.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = (8 * d).toInt() })
            if (hadFocus && k == selected) t.post { t.requestFocus(); if (pendingChipFocus === row) pendingChipFocus = null }
        }
        wireFocus()
    }

    private var pendingChipFocus: LinearLayout? = null

    private fun selectedChip(row: LinearLayout): View? =
        (0 until row.childCount).map { row.getChildAt(it) }.firstOrNull { it.isSelected } ?: row.getChildAt(0)

    /**
     * TV remote order, top to bottom: header buttons -> search box -> tab chips -> second chips -> list. Android's own
     * search jumped from the right-hand header buttons straight into the list, skipping the chips on the left.
     */
    protected fun wireFocus() {
        val search = b.searchRow.takeIf { it.visibility == View.VISIBLE }?.let { b.editSearch }
        val first = selectedChip(b.chipRow).takeIf { b.chipScroll.visibility == View.VISIBLE }
        val second = selectedChip(b.chipRow2).takeIf { b.chipScroll2.visibility == View.VISIBLE }
        val belowHeader = search ?: first ?: second
        listOf(b.btnBack, b.btnAction, b.btnMore).forEach { it.nextFocusDownId = belowHeader?.id ?: View.NO_ID }
        if (search != null) b.editSearch.nextFocusUpId = b.btnBack.id
        for (i in 0 until b.chipRow.childCount) b.chipRow.getChildAt(i).apply {
            nextFocusUpId = (search ?: b.btnBack).id
            nextFocusDownId = second?.id ?: View.NO_ID
        }
        for (i in 0 until b.chipRow2.childCount) b.chipRow2.getChildAt(i).nextFocusUpId = first?.id ?: (search ?: b.btnBack).id
    }

    protected fun loading(on: Boolean) { b.progressLoading.visibility = if (on) View.VISIBLE else View.GONE }

    protected fun show(rows: List<Row>, empty: String) {
        // keep the remote's focus on the same row when the list is redrawn (league switch, refresh, follow)
        val focused = b.rvItems.focusedChild?.let { b.rvItems.getChildAdapterPosition(it) } ?: -1
        adapter.submit(rows)
        if (focused >= 0 && rows.isNotEmpty()) {
            val pos = focused.coerceAtMost(rows.size - 1)
            b.rvItems.post { b.rvItems.findViewHolderForAdapterPosition(pos)?.itemView?.let { (it.findViewById<View>(R.id.cardRoot) ?: it).requestFocus() } }
        }
        b.txtEmpty.text = empty
        b.txtEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    protected fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    /** Plays [ch] full screen; channel up / down walks through [list] (the same way every other screen does it). */
    protected fun play(list: List<ChannelEntity>, ch: ChannelEntity) = ChannelLauncher.play(this, list, ch)
}

object ChannelLauncher {
    fun play(activity: android.app.Activity, list: List<ChannelEntity>, ch: ChannelEntity) {
        val channels = (if (list.any { it.streamId == ch.streamId }) list else listOf(ch)).map { it.toLiveChannel() }
        PlayerState.channels.clear()
        PlayerState.channels.addAll(channels)
        PlayerState.currentPosition = channels.indexOfFirst { it.stream_id == ch.streamId }.coerceAtLeast(0)
        activity.startActivity(Intent(activity, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_PLAY_SELECTED_CHANNEL, true))
    }
}

object Fmt {
    fun clock(ms: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms)).uppercase(Locale.getDefault())
    fun day(ms: Long): String {
        val d = Calendar.getInstance().apply { timeInMillis = ms }
        val diff = (startOfDay(ms) - startOfDay(System.currentTimeMillis())) / 86_400_000L
        return when (diff) { 0L -> "Today"; -1L -> "Yesterday"; 1L -> "Tomorrow"; else -> SimpleDateFormat("EEE, MMM d", Locale.US).format(d.time) }
    }
    fun startOfDay(ms: Long): Long = Calendar.getInstance().apply { timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    fun date(ms: Long): String = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ms))
}
