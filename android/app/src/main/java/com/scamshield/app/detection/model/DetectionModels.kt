package com.scamshield.app.detection.model

import com.google.gson.annotations.SerializedName

/**
 * Data models for the on-device detection pipeline.
 * Mirrors the backend Pydantic contracts for consistency.
 *
 * Gson emits Kotlin property names by default; FastAPI expects snake_case keys — use [@SerializedName].
 */

/** Source of the incoming message */
enum class InputSource(val value: String) {
    SMS("sms"),
    EMAIL("email"),
    NOTIFICATION("notification"),
    MANUAL("manual")
}

/** A single matched rule from the detection engine */
data class MatchedRule(
    val rule: String,
    val weight: Float,
    val category: String? = null
)

/** Output of the on-device RuleEngine */
data class RuleVerdict(
    val isSuspicious: Boolean = false,
    val confidence: Float = 0f,
    val matchedRules: List<MatchedRule> = emptyList(),
    val categoryHints: List<String> = emptyList()
)

/** API request body for /api/v1/detect */
data class DetectionRequest(
    val text: String,
    val source: String = "manual",
    val sender: String? = null,
    @SerializedName("language_hint") val languageHint: String? = null,
    @SerializedName("rule_verdict") val ruleVerdict: RuleVerdictDto? = null,
    @SerializedName("privacy_mode") val privacyMode: Boolean = false,
    val media_type: String? = null,
    val media_filename: String? = null
)

data class FeedbackRequest(
    val message_id: String,
    val label: String,
    val notes: String = ""
)

/** Rule verdict DTO for API serialization */
data class RuleVerdictDto(
    val is_suspicious: Boolean,
    val confidence: Float,
    val matched_rules: List<MatchedRuleDto>,
    val category_hints: List<String>
)

data class MatchedRuleDto(
    val rule: String,
    val weight: Float,
    val category: String? = null
)

/** Full detection response from the backend */
data class DetectionResponse(
    val verdict: DetectionVerdictDto,
    val processing_time_ms: Float = 0f,
    val llm_pending: Boolean = false
)

data class DetectionVerdictDto(
    val message_id: String = "",
    val is_scam: Boolean = false,
    val confidence: Float = 0f,
    val category: String = "unknown",
    val risk_level: String = "safe",
    val reasoning: String = "",
    val explanation: String = "",
    val recommended_action: String = "none",
    val language: String = "en",
    val detection_mode: String = "rule_only",
    val red_flags: List<String> = emptyList(),
    val sender_score: Float = 0.5f,
    val link_risk_score: Float = 0f,
    val domain_flags: List<String> = emptyList(),
    val media_risk_score: Float = 0f,
    val media_flags: List<String> = emptyList(),
    val media_type_detected: String? = null,
    val explainability: ExplainabilityDto = ExplainabilityDto()
)

data class ExplainabilityDto(
    val rule_score: Float = 0f,
    val llm_score: Float = 0f,
    val sender_reputation: Float = 0.5f,
    val final_score: Float = 0f,
    val reasons: List<String> = emptyList()
)

/** Converts local RuleVerdict to API-compatible DTO */
fun RuleVerdict.toDto(): RuleVerdictDto = RuleVerdictDto(
    is_suspicious = isSuspicious,
    confidence = confidence,
    matched_rules = matchedRules.map { MatchedRuleDto(it.rule, it.weight, it.category) },
    category_hints = categoryHints
)
