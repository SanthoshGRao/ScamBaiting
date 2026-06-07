package com.scamshield.app.detection

import android.util.Log
import com.scamshield.app.data.local.dao.ScamKeywordDao
import com.scamshield.app.data.local.entity.ScamKeywordEntity
import com.scamshield.app.detection.model.MatchedRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * KeywordMatcher — Matches message text against dynamic keyword lists.
 *
 * Keywords are loaded from Room DB (pre-populated from bundled JSON,
 * synced from server every 24 hours). Includes hardcoded fallback
 * keywords that work immediately before DB is ready.
 *
 * Thread-safe: Uses mutex for keyword cache refresh.
 * Performance: <10ms per match operation (cached keywords).
 */
@Singleton
class KeywordMatcher @Inject constructor(
    private val keywordDao: ScamKeywordDao
) {
    companion object {
        private const val TAG = "KeywordMatcher"
        private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 min in-memory cache

        /**
         * Hardcoded fallback keywords — these ALWAYS work even if DB is empty.
         * Ensures detection functions on first message after install.
         */
        private val FALLBACK_KEYWORDS: List<ScamKeywordEntity> = listOf(
            // Financial fraud
            ScamKeywordEntity(keyword = "lottery", categoryId = "financial_fraud", weight = 0.7f),
            ScamKeywordEntity(keyword = "prize", categoryId = "financial_fraud", weight = 0.6f),
            ScamKeywordEntity(keyword = "winner", categoryId = "financial_fraud", weight = 0.65f),
            ScamKeywordEntity(keyword = "won", categoryId = "financial_fraud", weight = 0.20f),
            ScamKeywordEntity(keyword = "claim your", categoryId = "financial_fraud", weight = 0.7f),
            ScamKeywordEntity(keyword = "guaranteed returns", categoryId = "financial_fraud", weight = 0.8f),
            ScamKeywordEntity(keyword = "double your money", categoryId = "financial_fraud", weight = 0.85f),
            ScamKeywordEntity(keyword = "processing fee", categoryId = "financial_fraud", weight = 0.7f),
            ScamKeywordEntity(keyword = "advance fee", categoryId = "financial_fraud", weight = 0.7f),
            ScamKeywordEntity(keyword = "lucky draw", categoryId = "financial_fraud", weight = 0.7f),
            ScamKeywordEntity(keyword = "congratulations", categoryId = "financial_fraud", weight = 0.15f),
            ScamKeywordEntity(keyword = "selected", categoryId = "financial_fraud", weight = 0.10f),
            ScamKeywordEntity(keyword = "crore", categoryId = "financial_fraud", weight = 0.5f),
            ScamKeywordEntity(keyword = "lakh", categoryId = "financial_fraud", weight = 0.20f),

            // Phishing
            ScamKeywordEntity(keyword = "verify your", categoryId = "phishing", weight = 0.6f),
            ScamKeywordEntity(keyword = "click here", categoryId = "phishing", weight = 0.65f),
            ScamKeywordEntity(keyword = "update account", categoryId = "phishing", weight = 0.65f),
            ScamKeywordEntity(keyword = "suspended", categoryId = "phishing", weight = 0.6f),
            ScamKeywordEntity(keyword = "unusual activity", categoryId = "phishing", weight = 0.65f),
            ScamKeywordEntity(keyword = "security alert", categoryId = "phishing", weight = 0.55f),
            ScamKeywordEntity(keyword = "confirm your identity", categoryId = "phishing", weight = 0.7f),
            ScamKeywordEntity(keyword = "unauthorized access", categoryId = "phishing", weight = 0.7f),
            ScamKeywordEntity(keyword = "expired", categoryId = "phishing", weight = 0.4f),
            ScamKeywordEntity(keyword = "reset password", categoryId = "phishing", weight = 0.5f),
            ScamKeywordEntity(keyword = "login now", categoryId = "phishing", weight = 0.5f),

            // Impersonation
            ScamKeywordEntity(keyword = "KYC update", categoryId = "impersonation", weight = 0.7f),
            ScamKeywordEntity(keyword = "PAN card", categoryId = "impersonation", weight = 0.5f),
            ScamKeywordEntity(keyword = "Aadhaar", categoryId = "impersonation", weight = 0.4f),
            ScamKeywordEntity(keyword = "delivery failed", categoryId = "impersonation", weight = 0.6f),
            ScamKeywordEntity(keyword = "your package", categoryId = "impersonation", weight = 0.5f),
            ScamKeywordEntity(keyword = "customs duty", categoryId = "impersonation", weight = 0.6f),
            ScamKeywordEntity(keyword = "India Post", categoryId = "impersonation", weight = 0.5f),

            // Job scams
            ScamKeywordEntity(keyword = "work from home", categoryId = "job_scam", weight = 0.5f),
            ScamKeywordEntity(keyword = "easy money", categoryId = "job_scam", weight = 0.65f),
            ScamKeywordEntity(keyword = "earn daily", categoryId = "job_scam", weight = 0.6f),
            ScamKeywordEntity(keyword = "no experience needed", categoryId = "job_scam", weight = 0.55f),
            ScamKeywordEntity(keyword = "registration fee", categoryId = "job_scam", weight = 0.7f),
            ScamKeywordEntity(keyword = "part time job", categoryId = "job_scam", weight = 0.4f),

            // Tech support
            ScamKeywordEntity(keyword = "virus detected", categoryId = "tech_support", weight = 0.75f),
            ScamKeywordEntity(keyword = "has been hacked", categoryId = "tech_support", weight = 0.7f),
            ScamKeywordEntity(keyword = "remote access", categoryId = "tech_support", weight = 0.65f),
            ScamKeywordEntity(keyword = "AnyDesk", categoryId = "tech_support", weight = 0.6f),
            ScamKeywordEntity(keyword = "TeamViewer", categoryId = "tech_support", weight = 0.6f),

            // Romance
            ScamKeywordEntity(keyword = "send money", categoryId = "romance_scam", weight = 0.6f),
            ScamKeywordEntity(keyword = "stuck abroad", categoryId = "romance_scam", weight = 0.6f),
            ScamKeywordEntity(keyword = "gift cards", categoryId = "romance_scam", weight = 0.55f),
            ScamKeywordEntity(keyword = "Western Union", categoryId = "romance_scam", weight = 0.6f),

            // OTP Phishing (ALWAYS scams — no legitimate entity asks to send/share/forward OTP)
            ScamKeywordEntity(keyword = "send the otp", categoryId = "phishing", weight = 0.85f),
            ScamKeywordEntity(keyword = "send otp", categoryId = "phishing", weight = 0.85f),
            ScamKeywordEntity(keyword = "share otp", categoryId = "phishing", weight = 0.85f),
            ScamKeywordEntity(keyword = "share the otp", categoryId = "phishing", weight = 0.85f),
            ScamKeywordEntity(keyword = "forward otp", categoryId = "phishing", weight = 0.85f),
            ScamKeywordEntity(keyword = "forward the otp", categoryId = "phishing", weight = 0.85f),
            ScamKeywordEntity(keyword = "roll back", categoryId = "phishing", weight = 0.80f),
            ScamKeywordEntity(keyword = "rollback", categoryId = "phishing", weight = 0.80f),
            ScamKeywordEntity(keyword = "reverse the transaction", categoryId = "phishing", weight = 0.80f),
            ScamKeywordEntity(keyword = "reverse transaction", categoryId = "phishing", weight = 0.80f),
            ScamKeywordEntity(keyword = "not your transaction", categoryId = "phishing", weight = 0.75f),
            ScamKeywordEntity(keyword = "not youe transaction", categoryId = "phishing", weight = 0.75f),
            ScamKeywordEntity(keyword = "cancel the debit", categoryId = "phishing", weight = 0.70f),
            ScamKeywordEntity(keyword = "otp for refund", categoryId = "phishing", weight = 0.85f),
        )
    }

    // --- In-Memory Cache ---
    private var cachedKeywords: List<ScamKeywordEntity> = emptyList()
    private var cacheTimestamp: Long = 0L
    private val cacheMutex = Mutex()

    /**
     * Match text against all keywords and return matched rules with scores.
     *
     * @param text Normalized message text (lowercase).
     * @return List of matched rules with weights and category associations.
     */
    suspend fun matchKeywords(text: String): List<MatchedRule> =
        withContext(Dispatchers.Default) {
            val dbKeywords = getKeywordsFromCache()
            // Fallback keywords must always remain active for offline/demo reliability.
            val keywords = (dbKeywords + FALLBACK_KEYWORDS)
                .distinctBy { "${it.keyword.lowercase()}|${it.categoryId}|${it.isRegex}" }

            val lowerText = text.lowercase()
            val matches = mutableListOf<MatchedRule>()

            for (keyword in keywords) {
                val matched = if (keyword.isRegex) {
                    matchRegex(lowerText, keyword)
                } else {
                    matchPlainText(lowerText, keyword)
                }

                if (matched) {
                    matches.add(
                        MatchedRule(
                            rule = "keyword:${keyword.keyword}",
                            weight = keyword.weight,
                            category = keyword.categoryId
                        )
                    )
                }
            }

            if (matches.isNotEmpty()) {
                Log.d(TAG, "Matched ${matches.size} keywords from ${keywords.size} total")
            }

            matches
        }

    /**
     * Get keywords from cache, refreshing from DB if stale.
     */
    private suspend fun getKeywordsFromCache(): List<ScamKeywordEntity> {
        val now = System.currentTimeMillis()
        if (cachedKeywords.isNotEmpty() && (now - cacheTimestamp) < CACHE_TTL_MS) {
            return cachedKeywords
        }

        return cacheMutex.withLock {
            // Double-check after acquiring lock
            if (cachedKeywords.isNotEmpty() && (now - cacheTimestamp) < CACHE_TTL_MS) {
                return@withLock cachedKeywords
            }

            try {
                val keywords = keywordDao.getAllKeywords()
                cachedKeywords = keywords
                cacheTimestamp = now
                Log.i(TAG, "Keyword cache refreshed: ${keywords.size} keywords from DB")
                keywords
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load keywords from DB", e)
                cachedKeywords // Return stale cache if DB fails
            }
        }
    }

    /**
     * Match plain text keyword (case-insensitive substring match).
     */
    private fun matchPlainText(text: String, keyword: ScamKeywordEntity): Boolean {
        return text.contains(keyword.keyword.lowercase())
    }

    /**
     * Match regex pattern against text. Catches malformed regex gracefully.
     */
    private fun matchRegex(text: String, keyword: ScamKeywordEntity): Boolean {
        return try {
            val regex = Regex(keyword.keyword, RegexOption.IGNORE_CASE)
            regex.containsMatchIn(text)
        } catch (e: Exception) {
            Log.w(TAG, "Invalid regex: ${keyword.keyword}", e)
            false
        }
    }

    /**
     * Force refresh the keyword cache from Room DB.
     * Call after a sync operation completes.
     */
    suspend fun invalidateCache() {
        cacheMutex.withLock {
            cachedKeywords = emptyList()
            cacheTimestamp = 0L
        }
        Log.d(TAG, "Keyword cache invalidated")
    }

    /**
     * Get count of loaded keywords (for diagnostics).
     */
    fun getCachedKeywordCount(): Int = cachedKeywords.size
}
