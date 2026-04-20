package com.scamshield.app.di

import android.content.Context
import androidx.room.Room
import com.scamshield.app.data.local.ScamShieldDatabase
import com.scamshield.app.data.local.dao.DetectionCacheDao
import com.scamshield.app.data.local.dao.ScamCategoryDao
import com.scamshield.app.data.local.dao.ScamKeywordDao
import com.scamshield.app.data.local.dao.SenderHistoryDao
import com.scamshield.app.data.local.dao.ScammerDao
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
}
