package com.network24.player.features.live.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.network24.player.databinding.ItemManageCategoryBinding
import com.network24.player.features.live.models.LiveCategory

class ManageCategoryAdapter(
    private val categories: MutableList<LiveCategory> = mutableListOf(),
    private val disabledIds: MutableSet<String> = mutableSetOf(),
    private val onLabel: String = "Enabled",
    private val offLabel: String = "Disabled",
    private val onChanged: (LiveCategory, Boolean) -> Unit
) : RecyclerView.Adapter<ManageCategoryAdapter.ViewHolder>() {

    fun updateList(items: List<LiveCategory>, disabled: Set<String>) {
        categories.clear()
        categories.addAll(items)
        disabledIds.clear()
        disabledIds.addAll(disabled)
        notifyDataSetChanged()
    }

    fun setEnabled(categoryId: String, enabled: Boolean) {
        if (enabled) disabledIds.remove(categoryId) else disabledIds.add(categoryId)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemManageCategoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(categories[position])
    }

    override fun getItemCount(): Int = categories.size

    inner class ViewHolder(
        private val binding: ItemManageCategoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(category: LiveCategory) {
            binding.txtCategory.text = category.category_name
            binding.switchEnabled.setOnCheckedChangeListener(null)
            binding.switchEnabled.isChecked = !disabledIds.contains(category.category_id)
            binding.txtStatus.text = if (binding.switchEnabled.isChecked) onLabel else offLabel

            binding.switchEnabled.setOnCheckedChangeListener { _, checked ->
                setEnabled(category.category_id, checked)
                binding.txtStatus.text = if (checked) onLabel else offLabel
                onChanged(category, checked)
            }

            binding.root.setOnClickListener {
                binding.switchEnabled.toggle()
            }
        }
    }
}
