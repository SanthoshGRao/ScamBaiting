package com.scamshield.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.scamshield.app.data.local.entity.ScammerEntity

@Dao
interface ScammerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(scammer: ScammerEntity)

    @Query("SELECT * FROM scammers ORDER BY timestamp DESC")
    suspend fun getAll(): List<ScammerEntity>

    @Query("SELECT * FROM scammers WHERE phoneNumber = :phone LIMIT 1")
    suspend fun getByPhone(phone: String): ScammerEntity?

    @Query("UPDATE scammers SET aiEnabled = :enabled, timestamp = :ts WHERE phoneNumber = :phone")
    suspend fun setAiEnabled(phone: String, enabled: Boolean, ts: Long = System.currentTimeMillis())

    @Query("DELETE FROM scammers")
    suspend fun deleteAll()
}
