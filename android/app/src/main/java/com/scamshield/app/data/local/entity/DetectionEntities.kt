package com.scamshield.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for scam keywords loaded from category config.
 * Synced from server every 24 hours, pre-populated from bundled JSON.
 */
@Entity(tableName = "scam_keywords")
data class ScamKeywordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val keyword: String,
    val categoryId: String,
    val weight: Float = 0.5f,
    val isRegex: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
)

/**
 * Room entity for scam categories.
 */
@Entity(tableName = "scam_categories")
data class ScamCategoryEntity(
    @PrimaryKey
    val categoryId: String,
    val label: String,
    val description: String,
    val weightBoost: Float = 1.0f,
    val lastUpdated: Long = System.currentTimeMillis()
)

/**
 * Room entity for sender reputation tracking.
 * Tracks all senders with a reputation score.
 */
@Entity(tableName = "sender_history")
data class SenderHistoryEntity(
    @PrimaryKey
    val senderId: String,
    val scamScore: Float = 0.5f,
    val totalMessages: Int = 0,
    val flaggedMessages: Int = 0,
    val lastSeen: Long = System.currentTimeMillis()
)

/**
 * Room entity for cached detection results.
 * Caches recent verdicts by message hash to avoid re-processing.
 */
@Entity(tableName = "detection_cache")
data class DetectionCacheEntity(
    @PrimaryKey
    val messageHash: String,
    val isSuspicious: Boolean,
    val confidence: Float,
    val category: String,
    val matchedRules: String, // JSON-serialized list
    val timestamp: Long = System.currentTimeMillis()
)
