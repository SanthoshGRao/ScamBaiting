package com.scamshield.app.service.providers

import android.util.Log
import com.scamshield.app.BuildConfig
import com.scamshield.app.data.local.entity.BaitingMessageEntity
import com.scamshield.app.data.local.entity.BaitingSessionEntity
import com.scamshield.app.data.local.entity.IntelligenceItemEntity
import com.scamshield.app.data.local.entity.MissionEntity
import com.scamshield.app.data.local.entity.ScammerDnaProfileEntity
import com.scamshield.app.data.remote.BaitingRequestDto
import com.scamshield.app.data.remote.ChatMessageDto
import com.scamshield.app.data.remote.DetectionApiService
import com.scamshield.app.data.remote.KnownIntelligenceDto
import com.scamshield.app.data.remote.LoginRequestDto
import com.scamshield.app.data.local.dao.BaitingDao
import com.scamshield.app.data.remote.OfflineAnalyticsSyncDto
import com.scamshield.app.data.remote.ScammerDnaDto
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class CloudReplyProvider @Inject constructor(
    private val apiService: DetectionApiService,
    private val baitingDao: BaitingDao
) : BaitingReplyProvider {

    companion object {
        private const val TAG = "CloudReplyProvider"
        private const val HEALTH_CACHE_MS = 30_000L
    }

    private var bearerToken: String? = null
    private var lastHealthCheckAt: Long = 0L
    private var lastHealthOk: Boolean = false

    suspend fun isCloudReachable(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastHealthCheckAt < HEALTH_CACHE_MS) return lastHealthOk

        val ok = withTimeoutOrNull(15_000L) {
            try {
                val response = apiService.healthCheck()
                val reachable = response.isSuccessful
                if (!reachable) {
                    Log.w(TAG, "Cloud health check failed: code=${response.code()}")
                }
                reachable
            } catch (e: Exception) {
                Log.w(TAG, "Cloud health check failed", e)
                false
            }
        } ?: false

        lastHealthCheckAt = now
        lastHealthOk = ok
        Log.i(TAG, "Cloud reachable=$ok")
        return ok
    }

    override suspend fun generateReply(
        session: BaitingSessionEntity,
        history: List<BaitingMessageEntity>,
        latestMessage: String,
        mission: MissionEntity,
        strategy: String,
        dna: ScammerDnaProfileEntity,
        knownIntelligence: List<IntelligenceItemEntity>
    ): String {
        var token = ensureToken() ?: throw IOException("Authentication failed")
        Log.i(TAG, "Authenticated cloud reply request for session=${session.senderId}, history=${history.size}")

        val offlineAnalytics = baitingDao.getUnsyncedAnalytics()
        val syncDtos = offlineAnalytics.map {
            OfflineAnalyticsSyncDto(
                detected_intent = it.detectedIntent,
                selected_persona = it.selectedPersona,
                selected_state = it.selectedState,
                timestamp = it.timestamp
            )
        }

        val request = BaitingRequestDto(
            sender_id = session.senderId,
            session_id = session.senderId,
            persona = session.persona,
            current_strategy = strategy,
            scam_category = dna.scamCategory,
            goal = mission.missionType,
            mission = mission.missionType,
            scammer_dna = ScammerDnaDto(
                urgency = dna.urgencyScore,
                aggression = dna.aggressionScore,
                persistence = dna.persistenceScore,
                technicalLevel = dna.technicalLevel,
                trustBuilding = dna.trustBuildingScore,
                repetition = dna.repetitionScore,
                avgMessageLength = dna.avgMessageLength,
                avgResponseLatencyMs = dna.avgResponseLatencyMs,
                scamCategory = dna.scamCategory
            ),
            known_intelligence = knownIntelligence.map {
                KnownIntelligenceDto(it.itemType, it.value, it.confidence)
            },
            history = history.map { ChatMessageDto(role = it.role, content = it.content) },
            offline_analytics = syncDtos
        )

        var response = apiService.generateBaitingReply("Bearer $token", request)
        if (response.code() == 401) {
            Log.w(TAG, "Bait API 401 — refreshing token and retrying once")
            bearerToken = null
            token = ensureToken() ?: throw IOException("Authentication failed after refresh")
            response = apiService.generateBaitingReply("Bearer $token", request)
        }

        if (response.isSuccessful && response.body() != null) {
            if (offlineAnalytics.isNotEmpty()) {
                baitingDao.markAnalyticsSynced(offlineAnalytics.map { it.id })
                Log.i(TAG, "Successfully synced ${offlineAnalytics.size} offline analytic events.")
            }
            val body = response.body()!!
            // We just return the unified text, BaitingManager will split it.
            val reply = body.reply_text.ifBlank { body.reply_parts.joinToString(" ") }
            Log.i(
                TAG,
                "Cloud reply success: strategy=${body.strategy_used}, persona=${body.persona_used}, chars=${reply.length}, parts=${body.reply_parts.size}"
            )
            return reply
        } else {
            val err = runCatching { response.errorBody()?.string() }.getOrNull()
            Log.e(TAG, "Bait API failed: code=${response.code()}, body=$err")
            throw IOException("Backend error: ${response.code()}")
        }
    }

    private suspend fun ensureToken(): String? {
        bearerToken?.let { return it }
        return try {
            Log.i(TAG, "Logging in for cloud bait replies")
            val response = apiService.login(
                LoginRequestDto(
                    username = BuildConfig.AUTH_USERNAME,
                    password = BuildConfig.AUTH_PASSWORD
                )
            )
            if (response.isSuccessful) {
                val token = response.body()?.access_token
                bearerToken = token
                Log.i(TAG, "Cloud login succeeded")
                token
            } else {
                val err = runCatching { response.errorBody()?.string() }.getOrNull()
                Log.e(TAG, "Cloud login failed: code=${response.code()}, body=$err")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login call failed", e)
            null
        }
    }
}
