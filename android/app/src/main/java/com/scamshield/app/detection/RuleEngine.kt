package com.scamshield.app.detection

import android.util.Log
import com.scamshield.app.data.local.dao.ScamCategoryDao
import com.scamshield.app.data.local.dao.SenderHistoryDao
import com.scamshield.app.data.local.entity.SenderHistoryEntity
import com.scamshield.app.detection.model.MatchedRule
import com.scamshield.app.detection.model.RuleVerdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RuleEngine — On-device scam detection orchestrator.
 *
 * Coordinates KeywordMatcher + PatternDetector + SenderAnalyzer
 * to produce a fast, offline-capable RuleVerdict.
 *
 * Performance target: <50ms per message.
 * Threading: Runs on Dispatchers.Default (coroutine).
 * No network calls — fully offline.
 */
@Singleton
class RuleEngine @Inject constructor(
    private val keywordMatcher: KeywordMatcher,
    private val patternDetector: PatternDetector,
    private val categoryDao: ScamCategoryDao,
    private val senderHistoryDao: SenderHistoryDao
) {
    companion object {
        private const val TAG = "RuleEngine"
        private const val MAX_CONFIDENCE = 1.0f
        private const val SENDER_BOOST = 0.15f // Boost for known scam senders
    }

    /**
     * Analyze a message and produce a RuleVerdict.
     *
     * Runs keyword matching and pattern detection in parallel,
     * then scores and merges results.
     *
     * @param text Message text to analyze.
     * @param sender Optional sender identifier.
     * @return RuleVerdict with confidence, matched rules, and category hints.
     */
    suspend fun analyze(text: String, sender: String? = null): RuleVerdict =
        withContext(Dispatchers.Default) {
            val startTime = System.nanoTime()

            // Run keyword + pattern detection in parallel
            val allMatches = coroutineScope {
                val keywordJob = async { keywordMatcher.matchKeywords(text) }
                val patternJob = async { patternDetector.detectPatterns(text) }

                val keywordMatches = keywordJob.await()
                val patternMatches = patternJob.await()
                keywordMatches + patternMatches
            }

            // Calculate confidence score
            var confidence = calculateConfidence(allMatches)

            // Apply sender history boost
            if (sender != null) {
                val senderBoost = getSenderBoost(sender)
                confidence = (confidence + senderBoost).coerceAtMost(MAX_CONFIDENCE)
            }

            // Determine category hints (top categories by match count)
            val categoryHints = extractCategoryHints(allMatches)

            // Determine if suspicious
            val isSuspicious = confidence > 0.25f || allMatches.size >= 2

            val elapsed = (System.nanoTime() - startTime) / 1_000_000.0
            Log.i(
                TAG,
                "Analysis: ${allMatches.size} matches, conf=%.2f, " +
                    "suspicious=$isSuspicious, ${categoryHints.size} categories, %.1fms"
                        .format(confidence, elapsed)
            )

            // Update sender history
            if (sender != null) {
                updateSenderHistory(sender, isSuspicious)
            }

            RuleVerdict(
                isSuspicious = isSuspicious,
                confidence = confidence,
                matchedRules = allMatches,
                categoryHints = categoryHints
            )
        }

    /**
     * Analyze a message WITH conversation context from the same sender.
     *
     * Concatenates recent messages and runs analysis on the combined text
     * to catch multi-message scam patterns (e.g., bank alert + OTP phishing).
     * Returns the max confidence between individual and concatenated analysis.
     *
     * @param text Current message text.
     * @param previousMessages Recent messages from the same sender.
     * @param sender Optional sender identifier.
     * @return RuleVerdict with confidence from whichever analysis scored higher.
     */
    suspend fun analyzeWithContext(
        text: String,
        previousMessages: List<String>,
        sender: String? = null
    ): RuleVerdict = withContext(Dispatchers.Default) {
        // Always run individual analysis first
        val individualVerdict = analyze(text, sender)

        if (previousMessages.isEmpty()) {
            return@withContext individualVerdict
        }

        // Concatenate previous messages + current for context analysis
        val combinedText = (previousMessages + text).joinToString(" ")
        val contextVerdict = analyze(combinedText, sender)

        Log.i(
            TAG,
            "Context analysis: individual_conf=%.2f, context_conf=%.2f, prevMsgs=${previousMessages.size}"
                .format(individualVerdict.confidence, contextVerdict.confidence)
        )

        // Return whichever scored higher
        if (contextVerdict.confidence > individualVerdict.confidence) {
            contextVerdict
        } else {
            individualVerdict
        }
    }

    /**
     * Calculate overall confidence from matched rules.
     *
     * Formula: weighted sum with diminishing returns.
     * First matches contribute more, later ones less (prevents stacking).
     */
    private suspend fun calculateConfidence(matches: List<MatchedRule>): Float {
        if (matches.isEmpty()) return 0f

        // Sort by weight descending — strongest signals first
        val sorted = matches.sortedByDescending { it.weight }

        var score = 0f
        var remaining = 1f // Remaining "confidence space"

        for ((index, match) in sorted.withIndex()) {
            // Apply category boost from DB
            val categoryBoost = match.category?.let {
                getCategoryBoost(it)
            } ?: 1f

            // Diminishing returns: each match fills less of remaining space
            val contribution = match.weight * categoryBoost * remaining * 0.5f
            score += contribution
            remaining -= contribution

            if (remaining < 0.05f) break // Diminishing returns cutoff
        }

        return score.coerceIn(0f, MAX_CONFIDENCE)
    }

    /**
     * Get category weight boost from Room DB.
     */
    private suspend fun getCategoryBoost(categoryId: String): Float {
        return try {
            categoryDao.getWeightBoost(categoryId) ?: 1f
        } catch (e: Exception) {
            1f // Default boost on DB error
        }
    }

    /**
     * Get sender reputation boost (higher = more suspicious).
     */
    private suspend fun getSenderBoost(sender: String): Float {
        return try {
            val score = senderHistoryDao.getSenderScore(sender) ?: 0.5f
            // Only boost if sender has high scam score
            if (score > 0.7f) SENDER_BOOST else 0f
        } catch (e: Exception) {
            0f
        }
    }

    /**
     * Update sender's reputation after analysis.
     */
    private suspend fun updateSenderHistory(sender: String, wasFlagged: Boolean) {
        try {
            val existing = senderHistoryDao.getSender(sender)
            if (existing != null) {
                senderHistoryDao.updateSenderStats(sender, wasFlagged)
            } else {
                senderHistoryDao.upsert(
                    SenderHistoryEntity(
                        senderId = sender,
                        scamScore = if (wasFlagged) 0.6f else 0.4f,
                        totalMessages = 1,
                        flaggedMessages = if (wasFlagged) 1 else 0
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update sender history", e)
        }
    }

    /**
     * Extract top category hints from matched rules.
     * Returns categories ordered by frequency of matches.
     */
    private fun extractCategoryHints(matches: List<MatchedRule>): List<String> {
        return matches
            .mapNotNull { it.category }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(3)
    }
}
