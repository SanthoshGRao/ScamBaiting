package com.scamshield.app.detection

import android.util.Log
import com.scamshield.app.BuildConfig
import com.scamshield.app.data.local.dao.DetectionCacheDao
import com.scamshield.app.data.local.entity.DetectionCacheEntity
import com.scamshield.app.data.remote.DetectionApiService
import com.scamshield.app.data.remote.LoginRequestDto
import com.scamshield.app.data.remote.AnalyticsSummaryDto
import com.scamshield.app.detection.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * DetectionRepository — Coordinates local rule engine + remote API.
 *
 * Hybrid detection flow:
 * 1. Check cache for recent result (by message hash)
 * 2. Run RuleEngine locally (instant, offline)
 * 3. If online + not privacy mode → send to backend with rule verdict
 * 4. Return best available result (rule-only or hybrid)
 *
 * Handles: offline fallback, result caching, error recovery.
 * Architecture: Repository pattern (Clean Architecture data layer).
 */
@Singleton
class DetectionRepository @Inject constructor(
    private val ruleEngine: RuleEngine,
    private val apiService: DetectionApiService,
    private val cacheDao: DetectionCacheDao
) {
    companion object {
        private const val TAG = "DetectionRepository"
        private const val CACHE_EXPIRY_MS = 30 * 60 * 1000L // 30 min cache
        private const val MAX_RETRIES = 3
    }
    private var bearerToken: String? = null
    private var refreshToken: String? = null
    private val inFlightRequests = ConcurrentHashMap<String, Boolean>()

    /**
     * Analyze a message through the full detection pipeline.
     *
     * Returns immediately with rule-based verdict. If server is
     * reachable, also returns enhanced LLM verdict.
     *
     * @param text Message text to analyze.
     * @param source Source of the message (sms, notification, etc.).
     * @param sender Optional sender identifier.
     * @param privacyMode If true, skip all cloud API calls.
     * @return DetectionResult with verdict, source flag, and timing.
     */
    suspend fun analyzeMessage(
        text: String,
        source: InputSource = InputSource.NOTIFICATION,
        sender: String? = null,
        privacyMode: Boolean = false
    ): DetectionResult = withContext(Dispatchers.Default) {
        val startTime = System.nanoTime()
        val messageHash = hashMessage(text)

        // Step 1: Check cache (keyed by message body only — same sender, new text must re-analyze)
        val cached = getCachedResult(messageHash)
        if (cached != null) {
            val elapsed = elapsedMs(startTime)
            Log.d(TAG, "Cache hit for $messageHash, %.1fms".format(elapsed))
            return@withContext DetectionResult(
                ruleVerdict = cached,
                serverVerdict = null,
                fromCache = true,
                isOffline = false,
                processingTimeMs = elapsed
            )
        }

        // Step 2: Run local rule engine
        val ruleVerdict = ruleEngine.analyze(text, sender)

        // Step 3: Send to backend (if online + not privacy mode)
        var serverVerdict: DetectionVerdictDto? = null
        var isOffline = false

        if (!privacyMode && inFlightRequests.putIfAbsent(messageHash, true) == null) {
            try {
                serverVerdict = sendToBackend(text, source, sender, ruleVerdict)
            } catch (e: Exception) {
                Log.w(TAG, "Backend unavailable, using rule-only: ${e.message}")
                isOffline = true
            } finally {
                inFlightRequests.remove(messageHash)
            }
        }

        // Step 4: Cache the result
        cacheResult(messageHash, ruleVerdict)

        val elapsed = elapsedMs(startTime)
        Log.i(
            TAG,
            "Detection complete: rule_conf=%.2f, server=%s, offline=%s, %.1fms"
                .format(ruleVerdict.confidence, serverVerdict != null, isOffline, elapsed)
        )

        DetectionResult(
            ruleVerdict = ruleVerdict,
            serverVerdict = serverVerdict,
            fromCache = false,
            isOffline = isOffline,
            processingTimeMs = elapsed
        )
    }

    /**
     * Quick analysis — rule engine only, no network.
     * Use for real-time notification processing where speed is critical.
     */
    suspend fun analyzeQuick(text: String, sender: String? = null): RuleVerdict {
        return ruleEngine.analyze(text, sender)
    }

    /**
     * Quick analysis WITH conversation context from the same sender.
     * Catches multi-message scam patterns (e.g., bank alert + OTP phishing).
     */
    suspend fun analyzeQuickWithContext(
        text: String,
        previousMessages: List<String>,
        sender: String? = null
    ): RuleVerdict {
        return ruleEngine.analyzeWithContext(text, previousMessages, sender)
    }

    /**
     * Send detection request to the backend API.
     */
    private suspend fun sendToBackend(
        text: String,
        source: InputSource,
        sender: String?,
        ruleVerdict: RuleVerdict
    ): DetectionVerdictDto? = withContext(Dispatchers.IO) {
        val request = DetectionRequest(
            text = text,
            source = source.value,
            sender = sender,
            ruleVerdict = ruleVerdict.toDto()
        )

        try {
            var token = ensureToken() ?: return@withContext null
            var attempt = 0
            while (attempt < MAX_RETRIES) {
                val response = apiService.detectScamFull("Bearer $token", request)
                if (response.isSuccessful) {
                    return@withContext response.body()?.verdict
                }
                if (response.code() == 401) {
                    Log.w(TAG, "Detection API 401, refreshing token and retrying")
                    bearerToken = null
                    refreshToken = null
                    token = ensureToken() ?: return@withContext null
                    attempt++
                    continue
                }
                attempt++
                delay((attempt * 300).toLong())
            }
            Log.w(TAG, "Backend detection failed after retries")
            null
        } catch (e: Exception) {
            Log.e(TAG, "API call failed", e)
            throw e // Propagate for offline detection
        }
    }

    private suspend fun ensureToken(): String? {
        bearerToken?.let { return it }
        return try {
            val login = apiService.login(
                LoginRequestDto(
                    username = BuildConfig.AUTH_USERNAME,
                    password = BuildConfig.AUTH_PASSWORD
                )
            )
            if (login.isSuccessful) {
                val token = login.body()?.access_token
                refreshToken = login.body()?.refresh_token
                bearerToken = token
                token
            } else {
                Log.e(TAG, "Login failed ${login.code()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login call failed", e)
            null
        }
    }

    suspend fun submitFeedback(messageId: String, label: String, notes: String = ""): Boolean {
        return withContext(Dispatchers.IO) {
            val token = ensureToken() ?: return@withContext false
            val response = apiService.submitFeedback(
                "Bearer $token",
                FeedbackRequest(message_id = messageId, label = label, notes = notes)
            )
            response.isSuccessful
        }
    }

    suspend fun fetchAnalyticsSummary(): AnalyticsSummaryDto? = withContext(Dispatchers.IO) {
        val token = ensureToken() ?: return@withContext null
        return@withContext try {
            val response = apiService.getAnalyticsSummary("Bearer $token")
            if (response.isSuccessful) response.body() else null
        } catch (e: Exception) {
            Log.w(TAG, "Analytics fetch failed", e)
            null
        }
    }

    // --- Cache Operations ---

    private suspend fun getCachedResult(hash: String): RuleVerdict? {
        return try {
            // Clean expired entries
            val expiry = System.currentTimeMillis() - CACHE_EXPIRY_MS
            cacheDao.clearExpired(expiry)

            val cached = cacheDao.getCachedResult(hash)
            if (cached != null) {
                RuleVerdict(
                    isSuspicious = cached.isSuspicious,
                    confidence = cached.confidence,
                    categoryHints = if (cached.category != "unknown") listOf(cached.category) else emptyList()
                )
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Cache read failed", e)
            null
        }
    }

    private suspend fun cacheResult(hash: String, verdict: RuleVerdict) {
        try {
            val existing = cacheDao.getCachedResult(hash)
            cacheDao.insert(
                DetectionCacheEntity(
                    messageHash = hash,
                    isSuspicious = verdict.isSuspicious,
                    confidence = verdict.confidence,
                    category = verdict.categoryHints.firstOrNull() ?: "unknown",
                    matchedRules = verdict.matchedRules.joinToString(",") { it.rule },
                    // Preserve first time we cached this exact message body.
                    timestamp = existing?.timestamp ?: System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Cache write failed", e)
        }
    }

    // --- Utility ---

    private fun hashMessage(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun elapsedMs(startNano: Long): Float {
        return (System.nanoTime() - startNano) / 1_000_000f
    }

    /**
     * Check if backend is reachable.
     */
    suspend fun isBackendAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = apiService.healthCheck()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Clear all cached detection results.
     */
    suspend fun clearCache() {
        try {
            cacheDao.clearAll()
            Log.d(TAG, "Detection cache cleared")
        } catch (e: Exception) {
            Log.w(TAG, "Cache clear failed", e)
        }
    }
}

/**
 * Result of the detection pipeline.
 * Contains both local and server results for the UI layer.
 */
data class DetectionResult(
    val ruleVerdict: RuleVerdict,
    val serverVerdict: DetectionVerdictDto? = null,
    val fromCache: Boolean = false,
    val isOffline: Boolean = false,
    val processingTimeMs: Float = 0f
) {
    /** Best available confidence (server > rule) */
    val bestConfidence: Float
        get() = serverVerdict?.confidence ?: ruleVerdict.confidence

    /** Best available category */
    val bestCategory: String
        get() = serverVerdict?.category
            ?: ruleVerdict.categoryHints.firstOrNull()
            ?: "unknown"

    /** Whether this result should trigger a user alert */
    val shouldAlert: Boolean
        get() = bestConfidence >= 0.30f

    /** Whether enhanced (LLM) detection was used */
    val isEnhanced: Boolean
        get() = serverVerdict != null

    /** User-facing status message */
    val statusMessage: String
        get() = when {
            fromCache -> "Cached result"
            isOffline -> "Enhanced detection unavailable (offline)"
            isEnhanced -> "Full analysis complete"
            else -> "Rule-based analysis"
        }
}
