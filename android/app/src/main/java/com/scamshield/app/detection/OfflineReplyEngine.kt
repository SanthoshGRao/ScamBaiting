package com.scamshield.app.detection

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.scamshield.app.data.local.dao.BaitingDao
import com.scamshield.app.data.local.entity.BaitingMessageEntity
import com.scamshield.app.data.local.entity.BaitingSessionEntity
import com.scamshield.app.data.local.entity.IntelligenceItemEntity
import com.scamshield.app.data.local.entity.MissionEntity
import com.scamshield.app.data.local.entity.OfflineAnalyticsEntity
import com.scamshield.app.data.local.entity.OfflineDatasetDto
import com.scamshield.app.data.local.entity.ScammerDnaProfileEntity
import com.scamshield.app.service.providers.BaitingReplyProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStreamReader
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class OfflineReplyEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val baitingDao: BaitingDao
) : BaitingReplyProvider {
    companion object {
        private const val TAG = "OfflineReplyEngine"
        private const val INTENT_THRESHOLD = 10
        private const val RECENT_REPLY_WINDOW = 6
    }

    private data class IntentScore(val intentId: String, val confidenceScore: Int)
    private data class ScamContext(
        val text: String,
        val lower: String,
        val amount: String?,
        val linkHost: String?,
        val reference: String?
    )

    private var cachedDataset: OfflineDatasetDto? = null

    private suspend fun loadDataset(): OfflineDatasetDto {
        cachedDataset?.let { return it }
        return withContext(Dispatchers.IO) {
            val dataset = context.assets.open("offline_replies.json").use { input ->
                Gson().fromJson(InputStreamReader(input), OfflineDatasetDto::class.java)
            }
            cachedDataset = dataset
            dataset
        }
    }

    private fun recentScammerContext(history: List<BaitingMessageEntity>, latestMessage: String): String {
        val recent = history.filter { it.role == "user" }.takeLast(4).map { it.content }
        return (if (recent.isEmpty()) listOf(latestMessage) else recent).joinToString(" ").take(900)
    }

    private fun extractScamContext(text: String): ScamContext {
        val amount = Regex("(?:₹|rs\\.?|inr)\\s*\\d+[,.]?\\d*(?:\\s*(?:lakh|crore|k|lac))?", RegexOption.IGNORE_CASE)
            .find(text)?.value
        val linkHost = Regex("(?:https?://|www\\.)([^\\s/]+)|\\b([a-z0-9.-]+\\.(?:tk|ml|ga|cf|gq|xyz|top|buzz|click|link|icu|shop|online|site))\\b", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
        val reference = Regex("(?:ref|reference|awb|tracking|txn|transaction|case|ticket)\\s*[:#-]?\\s*([a-z0-9-]{5,})", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)
        return ScamContext(text, text.lowercase(Locale.US), amount, linkHost, reference)
    }

    private fun scoreIntent(message: String, dataset: OfflineDatasetDto): IntentScore {
        val terms = parseTerms(message)
        var bestIntent = "general_unknown"
        var maxScore = 0
        var maxPossibleScore = INTENT_THRESHOLD

        for (intent in dataset.intents) {
            var score = 0
            var possibleScore = 0
            for ((term, weight) in intent.weights) {
                possibleScore += weight
                if (terms.contains(normalizeTerm(term))) score += weight
            }
            if (score > maxScore) {
                maxScore = score
                bestIntent = intent.id
                maxPossibleScore = possibleScore.coerceAtLeast(INTENT_THRESHOLD)
            }
        }

        val detectedIntent = if (maxScore >= INTENT_THRESHOLD) bestIntent else inferIntent(message)
        val confidence = ((maxScore.toDouble() / maxPossibleScore) * 100).roundToInt().coerceIn(0, 100)
        return IntentScore(detectedIntent, confidence)
    }

    private fun inferIntent(message: String): String {
        val lower = message.lowercase(Locale.US)
        return when {
            "otp" in lower || "code" in lower || "pin" in lower -> "otp_scam"
            "bank" in lower || "kyc" in lower || "account" in lower || "card" in lower -> "bank_scam"
            "invest" in lower || "profit" in lower || "return" in lower || "earning" in lower -> "investment_scam"
            "remote" in lower || "anydesk" in lower || "teamviewer" in lower || "virus" in lower -> "tech_support"
            else -> "general_unknown"
        }
    }

    private fun parseTerms(message: String): Set<String> {
        val tokens = Regex("[a-z0-9]+").findAll(message.lowercase(Locale.US)).map { it.value }.toList()
        val terms = tokens.toMutableSet()
        val stemmed = tokens.map { stemToken(it) }
        terms.addAll(stemmed)
        for (size in 2..4) {
            tokens.windowed(size).forEach { terms.add(it.joinToString(" ")) }
            stemmed.windowed(size).forEach { terms.add(it.joinToString(" ")) }
        }
        return terms
    }

    private fun normalizeTerm(term: String): String = Regex("[a-z0-9]+")
        .findAll(term.lowercase(Locale.US))
        .map { stemToken(it.value) }
        .joinToString(" ")

    private fun stemToken(token: String): String = when {
        token.length > 5 && token.endsWith("ing") -> token.dropLast(3)
        token.length > 4 && token.endsWith("ed") -> token.dropLast(2)
        token.length > 3 && token.endsWith("s") -> token.dropLast(1)
        else -> token
    }

    private fun resolveState(session: BaitingSessionEntity, history: List<BaitingMessageEntity>): String {
        if (session.conversationState.isNotBlank() && session.conversationState != "INITIAL") return session.conversationState
        return when (history.count { it.role == "assistant" } % 4) {
            0 -> "INITIAL"
            1 -> "QUESTIONING"
            2 -> "DELAYING"
            else -> "CONFUSED"
        }
    }

    private fun nextState(currentState: String): String = when (currentState) {
        "INITIAL" -> "QUESTIONING"
        "QUESTIONING" -> "DELAYING"
        "DELAYING" -> "CONFUSED"
        "CONFUSED" -> "AGREEING"
        else -> "QUESTIONING"
    }

    private fun personaLeadIn(persona: String, turn: Int): String? {
        if (turn % 3 != 0) return null
        return when (persona) {
            "busy_professional" -> listOf("I am between meetings.", "Give me one minute.", "I am not at my desk right now.").random()
            "skeptical_buyer" -> listOf("I am being careful here.", "I need to verify before I proceed.", "This sounds unusual to me.").random()
            "half_understanding_user" -> listOf("I am following slowly.", "I think I understand, but not fully.", "I may be missing one step.").random()
            "lonely_conversationalist" -> listOf("I was just making tea.", "I am here now.", "Sorry, I got distracted for a moment.").random()
            "hopeful_opportunity_seeker" -> listOf("I do not want to miss this.", "This sounds important.", "I am interested, just confused.").random()
            "confused_elderly" -> listOf("Dear, I am a little confused.", "My eyes are not good on this phone.", "Please go slowly with me.").random()
            else -> listOf("I am checking now.", "One moment.", "I need to understand first.").random()
        }
    }

    private fun contextualCandidates(
        intentId: String,
        state: String,
        persona: String,
        c: ScamContext,
        turn: Int,
        mission: MissionEntity,
        strategy: String,
        dna: ScammerDnaProfileEntity,
        knownIntelligence: List<IntelligenceItemEntity>
    ): List<String> {
        val ref = c.reference?.let { " Reference $it, right?" } ?: ""
        val amount = c.amount ?: "that amount"
        val link = c.linkHost ?: "that link"
        val knownWallet = knownIntelligence.firstOrNull { it.itemType == "WALLET" || it.itemType == "UPI" }?.value
        val knownWebsite = knownIntelligence.firstOrNull { it.itemType == "URL" || it.itemType == "DOMAIN" }?.value
        val missionBase = when (mission.missionType) {
            "EXTRACT_PAYMENT_INSTRUCTIONS" -> listOf(
                "I tried to arrange the payment, but I need the exact UPI, wallet, or account details again.",
                "I think I can pay now. Send the payment details in one message so I do not copy it wrong.",
                "Before I transfer $amount, confirm the receiving name and payment address."
            )
            "EXTRACT_SCAM_WEBSITE" -> listOf(
                "The page will not load properly. Can you send the website link again?",
                "I opened ${knownWebsite ?: link}, but it is stuck. Is there another link?",
                "Which website should I use exactly? Send only the correct link."
            )
            "EXTRACT_TOOLING_AND_CONTACT" -> listOf(
                "Which app should I install and what support number should I contact if it fails?",
                "The remote app is confusing. Send the exact app name and code process.",
                "If the app does not open, who should I call?"
            )
            "IDENTIFY_PROCESS_AND_CONTACT" -> listOf(
                "Tell me the department name and the steps in order, I am getting mixed up.",
                "Who is handling my case? Send the contact and reference details once more.",
                "Before I continue, confirm what this process is officially called."
            )
            "GATHER_CONTACT_INFORMATION" -> listOf(
                "If this chat disconnects, what number should I contact you on?",
                "Who should I ask for if I call back later?",
                "Send your alternate contact so I do not lose this process."
            )
            else -> emptyList()
        }
        val base = missionBase + when {
            intentId == "otp_scam" || "otp" in c.lower || "code" in c.lower -> when (state) {
                "INITIAL" -> listOf("What is this code for? I received something but I do not want to send the wrong thing.", "I see a code on my phone. Which part of it do you need and why?", "Before I share anything, tell me what transaction this code is connected to.")
                "QUESTIONING" -> listOf("You said it is verification, but verification for what account exactly?", "Should I read the whole code or only the last digits? I am not sure.", "If this is from the bank, why did it come to my phone and not yours?")
                "DELAYING" -> listOf("Wait, a new message came. I need to check which code is the latest one.", "The code disappeared from the notification. I am opening messages now.", "Hold on, I typed it once and it did not look right.")
                else -> listOf("I am confused because there are two numbers here. Which one are you asking for?", "Can you send the steps again from the beginning? I do not want to make a mistake.", "If I give this code, what happens next on your side?")
            }
            intentId == "bank_scam" || "kyc" in c.lower || "bank" in c.lower || "account" in c.lower -> when (state) {
                "INITIAL" -> listOf("Which bank account is this about? I have more than one.", "Is this for KYC or card blocking? Your message is not very clear.", "Tell me the branch or department first. I need to know this is the right account.")
                "QUESTIONING" -> listOf("What details are actually pending? PAN, Aadhaar, or something else?", "Can this be done tomorrow at the branch, or must it be done on phone now?", "What happens if I do not finish this today?")
                "DELAYING" -> listOf("I am looking for my card. The numbers are small, give me a minute.", "I need to find my PAN card. It is not with me right now.", "The bank app is loading very slowly. Stay on chat.")
                else -> listOf("I found an old document, but I am not sure if it is the correct one.", "Can you confirm the exact name on the account before I continue?", "Please repeat the steps one by one. I got mixed up.")
            }
            intentId == "investment_scam" || c.amount != null || "investment" in c.lower || "profit" in c.lower -> when (state) {
                "INITIAL" -> listOf("How does this return work? $amount sounds high.", "What is the minimum I need to start with, and when do I get it back?", "Is there any written proof for this plan? I need to understand first.")
                "QUESTIONING" -> listOf("Who is holding the money after I send it?", "If I pay $amount, what confirmation will I receive?", "Can I start with a smaller amount first to test it?")
                "DELAYING" -> listOf("I need to check my balance. The app is slow.", "My bank is asking for confirmation. I am reading it carefully.", "Wait, I may have a daily transfer limit.")
                else -> listOf("I am not refusing, I just need the steps clearly.", "Send me the payment name again. I do not want to send to the wrong person.", "What should I write in the payment remarks?")
            }
            intentId == "tech_support" || "anydesk" in c.lower || "remote" in c.lower || "virus" in c.lower -> when (state) {
                "INITIAL" -> listOf("What exactly is wrong with my phone? It seems normal to me.", "Which app do you want me to open first?", "Is this for my phone or computer? I am not sure which device you mean.")
                "QUESTIONING" -> listOf("Why do you need remote access for this?", "What code will appear after I install it?", "Can you stay on chat while I check the app name?")
                "DELAYING" -> listOf("The download is taking time. My internet is weak.", "I see many apps with similar names. Which one exactly?", "It is asking for permissions. Which ones should I allow?")
                else -> listOf("I pressed back by mistake. Please tell me the previous step again.", "The screen changed. I do not know if I am in the right place.", "Can you explain what I should see on the screen?")
            }
            "parcel" in c.lower || "package" in c.lower || "delivery" in c.lower || "customs" in c.lower -> when (state) {
                "INITIAL" -> listOf("Which parcel is this for?$ref I was expecting more than one delivery.", "What is pending on the delivery, address or payment?", "Can you give me the tracking number first?")
                "QUESTIONING" -> listOf("Why is there a fee before delivery?", "Which courier company is handling this?", "Can I pay when the delivery person comes?")
                "DELAYING" -> listOf("I am checking my order messages. Give me a little time.", "I cannot find the tracking number. Send it once more.", "The link is not opening properly.")
                else -> listOf("I may have entered the wrong address. Can you show me what you have?", "Please tell me the exact pending amount and courier name again.", "I am confused between two deliveries. Which one is yours?")
            }
            c.linkHost != null -> when (state) {
                "INITIAL" -> listOf("What is $link for? I do not usually open links from messages.", "Can you explain what I will see after opening $link?", "Is there another way without opening the link?")
                "QUESTIONING" -> listOf("Why is the website name different from the company name?", "Do I need to login there, or only check something?", "What details will it ask me on $link?")
                "DELAYING" -> listOf("The page is loading slowly. I am waiting.", "It opened but I am not sure which button to press.", "My browser is showing a warning. What should I do?")
                else -> listOf("I lost the page. Send the link again with the exact step.", "It asks for details. Tell me which fields are required.", "I do not understand this page. Guide me slowly.")
            }
            else -> when (state) {
                "INITIAL" -> listOf("I saw your message, but I need more details before doing anything.", "What is this regarding exactly? Give me the simple version.", "I am interested, but explain the process first.")
                "QUESTIONING" -> listOf("Who should I ask for if I call back later?", "What information do you need from me first?", "Can you send the steps in order?")
                "DELAYING" -> listOf("Give me a minute. I am checking it now.", "My phone is slow. I am still here.", "I need to find the details you are asking for.")
                else -> listOf("I got confused halfway. Start again from the first step.", "I may have done the wrong thing. Tell me what to check now.", "Please do not rush me. I am trying to follow.")
            }
        }
        val strategyBase = when (strategy) {
            "DELAY" -> listOf("Let me restart the phone first.", "The app is loading slowly, wait a bit.", "I need to check this carefully before I continue.")
            "QUESTION" -> listOf("Why do you need that information?", "What is the next step after I send this?", "Can you explain why this is required?")
            "AGREE" -> listOf("I think I completed the transfer, but I need you to confirm the receiving details.", "Okay, I am trying to follow your steps now.", "I am ready to do it, send the exact details again.")
            "CONFUSE" -> listOf("Which button are you referring to?", "I see two options and I do not know which one is correct.", "I may be on the wrong screen. Explain from the start.")
            "PANIC" -> listOf("I am worried this will get blocked. Tell me quickly what to do.", "This is making me nervous. What is the safest step?", "Please stay here, I do not want to lose the account.")
            "SOCIALIZE" -> listOf("Are you from the local office or head office?", "How long does this usually take?", "I have never done this on phone before.")
            "DISTRACT" -> listOf("Before that, can you confirm the name on your side?", "Wait, I got another message. Is that related?", "My phone battery is low, what is the shortest step?")
            else -> emptyList()
        }
        val lead = personaLeadIn(persona, turn)
        val dnaNudge = when {
            dna.urgencyScore >= 70 -> listOf("You are saying it is urgent, so tell me the fastest safe way.")
            dna.aggressionScore >= 60 -> listOf("I am scared of making a mistake now. Please slow down.")
            knownWallet != null -> listOf("I have $knownWallet here, but I want to confirm it before sending anything.")
            else -> emptyList()
        }
        val all = strategyBase + missionBase + dnaNudge + base
        return if (lead == null) all else all + all.map { "$lead $it" }
    }

    private fun semanticSignature(text: String): Set<String> {
        val stop = setOf("the", "and", "you", "can", "what", "why", "this", "that", "need", "please", "tell", "message", "thing", "now", "exactly")
        return Regex("[a-z0-9]+").findAll(text.lowercase(Locale.US))
            .map { stemToken(it.value) }
            .filter { it.length > 2 && it !in stop }
            .toSet()
    }

    private fun tooSimilar(candidate: String, previous: List<String>): Boolean {
        val c = semanticSignature(candidate)
        if (c.isEmpty()) return false
        return previous.any {
            val p = semanticSignature(it)
            p.isNotEmpty() && c.intersect(p).size.toFloat() / c.union(p).size >= 0.55f
        }
    }

    private fun chooseNaturalReply(candidates: List<String>, history: List<BaitingMessageEntity>): String? {
        val previous = history.filter { it.role == "assistant" }.map { it.content }
        return candidates.shuffled().firstOrNull { it !in previous && !tooSimilar(it, previous.takeLast(10)) }
            ?: candidates.shuffled().firstOrNull { it !in previous.takeLast(RECENT_REPLY_WINDOW) }
    }

    private fun fallbackReply(history: List<BaitingMessageEntity>): String {
        val replies = listOf(
            "I am not understanding the last step. Explain it another way.",
            "Give me a moment, I am checking what you sent.",
            "Can you repeat the next step slowly?",
            "I do not want to make a mistake. What should I do first?"
        )
        val previous = history.filter { it.role == "assistant" }.takeLast(RECENT_REPLY_WINDOW).map { it.content }
        return replies.firstOrNull { it !in previous } ?: replies.random()
    }

    override suspend fun generateReply(
        session: BaitingSessionEntity,
        history: List<BaitingMessageEntity>,
        latestMessage: String,
        mission: MissionEntity,
        strategy: String,
        dna: ScammerDnaProfileEntity,
        knownIntelligence: List<IntelligenceItemEntity>
    ): String {
        return try {
            val dataset = loadDataset()
            val messageContext = recentScammerContext(history, latestMessage)
            val scamContext = extractScamContext(messageContext)
            val score = scoreIntent(messageContext, dataset)
            val state = resolveState(session, history)
            val reply = chooseNaturalReply(
                contextualCandidates(score.intentId, state, session.persona, scamContext, history.count { it.role == "assistant" }, mission, strategy, dna, knownIntelligence),
                history
            ) ?: fallbackReply(history)

            runCatching {
                baitingDao.insertOfflineAnalytics(
                    OfflineAnalyticsEntity(
                        sessionId = session.senderId,
                        timestamp = System.currentTimeMillis(),
                        detectedIntent = score.intentId,
                        confidenceScore = score.confidenceScore,
                        selectedState = state,
                        selectedPersona = session.persona,
                        selectedReply = reply
                    )
                )
                baitingDao.updateConversationState(session.senderId, nextState(state))
            }.onFailure { Log.w(TAG, "Offline analytics/state persistence failed", it) }

            reply
        } catch (e: Exception) {
            Log.e(TAG, "Offline engine failed; using fallback", e)
            fallbackReply(history)
        }
    }
}
