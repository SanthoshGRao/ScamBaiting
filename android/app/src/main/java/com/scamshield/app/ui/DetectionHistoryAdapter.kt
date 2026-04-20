package com.scamshield.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.scamshield.app.R
import com.scamshield.app.data.local.entity.DetectionCacheEntity
import com.scamshield.app.databinding.ItemDetectionHistoryBinding
import com.scamshield.app.util.formatCategoryLabel

/**
 * RecyclerView adapter for detection history items in the Analytics tab.
 */
class DetectionHistoryAdapter : ListAdapter<DetectionCacheEntity, DetectionHistoryAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDetectionHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemDetectionHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DetectionCacheEntity) {
            val ctx = binding.root.context
            val confPercent = (item.confidence * 100).toInt()

            binding.tvCategory.text = item.category.formatCategoryLabel()

            binding.tvRules.text = item.matchedRules.replace(",", " | ")
            binding.tvConf.text = "$confPercent%"

            // Color by risk
            val confColor = when {
                item.confidence >= 0.80f -> R.color.risk_high
                item.confidence >= 0.65f -> R.color.risk_medium
                item.confidence >= 0.30f -> R.color.risk_low
                else -> R.color.risk_safe
            }
            binding.tvConf.setTextColor(ContextCompat.getColor(ctx, confColor))

            // Dot color
            val dotColor = if (item.isSuspicious) R.color.risk_high else R.color.risk_safe
            binding.viewRiskDot.background.setTint(ContextCompat.getColor(ctx, dotColor))

            // Item entrance animation
            binding.root.alpha = 0f
            binding.root.translationX = 50f
            binding.root.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(300)
                .setStartDelay((bindingAdapterPosition * 50).toLong())
                .start()
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<DetectionCacheEntity>() {
        override fun areItemsTheSame(oldItem: DetectionCacheEntity, newItem: DetectionCacheEntity): Boolean =
            oldItem.messageHash == newItem.messageHash

        override fun areContentsTheSame(oldItem: DetectionCacheEntity, newItem: DetectionCacheEntity): Boolean =
            oldItem == newItem
    }
}
