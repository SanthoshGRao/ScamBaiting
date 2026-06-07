package com.scamshield.app.data.local.entity

data class OfflineDatasetDto(
    val datasetVersion: Int,
    val generatedAt: String = "",
    val personas: List<String> = emptyList(),
    val states: List<String> = emptyList(),
    val intents: List<IntentDto>
)

data class IntentDto(
    val id: String,
    val weights: Map<String, Int>,
    val templates: List<TemplateDto>
)

data class TemplateDto(
    val persona: String,
    val state: String,
    val text: String
)
