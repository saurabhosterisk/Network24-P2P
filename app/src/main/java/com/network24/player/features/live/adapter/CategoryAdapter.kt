package com.network24.player.features.live.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.network24.player.databinding.ItemCategoryBinding
import com.network24.player.features.live.models.LiveCategory

class CategoryAdapter(
    private val listener: (LiveCategory) -> Unit,
    private val onLongClick: (LiveCategory) -> Unit
) : ListAdapter<LiveCategory, CategoryAdapter.ViewHolder>(DIFF_CALLBACK) {

    inner class ViewHolder(val binding: ItemCategoryBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCategoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.binding.txtCategory.text = item.category_name

        holder.itemView.setOnClickListener { listener(item) }

        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }

        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            holder.binding.cardCategory.strokeWidth = dp(holder, if (hasFocus) 2 else 1)
        }
    }

    fun updateList(newList: List<LiveCategory>) {
        submitList(newList.toList())
    }

    private fun dp(holder: ViewHolder, value: Int): Int {
        return (value * holder.itemView.resources.displayMetrics.density).toInt()
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<LiveCategory>() {
            override fun areItemsTheSame(oldItem: LiveCategory, newItem: LiveCategory): Boolean =
                oldItem.category_id == newItem.category_id

            override fun areContentsTheSame(oldItem: LiveCategory, newItem: LiveCategory): Boolean =
                oldItem == newItem
        }
    }
}
