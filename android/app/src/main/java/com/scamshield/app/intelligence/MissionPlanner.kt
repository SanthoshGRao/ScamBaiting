package com.scamshield.app.intelligence

import com.scamshield.app.data.local.dao.IntelligenceDao
import com.scamshield.app.data.local.entity.MissionEntity
import com.scamshield.app.data.local.entity.ScammerDnaProfileEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MissionPlanner @Inject constructor(
    private val intelligenceDao: IntelligenceDao
) {
    suspend fun assignMission(sessionId: String, senderId: String, dna: ScammerDnaProfileEntity): MissionEntity {
        intelligenceDao.getActiveMission(sessionId)?.let { active ->
            if (!missionAlreadySatisfied(sessionId, active.missionType)) return active
            intelligenceDao.updateMissionStatus(active.id, "COMPLETED")
        }
        val missionType = when (dna.scamCategory) {
            "financial_fraud" -> chooseFinancialMission(sessionId, dna)
            "phishing" -> if (hasWebsite(sessionId)) "WASTE_MAXIMUM_TIME" else "EXTRACT_SCAM_WEBSITE"
            "impersonation" -> "IDENTIFY_PROCESS_AND_CONTACT"
            "tech_support" -> "EXTRACT_TOOLING_AND_CONTACT"
            "job_scam" -> "EXTRACT_PAYMENT_INSTRUCTIONS"
            else -> if (dna.persistenceScore >= 50) "GATHER_CONTACT_INFORMATION" else "WASTE_MAXIMUM_TIME"
        }
        val mission = MissionEntity(
            sessionId = sessionId,
            senderId = senderId,
            missionType = missionType,
            priority = priorityFor(dna),
            scamCategory = dna.scamCategory,
            successCriteria = successCriteria(missionType)
        )
        intelligenceDao.upsertMission(mission)
        return intelligenceDao.getActiveMission(sessionId) ?: mission
    }

    private suspend fun chooseFinancialMission(sessionId: String, dna: ScammerDnaProfileEntity): String = when {
        intelligenceDao.hasIntelligenceOfTypes(sessionId, listOf("WALLET", "UPI")) -> "WASTE_MAXIMUM_TIME"
        dna.technicalLevel >= 45 -> "EXTRACT_PAYMENT_INSTRUCTIONS"
        else -> "WASTE_MAXIMUM_TIME"
    }

    private suspend fun hasWebsite(sessionId: String): Boolean =
        intelligenceDao.hasIntelligenceOfTypes(sessionId, listOf("URL", "DOMAIN"))

    private suspend fun missionAlreadySatisfied(sessionId: String, missionType: String): Boolean = when (missionType) {
        "EXTRACT_PAYMENT_INSTRUCTIONS" -> intelligenceDao.hasIntelligenceOfTypes(sessionId, listOf("WALLET", "UPI"))
        "EXTRACT_SCAM_WEBSITE" -> hasWebsite(sessionId)
        "EXTRACT_TOOLING_AND_CONTACT" -> intelligenceDao.hasIntelligenceOfTypes(sessionId, listOf("PHONE", "URL", "DOMAIN"))
        "GATHER_CONTACT_INFORMATION", "IDENTIFY_PROCESS_AND_CONTACT" -> intelligenceDao.hasIntelligenceOfTypes(sessionId, listOf("PHONE"))
        else -> false
    }

    private fun priorityFor(dna: ScammerDnaProfileEntity): Int =
        (40 + dna.persistenceScore / 3 + dna.technicalLevel / 4 + dna.urgencyScore / 5).coerceIn(1, 100)

    private fun successCriteria(mission: String): String = when (mission) {
        "EXTRACT_PAYMENT_INSTRUCTIONS" -> "Collect UPI, wallet, bank, or payment recipient details"
        "EXTRACT_SCAM_WEBSITE" -> "Collect scam URL, domain, or login page"
        "IDENTIFY_PROCESS_AND_CONTACT" -> "Collect department claim, process steps, and callback/contact data"
        "EXTRACT_TOOLING_AND_CONTACT" -> "Collect remote tool name, access instructions, and support contact"
        "GATHER_CONTACT_INFORMATION" -> "Collect alternate phone, handle, or operator identity"
        else -> "Maximize scammer time spent while preserving engagement"
    }
}
