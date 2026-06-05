package com.scamshield.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.scamshield.app.data.local.entity.ScammerEntity
import com.scamshield.app.databinding.ItemScammerBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScammerListAdapter(
    private val onAiToggle: (ScammerEntity, Boolean) -> Unit,
    private val onShowChat: (ScammerEntity) -> Unit
) : RecyclerView.Adapter<ScammerListAdapter.Holder>() {
    private var items: List<ScammerEntity> = emptyList()
    private var clearTime: Long = 0L

    fun submit(data: List<ScammerEntity>, clearTime: Long = 0L) {
        this.items = data
        this.clearTime = clearTime
        notifyDataSetChanged()
    }

    class Holder(val binding: ItemScammerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemScammerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.binding.tvPhone.text = item.phoneNumber
        val time = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(item.timestamp))
        holder.binding.tvMeta.text = "Risk ${item.riskLevel} • $time"
        holder.binding.tvLastMessage.text = item.lastMessage
        holder.binding.switchAi.setOnCheckedChangeListener(null)
        holder.binding.switchAi.isChecked = item.aiEnabled
        holder.binding.switchAi.setOnCheckedChangeListener { _, isChecked -> onAiToggle(item, isChecked) }
        holder.binding.btnShowChat.setOnClickListener { onShowChat(item) }

        if (item.timestamp < clearTime) {
            holder.binding.switchAi.visibility = android.view.View.GONE
            holder.binding.btnShowChat.visibility = android.view.View.GONE
        } else {
            holder.binding.switchAi.visibility = android.view.View.VISIBLE
            holder.binding.btnShowChat.visibility = android.view.View.VISIBLE
        }
    }

    override fun getItemCount(): Int = items.size
}
