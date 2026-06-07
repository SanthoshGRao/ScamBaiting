package com.scamshield.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.scamshield.app.data.local.dao.DetectionCacheDao
import com.scamshield.app.data.local.dao.ScamCategoryDao
import com.scamshield.app.data.local.dao.ScamKeywordDao
import com.scamshield.app.data.local.dao.SenderHistoryDao
import com.scamshield.app.data.local.entity.DetectionCacheEntity
import com.scamshield.app.data.local.entity.ScamCategoryEntity
import com.scamshield.app.data.local.entity.ScamKeywordEntity
import com.scamshield.app.data.local.entity.SenderHistoryEntity
import com.scamshield.app.data.local.entity.BaitingSessionEntity
import com.scamshield.app.data.local.entity.BaitingMessageEntity
import com.scamshield.app.data.local.dao.BaitingDao
import com.scamshield.app.data.local.entity.ScammerEntity
import com.scamshield.app.data.local.dao.ScammerDao
import com.scamshield.app.data.local.dao.IntelligenceDao
import com.scamshield.app.data.local.entity.MissionEntity
import com.scamshield.app.data.local.entity.IntelligenceItemEntity
import com.scamshield.app.data.local.entity.ProcessedMessageFingerprintEntity
import com.scamshield.app.data.local.entity.ScammerDnaProfileEntity
import com.scamshield.app.data.local.entity.StrategyTurnEntity
import com.scamshield.app.data.local.entity.EngagementEffectivenessEntity
import com.scamshield.app.data.local.entity.StrategyOutcomeEntity

/**
 * Room database for ScamShield detection data.
 *
 * Contains:
 * - Scam keywords (dynamic, synced from server)
 * - Scam categories (config)
 * - Sender reputation history
 * - Detection result cache
 */
@Database(
    entities = [
        ScamKeywordEntity::class,
        ScamCategoryEntity::class,
        SenderHistoryEntity::class,
        DetectionCacheEntity::class,
        BaitingSessionEntity::class,
        BaitingMessageEntity::class,
        ScammerEntity::class,
        com.scamshield.app.data.local.entity.OfflineAnalyticsEntity::class,
        ScammerDnaProfileEntity::class,
        MissionEntity::class,
        StrategyTurnEntity::class,
        ProcessedMessageFingerprintEntity::class,
        IntelligenceItemEntity::class,
        EngagementEffectivenessEntity::class,
        StrategyOutcomeEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class ScamShieldDatabase : RoomDatabase() {
    abstract fun scamKeywordDao(): ScamKeywordDao
    abstract fun scamCategoryDao(): ScamCategoryDao
    abstract fun senderHistoryDao(): SenderHistoryDao
    abstract fun detectionCacheDao(): DetectionCacheDao
    abstract fun baitingDao(): BaitingDao
    abstract fun scammerDao(): ScammerDao
    abstract fun intelligenceDao(): IntelligenceDao
}
