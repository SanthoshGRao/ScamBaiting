package com.scamshield.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "baiting_sessions")
data class BaitingSessionEntity(
    @PrimaryKey
    val senderId: String,
    val persona: String,
    val startTime: Long,
    val isActive: Boolean = true,
    val totalMessages: Int = 0,
    /** Last strategy from backend (CONFUSION, DELAY, …); drives the next /bait/reply request. */
    val currentStrategy: String = "CONFUSION",
    // New v2 columns
    val conversationState: String = "INITIAL"
)

@Entity(
    tableName = "baiting_messages",
    foreignKeys = [
        ForeignKey(
            entity = BaitingSessionEntity::class,
            parentColumns = ["senderId"],
            childColumns = ["senderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["senderId"])]
)
data class BaitingMessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val senderId: String,
    val role: String, // "user" (scammer) or "assistant" (AI)
    val content: String,
    val timestamp: Long
)

@Entity(tableName = "offline_analytics")
data class OfflineAnalyticsEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sessionId: String,
    val timestamp: Long,
    val detectedIntent: String,
    val confidenceScore: Int,
    val selectedState: String,
    val selectedPersona: String,
    val selectedReply: String,
    val isSynced: Boolean = false
)
