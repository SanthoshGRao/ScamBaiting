package com.scamshield.app.service.providers

import com.scamshield.app.data.local.entity.BaitingMessageEntity
import com.scamshield.app.data.local.entity.BaitingSessionEntity
import com.scamshield.app.data.local.entity.IntelligenceItemEntity
import com.scamshield.app.data.local.entity.MissionEntity
import com.scamshield.app.data.local.entity.ScammerDnaProfileEntity

interface BaitingReplyProvider {
    suspend fun generateReply(
        session: BaitingSessionEntity,
        history: List<BaitingMessageEntity>,
        latestMessage: String,
        mission: MissionEntity,
        strategy: String,
        dna: ScammerDnaProfileEntity,
        knownIntelligence: List<IntelligenceItemEntity>
    ): String
}
