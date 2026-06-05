package com.scamshield.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.scamshield.app.data.local.entity.BaitingMessageEntity
import com.scamshield.app.data.local.entity.BaitingSessionEntity

@Dao
abstract class BaitingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun createSession(session: BaitingSessionEntity)

    @Query("SELECT * FROM baiting_sessions WHERE senderId = :senderId LIMIT 1")
    abstract suspend fun getSession(senderId: String): BaitingSessionEntity?

    @Query("SELECT * FROM baiting_sessions WHERE isActive = 1")
    abstract suspend fun getActiveSessions(): List<BaitingSessionEntity>

    @Query("SELECT * FROM baiting_sessions ORDER BY startTime DESC")
    abstract suspend fun getAllSessions(): List<BaitingSessionEntity>

    @Query("UPDATE baiting_sessions SET isActive = 0 WHERE senderId = :senderId")
    abstract suspend fun endSession(senderId: String)

    @Query("UPDATE baiting_sessions SET isActive = 1 WHERE senderId = :senderId")
    abstract suspend fun reactivateSession(senderId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertMessage(message: BaitingMessageEntity)

    @Query("SELECT * FROM baiting_messages WHERE senderId = :senderId ORDER BY id ASC")
    abstract suspend fun getMessagesForSender(senderId: String): List<BaitingMessageEntity>

    @Query("SELECT * FROM baiting_messages WHERE senderId = :senderId ORDER BY id ASC")
    abstract fun observeMessagesForSender(senderId: String): kotlinx.coroutines.flow.Flow<List<BaitingMessageEntity>>

    @Transaction
    open suspend fun addMessageAndUpdateSession(message: BaitingMessageEntity) {
        insertMessage(message)
        updateMessageCount(message.senderId)
    }

    @Query("UPDATE baiting_sessions SET totalMessages = totalMessages + 1 WHERE senderId = :senderId")
    abstract suspend fun updateMessageCount(senderId: String)

    @Query("UPDATE baiting_sessions SET currentStrategy = :strategy WHERE senderId = :senderId")
    abstract suspend fun updateSessionStrategy(senderId: String, strategy: String)

    @Query("DELETE FROM baiting_messages WHERE senderId = :senderId")
    abstract suspend fun deleteMessagesForSender(senderId: String)

    @Query("DELETE FROM baiting_sessions")
    abstract suspend fun deleteAllSessions()

    @Query("DELETE FROM baiting_messages")
    abstract suspend fun deleteAllMessages()
}
