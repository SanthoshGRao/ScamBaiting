package com.scamshield.app.intelligence

import com.scamshield.app.data.local.dao.IntelligenceDao
import com.scamshield.app.data.local.entity.MissionEntity
import com.scamshield.app.data.local.entity.ScammerDnaProfileEntity
import com.scamshield.app.data.local.entity.StrategyTurnEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StrategyPlanner @Inject constructor(
    private val intelligenceDao: IntelligenceDao,
    private val strategyLearningEngine: StrategyLearningEngine
) {
    suspend fun chooseStrategy(sessionId: String, persona: String, dna: ScammerDnaProfileEntity, mission: MissionEntity): String {
        val recent = intelligenceDao.getRecentStrategyTurns(sessionId, 4).map { it.strategy }
        val learned = strategyLearningEngine.recommendStrategy(mission.missionType, persona)
        val preferred = dnaGuardrailStrategy(dna) ?: learned ?: heuristicStrategy(dna, mission)
        if (recent.count { it == preferred } >= 2) {
            return when (preferred) {
                "QUESTION" -> "DELAY"
                "DELAY" -> "CONFUSE"
                "AGREE" -> "QUESTION"
                "PANIC" -> "DELAY"
                else -> "QUESTION"
            }
        }
        return preferred
    }

    private fun dnaGuardrailStrategy(dna: ScammerDnaProfileEntity): String? = when {
        dna.aggressionScore >= 75 -> "PANIC"
        dna.urgencyScore >= 80 -> "DELAY"
        else -> null
    }

    private fun heuristicStrategy(dna: ScammerDnaProfileEntity, mission: MissionEntity): String = when {
        dna.aggressionScore >= 65 -> "PANIC"
        dna.urgencyScore >= 70 -> "DELAY"
        mission.missionType.startsWith("EXTRACT") && dna.trustBuildingScore >= 35 -> "AGREE"
        mission.missionType.startsWith("EXTRACT") -> "QUESTION"
        dna.persistenceScore >= 70 -> "CONFUSE"
        else -> "SOCIALIZE"
    }

    suspend fun recordTurn(
        sessionId: String,
        senderId: String,
        mission: MissionEntity,
        strategy: String,
        persona: String,
        scammerMessage: String,
        assistantReply: String
    ) {
        intelligenceDao.insertStrategyTurn(
            StrategyTurnEntity(
                sessionId = sessionId,
                senderId = senderId,
                missionType = mission.missionType,
                strategy = strategy,
                persona = persona,
                scamCategory = mission.scamCategory,
                scammerMessage = scammerMessage,
                assistantReply = assistantReply
            )
        )
    }
}
