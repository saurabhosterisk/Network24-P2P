package com.network24.player.features.support.adapter

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation
import coil.transform.RoundedCornersTransformation
import com.network24.player.R
import com.network24.player.common.models.SupportChannel
import com.network24.player.common.models.SupportMessage
import com.network24.player.databinding.ItemSupportMessageBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Channel column of the Live Support screen. */
class SupportChannelAdapter(
    private val onPick: (SupportChannel) -> Unit
) : RecyclerView.Adapter<SupportChannelAdapter.Holder>() {

    private val channels = mutableListOf<SupportChannel>()
    var selectedId: String? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** Channels with messages the customer has not seen yet. */
    var unread: Set<String> = emptySet()
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    /** Where D-pad Right goes from a channel row (message box or list). */
    var rightTargetId: Int = View.NO_ID
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

    fun submit(list: List<SupportChannel>) {
        channels.clear()
        channels.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val density = parent.resources.displayMetrics.density
        val text = TextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * density).toInt()).apply {
                bottomMargin = (4 * density).toInt()
            }
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), 0, (10 * density).toInt(), 0)
            setBackgroundResource(R.drawable.bg_support_channel)
            isFocusable = true
            isClickable = true
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            textSize = 14f
        }
        return Holder(text)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val channel = channels[position]
        val context = holder.text.context
        val selected = channel.id == selectedId
        val hasNew = !selected && channel.id in unread
        // Unread: white + bold with a purple dot, like Discord's unread channels.
        holder.text.text = if (hasNew) {
            SpannableStringBuilder("# " + channel.name + "  ").apply {
                val start = length
                append("●")
                setSpan(ForegroundColorSpan(context.getColor(R.color.primary_light)), start, length, 0)
            }
        } else {
            "# " + channel.name
        }
        holder.text.isSelected = selected
        holder.text.setTextColor(
            context.getColor(if (selected || hasNew) R.color.text_primary else R.color.text_hint)
        )
        holder.text.setTypeface(null, if (selected || hasNew) Typeface.BOLD else Typeface.NORMAL)
        holder.text.nextFocusRightId = rightTargetId
        holder.text.setOnClickListener { onPick(channel) }
    }

    override fun getItemCount() = channels.size
}

/** Message list of the open channel, oldest at the top. */
class SupportMessageAdapter(
    private val onMessage: (SupportMessage) -> Unit,
    private val onImage: (String) -> Unit
) : RecyclerView.Adapter<SupportMessageAdapter.Holder>() {

    private val messages = mutableListOf<SupportMessage>()
    var you: String? = null

    class Holder(val b: ItemSupportMessageBinding) : RecyclerView.ViewHolder(b.root)

    val items: List<SupportMessage> get() = messages

    fun submit(list: List<SupportMessage>) {
        messages.clear()
        messages.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemSupportMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val m = messages[position]
        val b = holder.b
        val context = b.root.context
        val density = context.resources.displayMetrics.density
        val mine = m.author.app && plainName(you) != null &&
            plainName(m.author.name).equals(plainName(you), ignoreCase = true)
        // App customers can't be @mentioned in Discord (webhook authors aren't
        // members), so staff reply to their message or type "@username".
        // Those messages are marked so the customer sees they're for them.
        val forYou = !mine && isForYou(m)
        b.root.setBackgroundResource(if (forYou) R.drawable.bg_support_message_for_you else R.drawable.bg_support_message)

        b.avatar.load(m.author.avatar) {
            crossfade(false)
            placeholder(R.drawable.bg_account_avatar)
            error(R.drawable.bg_account_avatar)
            transformations(CircleCropTransformation())
        }

        b.authorName.text = m.author.name ?: "Unknown"
        b.authorName.setTextColor(
            when {
                mine -> context.getColor(R.color.primary_light)
                m.author.staff -> Color.rgb(196, 178, 255)
                else -> context.getColor(R.color.text_primary)
            }
        )

        val badge = when {
            mine -> "YOU"
            m.author.bot -> "BOT"
            m.author.staff -> "STAFF"
            else -> null
        }
        b.authorBadge.visibility = if (badge == null) View.GONE else View.VISIBLE
        if (badge != null) {
            b.authorBadge.text = badge
            b.authorBadge.background.mutate().setTint(
                when (badge) {
                    "BOT" -> Color.rgb(88, 101, 242)
                    "STAFF" -> context.getColor(R.color.primary)
                    else -> Color.rgb(70, 70, 80)
                }
            )
        }

        b.messageTime.text = friendlyTime(m.time) + if (m.edited) "  (edited)" else ""

        val reply = m.reply
        b.replyQuote.visibility = if (reply == null) View.GONE else View.VISIBLE
        if (reply != null) {
            b.replyQuote.text = "↪ ${reply.author ?: ""}: ${reply.text.orEmpty().ifBlank { "picture" }}"
        }

        val text = m.text.orEmpty()
        b.messageText.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        b.messageText.text = styled(text)

        b.extras.removeAllViews()
        m.attachments.orEmpty().forEach { a ->
            val url = a.url ?: return@forEach
            if (a.isImage) {
                val image = ImageView(context).apply {
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_START
                    maxHeight = (220 * density).toInt()
                    isFocusable = true
                    isClickable = true
                    setBackgroundResource(R.drawable.bg_support_message)
                    setPadding((2 * density).toInt(), (2 * density).toInt(), (2 * density).toInt(), (2 * density).toInt())
                    contentDescription = "Picture"
                    setOnClickListener { onImage(url) }
                    load(url) {
                        crossfade(true)
                        transformations(RoundedCornersTransformation(8 * density))
                    }
                }
                b.extras.addView(image, LinearLayout.LayoutParams(
                    (320 * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * density).toInt() })
            } else {
                b.extras.addView(extraText(context, "📎 " + (a.name ?: "file"), density))
            }
        }
        m.embeds.orEmpty().forEach { e ->
            val parts = listOfNotNull(e.title?.takeIf { it.isNotBlank() }, e.description?.takeIf { it.isNotBlank() })
            if (parts.isNotEmpty()) {
                b.extras.addView(extraText(context, parts.joinToString("\n"), density).apply {
                    setBackgroundResource(R.drawable.bg_support_quote)
                    setPadding((12 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
                })
            }
        }

        b.root.setOnClickListener { onMessage(m) }
        b.root.setOnLongClickListener { onMessage(m); true }
    }

    override fun getItemCount() = messages.size

    private fun isForYou(m: SupportMessage): Boolean {
        val name = plainName(you) ?: return false
        if (plainName(m.reply?.author).equals(name, ignoreCase = true)) return true
        return Regex("@" + Regex.escape(name) + "(?![\\w.-])", RegexOption.IGNORE_CASE)
            .containsMatchIn(m.text.orEmpty())
    }

    /** "ntesting [ Network24 App ]" -> "ntesting" (the tag Main adds to app names). */
    private fun plainName(name: String?): String? =
        name?.substringBefore(" [ Network24 App ]")?.trim()?.takeIf { it.isNotEmpty() }

    private fun extraText(context: android.content.Context, value: String, density: Float) =
        TextView(context).apply {
            text = styled(value)
            textSize = 13f
            setTextColor(context.getColor(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * density).toInt() }
        }

    companion object {
        private val BOLD = Regex("\\*\\*(.+?)\\*\\*", RegexOption.DOT_MATCHES_ALL)

        /** Discord markdown the app can show: **bold**; other markers are stripped. */
        fun styled(raw: String): CharSequence {
            val cleaned = raw
                .replace("||", "")
                .replace("```", "")
                .replace(Regex("(?m)^> ?"), "│ ")
                .replace(Regex("(?m)^#{1,3} "), "")
                .replace(Regex("\\[(.+?)]\\((https?://\\S+?)\\)"), "$1")
            val out = SpannableStringBuilder()
            var last = 0
            BOLD.findAll(cleaned).forEach { match ->
                out.append(cleaned, last, match.range.first)
                val start = out.length
                out.append(match.groupValues[1])
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, 0)
                last = match.range.last + 1
            }
            out.append(cleaned, last, cleaned.length)
            return out
        }

        private val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        fun friendlyTime(iso: String?): String {
            if (iso.isNullOrBlank() || iso.length < 19) return ""
            val date = try {
                parser.parse(iso.substring(0, 19))
            } catch (e: Exception) {
                null
            } ?: return ""
            val then = Calendar.getInstance().apply { time = date }
            val now = Calendar.getInstance()
            val clock = SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
            return when {
                then.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                    then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) -> "Today $clock"
                then.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
                    then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) - 1 -> "Yesterday $clock"
                else -> SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(date)
            }
        }
    }
}
