package com.scamshield.app.intelligence

import com.scamshield.app.data.local.dao.IntelligenceDao
import com.scamshield.app.data.local.entity.EngagementEffectivenessEntity
import com.scamshield.app.data.local.entity.MissionEntity
import com.scamshield.app.data.local.entity.StrategyOutcomeEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StrategyOutcomeTracker @Inject constructor(
    private val intelligenceDao: IntelligenceDao
) {
    suspend fun recordOutcome(
        mission: MissionEntity,
        strategy: String,
        persona: String,
        effectiveness: EngagementEffectivenessEntity
    ): StrategyOutcomeEntity {
        val existing = intelligenceDao.getStrategyOutcome(mission.missionType, strategy, persona)
        val attempts = (existing?.attempts ?: 0) + 1
        val success = effectiveness.missionCompleted || effectiveness.effectivenessScore >= 60
        val successes = (existing?.successes ?: 0) + if (success) 1 else 0
        val avg = (((existing?.avgEffectivenessScore ?: 0f) * (attempts - 1)) + effectiveness.effectivenessScore) / attempts
        val outcome = StrategyOutcomeEntity(
            missionType = mission.missionType,
            strategy = strategy,
            persona = persona,
            attempts = attempts,
            successes = successes,
            successRate = successes.toFloat() / attempts,
            avgEffectivenessScore = avg,
            updatedAt = System.currentTimeMillis()
        )
        intelligenceDao.upsertStrategyOutcome(outcome)
        return outcome
    }
}
