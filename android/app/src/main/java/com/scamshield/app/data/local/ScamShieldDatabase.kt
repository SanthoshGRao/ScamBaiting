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
    ],
    version = 4,
    exportSchema = false,
)
abstract class ScamShieldDatabase : RoomDatabase() {
    abstract fun scamKeywordDao(): ScamKeywordDao
    abstract fun scamCategoryDao(): ScamCategoryDao
    abstract fun senderHistoryDao(): SenderHistoryDao
    abstract fun detectionCacheDao(): DetectionCacheDao
    abstract fun baitingDao(): BaitingDao
    abstract fun scammerDao(): ScammerDao
}
