package com.network24.player.features.live.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.network24.player.R
import com.network24.player.common.utils.EpgTimeFormatter
import com.network24.player.core.database.DatabaseProvider
import com.network24.player.databinding.ItemChannelBinding
import com.network24.player.features.live.models.LiveChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ChannelAdapter(
    private val channels: MutableList<LiveChannel>,
    private var favoriteIds: Set<String> = emptySet(),
    private val onFocused: (LiveChannel, Int) -> Unit,
    private val onClicked: (LiveChannel, Int) -> Unit,
    private val onLongClicked: ((LiveChannel, Int) -> Unit)? = null
) : RecyclerView.Adapter<ChannelAdapter.ChannelVH>() {

    private var playingPosition = RecyclerView.NO_POSITION
    private val epgScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var epgJob: Job? = null
    private var epgChannelId: String? = null
    private var attachedRecyclerView: RecyclerView? = null

    inner class ChannelVH(val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelVH {
        return ChannelVH(
            ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    override fun getItemCount() = channels.size

    override fun onBindViewHolder(holder: ChannelVH, position: Int) {
        val channel = channels[position]
        holder.binding.txtName.text = channel.name

        holder.binding.imgLogo.load(channel.stream_icon) {
            placeholder(R.drawable.app_logo)
            error(R.drawable.app_logo)
            crossfade(true)
        }

        holder.binding.imgPlaying.visibility =
            if (position == playingPosition) View.VISIBLE else View.GONE

        val isFavorite = favoriteIds.contains(channel.stream_id.toString())
        holder.binding.imgFavorite.visibility =
            if (isFavorite) View.VISIBLE else View.GONE

        holder.itemView.setOnFocusChangeListener { view, hasFocus ->
            if (!hasFocus) return@setOnFocusChangeListener

            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnFocusChangeListener

            // Fire Stick / Android TV: update only the EPG card for the channel
            // currently receiving DPAD focus. The compact EPG text directly under
            // the player/overlay title belongs to the channel that is actually
            // playing and must not change just because focus moved.
            loadSideEpg(view, channel)
            onFocused(channel, pos)
        }

        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            onClicked(channel, pos)
        }

        holder.itemView.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onLongClicked?.invoke(channel, pos)
            }
            true
        }
    }

    /**
     * Updates only the EPG TextViews belonging to the channel-list EPG card.
     *
     * IMPORTANT: txtOverlayProgram is intentionally NOT changed here. That field
     * is the compact EPG shown directly below the player/channel title and must
     * always describe the channel that is actually playing. DPAD focus can move
     * independently of playback, so only the lower highlighted-channel EPG card
     * should follow focus.
     */
    private fun loadSideEpg(itemView: View, channel: LiveChannel) {
        val epgId = channel.epg_channel_id ?: channel.stream_id?.toString() ?: return
        if (epgId == epgChannelId) return

        epgChannelId = epgId
        epgJob?.cancel()

        epgJob = epgScope.launch {
            try {
                val root = itemView.rootView
                val nowTitle = root.findViewById<TextView>(R.id.txtNowTitle) ?: return@launch
                val nowTime = root.findViewById<TextView>(R.id.txtNowTime) ?: return@launch
                val nextTitle = root.findViewById<TextView>(R.id.txtNextTitle) ?: return@launch
                val nextTime = root.findViewById<TextView>(R.id.txtNextTime) ?: return@launch

                val programs = DatabaseProvider.get(itemView.context)
                    .epgDao()
                    .getByEpgChannelId(epgId)

                val now = System.currentTimeMillis()
                val visible = programs
                    .filter { program ->
                        val stop = program.stopTimestamp ?: return@filter false
                        program.startTimestamp != null && stop > now
                    }
                    .sortedBy { it.startTimestamp }

                val current = visible.firstOrNull { program ->
                    val start = program.startTimestamp ?: 0L
                    val stop = program.stopTimestamp ?: 0L
                    start <= now && stop > now
                } ?: visible.firstOrNull()

                val next = visible.firstOrNull { program ->
                    program !== current && (program.startTimestamp ?: 0L) > now
                }

                if (current != null) {
                    nowTitle.text = current.title ?: "No Program Info"
                    nowTime.text = "${EpgTimeFormatter.format(current.startTimestamp)} - ${EpgTimeFormatter.format(current.stopTimestamp)}"
                } else {
                    nowTitle.text = "No EPG"
                    nowTime.text = ""
                }

                if (next != null) {
                    nextTitle.text = next.title ?: ""
                    nextTime.text = "${EpgTimeFormatter.format(next.startTimestamp)} - ${EpgTimeFormatter.format(next.stopTimestamp)}"
                } else {
                    nextTitle.text = ""
                    nextTime.text = ""
                }
            } catch (_: Exception) {
                // Keep the current EPG content if loading fails.
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateData(list: List<LiveChannel>) {
        channels.clear()
        channels.addAll(list)
        epgChannelId = null
        notifyDataSetChanged()
    }

    // Update only rows whose favorite state changed so Fire Stick DPAD focus is preserved.
    fun updateFavorites(newFavIds: Set<String>) {
        val oldFavIds = favoriteIds
        favoriteIds = newFavIds

        for (index in channels.indices) {
            val streamId = channels[index].stream_id?.toString() ?: continue
            val wasFavorite = oldFavIds.contains(streamId)
            val isFavorite = newFavIds.contains(streamId)
            if (wasFavorite != isFavorite) {
                notifyItemChanged(index)
            }
        }
    }

    fun setPlaying(position: Int) {
        val old = playingPosition
        playingPosition = position
        if (old != RecyclerView.NO_POSITION) notifyItemChanged(old)
        if (position != RecyclerView.NO_POSITION) notifyItemChanged(position)

        // RecyclerView only creates ViewHolders for visible rows. When fullscreen
        // playback moves 10/20/etc. channels away, the playing row is therefore not
        // available to requestFocus() until the list is scrolled to it. Always bring
        // the actual playing row into the viewport first, then request DPAD focus.
        if (position in channels.indices) {
            attachedRecyclerView?.post {
                val recyclerView = attachedRecyclerView ?: return@post
                recyclerView.scrollToPosition(position)
                recyclerView.post {
                    recyclerView.findViewHolderForAdapterPosition(position)
                        ?.itemView
                        ?.requestFocus()
                }
            }
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        if (attachedRecyclerView === recyclerView) {
            attachedRecyclerView = null
        }
        epgJob?.cancel()
        epgScope.cancel()
        super.onDetachedFromRecyclerView(recyclerView)
    }
}
