package com.scamshield.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "scammer_dna_profiles")
data class ScammerDnaProfileEntity(
    @PrimaryKey val senderId: String,
    val urgencyScore: Int = 0,
    val aggressionScore: Int = 0,
    val persistenceScore: Int = 0,
    val technicalLevel: Int = 0,
    val trustBuildingScore: Int = 0,
    val repetitionScore: Int = 0,
    val avgMessageLength: Float = 0f,
    val avgResponseLatencyMs: Float = 0f,
    val messageCount: Int = 0,
    val scamCategory: String = "unknown",
    val lastMessageAt: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "missions", indices = [Index(value = ["sessionId", "status"], name = "index_missions_session_status")])
data class MissionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val senderId: String,
    val missionType: String,
    val status: String = "ACTIVE",
    val priority: Int = 50,
    val scamCategory: String = "unknown",
    val successCriteria: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "strategy_turns", indices = [Index(value = ["sessionId"], name = "index_strategy_turns_session")])
data class StrategyTurnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val senderId: String,
    val missionType: String,
    val strategy: String,
    val persona: String,
    val scamCategory: String,
    val scammerMessage: String,
    val assistantReply: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "processed_message_fingerprints", indices = [Index(value = ["senderId"], name = "index_processed_message_fingerprints_sender")])
data class ProcessedMessageFingerprintEntity(
    @PrimaryKey val fingerprint: String,
    val senderId: String,
    val processedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "intelligence_items",
    indices = [
        Index(value = ["sessionId", "itemType", "normalizedValue"], unique = true, name = "index_intelligence_items_unique"),
        Index(value = ["sessionId"], name = "index_intelligence_items_session")
    ]
)
data class IntelligenceItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val senderId: String,
    val itemType: String,
    val value: String,
    val normalizedValue: String,
    val sourceFingerprint: String,
    val confidence: Float = 1.0f,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "engagement_effectiveness")
data class EngagementEffectivenessEntity(
    @PrimaryKey val sessionId: String,
    val timeWastedMs: Long = 0L,
    val messagesExchanged: Int = 0,
    val scammerMessages: Int = 0,
    val assistantReplies: Int = 0,
    val intelligenceExtractedCount: Int = 0,
    val walletsCollected: Int = 0,
    val urlsCollected: Int = 0,
    val upiIdsCollected: Int = 0,
    val domainsCollected: Int = 0,
    val responseRate: Float = 0f,
    val missionCompleted: Boolean = false,
    val sessionAbandoned: Boolean = false,
    val effectivenessScore: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "strategy_outcomes",
    primaryKeys = ["missionType", "strategy", "persona"]
)
data class StrategyOutcomeEntity(
    val missionType: String,
    val strategy: String,
    val persona: String,
    val attempts: Int = 0,
    val successes: Int = 0,
    val successRate: Float = 0f,
    val avgEffectivenessScore: Float = 0f,
    val updatedAt: Long = System.currentTimeMillis()
)
