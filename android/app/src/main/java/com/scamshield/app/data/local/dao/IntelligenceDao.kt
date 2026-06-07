package com.scamshield.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.scamshield.app.data.local.entity.MissionEntity
import com.scamshield.app.data.local.entity.EngagementEffectivenessEntity
import com.scamshield.app.data.local.entity.IntelligenceItemEntity
import com.scamshield.app.data.local.entity.ProcessedMessageFingerprintEntity
import com.scamshield.app.data.local.entity.ScammerDnaProfileEntity
import com.scamshield.app.data.local.entity.StrategyOutcomeEntity
import com.scamshield.app.data.local.entity.StrategyTurnEntity

@Dao
interface IntelligenceDao {
    @Query("SELECT EXISTS(SELECT 1 FROM processed_message_fingerprints WHERE fingerprint = :fingerprint)")
    suspend fun hasProcessedFingerprint(fingerprint: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProcessedFingerprint(fingerprint: ProcessedMessageFingerprintEntity): Long

    @Query("SELECT * FROM scammer_dna_profiles WHERE senderId = :senderId LIMIT 1")
    suspend fun getDnaProfile(senderId: String): ScammerDnaProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDnaProfile(profile: ScammerDnaProfileEntity)

    @Query("SELECT * FROM missions WHERE sessionId = :sessionId AND status = 'ACTIVE' ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getActiveMission(sessionId: String): MissionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMission(mission: MissionEntity): Long

    @Query("UPDATE missions SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateMissionStatus(id: Long, status: String, updatedAt: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIntelligenceItems(items: List<IntelligenceItemEntity>)

    @Query("SELECT * FROM intelligence_items WHERE sessionId = :sessionId ORDER BY createdAt DESC")
    suspend fun getIntelligenceItems(sessionId: String): List<IntelligenceItemEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM intelligence_items WHERE sessionId = :sessionId AND itemType IN (:types))")
    suspend fun hasIntelligenceOfTypes(sessionId: String, types: List<String>): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStrategyTurn(turn: StrategyTurnEntity): Long

    @Query("SELECT * FROM strategy_turns WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentStrategyTurns(sessionId: String, limit: Int = 8): List<StrategyTurnEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEngagementEffectiveness(effectiveness: EngagementEffectivenessEntity)

    @Query("SELECT * FROM engagement_effectiveness WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getEngagementEffectiveness(sessionId: String): EngagementEffectivenessEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStrategyOutcome(outcome: StrategyOutcomeEntity)

    @Query("SELECT * FROM strategy_outcomes WHERE missionType = :missionType AND strategy = :strategy AND persona = :persona LIMIT 1")
    suspend fun getStrategyOutcome(missionType: String, strategy: String, persona: String): StrategyOutcomeEntity?

    @Query("SELECT * FROM strategy_outcomes WHERE missionType = :missionType AND persona = :persona AND attempts >= :minAttempts ORDER BY successRate DESC, avgEffectivenessScore DESC LIMIT 1")
    suspend fun getBestStrategyOutcome(missionType: String, persona: String, minAttempts: Int = 2): StrategyOutcomeEntity?

    @Query("SELECT COUNT(*) FROM intelligence_items WHERE sessionId = :sessionId")
    suspend fun getIntelligenceCount(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM intelligence_items WHERE sessionId = :sessionId AND itemType = :itemType")
    suspend fun getIntelligenceCountByType(sessionId: String, itemType: String): Int
}
