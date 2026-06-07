package com.scamshield.app.intelligence

import com.scamshield.app.data.local.dao.IntelligenceDao
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StrategyLearningEngine @Inject constructor(
    private val intelligenceDao: IntelligenceDao
) {
    suspend fun recommendStrategy(missionType: String, persona: String): String? {
        val best = intelligenceDao.getBestStrategyOutcome(missionType, persona, minAttempts = 2) ?: return null
        if (best.successRate < 0.45f && best.avgEffectivenessScore < 50f) return null
        return best.strategy
    }
}
