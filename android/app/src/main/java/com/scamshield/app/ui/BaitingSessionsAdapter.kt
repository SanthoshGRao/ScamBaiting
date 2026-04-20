package com.scamshield.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.scamshield.app.R
import com.scamshield.app.data.local.entity.BaitingSessionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BaitingSessionsAdapter — Displays baiting sessions with
 * sender info, persona, message count, status indicator,
 * and Bait/Stop toggle action.
 */
class BaitingSessionsAdapter(
    private val onItemClick: (String) -> Unit,
    private val onBaitToggle: (String, Boolean) -> Unit = { _, _ -> }
) : ListAdapter<BaitingSessionEntity, BaitingSessionsAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<BaitingSessionEntity>() {
            override fun areItemsTheSame(old: BaitingSessionEntity, new: BaitingSessionEntity) =
                old.senderId == new.senderId
            override fun areContentsTheSame(old: BaitingSessionEntity, new: BaitingSessionEntity) =
                old == new
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvSender: TextView = itemView.findViewById(R.id.tvBaitSender)
        val tvPersona: TextView = itemView.findViewById(R.id.tvBaitPersona)
        val tvMessages: TextView = itemView.findViewById(R.id.tvBaitMessages)
        val tvTime: TextView = itemView.findViewById(R.id.tvBaitTime)
        val statusDot: View = itemView.findViewById(R.id.baitStatusDot)
        val tvStatus: TextView = itemView.findViewById(R.id.tvBaitStatus)
        val btnToggle: MaterialButton = itemView.findViewById(R.id.btnBaitToggle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_baiting_session, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val session = getItem(position)
        val ctx = holder.itemView.context

        // Persona names without emojis
        val personaNames = mapOf(
            "busy_professional" to "Busy Professional",
            "skeptical_buyer" to "Skeptical Buyer",
            "half_understanding_user" to "Half-Understanding User",
            "lonely_conversationalist" to "Lonely Conversationalist",
            "hopeful_opportunity_seeker" to "Hopeful Opportunity Seeker",
            "curious_user" to "Curious User"
        )

        holder.tvSender.text = session.senderId
        holder.tvPersona.text = personaNames[session.persona] ?: session.persona
        holder.tvMessages.text = "${session.totalMessages} messages"

        // Format timestamp
        val timeFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
        holder.tvTime.text = timeFormat.format(Date(session.startTime))

        // Status indicator
        if (session.isActive) {
            holder.statusDot.background.setTint(
                ContextCompat.getColor(ctx, R.color.status_active)
            )
            holder.tvStatus.text = "Active"
            holder.tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.status_active))
            holder.btnToggle.text = "Stop"
            holder.btnToggle.setTextColor(ContextCompat.getColor(ctx, R.color.risk_high))
            holder.btnToggle.setStrokeColorResource(R.color.risk_high)
        } else {
            holder.statusDot.background.setTint(
                ContextCompat.getColor(ctx, R.color.status_inactive)
            )
            holder.tvStatus.text = "Stopped"
            holder.tvStatus.setTextColor(ContextCompat.getColor(ctx, R.color.text_hint))
            holder.btnToggle.text = "Bait"
            holder.btnToggle.setTextColor(ContextCompat.getColor(ctx, R.color.accent))
            holder.btnToggle.setStrokeColorResource(R.color.accent)
        }

        // Toggle action
        holder.btnToggle.setOnClickListener {
            onBaitToggle(session.senderId, session.isActive)
        }

        // Item click opens log
        holder.itemView.setOnClickListener {
            onItemClick(session.senderId)
        }

        // Slide-up animation
        holder.itemView.alpha = 0f
        holder.itemView.translationY = 30f
        holder.itemView.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(300)
            .setStartDelay((position * 50).toLong())
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
}
