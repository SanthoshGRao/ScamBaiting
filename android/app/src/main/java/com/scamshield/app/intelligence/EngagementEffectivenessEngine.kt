package com.scamshield.app.intelligence

import com.scamshield.app.data.local.dao.BaitingDao
import com.scamshield.app.data.local.dao.IntelligenceDao
import com.scamshield.app.data.local.entity.EngagementEffectivenessEntity
import com.scamshield.app.data.local.entity.MissionEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EngagementEffectivenessEngine @Inject constructor(
    private val baitingDao: BaitingDao,
    private val intelligenceDao: IntelligenceDao
) {
    suspend fun updateEffectiveness(sessionId: String, mission: MissionEntity): EngagementEffectivenessEntity {
        val session = baitingDao.getSession(sessionId)
        val messages = baitingDao.getMessagesForSender(sessionId)
        val scammerMessages = messages.count { it.role == "user" }
        val assistantReplies = messages.count { it.role == "assistant" }
        val now = System.currentTimeMillis()
        val firstTs = session?.startTime ?: messages.minOfOrNull { it.timestamp } ?: now
        val lastTs = messages.maxOfOrNull { it.timestamp } ?: now
        val timeWastedMs = (lastTs - firstTs).coerceAtLeast(0L)
        val intelligenceCount = intelligenceDao.getIntelligenceCount(sessionId)
        val wallets = intelligenceDao.getIntelligenceCountByType(sessionId, "WALLET")
        val upis = intelligenceDao.getIntelligenceCountByType(sessionId, "UPI")
        val urls = intelligenceDao.getIntelligenceCountByType(sessionId, "URL")
        val domains = intelligenceDao.getIntelligenceCountByType(sessionId, "DOMAIN")
        val responseRate = if (scammerMessages == 0) 0f else (assistantReplies.toFloat() / scammerMessages).coerceIn(0f, 1.5f)
        val missionCompleted = mission.status == "COMPLETED" || isMissionSatisfied(mission.missionType, wallets, upis, urls, domains, intelligenceCount)
        val sessionAbandoned = scammerMessages >= 2 && assistantReplies > 0 && now - lastTs > 30 * 60 * 1000L
        val score = calculateScore(
            missionCompleted = missionCompleted,
            wallets = wallets,
            upis = upis,
            urls = urls,
            domains = domains,
            timeWastedMs = timeWastedMs,
            sessionAbandoned = sessionAbandoned,
            intelligenceCount = intelligenceCount,
            responseRate = responseRate
        )

        val result = EngagementEffectivenessEntity(
            sessionId = sessionId,
            timeWastedMs = timeWastedMs,
            messagesExchanged = messages.size,
            scammerMessages = scammerMessages,
            assistantReplies = assistantReplies,
            intelligenceExtractedCount = intelligenceCount,
            walletsCollected = wallets,
            urlsCollected = urls,
            upiIdsCollected = upis,
            domainsCollected = domains,
            responseRate = responseRate,
            missionCompleted = missionCompleted,
            sessionAbandoned = sessionAbandoned,
            effectivenessScore = score,
            updatedAt = now
        )
        intelligenceDao.upsertEngagementEffectiveness(result)
        return result
    }

    private fun isMissionSatisfied(mission: String, wallets: Int, upis: Int, urls: Int, domains: Int, intel: Int): Boolean = when (mission) {
        "EXTRACT_PAYMENT_INSTRUCTIONS" -> wallets + upis > 0
        "EXTRACT_SCAM_WEBSITE" -> urls + domains > 0
        "EXTRACT_TOOLING_AND_CONTACT", "IDENTIFY_PROCESS_AND_CONTACT", "GATHER_CONTACT_INFORMATION" -> intel > 0
        else -> false
    }

    private fun calculateScore(
        missionCompleted: Boolean,
        wallets: Int,
        upis: Int,
        urls: Int,
        domains: Int,
        timeWastedMs: Long,
        sessionAbandoned: Boolean,
        intelligenceCount: Int,
        responseRate: Float
    ): Int {
        var score = 0
        if (missionCompleted) score += 40
        if (wallets > 0) score += 20
        if (upis > 0) score += 18
        if (urls > 0) score += 15
        if (domains > 0) score += 8
        if (timeWastedMs >= 10 * 60 * 1000L) score += 15 else score += (timeWastedMs / 40_000L).toInt().coerceIn(0, 15)
        if (sessionAbandoned) score += 10
        score += (intelligenceCount * 3).coerceAtMost(12)
        if (responseRate >= 0.8f) score += 5
        return score.coerceIn(0, 100)
    }
}
