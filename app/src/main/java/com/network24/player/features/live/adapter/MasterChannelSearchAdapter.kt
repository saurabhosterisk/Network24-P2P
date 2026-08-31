package com.network24.player.features.live.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.network24.player.R
import com.network24.player.core.database.entity.MasterChannelSearchResult
import com.network24.player.databinding.ItemMasterChannelSearchResultBinding

class MasterChannelSearchAdapter(
    private val onSelected: (MasterChannelSearchResult) -> Unit,
    private val onLongClicked: (MasterChannelSearchResult) -> Unit
) : RecyclerView.Adapter<MasterChannelSearchAdapter.ResultViewHolder>() {

    private val results = mutableListOf<MasterChannelSearchResult>()
    private var currentProgramByEpgChannelId: Map<String, String> = emptyMap()

    inner class ResultViewHolder(
        val binding: ItemMasterChannelSearchResultBinding
    ) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        return ResultViewHolder(
            ItemMasterChannelSearchResultBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )
    }

    override fun getItemCount(): Int = results.size

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        val result = results[position]
        holder.binding.imgChannelLogo.load(result.icon) {
            placeholder(R.drawable.app_logo)
            error(R.drawable.app_logo)
            crossfade(true)
        }

        holder.binding.txtChannelName.text = result.channelName ?: "Unknown channel"
        holder.binding.txtCategoryName.text = result.categoryName ?: "Uncategorized"
        holder.binding.txtCurrentlyPlaying.text = result.epgChannelId
            ?.let { currentProgramByEpgChannelId[it] }
            ?.takeIf { it.isNotBlank() }
            ?.let { "Currently Playing: $it" }
            ?: "No EPG Data"
        holder.binding.imgFavorite.visibility = if (result.isFavorite) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }

        holder.binding.cardRoot.setOnFocusChangeListener { _, hasFocus ->
            holder.binding.cardRoot.strokeWidth = if (hasFocus) dp(holder, 2) else dp(holder, 1)
        }
        holder.binding.root.setOnClickListener {
            holder.bindingAdapterPosition
                .takeIf { it != RecyclerView.NO_POSITION }
                ?.let { onSelected(results[it]) }
        }
        holder.binding.root.setOnLongClickListener {
            holder.bindingAdapterPosition
                .takeIf { it != RecyclerView.NO_POSITION }
                ?.let { onLongClicked(results[it]) }
            true
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submitResults(newResults: List<MasterChannelSearchResult>) {
        results.clear()
        results.addAll(newResults)
        notifyDataSetChanged()
    }

    fun updateCurrentPrograms(programs: Map<String, String>) {
        currentProgramByEpgChannelId = programs
        notifyItemRangeChanged(0, results.size)
    }

    private fun dp(holder: ResultViewHolder, value: Int): Int {
        return (value * holder.itemView.resources.displayMetrics.density).toInt()
    }
}
