package com.scamshield.app.data.remote

data class ChatMessageDto(
    val role: String,
    val content: String
)

data class BaitingRequestDto(
    val sender_id: String,
    val session_id: String = "default_session",
    val persona: String,
    val current_strategy: String = "CONFUSION",
    val scam_category: String = "unknown",
    val goal: String = "waste_time",
    val mission: String = "WASTE_MAXIMUM_TIME",
    val scammer_dna: ScammerDnaDto? = null,
    val known_intelligence: List<KnownIntelligenceDto> = emptyList(),
    val history: List<ChatMessageDto>,
    val offline_analytics: List<OfflineAnalyticsSyncDto> = emptyList()
)

data class ScammerDnaDto(
    val urgency: Int,
    val aggression: Int,
    val persistence: Int,
    val technicalLevel: Int,
    val trustBuilding: Int,
    val repetition: Int,
    val avgMessageLength: Float,
    val avgResponseLatencyMs: Float,
    val scamCategory: String
)

data class KnownIntelligenceDto(
    val type: String,
    val value: String,
    val confidence: Float
)

data class OfflineAnalyticsSyncDto(
    val detected_intent: String,
    val selected_persona: String,
    val selected_state: String,
    val timestamp: Long
)

data class BaitingResponseDto(
    val reply_text: String = "",
    val reply_parts: List<String> = emptyList(),
    val response_delay_seconds: Int = 3,
    /** Seconds to pause before each bubble; when null, [response_delay_seconds] applies to every part. */
    val part_delay_seconds: List<Int>? = null,
    val should_terminate: Boolean = false,
    val processing_time_ms: Float = 0f,
    val strategy_used: String = "CONFUSION",
    val persona_used: String = "curious_user",
    val goal: String = "waste_time",
    /** Persist locally for the next /bait/reply; sent every turn (including stall). */
    val session_strategy: String = "",
    val image_base64: String? = null,
    val image_context: String? = null
)
