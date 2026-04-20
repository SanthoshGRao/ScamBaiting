package com.scamshield.app.data.remote

data class ScammerRegisterRequestDto(
    val phone_number: String,
    val last_message: String,
    val risk_level: String = "HIGH",
    val is_active: Boolean = true
)

data class ActivateAiRequestDto(
    val enabled: Boolean
)

data class ScammerHistoryResponseDto(
    val phone_number: String,
    val ai_enabled: Boolean,
    val history: List<ScammerHistoryItemDto>
)

data class ScammerHistoryItemDto(
    val role: String,
    val content: String,
    val timestamp: String
)
