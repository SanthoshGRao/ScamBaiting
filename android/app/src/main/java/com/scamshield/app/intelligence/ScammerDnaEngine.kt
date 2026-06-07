package com.scamshield.app.intelligence

import com.scamshield.app.data.local.dao.IntelligenceDao
import com.scamshield.app.data.local.entity.IntelligenceItemEntity
import com.scamshield.app.data.local.entity.ProcessedMessageFingerprintEntity
import com.scamshield.app.data.local.entity.ScammerDnaProfileEntity
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScammerDnaEngine @Inject constructor(
    private val intelligenceDao: IntelligenceDao,
    private val intelligenceCollector: IntelligenceCollector
) {
    suspend fun updateProfile(
        senderId: String,
        sessionId: String,
        message: String,
        fallbackCategory: String = "unknown",
        timestamp: Long = System.currentTimeMillis()
    ): ScammerDnaProfileEntity {
        val fingerprint = fingerprint(senderId, message)
        if (intelligenceDao.hasProcessedFingerprint(fingerprint)) {
            return intelligenceDao.getDnaProfile(senderId) ?: ScammerDnaProfileEntity(senderId = senderId)
        }
        intelligenceDao.insertProcessedFingerprint(ProcessedMessageFingerprintEntity(fingerprint, senderId, timestamp))

        val extracted = intelligenceCollector.extractItems(message).map {
            IntelligenceItemEntity(
                sessionId = sessionId,
                senderId = senderId,
                itemType = it.itemType,
                value = it.value,
                normalizedValue = it.normalizedValue,
                sourceFingerprint = fingerprint,
                confidence = it.confidence,
                createdAt = timestamp
            )
        }
        if (extracted.isNotEmpty()) intelligenceDao.insertIntelligenceItems(extracted)

        val snapshot = intelligenceCollector.collect(message, fallbackCategory)
        val existing = intelligenceDao.getDnaProfile(senderId)
        val prevCount = existing?.messageCount ?: 0
        val newCount = prevCount + 1
        val latency = existing?.lastMessageAt?.takeIf { it > 0L }?.let { timestamp - it }?.coerceAtLeast(0L) ?: 0L

        fun blend(old: Int, signal: Int, scale: Int = 22): Int =
            (((old * prevCount) + (signal * scale).coerceIn(0, 100)) / newCount.coerceAtLeast(1)).coerceIn(0, 100)

        val profile = ScammerDnaProfileEntity(
            senderId = senderId,
            urgencyScore = blend(existing?.urgencyScore ?: 0, snapshot.urgencySignals),
            aggressionScore = blend(existing?.aggressionScore ?: 0, snapshot.aggressionSignals),
            persistenceScore = ((existing?.persistenceScore ?: 0) + 8).coerceAtMost(100),
            technicalLevel = blend(existing?.technicalLevel ?: 0, snapshot.technicalSignals, 18),
            trustBuildingScore = blend(existing?.trustBuildingScore ?: 0, snapshot.trustSignals, 18),
            repetitionScore = (((existing?.repetitionScore ?: 0) * prevCount) + (snapshot.repeatedTokenRatio * 100).toInt()) / newCount,
            avgMessageLength = (((existing?.avgMessageLength ?: 0f) * prevCount) + message.length) / newCount,
            avgResponseLatencyMs = if (latency > 0L) (((existing?.avgResponseLatencyMs ?: 0f) * prevCount) + latency) / newCount else existing?.avgResponseLatencyMs ?: 0f,
            messageCount = newCount,
            scamCategory = snapshot.scamCategory,
            lastMessageAt = timestamp,
            updatedAt = timestamp
        )
        intelligenceDao.upsertDnaProfile(profile)
        return profile
    }

    private fun fingerprint(senderId: String, message: String): String {
        val normalized = "$senderId|${message.trim().lowercase()}"
        val bytes = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(24)
    }
}
