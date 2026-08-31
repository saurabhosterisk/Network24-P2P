package com.network24.player.features.chat.adapter

import android.graphics.Color
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.Timestamp
import com.network24.player.R
import com.network24.player.features.chat.repo.ChatMessage
import java.text.SimpleDateFormat
import java.util.Locale

class ChatMessagesAdapter(
    private val mySenderId: String,
    private val onReply: (ChatMessage) -> Unit = {},
    private val onMessageMenu: (ChatMessage) -> Unit = {},
    private val onReportedChannelClick: (ChatMessage) -> Unit = {}
) : RecyclerView.Adapter<ChatMessagesAdapter.VH>() {
    private val items = mutableListOf<ChatMessage>()
    fun submit(list: List<ChatMessage>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
    override fun getItemViewType(position: Int): Int = if (items[position].senderId == mySenderId) VIEW_RIGHT else VIEW_LEFT
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (viewType == VIEW_RIGHT) R.layout.item_chat_message_right else R.layout.item_chat_message_left
        return VH(LayoutInflater.from(parent.context).inflate(layout, parent, false), onReply, onMessageMenu, onReportedChannelClick)
    }
    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], mySenderId)
    override fun getItemCount(): Int = items.size

    class VH(itemView: View, private val onReply: (ChatMessage) -> Unit, private val onMessageMenu: (ChatMessage) -> Unit, private val onReportedChannelClick: (ChatMessage) -> Unit) : RecyclerView.ViewHolder(itemView) {
        private val tvSender: TextView = itemView.findViewById(R.id.tvSender)
        private val tvText: TextView = itemView.findViewById(R.id.tvText)
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvReplyPreview: TextView = itemView.findViewById(R.id.tvReplyPreview)
        private val tvUserIcon: TextView? = itemView.findViewById<View?>(R.id.tvUserIcon) as? TextView
        private lateinit var current: ChatMessage

        init {
            tvText.setOnClickListener {
                if (current.reportedChannelStreamId != null || reportedChannelName(current) != null) {
                    onReportedChannelClick(current)
                } else {
                    onReply(current)
                }
            }
            tvText.setOnLongClickListener { onMessageMenu(current); true }
        }

        fun bind(m: ChatMessage, mySenderId: String) {
            current = m
            val isMine = m.senderId.isNotBlank() && m.senderId == mySenderId
            tvSender.text = if (isMine) "You" else m.senderName.ifBlank { "Unknown" }
            tvText.text = if (m.deleted) "🚫 This message was deleted" else m.text
            if (!m.deleted) {
                tvText.text = reportedChannelText(m)
            }
            tvText.setTextColor(if (m.deleted) Color.parseColor("#78909C") else Color.WHITE)
            tvTime.text = formatLocalTime(m.ts) + if (m.edited && !m.deleted) "  (edited)" else ""
            tvUserIcon?.text = "👤"
            tvText.movementMethod = ScrollingMovementMethod.getInstance()
            if (!m.replyToMessageId.isNullOrBlank()) {
                tvReplyPreview.text = "↩ Reply to ${m.replyToSenderName?.ifBlank { "Unknown" } ?: "Unknown"}: ${m.replyToText.orEmpty().take(120)}"
                tvReplyPreview.visibility = View.VISIBLE
            } else tvReplyPreview.visibility = View.GONE
        }

        private fun reportedChannelText(message: ChatMessage): CharSequence {
            val channelName = reportedChannelName(message) ?: return message.text
            val start = message.text.indexOf(channelName, ignoreCase = true)
            if (start < 0) return message.text

            return SpannableString(message.text).apply {
                setSpan(
                    ForegroundColorSpan(Color.parseColor("#F44336")),
                    start,
                    start + channelName.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                setSpan(
                    UnderlineSpan(),
                    start,
                    start + channelName.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        private fun reportedChannelName(message: ChatMessage): String? {
            return message.reportedChannelName?.takeIf { it.isNotBlank() }
                ?: Regex("""channel ['\"](.+?)['\"]""", RegexOption.IGNORE_CASE)
                    .find(message.text)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.takeIf { it.isNotBlank() }
        }

        private fun formatLocalTime(ts: Timestamp?): String {
            if (ts == null) return ""
            return SimpleDateFormat("dd/MM/yyyy, HH:mm", Locale.getDefault()).format(ts.toDate())
        }
    }
    companion object { private const val VIEW_LEFT = 0; private const val VIEW_RIGHT = 1 }
}
