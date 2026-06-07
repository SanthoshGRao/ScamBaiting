package com.scamshield.app.di

import android.content.Context
import androidx.room.Room
import com.scamshield.app.data.local.ScamShieldDatabase
import com.scamshield.app.data.local.dao.DetectionCacheDao
import com.scamshield.app.data.local.dao.ScamCategoryDao
import com.scamshield.app.data.local.dao.ScamKeywordDao
import com.scamshield.app.data.local.dao.SenderHistoryDao
import com.scamshield.app.data.local.dao.ScammerDao
import com.scamshield.app.data.local.dao.IntelligenceDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing Room database and DAO instances.
 *
 * @Singleton ensures a single database instance across the app.
 * fallbackToDestructiveMigration is used for Phase 1 simplicity.
 */
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Add new columns to baiting_sessions
        db.execSQL("ALTER TABLE baiting_sessions ADD COLUMN persona TEXT NOT NULL DEFAULT 'confused_elderly'")
        db.execSQL("ALTER TABLE baiting_sessions ADD COLUMN conversationState TEXT NOT NULL DEFAULT 'INITIAL'")
        
        // Create offline_analytics table
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `offline_analytics` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL,
                `detectedIntent` TEXT NOT NULL,
                `confidenceScore` INTEGER NOT NULL,
                `selectedState` TEXT NOT NULL,
                `selectedPersona` TEXT NOT NULL,
                `selectedReply` TEXT NOT NULL,
                `isSynced` INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `scammer_dna_profiles` (
                `senderId` TEXT NOT NULL,
                `urgencyScore` INTEGER NOT NULL DEFAULT 0,
                `aggressionScore` INTEGER NOT NULL DEFAULT 0,
                `persistenceScore` INTEGER NOT NULL DEFAULT 0,
                `technicalLevel` INTEGER NOT NULL DEFAULT 0,
                `trustBuildingScore` INTEGER NOT NULL DEFAULT 0,
                `repetitionScore` INTEGER NOT NULL DEFAULT 0,
                `avgMessageLength` REAL NOT NULL DEFAULT 0,
                `avgResponseLatencyMs` REAL NOT NULL DEFAULT 0,
                `messageCount` INTEGER NOT NULL DEFAULT 0,
                `scamCategory` TEXT NOT NULL DEFAULT 'unknown',
                `lastMessageAt` INTEGER NOT NULL DEFAULT 0,
                `updatedAt` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`senderId`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `missions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `senderId` TEXT NOT NULL,
                `missionType` TEXT NOT NULL,
                `status` TEXT NOT NULL DEFAULT 'ACTIVE',
                `priority` INTEGER NOT NULL DEFAULT 50,
                `scamCategory` TEXT NOT NULL DEFAULT 'unknown',
                `successCriteria` TEXT NOT NULL DEFAULT '',
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_missions_session_status` ON `missions` (`sessionId`, `status`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `strategy_turns` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `senderId` TEXT NOT NULL,
                `missionType` TEXT NOT NULL,
                `strategy` TEXT NOT NULL,
                `persona` TEXT NOT NULL,
                `scamCategory` TEXT NOT NULL,
                `scammerMessage` TEXT NOT NULL,
                `assistantReply` TEXT NOT NULL,
                `timestamp` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_strategy_turns_session` ON `strategy_turns` (`sessionId`)")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `processed_message_fingerprints` (
                `fingerprint` TEXT NOT NULL,
                `senderId` TEXT NOT NULL,
                `processedAt` INTEGER NOT NULL,
                PRIMARY KEY(`fingerprint`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_processed_message_fingerprints_sender` ON `processed_message_fingerprints` (`senderId`)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `intelligence_items` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` TEXT NOT NULL,
                `senderId` TEXT NOT NULL,
                `itemType` TEXT NOT NULL,
                `value` TEXT NOT NULL,
                `normalizedValue` TEXT NOT NULL,
                `sourceFingerprint` TEXT NOT NULL,
                `confidence` REAL NOT NULL DEFAULT 1.0,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_intelligence_items_unique` ON `intelligence_items` (`sessionId`, `itemType`, `normalizedValue`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_intelligence_items_session` ON `intelligence_items` (`sessionId`)")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `engagement_effectiveness` (
                `sessionId` TEXT NOT NULL,
                `timeWastedMs` INTEGER NOT NULL DEFAULT 0,
                `messagesExchanged` INTEGER NOT NULL DEFAULT 0,
                `scammerMessages` INTEGER NOT NULL DEFAULT 0,
                `assistantReplies` INTEGER NOT NULL DEFAULT 0,
                `intelligenceExtractedCount` INTEGER NOT NULL DEFAULT 0,
                `walletsCollected` INTEGER NOT NULL DEFAULT 0,
                `urlsCollected` INTEGER NOT NULL DEFAULT 0,
                `upiIdsCollected` INTEGER NOT NULL DEFAULT 0,
                `domainsCollected` INTEGER NOT NULL DEFAULT 0,
                `responseRate` REAL NOT NULL DEFAULT 0,
                `missionCompleted` INTEGER NOT NULL DEFAULT 0,
                `sessionAbandoned` INTEGER NOT NULL DEFAULT 0,
                `effectivenessScore` INTEGER NOT NULL DEFAULT 0,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`sessionId`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `strategy_outcomes` (
                `missionType` TEXT NOT NULL,
                `strategy` TEXT NOT NULL,
                `persona` TEXT NOT NULL,
                `attempts` INTEGER NOT NULL DEFAULT 0,
                `successes` INTEGER NOT NULL DEFAULT 0,
                `successRate` REAL NOT NULL DEFAULT 0,
                `avgEffectivenessScore` REAL NOT NULL DEFAULT 0,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`missionType`, `strategy`, `persona`)
            )
            """.trimIndent()
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): ScamShieldDatabase {
        return Room.databaseBuilder(
            context,
            ScamShieldDatabase::class.java,
            "scamshield_db"
        )
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
            // Still fallback to destructive for older unmigrated versions (e.g. 1->4) if necessary, 
            // but 4->5 will use the non-destructive migration.
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideScamKeywordDao(db: ScamShieldDatabase): ScamKeywordDao {
        return db.scamKeywordDao()
    }

    @Provides
    @Singleton
    fun provideScamCategoryDao(db: ScamShieldDatabase): ScamCategoryDao {
        return db.scamCategoryDao()
    }

    @Provides
    @Singleton
    fun provideSenderHistoryDao(db: ScamShieldDatabase): SenderHistoryDao {
        return db.senderHistoryDao()
    }

    @Provides
    @Singleton
    fun provideDetectionCacheDao(db: ScamShieldDatabase): DetectionCacheDao {
        return db.detectionCacheDao()
    }

    @Provides
    @Singleton
    fun provideBaitingDao(db: ScamShieldDatabase): com.scamshield.app.data.local.dao.BaitingDao {
        return db.baitingDao()
    }

    @Provides
    @Singleton
    fun provideScammerDao(db: ScamShieldDatabase): ScammerDao {
        return db.scammerDao()
    }

    @Provides
    @Singleton
    fun provideIntelligenceDao(db: ScamShieldDatabase): IntelligenceDao {
        return db.intelligenceDao()
    }
}
