package com.scamshield.app.detection

import android.util.Log
import com.scamshield.app.detection.model.MatchedRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PatternDetector — Detects structural scam patterns in message text.
 *
 * Unlike KeywordMatcher (database-driven), PatternDetector uses
 * hardcoded structural patterns that detect suspicious message
 * characteristics: URLs, UPI IDs, phone numbers, urgency cues, etc.
 *
 * Performance: <5ms per analysis (regex-based, no I/O).
 */
@Singleton
class PatternDetector @Inject constructor() {

    companion object {
        private const val TAG = "PatternDetector"

        // --- URL Patterns ---
        private val SHORTENED_URL = Regex(
            "https?://(?:bit\\.ly|tinyurl\\.com|t\\.co|goo\\.gl|is\\.gd|buff\\.ly)/\\S+",
            RegexOption.IGNORE_CASE
        )
        private val SUSPICIOUS_TLD = Regex(
            "https?://[a-z0-9.-]*\\.(?:tk|ml|ga|cf|gq|xyz|top|buzz|click|link)/\\S*",
            RegexOption.IGNORE_CASE
        )
        private val ANY_URL = Regex(
            "https?://[^\\s<>\"'\\])+]+",
            RegexOption.IGNORE_CASE
        )

        // --- Financial Patterns ---
        private val UPI_ID = Regex(
            "[a-zA-Z0-9._-]+@(?:ybl|paytm|oksbi|okaxis|okicici|okhdfcbank|upi|apl|axisb|ibl|sbi)",
            RegexOption.IGNORE_CASE
        )
        private val MONEY_PATTERN = Regex(
            "(?:₹|rs\\.?|inr)\\s*\\d+[,.]?\\d*(?:\\s*(?:lakh|crore|k|lac))?",
            RegexOption.IGNORE_CASE
        )
        private val LARGE_AMOUNT = Regex(
            "\\b\\d+\\s*(?:lakh|crore|million|billion)\\b",
            RegexOption.IGNORE_CASE
        )

        // --- Urgency Patterns ---
        private val URGENCY_PHRASES = listOf(
            "act now", "limited time", "expires today", "last chance",
            "urgent", "immediately", "within 24 hours", "hurry",
            "don't delay", "right now", "before it's too late", "asap"
        )

        // --- Action Demand Patterns ---
        private val CLICK_DEMAND = Regex(
            "(?:click|tap|visit|open|go to)\\s+(?:here|this|the|below|link|now)",
            RegexOption.IGNORE_CASE
        )
        private val CALL_DEMAND = Regex(
            "(?:call|dial|contact|reach)\\s+(?:us|now|this|the|immediately|\\d)",
            RegexOption.IGNORE_CASE
        )
        private val SHARE_DEMAND = Regex(
            "(?:share|send|provide|give)\\s+(?:your|the|ur)\\s+(?:otp|pin|password|cvv|card|account|aadhaar|pan)",
            RegexOption.IGNORE_CASE
        )

        // --- Threat Patterns ---
        private val THREAT_LANGUAGE = Regex(
            "(?:account|card|service)\\s+(?:will be|has been|is)\\s+(?:blocked|suspended|deactivated|closed|terminated)",
            RegexOption.IGNORE_CASE
        )
        private val LEGAL_THREAT = Regex(
            "(?:legal action|police|arrest|court|FIR|complaint|warrant)",
            RegexOption.IGNORE_CASE
        )

        // --- OTP Phishing Patterns (ALWAYS scams — no legitimate entity asks to send/share OTP) ---
        private val OTP_SOLICITATION = Regex(
            "(?:send|share|forward|give|tell|provide).{0,15}(?:otp|o\\.t\\.p|one.?time.?pass)",
            RegexOption.IGNORE_CASE
        )
        private val OTP_ROLLBACK = Regex(
            "(?:roll\\s*back|reverse|cancel|refund).{0,25}(?:otp|o\\.t\\.p|one.?time)|" +
            "(?:otp|o\\.t\\.p|one.?time).{0,25}(?:roll\\s*back|reverse|cancel|refund)",
            RegexOption.IGNORE_CASE
        )
        private val NOT_YOUR_TRANSACTION = Regex(
            "not\\s+(?:your?e?|ur|u)\\s+(?:transaction|payment|transfer|debit).{0,30}(?:send|share|otp|verify|confirm|call)",
            RegexOption.IGNORE_CASE
        )
        private val FAKE_VERIFY_OTP = Regex(
            "(?:verify|confirm|validate).{0,15}(?:by|via|through).{0,15}(?:sending|sharing|forwarding|entering).{0,15}(?:otp|code|pin)",
            RegexOption.IGNORE_CASE
        )
        private val ENTER_OTP_HERE = Regex(
            "(?:enter|type|put|input).{0,15}(?:otp|o\\.t\\.p|code).{0,15}(?:here|below|now|this|link)",
            RegexOption.IGNORE_CASE
        )
    }

    /**
     * Analyze text for structural scam patterns.
     *
     * @param text Message text to analyze.
     * @return List of matched structural rules with weights.
     */
    suspend fun detectPatterns(text: String): List<MatchedRule> =
        withContext(Dispatchers.Default) {
            val matches = mutableListOf<MatchedRule>()

            // URL patterns
            if (SHORTENED_URL.containsMatchIn(text)) {
                matches.add(MatchedRule("pattern:shortened_url", 0.7f, "phishing"))
            }
            if (SUSPICIOUS_TLD.containsMatchIn(text)) {
                matches.add(MatchedRule("pattern:suspicious_tld", 0.8f, "phishing"))
            }

            // Financial patterns
            if (UPI_ID.containsMatchIn(text)) {
                matches.add(MatchedRule("pattern:upi_id", 0.6f, "financial_fraud"))
            }
            if (LARGE_AMOUNT.containsMatchIn(text)) {
                matches.add(MatchedRule("pattern:large_amount", 0.5f, "financial_fraud"))
            }
            if (MONEY_PATTERN.containsMatchIn(text)) {
                matches.add(MatchedRule("pattern:money_mention", 0.3f, "financial_fraud"))
            }

            // Urgency
            val lowerText = text.lowercase()
            val urgencyCount = URGENCY_PHRASES.count { lowerText.contains(it) }
            if (urgencyCount > 0) {
                val urgencyWeight = (urgencyCount * 0.2f).coerceAtMost(0.8f)
                matches.add(MatchedRule("pattern:urgency", urgencyWeight))
            }

            // Action demands
            if (CLICK_DEMAND.containsMatchIn(text)) {
                matches.add(MatchedRule("pattern:click_demand", 0.5f, "phishing"))
            }
            if (CALL_DEMAND.containsMatchIn(text)) {
                matches.add(MatchedRule("pattern:call_demand", 0.4f, "tech_support"))
            }
            if (SHARE_DEMAND.containsMatchIn(text)) {
                matches.add(MatchedRule("pattern:credential_request", 0.9f, "phishing"))
            }

            // Threats
            if (THREAT_LANGUAGE.containsMatchIn(text)) {
                matches.add(MatchedRule("pattern:service_threat", 0.6f, "impersonation"))
            }
            if (LEGAL_THREAT.containsMatchIn(text)) {
                matches.add(MatchedRule("pattern:legal_threat", 0.5f, "impersonation"))
            }

            // OTP Phishing (ALWAYS scams — weight very high)
            if (OTP_SOLICITATION.containsMatchIn(text)) {
                matches.add(MatchedRule("pattern:otp_solicitation", 0.90f, "phishing"))
            }
            if (OTP_ROLLBACK.containsMatchIn(text)) {
                matches.add(MatchedRule("pattern:otp_rollback", 0.85f, "phishing"))
            }
            if (NOT_YOUR_TRANSACTION.containsMatchIn(text)) {
                matches.add(MatchedRule("pattern:not_your_transaction", 0.85f, "phishing"))
            }
            if (FAKE_VERIFY_OTP.containsMatchIn(text)) {
                matches.add(MatchedRule("pattern:fake_verify_otp", 0.85f, "phishing"))
            }
            if (ENTER_OTP_HERE.containsMatchIn(text)) {
                matches.add(MatchedRule("pattern:enter_otp_here", 0.80f, "phishing"))
            }

            // Excessive caps (more than 50% uppercase in text > 20 chars)
            if (text.length > 20) {
                val upperRatio = text.count { it.isUpperCase() }.toFloat() / text.length
                if (upperRatio > 0.5f) {
                    matches.add(MatchedRule("pattern:excessive_caps", 0.3f))
                }
            }

            // Extract URLs into metadata
            val urlCount = ANY_URL.findAll(text).count()
            if (urlCount > 2) {
                matches.add(MatchedRule("pattern:multiple_urls", 0.4f, "phishing"))
            }

            if (matches.isNotEmpty()) {
                Log.d(TAG, "Detected ${matches.size} structural patterns")
            }

            matches
        }

    /**
     * Extract all URLs from text (for metadata, not scoring).
     */
    fun extractUrls(text: String): List<String> {
        return ANY_URL.findAll(text).map { it.value }.toList()
    }
}
