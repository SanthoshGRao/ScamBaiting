package com.scamshield.app.data.local.dao

import androidx.room.*
import com.scamshield.app.data.local.entity.DetectionCacheEntity
import com.scamshield.app.data.local.entity.ScamCategoryEntity
import com.scamshield.app.data.local.entity.ScamKeywordEntity
import com.scamshield.app.data.local.entity.SenderHistoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for scam keyword operations.
 * Supports dynamic keyword loading for the RuleEngine.
 */
@Dao
interface ScamKeywordDao {

    @Query("SELECT * FROM scam_keywords WHERE categoryId = :categoryId")
    suspend fun getKeywordsByCategory(categoryId: String): List<ScamKeywordEntity>

    @Query("SELECT * FROM scam_keywords")
    suspend fun getAllKeywords(): List<ScamKeywordEntity>

    @Query("SELECT * FROM scam_keywords")
    fun observeAllKeywords(): Flow<List<ScamKeywordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(keywords: List<ScamKeywordEntity>)

    @Query("DELETE FROM scam_keywords")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(keywords: List<ScamKeywordEntity>) {
        deleteAll()
        insertAll(keywords)
    }
}

/**
 * DAO for scam category operations.
 */
@Dao
interface ScamCategoryDao {

    @Query("SELECT * FROM scam_categories")
    suspend fun getAllCategories(): List<ScamCategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(categories: List<ScamCategoryEntity>)

    @Query("SELECT weightBoost FROM scam_categories WHERE categoryId = :id")
    suspend fun getWeightBoost(id: String): Float?

    @Query("DELETE FROM scam_categories")
    suspend fun deleteAll()
}

/**
 * DAO for sender history tracking.
 * Tracks reputation of all message senders.
 */
@Dao
interface SenderHistoryDao {

    @Query("SELECT * FROM sender_history WHERE senderId = :senderId")
    suspend fun getSender(senderId: String): SenderHistoryEntity?

    @Query("SELECT scamScore FROM sender_history WHERE senderId = :senderId")
    suspend fun getSenderScore(senderId: String): Float?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sender: SenderHistoryEntity)

    @Query("""
        UPDATE sender_history 
        SET totalMessages = totalMessages + 1,
            flaggedMessages = CASE WHEN :wasFlagged THEN flaggedMessages + 1 ELSE flaggedMessages END,
            scamScore = CASE WHEN :wasFlagged 
                THEN MIN(1.0, scamScore + 0.1) 
                ELSE MAX(0.0, scamScore - 0.02) END,
            lastSeen = :timestamp
        WHERE senderId = :senderId
    """)
    suspend fun updateSenderStats(
        senderId: String,
        wasFlagged: Boolean,
        timestamp: Long = System.currentTimeMillis()
    )

    @Query("SELECT * FROM sender_history ORDER BY scamScore DESC LIMIT :limit")
    suspend fun getTopScammers(limit: Int = 50): List<SenderHistoryEntity>

    @Query("DELETE FROM sender_history")
    suspend fun deleteAll()
}

/**
 * DAO for detection result cache.
 * Avoids re-processing recently analyzed messages.
 */
@Dao
interface DetectionCacheDao {

    @Query("SELECT * FROM detection_cache WHERE messageHash = :hash")
    suspend fun getCachedResult(hash: String): DetectionCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cache: DetectionCacheEntity)

    @Query("DELETE FROM detection_cache WHERE timestamp < :expiry")
    suspend fun clearExpired(expiry: Long)

    @Query("SELECT * FROM detection_cache ORDER BY timestamp DESC")
    suspend fun getAll(): List<DetectionCacheEntity>

    @Query("SELECT * FROM detection_cache ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<DetectionCacheEntity>>

    @Query("DELETE FROM detection_cache")
    suspend fun clearAll()
}
