package com.scamshield.app.data.remote

data class LoginRequestDto(
    val username: String,
    val password: String
)

data class TokenResponseDto(
    val access_token: String,
    val refresh_token: String,
    val token_type: String
)

data class AnalyticsSummaryDto(
    val total_scams_detected: Int,
    val total_messages_processed: Int,
    val scam_categories: Map<String, Int>,
    val detection_trend: List<TrendPointDto> = emptyList(),
    val estimated_money_saved: Double
)

data class TrendPointDto(
    val date: String,
    val count: Int
)
