package com.scamshield.app.intelligence

import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class IntelligenceSnapshot(
    val scamCategory: String,
    val urgencySignals: Int,
    val aggressionSignals: Int,
    val technicalSignals: Int,
    val trustSignals: Int,
    val repeatedTokenRatio: Float
)

data class ExtractedIntelligence(
    val itemType: String,
    val value: String,
    val normalizedValue: String,
    val confidence: Float = 1.0f
)

@Singleton
class IntelligenceCollector @Inject constructor() {
    fun collect(message: String, fallbackCategory: String = "unknown"): IntelligenceSnapshot {
        val lower = message.lowercase(Locale.US)
        val category = inferCategory(lower, fallbackCategory)
        val urgency = countAny(lower, "urgent", "immediate", "now", "today", "expire", "blocked", "last chance", "fast")
        val aggression = countAny(lower, "police", "arrest", "legal", "fir", "fine", "penalty", "block", "suspend")
        val technical = countAny(lower, "otp", "kyc", "link", "login", "server", "remote", "anydesk", "teamviewer", "wallet", "upi", "http")
        val trust = countAny(lower, "sir", "madam", "dear", "official", "bank", "support", "verified", "department")
        return IntelligenceSnapshot(category, urgency, aggression, technical, trust, repeatedTokenRatio(lower))
    }

    fun extractItems(message: String): List<ExtractedIntelligence> {
        val items = mutableListOf<ExtractedIntelligence>()
        val urlRegex = Regex("(?:https?://|www\\.)[^\\s<>\"')]+", RegexOption.IGNORE_CASE)
        val domainRegex = Regex("\\b[a-z0-9.-]+\\.(?:com|net|org|in|co|io|xyz|top|buzz|click|link|icu|shop|online|site|tk|ml|ga|cf|gq)\\b", RegexOption.IGNORE_CASE)
        val phoneRegex = Regex("(?:\\+?\\d[\\d\\s\\-()]{7,}\\d)")
        val upiRegex = Regex("[a-zA-Z0-9._-]+@(?:ybl|paytm|oksbi|okaxis|okicici|okhdfcbank|upi|apl|axisb|ibl|sbi)", RegexOption.IGNORE_CASE)
        val walletRegex = Regex("\\b(?:0x[a-fA-F0-9]{40}|[13][a-km-zA-HJ-NP-Z1-9]{25,34}|bc1[a-zA-HJ-NP-Z0-9]{25,62})\\b")

        urlRegex.findAll(message).forEach { match ->
            val value = match.value.trimEnd('.', ',', ';')
            items.add(ExtractedIntelligence("URL", value, value.lowercase(Locale.US)))
        }
        domainRegex.findAll(message).forEach { match ->
            val value = match.value.trimEnd('.', ',', ';')
            items.add(ExtractedIntelligence("DOMAIN", value, value.lowercase(Locale.US), 0.85f))
        }
        phoneRegex.findAll(message).forEach { match ->
            val value = match.value.trim()
            val normalized = value.filter { it.isDigit() || it == '+' }
            if (normalized.count { it.isDigit() } >= 8) items.add(ExtractedIntelligence("PHONE", value, normalized, 0.8f))
        }
        upiRegex.findAll(message).forEach { match ->
            val value = match.value
            items.add(ExtractedIntelligence("UPI", value, value.lowercase(Locale.US)))
        }
        walletRegex.findAll(message).forEach { match ->
            val value = match.value
            items.add(ExtractedIntelligence("WALLET", value, value.lowercase(Locale.US)))
        }

        return items.distinctBy { "${it.itemType}|${it.normalizedValue}" }
    }

    private fun inferCategory(lower: String, fallback: String): String = when {
        fallback != "unknown" -> fallback
        "otp" in lower || "code" in lower || "login" in lower || "verify" in lower -> "phishing"
        "kyc" in lower || "bank" in lower || "account" in lower || "card" in lower -> "impersonation"
        "upi" in lower || "wallet" in lower || "invest" in lower || "profit" in lower || "return" in lower -> "financial_fraud"
        "parcel" in lower || "package" in lower || "delivery" in lower || "customs" in lower -> "impersonation"
        "remote" in lower || "anydesk" in lower || "virus" in lower || "hacked" in lower -> "tech_support"
        "job" in lower || "work from home" in lower || "salary" in lower -> "job_scam"
        else -> fallback
    }

    private fun countAny(text: String, vararg signals: String): Int = signals.count { it in text }

    private fun repeatedTokenRatio(text: String): Float {
        val tokens = Regex("[a-z0-9]{4,}").findAll(text).map { it.value }.toList()
        if (tokens.size < 2) return 0f
        return ((tokens.size - tokens.toSet().size).toFloat() / tokens.size).coerceIn(0f, 1f)
    }
}
