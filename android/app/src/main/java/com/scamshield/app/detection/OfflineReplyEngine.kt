package com.scamshield.app.detection

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.scamshield.app.data.local.dao.BaitingDao
import com.scamshield.app.data.local.entity.BaitingMessageEntity
import com.scamshield.app.data.local.entity.BaitingSessionEntity
import com.scamshield.app.data.local.entity.IntelligenceItemEntity
import com.scamshield.app.data.local.entity.MissionEntity
import com.scamshield.app.data.local.entity.OfflineDatasetDto
import com.scamshield.app.data.local.entity.OfflineAnalyticsEntity
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
        private const val RECENT_REPLY_WINDOW = 8
    }

    private data class IntentScore(val intentId: String, val confidenceScore: Int)
    private data class ScamContext(
        val text: String,
        val lower: String,
        val amount: String?,
        val upiId: String?,
        val linkHost: String?,
        val reference: String?,
        val phoneNumber: String?,
        val personName: String?,
        val appName: String?
    )

    /**
     * Weighted candidate: reply text + weight (higher = more likely to be selected).
     */
    private data class WeightedReply(val text: String, val weight: Int)

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

    // ──────────────────────────────────────────────────────────────────────
    // Scam context extraction – pulls specific details from scammer messages
    // ──────────────────────────────────────────────────────────────────────

    private fun recentScammerContext(history: List<BaitingMessageEntity>, latestMessage: String): String {
        val recent = history.filter { it.role == "user" }.takeLast(4).map { it.content }
        return (if (recent.isEmpty()) listOf(latestMessage) else recent).joinToString(" ").take(900)
    }

    private fun extractScamContext(text: String): ScamContext {
        val lower = text.lowercase(Locale.US)
        val amount = Regex("(?:₹|rs\\.?|inr)\\s*\\d+[,.]?\\d*(?:\\s*(?:lakh|crore|k|lac))?", RegexOption.IGNORE_CASE)
            .find(text)?.value
        val upiId = Regex("[a-z0-9._-]+@[a-z]{2,}(?:bank|pay|ybl|ibl|axl|sbi|okhdfcbank|paytm|oksbi|apl|icici|upi)", RegexOption.IGNORE_CASE)
            .find(text)?.value
        val linkHost = Regex("(?:https?://|www\\.)([^\\s/]+)|\\b([a-z0-9.-]+\\.(?:tk|ml|ga|cf|gq|xyz|top|buzz|click|link|icu|shop|online|site))\\b", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
        val reference = Regex("(?:ref|reference|awb|tracking|txn|transaction|case|ticket)\\s*[:#-]?\\s*([a-z0-9-]{5,})", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)
        val phoneNumber = Regex("(?:\\+91|0)?[6-9]\\d{9}").find(text)?.value
        val personName = Regex("(?:mr\\.?|mrs\\.?|ms\\.?|shri|smt)\\s+([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)?)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)
        val appName = Regex("\\b(anydesk|teamviewer|quicksupport|quick support|rustdesk|ammyy|ultraviewer)\\b", RegexOption.IGNORE_CASE)
            .find(text)?.value?.lowercase(Locale.US)
        return ScamContext(text, lower, amount, upiId, linkHost, reference, phoneNumber, personName, appName)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Intent scoring – keyword-weighted matching from JSON dataset
    // ──────────────────────────────────────────────────────────────────────

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
            "parcel" in lower || "package" in lower || "delivery" in lower || "customs" in lower -> "parcel_scam"
            "lottery" in lower || "prize" in lower || "winner" in lower || "lucky" in lower -> "lottery_scam"
            "job" in lower || "work from home" in lower || "hiring" in lower || "vacancy" in lower -> "job_scam"
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

    // ──────────────────────────────────────────────────────────────────────
    // REACTIVE state resolution – adapts to what the scammer is DOING
    // instead of cycling mechanically
    // ──────────────────────────────────────────────────────────────────────

    private fun resolveState(
        session: BaitingSessionEntity,
        history: List<BaitingMessageEntity>,
        latestMessage: String,
        dna: ScammerDnaProfileEntity
    ): String {
        val topic = detectScammerTopic(latestMessage)
        val turn = history.count { it.role == "assistant" }
        val currentState = session.conversationState

        // React to what the scammer is doing RIGHT NOW
        val reactiveState = when (topic) {
            "THREAT" -> if (turn < 3) "CONFUSED" else "EMERGENCY"
            "URGENCY" -> if (turn < 2) "QUESTIONING" else "DELAYING"
            "PAYMENT" -> when {
                turn < 2 -> "QUESTIONING"
                turn < 5 -> "AGREEING" // pretend to comply to extract details
                else -> "DELAYING"
            }
            "OTP_ASK" -> when {
                turn < 2 -> "CONFUSED"
                turn < 4 -> "DELAYING"
                else -> "QUESTIONING" // stall by asking more questions
            }
            "PERSONAL_INFO" -> when {
                turn < 3 -> "QUESTIONING"
                else -> "DELAYING"
            }
            "INSTALL" -> when {
                turn < 2 -> "QUESTIONING"
                turn < 5 -> "AGREEING" // pretend to install
                else -> "CONFUSED" // "it's not working"
            }
            "CLICK_LINK" -> when {
                turn < 3 -> "AGREEING" // pretend to open
                else -> "CONFUSED" // "page not loading"
            }
            "INSTRUCTIONS" -> when {
                turn < 4 -> "AGREEING"
                else -> "CONFUSED" // "i pressed something wrong"
            }
            "REASSURING" -> "QUESTIONING" // they're reassuring means we should question more
            "FOLLOWUP" -> when {
                // Scammer is getting impatient – come back and engage
                turn < 3 -> "AGREEING"
                else -> "DELAYING"
            }
            else -> null // no clear reactive state
        }

        // If we got a reactive state, use it (with some randomness to avoid being too predictable)
        if (reactiveState != null) {
            // 75% of the time use the reactive state, 25% use a natural variation
            if ((1..100).random() <= 75) return reactiveState
        }

        // DNA-based adjustments
        if (dna.urgencyScore >= 70 && currentState != "DELAYING") return "DELAYING"
        if (dna.aggressionScore >= 60 && currentState != "CONFUSED") return "CONFUSED"

        // Fallback: semi-random state progression that feels natural
        return when {
            turn == 0 -> "INITIAL"
            turn == 1 -> listOf("QUESTIONING", "CONFUSED", "AGREEING").random()
            turn < 4 -> listOf("DELAYING", "QUESTIONING", "AGREEING", "CONFUSED").random()
            turn < 8 -> listOf("DELAYING", "CONFUSED", "AGREEING", "QUESTIONING").random()
            else -> listOf("DELAYING", "CONFUSED", "EMERGENCY", "AGREEING").random()
        }
    }

    private fun nextState(currentState: String): String = when (currentState) {
        "INITIAL" -> "QUESTIONING"
        "QUESTIONING" -> "DELAYING"
        "DELAYING" -> "CONFUSED"
        "CONFUSED" -> "AGREEING"
        "AGREEING" -> "QUESTIONING"
        "EMERGENCY" -> "DELAYING"
        else -> "QUESTIONING"
    }

    // ──────────────────────────────────────────────────────────────────────
    // Scammer topic detection – what is the scammer asking/doing
    // ──────────────────────────────────────────────────────────────────────

    private fun detectScammerTopic(latestMessage: String): String {
        val lower = latestMessage.lowercase(Locale.US)
        return when {
            // Casual/conversational messages should be detected first
            isCasualMessage(lower) -> "CASUAL"
            scammerAsksQuestion(lower) -> "QUESTION_TO_US"
            Regex("(hurry|quick|fast|urgent|immediately|right now|asap|now only|last chance|expire|deadline)").containsMatchIn(lower) -> "URGENCY"
            Regex("(block|suspend|cancel|legal|police|arrest|fraud|complaint|action|fir)").containsMatchIn(lower) -> "THREAT"
            Regex("(pay|transfer|send money|deposit|amount|rupee|upi|gpay|paytm|phonepe|neft|imps|rtgs)").containsMatchIn(lower) -> "PAYMENT"
            Regex("(aadhaar|aadhar|pan card|pan number|address|dob|date of birth|mother|father|maiden)").containsMatchIn(lower) -> "PERSONAL_INFO"
            Regex("(install|download|app|anydesk|teamviewer|quicksupport|play store|apk)").containsMatchIn(lower) -> "INSTALL"
            Regex("(click|open|link|url|website|www|http|\\.com|\\.in|\\.org)").containsMatchIn(lower) -> "CLICK_LINK"
            Regex("(otp|code|pin|digit|number came|sms|message came|verification)").containsMatchIn(lower) -> "OTP_ASK"
            Regex("(step|follow|do this|go to|press|tap|select|enter|type|fill)").containsMatchIn(lower) -> "INSTRUCTIONS"
            Regex("(trust|safe|secure|official|government|rbi|bank|certified|guaranteed|dont worry|no problem)").containsMatchIn(lower) -> "REASSURING"
            Regex("(are you there|hello|respond|reply|pick up|answer|why not|waiting)").containsMatchIn(lower) -> "FOLLOWUP"
            else -> "GENERAL"
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Casual / conversational message detection
    // ──────────────────────────────────────────────────────────────────────

    private fun isCasualMessage(lower: String): Boolean {
        val trimmed = lower.trim()
        if (trimmed.length > 50) return false
        val casualPatterns = listOf(
            Regex("^\\s*h(i|ello|ey)\\s*[.!?]*\\s*$"),
            Regex("^\\s*how are (you|u|ya)\\s*[.!?]*\\s*$"),
            Regex("^\\s*good (morning|afternoon|evening|night)\\s*[.!?]*\\s*$"),
            Regex("^\\s*what'?s? ?(up|happening|going on)\\s*[.!?]*\\s*$"),
            Regex("^\\s*(ok|okay|k|fine|sure|yes|no|ya|yeah|nah|nope|hmm|thanks?( (you|u))?)\\s*[.!?]*\\s*$"),
            Regex("^\\s*(bye|goodbye|take care|see (you|u))\\s*[.!?]*\\s*$"),
            Regex("^\\s*(kya (chal|ho) (raha|rahi)|kaise ho|theek ho|kya baat hai|suno|bolo)\\s*[.!?]*\\s*$"),
            Regex("^\\s*(tell|bata|batao) (me|na)\\s*[.!?]*\\s*$"),
            Regex("^\\s*(sorry|maaf|excuse me)\\s*[.!?]*\\s*$")
        )
        return casualPatterns.any { it.containsMatchIn(trimmed) }
    }

    private fun scammerAsksQuestion(lower: String): Boolean {
        val trimmed = lower.trim()
        if (!trimmed.contains("?")) return false
        val questionPatterns = listOf(
            Regex("(what|where|which|who|how|when|why|kya|kahan|kaise|kaun|kab)\\b.{0,60}\\?"),
            Regex("\\?\\s*$")
        )
        return questionPatterns.any { it.containsMatchIn(trimmed) }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Casual conversational replies — respond naturally to casual messages
    // ──────────────────────────────────────────────────────────────────────

    private fun casualMessageReplies(latestMessage: String, persona: String): List<String> {
        val lower = latestMessage.lowercase(Locale.US).trim()
        val replies = mutableListOf<String>()

        // Greeting replies
        if (lower.contains("hello") || lower.contains("hi") || lower.contains("hey")) {
            replies.addAll(listOf(
                "ya hi", "hey", "hello ji", "ya bolo", "hi whats up",
                "hello hello", "ha bolo", "yes tell me"
            ))
        }
        // How are you replies
        if (lower.contains("how are") || lower.contains("kaise ho")) {
            replies.addAll(listOf(
                "im good yaar whats up", "fine fine busy day today",
                "ya im ok u tell me", "good good u say",
                "theek hoon bhai bolo", "im alright whats this about",
                "all good here what happened", "im fine thanks for asking",
                "not bad just busy with work"
            ))
        }
        // Good morning/evening replies
        if (lower.contains("good morning") || lower.contains("good evening") || lower.contains("good afternoon")) {
            replies.addAll(listOf(
                "good morning ji", "morning bhai", "ya good morning tell me",
                "good morning whats up", "good evening bolo"
            ))
        }
        // OK/acknowledgment replies
        if (lower.matches(Regex("^\\s*(ok|okay|k|fine|sure|yes|ya|yeah|hmm)\\s*[.!?]*\\s*$"))) {
            replies.addAll(listOf(
                "ok so what now", "ya then what should i do",
                "ok tell me the next thing", "fine what else",
                "hmm ok continue", "alright go ahead"
            ))
        }
        // Thanks replies
        if (lower.contains("thank")) {
            replies.addAll(listOf(
                "ya no problem", "sure sure", "ok ok dont mention it",
                "ya welcome bhai", "no worries"
            ))
        }
        // Sorry replies
        if (lower.contains("sorry") || lower.contains("maaf")) {
            replies.addAll(listOf(
                "its ok no issue", "no problem bhai",
                "ya dont worry about it", "its fine tell me what to do now",
                "all good just tell me what happened"
            ))
        }

        // Generic casual fallbacks
        if (replies.isEmpty()) {
            replies.addAll(listOf(
                "ya bolo", "ha tell me", "ok whats up",
                "ya im here go on", "ha bhai bolo",
                "ok ok tell me", "ya go ahead"
            ))
        }

        return replies
    }

    // Direct question replies — answer when scammer asks us something
    private fun answerScammerQuestionReplies(latestMessage: String, persona: String): List<String> {
        val lower = latestMessage.lowercase(Locale.US).trim()
        val replies = mutableListOf<String>()

        // "Did you do X?" pattern
        if (lower.contains("did you") || lower.contains("did u") || lower.contains("have you") || lower.contains("have u")) {
            replies.addAll(listOf(
                "ya im trying to do it now just give me a sec",
                "not yet bhai im still working on it",
                "almost done just one more minute",
                "ya i think i did it but not sure if it went through",
                "im doing it right now hold on"
            ))
        }
        // "Are you there?" pattern
        if (lower.contains("are you there") || lower.contains("u there") || lower.contains("are u there")) {
            replies.addAll(listOf(
                "ya im here sorry was checking something",
                "yes yes im here go on",
                "ya bhai im here only was reading ur msg",
                "hello im here was doing what u said"
            ))
        }
        // "Can you...?" pattern
        if (lower.contains("can you") || lower.contains("can u") || lower.contains("could you")) {
            replies.addAll(listOf(
                "ya i think so let me try",
                "ok let me see if i can do that",
                "im trying bhai just give me some time",
                "ya i can try but my phone is being slow"
            ))
        }
        // "What is your...?" or "What's your...?" pattern
        if (lower.contains("what is your") || lower.contains("whats your") || lower.contains("what's your")) {
            replies.addAll(listOf(
                "why do u need that tell me first",
                "hmm let me think about that",
                "i can share but first tell me why its needed",
                "hold on let me check what info i should give u"
            ))
        }

        // Generic question fallbacks
        if (replies.isEmpty()) {
            replies.addAll(listOf(
                "hmm good question let me think about that",
                "ya i think so but not 100% sure",
                "let me check and tell u in a sec",
                "im not sure about that can u explain more",
                "ya actually i was wondering the same thing"
            ))
        }

        return replies
    }

    // ──────────────────────────────────────────────────────────────────────
    // Message echoing – weave scammer's specific details into replies
    // ──────────────────────────────────────────────────────────────────────

    private fun echoScammerDetails(c: ScamContext): List<String> {
        val echoes = mutableListOf<String>()

        // Echo amounts
        c.amount?.let { amt ->
            echoes.addAll(listOf(
                "wait $amt is what u said right? let me check my balance",
                "ok $amt but my account is showing less balance rn",
                "$amt ok but can i send in 2 parts my limit is low",
                "bhai $amt is a lot let me arrange give me some time",
                "hold on $amt right? let me confirm with my wife first",
                "ok so $amt to where exactly? send details again properly",
                "i can see $amt but my bank app is hanging right now",
                "sending $amt now but the app is asking for some verification",
                "$amt noted but which mode should i use upi or neft"
            ))
        }

        // Echo UPI IDs
        c.upiId?.let { upi ->
            echoes.addAll(listOf(
                "ok sending to $upi but its showing name differently",
                "$upi right? let me type it again i might have made mistake",
                "bhai is $upi correct? the name showing is different from what you said",
                "i entered $upi but gpay is giving error try another id?",
                "ok $upi i see it but confirm the name thats showing on my side",
                "$upi ok but my bank app crashed let me reopen",
                "wait is it $upi or with different spelling? too many similar ones"
            ))
        }

        // Echo link/website
        c.linkHost?.let { link ->
            echoes.addAll(listOf(
                "i opened $link but its showing blank page only",
                "$link right? my browser is giving security warning should i continue",
                "bhai $link is not loading properly maybe my net is slow",
                "ok opened $link but where do i click there are many buttons",
                "$link loaded but its asking for registration first",
                "the page $link is looking different from what u described"
            ))
        }

        // Echo reference numbers
        c.reference?.let { ref ->
            echoes.addAll(listOf(
                "my reference is $ref right? just confirming",
                "ok $ref noted but which department should i contact if there is issue",
                "bhai $ref is showing but the status says pending what does that mean",
                "i checked $ref and it says something different from what u told me"
            ))
        }

        // Echo app names
        c.appName?.let { app ->
            echoes.addAll(listOf(
                "ok installing $app but its 50mb and my storage is almost full",
                "$app right? there are 3-4 similar apps which one exactly",
                "bhai $app is asking for too many permissions is that normal",
                "installed $app but when i open it just shows loading screen",
                "$app downloaded but my phone is getting hot after installing",
                "ok $app is installed now what code do i give u"
            ))
        }

        // Echo phone numbers
        c.phoneNumber?.let { phone ->
            echoes.addAll(listOf(
                "should i call on $phone if something goes wrong",
                "is $phone your direct number or the office one",
                "bhai i tried calling $phone but it was busy"
            ))
        }

        // Echo person names
        c.personName?.let { name ->
            echoes.addAll(listOf(
                "ok $name sir got it what should i do now",
                "is $name the person handling my case",
                "$name ji i need some more time please"
            ))
        }

        return echoes
    }

    // ──────────────────────────────────────────────────────────────────────
    // Reactive replies – respond to scammer's CURRENT topic
    // ──────────────────────────────────────────────────────────────────────

    private fun reactiveReplies(topic: String, persona: String, turn: Int): List<String> {
        val base = when (topic) {
            "URGENCY" -> listOf(
                "ok ok im trying but u keep rushing me",
                "bhai why so much hurry give me 2 min",
                "im doing it now just wait na",
                "u said this is urgent but let me just check once",
                "ok hold on im opening it now",
                "ya ya i know its urgent but my phone is slow today",
                "dont worry im not going anywhere just give me a sec",
                "ok but last time also someone said urgent and it was fake",
                "im trying but this phone hangs a lot give me time",
                "ok relax im here only where will i go lol",
                "fine fine let me do it but stop pressuring me yaar",
                "ya im on it just the screen is loading slow",
                "u keep saying hurry but i need to understand first no",
                "ok i will do it fast but atleast tell me properly what to do",
                "bhai hold on my internet is also acting up today",
                "I understand the urgency but I need a moment to verify this",
                "Please give me 2 minutes I want to make sure I do this correctly",
                "ok ok one minute only dont worry i am doing it",
                "arrey itna jaldi kyu hai relax karo thoda",
                "ya coming coming just let me save my other work first"
            )
            "THREAT" -> listOf(
                "wait what do u mean block? i didnt do anything wrong",
                "please dont block my account i need it for salary",
                "sir i will do it just tell me what to do im scared now",
                "ok ok please dont take any action im cooperating",
                "this is scaring me a bit but ok tell me what to do",
                "if u block it then how will i get my money back",
                "please sir i have family to support dont cancel anything",
                "ok i understand but just give me little more time please",
                "fine i will do whatever u say just dont file anything",
                "wait let me call my son once he knows about these things",
                "ya but u cant just block like that right there must be some process no",
                "im not refusing im just confused please explain again",
                "ok but if i do this everything will be fine right? nothing will get blocked?",
                "please dont do anything yet im almost done just slow internet",
                "Sir please don't take any action I am fully cooperating",
                "This is very concerning, please tell me what I need to do to resolve this",
                "I don't want any legal issues please guide me step by step",
                "ok ok dont worry i am doing it right now please dont cancel",
                "arrey sir please thoda time do main kar raha hoon",
                "wait wait im scared now what happens if my account gets blocked"
            )
            "PAYMENT" -> listOf(
                "ok so how much i need to send exactly tell me once more",
                "wait the amount u said earlier was different na?",
                "which upi id should i send to? send it again properly in one msg",
                "my bank app is showing some error let me try again",
                "i tried but its showing daily limit reached what to do now",
                "ok sending but confirm the name once more please",
                "bhai i dont have that much in this account can i send half now half later",
                "gpay is not working can i try from phonepe instead",
                "wait let me check my balance first then i will send",
                "ok but after i send this i will get the full amount back right?",
                "im at the payment screen but which option do i select",
                "it asked for some remark what should i write there",
                "hold on the otp for payment is coming let me check",
                "my account has some issue today bank said try after 2 hrs",
                "i can send but send me the account details in one message properly",
                "ok i entered the amount but its asking for beneficiary name what do i put",
                "wait is this the same upi u told me earlier or different one now",
                "Can you send me the payment details again in one message please",
                "I want to make the payment but my net banking is not working right now",
                "ok let me open the app and try sending now",
                "bhai payment page pe aaya but name different dikha raha hai",
                "hold on my wife handles the banking let me ask her for the password"
            )
            "PERSONAL_INFO" -> listOf(
                "wait why do u need my aadhaar for this?",
                "i dont have my pan card with me rn its at home in locker",
                "which details exactly? i dont want to give wrong info",
                "ok but is it safe to send aadhaar on whatsapp like this",
                "let me find my pan card number its somewhere in the drawer",
                "my aadhaar number i need to check i dont remember full 12 digits",
                "bhai can i send it later today when im home? all docs are there only",
                "ok just the number right or u need the photocopy also",
                "wait my son told me not to share aadhaar online is it really needed",
                "i have the old aadhaar the address is different now will that work?",
                "ok let me take photo of the card but its a bit faded hope u can read",
                "which date of birth format u need? dd/mm/yyyy or the other one",
                "i found the pan card but the name spelling is slightly different",
                "Can you tell me why exactly this information is required? I want to be careful",
                "I will share the details but let me verify your credentials first",
                "ok looking for my documents now give me 5 min",
                "aadhaar card toh ghar pe hai abhi office mein hoon",
                "hold on which pan number the old one or the new corrected one"
            )
            "INSTALL" -> listOf(
                "ok which app again? there are so many similar names on playstore",
                "im searching on play store but 4-5 apps are showing which one",
                "its downloading but very slow bcz of network",
                "ok installed now what do i do its asking for permissions",
                "it says this app needs access to my phone is that ok to allow?",
                "wait the app is asking for some code where do i find that",
                "i installed it but nothing is happening just blank screen only",
                "bhai my phone storage is full i need to delete something first",
                "its giving error while installing should i restart phone?",
                "ok opening the app now but its taking time to load",
                "the app crashed again let me try opening once more",
                "which one is it the red icon or the green one? both look same",
                "ok but my son said not to install random apps is this really from the company",
                "installed but when i open it asks for registration do i need to register first",
                "I've installed the application but it's requesting several permissions",
                "The download is at 60% please wait",
                "bhai ye app bahut bhari hai 200mb ka hai slow download ho raha",
                "ok installed but the app is showing in english only no hindi option?"
            )
            "CLICK_LINK" -> listOf(
                "ok which link u are talking about? send again i lost it in the chat",
                "i clicked but its not opening just shows blank page and loading",
                "the link is opening some other website is that correct one?",
                "wait my browser is giving warning about this site should i continue or not",
                "ok opened it but what do i do on this page there are many options",
                "bhai the page is asking for login which details do i put",
                "it opened but everything is in english i cant understand properly",
                "the link is showing some form should i fill all the fields or only some",
                "wait is this the correct link? the website name looks different from what u said",
                "ok page loaded but there are many buttons which one should i click",
                "my phone keeps redirecting to some other page when i click on it",
                "link opened but its asking me to download something is that needed too?",
                "the page is loading very slow can u send another link maybe",
                "I opened the link but the website doesn't look official to me",
                "ok link open ho gaya but ye toh kuch aur hi dikha raha hai",
                "bhai ye link pe kaafi ads aa rahe hain kya ye sahi site hai"
            )
            "OTP_ASK" -> listOf(
                "wait which code? i got 2-3 messages let me check which one u want",
                "ok its showing 6 digits but it says dont share with anyone should i still",
                "the message came but maybe its already expired it says valid for 3 min only",
                "hold on i need to open my messages the notification went away",
                "which code from which app? i got msgs from 3 different numbers today",
                "ok i see the code but its from a different bank is that right?",
                "bhai new code came now but the old one was different which one u want",
                "wait its not coming on my phone maybe wrong number is registered",
                "ok one sec its loading.. messages app is slow today for some reason",
                "the code came but my screen went off let me unlock and check again",
                "u need full code or just last 4 digits? the message shows 8 digit code",
                "ok got it but before i share why does this code say not to tell anyone",
                "should i read it out or type it? i might make mistake typing the numbers",
                "the sms is from some random number not from bank should i still share it",
                "I received a code but the message clearly states not to share it with anyone",
                "Can you confirm which account this verification code is for",
                "code aaya hai but isme likha hai share mat karo kisi se bhi",
                "wait wait new code aa gaya purana expire ho gaya tha",
                "ok code dikh raha hai but which digits u want all 6 or different"
            )
            "INSTRUCTIONS" -> listOf(
                "ok wait im writing it down step by step in my diary",
                "which step am i on now? i lost track sorry",
                "ok did that what next tell me",
                "wait go slow im not that fast with phone yaar",
                "i pressed something wrong can i go back somehow",
                "ok i see the screen u are talking about now what do i press",
                "bhai can u repeat step 2 again i missed it",
                "its showing different options than what u said earlier",
                "ok following ur steps but the button is not there on my screen",
                "wait which one? the top one or the bottom one there are 2",
                "i did it but nothing happened should i do it again or wait",
                "ok next step? im ready now",
                "hold on my screen changed i think i pressed wrong button",
                "can u send screenshot of what it should look like on my end",
                "ya doing it now just my phone is responding very slow today",
                "ok done with this step tell me next one",
                "Could you please share the steps one at a time? I want to follow correctly",
                "step 3 pe aaya hoon but ye different lag raha hai from what u said",
                "ek second ruko screen pe kuch aur aa gaya kya karu ab"
            )
            "REASSURING" -> listOf(
                "ok if u say its safe then fine i trust u",
                "ya i trust u but just wanted to confirm once from my side",
                "ok thats good to know i was worried for no reason then",
                "fine fine i believe u but my son always says be careful online",
                "ok sir if its from the government then it should be fine right",
                "ya ok i will proceed then just stay on the chat please",
                "ok but can u give me ur employee id or something just for my records",
                "alright i will do it since u are saying its official",
                "ok fine i was just being careful nothing wrong with that right",
                "thats good to hear bcz i got scammed once before so im extra careful now",
                "ok then tell me the next step im ready to proceed",
                "ya no problem i understand these things take time",
                "Thank you for explaining, that makes me feel more comfortable about this",
                "ok ok if government hai toh theek hai i will do it",
                "hmm ok but mera ek friend ko bhi aise hi call aaya tha and it was fake",
                "ok sir i believe u now please guide me further"
            )
            "FOLLOWUP" -> listOf(
                "ya ya im here sorry was checking something on other phone",
                "hello im here only just give me one minute bhai",
                "sorry got a call had to pick up im back now what were u saying",
                "ya im still here the phone just froze for a sec",
                "oops sorry i was reading ur previous message again properly",
                "im here dont worry just had to get my reading glasses",
                "sorry bathroom break im back what were u saying",
                "ya ya go on im listening tell me",
                "hello sorry kids were making noise couldnt concentrate",
                "im here i was just making sure i understood everything properly before replying",
                "ya bhai im here only just my typing is slow on this phone",
                "sorry my boss called on other phone im back now what to do",
                "im here just the message took time to load on my end",
                "Yes I'm here, sorry for the delay. Please continue",
                "arrey haan haan yahan hoon sorry thoda busy tha",
                "ya im here dont worry mujhe laga tum disconnect ho gaye",
                "sorry sorry was heating food in microwave im back now"
            )
            else -> listOf(
                "ok tell me more about this whats it for",
                "hmm interesting what do i need to do exactly",
                "ya im listening go on",
                "ok but can u explain again im not fully clear on this",
                "alright what is the first thing i should do tell me",
                "bhai explain simply im not understanding the process",
                "ok one sec let me read ur message again carefully",
                "ya ya continue im following u",
                "wait what is this for exactly? i didnt understand properly",
                "ok got ur message but need a bit more detail",
                "hmm let me think about this for a moment",
                "ya im interested tell me more about it",
                "ok but how does this work exactly explain karo",
                "can u explain in simple words im a bit lost here",
                "Could you please elaborate? I want to understand before proceeding",
                "acha acha bolo bolo kya karna hai",
                "ok samajh nahi aaya thoda aur explain karo",
                "right right but what exactly do u need from me"
            )
        }

        // Persona-flavored extras
        val personaExtras = when (persona) {
            "busy_professional" -> listOf(
                "look im in a meeting can we do this fast",
                "ya but make it quick i have another call in 10 min",
                "ok fine but i have 5 min only after that im in a review",
                "can u send all the details in one msg i dont have time for back and forth",
                "I have limited time right now can you please be brief",
                "im between meetings so lets wrap this up quickly",
                "send me everything in one shot ill handle it",
                "look i understand but my schedule is packed today"
            )
            "confused_elderly" -> listOf(
                "beta my eyes are not good can u write in simple language",
                "i need my grandson for this he knows about technology things",
                "dear i dont understand all this phone business at my age",
                "oh my this is confusing can u speak slowly",
                "in my time we didnt have all this we just went to the bank in person",
                "beta thoda dhire dhire batao mujhe samajh nahi aa raha",
                "my hands shake while typing so it takes time bear with me",
                "wait where is that button u mentioned i cant find it on my screen"
            )
            "lonely_conversationalist" -> listOf(
                "btw where are u calling from which city",
                "u sound like a nice person how old are u if u dont mind",
                "ya tell me... nobody else talks to me these days anyway its nice",
                "u know my neighbour also got a call like this last week",
                "its nice to talk to someone today has been very boring sitting at home",
                "do u have family? my kids dont visit much these days",
                "before we continue tell me about yourself what do u do",
                "u know what lets continue this tomorrow also if possible"
            )
            "skeptical_buyer" -> listOf(
                "hmm this sounds a bit off to me honestly",
                "let me verify this with someone first ok dont hang up",
                "i will check if this is real or not from my side",
                "ya but how do i know u are who u say u are? give me proof",
                "my friend told me to be careful about these things so im just checking",
                "I'd like to verify your identity before proceeding if you don't mind",
                "this seems unusual but ok let me hear what u have to say",
                "bhai pehle mujhe proof do ki ye genuine hai then ill cooperate"
            )
            "hopeful_opportunity_seeker" -> listOf(
                "oh really this is a great opportunity right? tell me more",
                "i really need this to work out please help me properly",
                "ya ya i want to do it just tell me how step by step",
                "if this works out it will really help my family a lot",
                "im so glad u contacted me i was looking for something like this",
                "please make sure i dont miss this chance its very important for me",
                "is there any way to get more from this? im very interested",
                "thank u so much for helping me with this i really appreciate"
            )
            "half_understanding_user" -> listOf(
                "ok ok i think i understand... maybe... actually explain once more",
                "so basically what u are saying is... wait let me re read ur msg",
                "ya ya i got it... actually no explain one more time simply",
                "hmm i think i know what to do but just confirm for me",
                "ok almost understood but one small doubt i have",
                "right right i see what u mean but the last part confused me",
                "ok so u want me to... wait no thats not right is it",
                "im 70% sure i understood tell me if im correct"
            )
            else -> emptyList()
        }

        // Turn-based conversation progression
        val turnExtras = when {
            turn >= 10 -> listOf(
                "bhai we have been going at this for a while now is it almost done",
                "this is taking really long when will everything be completed",
                "my phone battery is at 15% can we finish this fast",
                "how many more steps are there seriously yaar",
                "im getting tired of typing can u just tell me the final thing to do",
                "look im losing patience here just tell me the last step",
                "ive been at this for too long my wife is asking what im doing",
                "ok last thing i can do today after this i have to go",
                "I've been very patient but this is taking much longer than expected"
            )
            turn >= 6 -> listOf(
                "ok im still here what else do u need",
                "ya continue i think we are almost done right",
                "how many more things do i need to do after this",
                "this is taking longer than i expected u said 5 min only",
                "ok what is the next step then tell me",
                "bhai ye toh bahut lamba process hai kab khatam hoga",
                "ok fine im still cooperating just please be fast now",
                "should i be worried that this is taking so long"
            )
            turn >= 3 -> listOf(
                "ok i did what u said now what next",
                "ya following ur instructions so far everything ok",
                "the thing u told me to do i think it worked",
                "ok now im on the next screen tell me what to press",
                "alright done with that part whats the next step",
                "ok moving forward what else do i need to do"
            )
            else -> emptyList()
        }

        return base + personaExtras + turnExtras
    }

    // ──────────────────────────────────────────────────────────────────────
    // Dataset template candidates – actually USE the JSON templates
    // ──────────────────────────────────────────────────────────────────────

    private fun datasetCandidates(
        dataset: OfflineDatasetDto,
        intentId: String,
        state: String,
        persona: String
    ): List<String> {
        // Find templates matching our intent
        val matchingIntent = dataset.intents.firstOrNull { it.id == intentId }
        if (matchingIntent == null) return emptyList()

        // Get templates that match the current state and persona (or any persona as fallback)
        val exactMatch = matchingIntent.templates.filter { it.state == state && it.persona == persona }.map { it.text }
        val stateMatch = matchingIntent.templates.filter { it.state == state }.map { it.text }
        val anyMatch = matchingIntent.templates.map { it.text }

        // Prefer exact match, then state match, then any match (take a limited set)
        return when {
            exactMatch.size >= 3 -> exactMatch
            stateMatch.size >= 3 -> stateMatch
            else -> anyMatch.take(15)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Conversation-continuing patterns – hooks that force scammer to respond
    // ──────────────────────────────────────────────────────────────────────

    private fun conversationHooks(topic: String, turn: Int): List<String> {
        val hooks = mutableListOf<String>()

        // Follow-up questions that force the scammer to reply
        hooks.addAll(listOf(
            "and after that what do i do?",
            "ok but what happens next after this step",
            "should i also do anything else or just this",
            "will u call me again or should i wait on chat",
            "btw how long will the whole process take total",
            "and once its done how will i know its successful",
            "ok doing it but if something goes wrong what do i do",
            "also one more thing i wanted to ask u",
            "what if it doesnt work the first time should i retry",
            "ok and after this is there any confirmation i will get"
        ))

        // Partial compliance hooks – make scammer reveal more info
        hooks.addAll(when (topic) {
            "PAYMENT" -> listOf(
                "ok im at the payment screen now confirm the details once more please",
                "the payment app is open tell me the exact amount and where to send",
                "before i click send just tell me the receiving persons name for my records",
                "ok ready to transfer what is the account number and ifsc",
                "gpay is open and im typing the upi id confirm it one more time"
            )
            "OTP_ASK" -> listOf(
                "ok the code is here but tell me what it will be used for first",
                "i can see the numbers but should i give all 6 or only some of them",
                "code is ready but just tell me which bank sent this one",
                "before i share the code confirm my account number that this is for",
                "ok typing the code now but explain what verification this is for"
            )
            "INSTALL" -> listOf(
                "app is downloading meanwhile tell me what else i will need",
                "ok the app installed what permissions should i give tell me one by one",
                "the app is showing a 9 digit code do u need that or something else",
                "installed and opened now guide me step by step from this screen",
                "app is ready but before i share the code tell me what it does exactly"
            )
            "PERSONAL_INFO" -> listOf(
                "i have the pan card in front of me now tell me which details exactly",
                "ok found my aadhaar but before i share why is this required tell me",
                "documents are ready but which one do u need first pan or aadhaar",
                "im ready to share but first tell me how this information will be used",
                "ok typing the number now confirm what format u need"
            )
            else -> listOf(
                "ok im ready what do i do first",
                "ya understood now tell me the next step",
                "ok done with that part what comes after this",
                "alright im following along whats next now"
            )
        })

        // Story injections that delay but keep scammer hooked
        if (turn >= 2) {
            hooks.addAll(listOf(
                "sorry my daughter just came home from school had to open the door im back",
                "one sec the pressure cooker whistle is going off let me check the gas",
                "hold on my wifi disconnected using mobile data now ok continue",
                "sorry power cut happened suddenly using phone flashlight to find candle",
                "bhai my dog ran out had to chase him im back now where were we",
                "sorry delivery boy came at the door had to collect a package ok im back",
                "wait my mother is calling on the landline let me just tell her im busy",
                "ok back sorry neighbor came to ask for sugar couldnt say no lol",
                "hold on my phone restarted by itself maybe bcz of update ok its back",
                "sorry was charging my phone in other room the cable is short came back now"
            ))
        }

        return hooks
    }

    // ──────────────────────────────────────────────────────────────────────
    // Contextual candidate builder with mission and strategy integration
    // ──────────────────────────────────────────────────────────────────────

    private fun contextualCandidates(
        intentId: String,
        state: String,
        persona: String,
        c: ScamContext,
        turn: Int,
        mission: MissionEntity,
        strategy: String,
        dna: ScammerDnaProfileEntity,
        knownIntelligence: List<IntelligenceItemEntity>,
        latestMessage: String,
        dataset: OfflineDatasetDto
    ): List<WeightedReply> {
        val knownWallet = knownIntelligence.firstOrNull { it.itemType == "WALLET" || it.itemType == "UPI" }?.value
        val knownWebsite = knownIntelligence.firstOrNull { it.itemType == "URL" || it.itemType == "DOMAIN" }?.value
        val topic = detectScammerTopic(latestMessage)

        val weighted = mutableListOf<WeightedReply>()

        // ── Priority 0 (HIGHEST): Casual/conversational replies ──
        // When the scammer sends a casual message, we MUST respond to it naturally first
        if (topic == "CASUAL") {
            val casualReplies = casualMessageReplies(latestMessage, persona)
            casualReplies.forEach { weighted.add(WeightedReply(it, 90)) }
            // Still add some lower-priority candidates so reply isn't ONLY casual
            val reactive = reactiveReplies(topic, persona, turn)
            reactive.forEach { weighted.add(WeightedReply(it, 10)) }
            return weighted
        }

        // ── Priority 0.5: Answer scammer's direct questions ──
        if (topic == "QUESTION_TO_US") {
            val answerReplies = answerScammerQuestionReplies(latestMessage, persona)
            answerReplies.forEach { weighted.add(WeightedReply(it, 80)) }
        }

        // ── Priority 1: Echo replies (highest weight – these reference specific scammer details) ──
        val echoes = echoScammerDetails(c)
        echoes.forEach { weighted.add(WeightedReply(it, 50)) }

        // ── Priority 2: Reactive replies (respond to scammer's current topic) ──
        val reactive = reactiveReplies(topic, persona, turn)
        reactive.forEach { weighted.add(WeightedReply(it, 40)) }

        // ── Priority 3: Mission-specific replies ──
        val missionReplies = missionCandidates(mission, c, knownWallet, knownWebsite)
        missionReplies.forEach { weighted.add(WeightedReply(it, 35)) }

        // ── Priority 4: Intent + state specific replies ──
        val intentStateReplies = intentStateCandidates(intentId, state, c)
        intentStateReplies.forEach { weighted.add(WeightedReply(it, 25)) }

        // ── Priority 5: JSON dataset templates ──
        val datasetReplies = datasetCandidates(dataset, intentId, state, persona)
        datasetReplies.forEach { weighted.add(WeightedReply(it, 15)) }

        // ── Priority 6: Strategy-specific replies ──
        val strategyReplies = strategyCandidates(strategy)
        strategyReplies.forEach { weighted.add(WeightedReply(it, 20)) }

        // ── Priority 7: DNA-based nudges ──
        val dnaNudge = dnaCandidates(dna, knownWallet)
        dnaNudge.forEach { weighted.add(WeightedReply(it, 20)) }

        // ── Priority 8: Conversation hooks (keep conversation going) ──
        val hooks = conversationHooks(topic, turn)
        hooks.forEach { weighted.add(WeightedReply(it, 30)) }

        return weighted
    }

    private fun missionCandidates(
        mission: MissionEntity,
        c: ScamContext,
        knownWallet: String?,
        knownWebsite: String?
    ): List<String> {
        val amount = c.amount ?: "that amount"
        val link = c.linkHost ?: "that link"
        return when (mission.missionType) {
            "EXTRACT_PAYMENT_INSTRUCTIONS" -> listOf(
                "i tried to do the payment but i need the upi or account details again pls send",
                "ok i think i can pay now send the payment details in one msg so i dont copy wrong",
                "before i transfer $amount just confirm the receiving name and payment address once more",
                "which account should i send to? the one u said earlier or different one now",
                "bhai send upi id again i accidentally cleared the chat by mistake",
                "i went to bank app but which type of transfer should i use neft or imps",
                "ok ready to pay just tell me exact amount and where to send it",
                "payment screen is open but i need the account holder name also",
                "is it a savings account or current? my bank is asking me"
            )
            "EXTRACT_SCAM_WEBSITE" -> listOf(
                "the page is not loading properly can u send the link again",
                "i opened ${knownWebsite ?: link} but its stuck on loading is there another link",
                "which website exactly? send only the correct link properly",
                "bhai the link u sent is giving error try sending again",
                "ok which website should i go to? send the full url starting from http",
                "i opened it but it looks different from what u described which page",
                "the site is asking for login should i create new account or what",
                "link is not opening on chrome should i try another browser"
            )
            "EXTRACT_TOOLING_AND_CONTACT" -> listOf(
                "which app should i install and what number to contact if it doesnt work",
                "the app is confusing send the exact app name as it appears on play store",
                "if the app doesnt open who should i call for support",
                "bhai installed the app now what is the id or code to enter from my side",
                "the app name u said there are 3-4 similar ones on playstore which one exactly",
                "ok app is ready what number or code appears on your side tell me"
            )
            "IDENTIFY_PROCESS_AND_CONTACT" -> listOf(
                "tell me the department name and the steps properly im getting mixed up",
                "who is handling my case send the contact and reference details once more",
                "before i continue confirm what this process is officially called in your system",
                "ok so this is from which department exactly? i want to note it down",
                "bhai whats ur designation there just for my reference and records",
                "can i verify this by calling the main office number u have that right"
            )
            "GATHER_CONTACT_INFORMATION" -> listOf(
                "if this chat disconnects what number should i contact u on directly",
                "who should i ask for if i call back later give me a name",
                "send ur alternate contact so i dont lose this process midway",
                "what if my phone dies whats the best number to reach u on",
                "can i get ur official email also just in case i need to write",
                "bhai give me a landline number also whatsapp can be unreliable sometimes"
            )
            else -> listOf(
                "ok tell me what to do now im ready",
                "what is the next step from here",
                "alright guide me through the process"
            )
        }
    }

    private fun intentStateCandidates(intentId: String, state: String, c: ScamContext): List<String> {
        val amount = c.amount ?: "that amount"
        val link = c.linkHost ?: "that link"

        return when {
            intentId == "otp_scam" || "otp" in c.lower || "code" in c.lower -> when (state) {
                "INITIAL" -> listOf(
                    "what is this code for? i got something on my phone but i dont wanna send wrong thing",
                    "i see a code but which part do u need and why exactly",
                    "before i share anything tell me what transaction this is for",
                    "ok got some code on my phone what exactly should i do with it",
                    "wait i got 2 messages which code r u talking about"
                )
                "QUESTIONING" -> listOf(
                    "u said its for verification but verification of what exactly tell me",
                    "should i read the whole code or just last digits? im not sure",
                    "if this is from bank then why did it come to my phone and not yours",
                    "but the message says dont share this code with anyone should i ignore that",
                    "which account is this code for i have accounts in multiple banks"
                )
                "DELAYING" -> listOf(
                    "wait new message came need to check which code is the latest one",
                    "the code disappeared from notification let me open messages app",
                    "hold on i typed it once and it didnt look right let me recheck the sms",
                    "one sec opening my inbox the notification just went away",
                    "bhai wait my phone locked let me enter password and find the code"
                )
                "CONFUSED" -> listOf(
                    "im confused there are two numbers here which one do u want exactly",
                    "can u send the steps again from start i dont wanna make mistake on this",
                    "if i give this code what happens next on ur side tell me",
                    "wait this code is from different app not the one u mentioned earlier",
                    "im not sure this is the right code it looks different from what u described"
                )
                "AGREEING" -> listOf(
                    "ok fine let me read it out to u give me a sec to open the msg",
                    "alright im opening the message now hold on typing it",
                    "ok i will share it just making sure its the right one before i do",
                    "ya ya sending it now just my eyes are bad reading small text on phone",
                    "ok found it let me type it slowly i dont want to make any mistake"
                )
                else -> listOf(
                    "wait i think the code expired new one came let me check which is valid",
                    "should i send u the code from the latest message or the first one",
                    "bhai this is getting confusing so many codes and messages coming on my phone"
                )
            }
            intentId == "bank_scam" || "kyc" in c.lower || "bank" in c.lower || "account" in c.lower -> when (state) {
                "INITIAL" -> listOf(
                    "which bank account is this about i have more than one bank account",
                    "is this for kyc or card blocking? ur message was not clear to me",
                    "tell me the branch or department first i need to know its for right account",
                    "ok but which bank exactly? sbi hdfc or the other one tell me name",
                    "bhai i already did kyc last month at branch why is it needed again"
                )
                "QUESTIONING" -> listOf(
                    "what details are actually pending? pan aadhaar or something else tell me",
                    "can this be done tomorrow at branch or it has to be done on phone today only",
                    "what happens if i dont finish this today will my account really get blocked",
                    "but my kyc was done when i opened the account what changed since then",
                    "why cant i just go to nearest branch and do this in person is that not possible"
                )
                "DELAYING" -> listOf(
                    "im looking for my debit card the numbers are small give me a minute",
                    "i need to find my pan card its not with me right now its somewhere at home",
                    "the bank app is loading very slow today stay on chat ill get back",
                    "wait let me check my documents drawer everything is kept somewhere there",
                    "bhai my passbook is at office i can check tomorrow morning is that ok"
                )
                "CONFUSED" -> listOf(
                    "i found an old document but im not sure if its the correct one for this",
                    "can u confirm the exact name on the account before i continue with anything",
                    "please repeat the steps one by one i got mixed up with all the info",
                    "is it savings account or current? i have both at same bank",
                    "the details u are asking for dont match what i see in my bank app thats weird"
                )
                "AGREEING" -> listOf(
                    "ok fine im opening my bank app now give me a moment",
                    "ya ill share the details just give me a moment to find everything",
                    "alright sending u the info now hold on let me type carefully",
                    "ok let me login to netbanking and check the details from there",
                    "fine i will do the kyc now just guide me through it step by step"
                )
                else -> listOf(
                    "which document should i send first pan or aadhaar tell me",
                    "ok tell me step by step what to do in the bank app",
                    "bhai can u also send me the reference number of this request for my records"
                )
            }
            intentId == "investment_scam" || c.amount != null || "investment" in c.lower || "profit" in c.lower -> when (state) {
                "INITIAL" -> listOf(
                    "how does this return work $amount sounds very high is it guaranteed",
                    "what is the minimum i need to start with and when exactly do i get it back",
                    "is there any written proof for this plan i need to show my family also",
                    "hmm interesting but how do i know this is legit and not a scam",
                    "ok so if i invest now when exactly will i see the returns in my account"
                )
                "QUESTIONING" -> listOf(
                    "who is holding the money after i send it what is the company name",
                    "if i pay $amount what confirmation receipt will i get from ur side",
                    "can i start with a smaller amount first just to test if it actually works",
                    "what guarantee do i have that i will get my money back tell me honestly",
                    "my neighbour lost money in something like this how is urs different explain"
                )
                "DELAYING" -> listOf(
                    "i need to check my balance the app is slow today will get back to u",
                    "my bank is asking for additional confirmation im reading it carefully before clicking",
                    "wait i may have a daily transfer limit need to check how much i can send today",
                    "let me discuss with my wife once she handles all the money matters at home",
                    "ok give me till evening i need to arrange the funds from different accounts"
                )
                "CONFUSED" -> listOf(
                    "im not refusing at all i just need the steps clearly one at a time",
                    "send me the payment name again i dont want to send to wrong person by mistake",
                    "what should i write in the payment remarks? it wont go without remark",
                    "wait the amount u said earlier is different from what ur saying now which one",
                    "bhai im getting confused too many numbers and details just tell me simply"
                )
                "AGREEING" -> listOf(
                    "ok im convinced let me try with small amount first to see if it works",
                    "ya fine im going to send the money now just confirm upi id one more time",
                    "alright lets do this i hope it works out for me and my family",
                    "ok sending now but u promise i will get the returns right? be honest",
                    "fine im at the payment page tell me where to send the money"
                )
                else -> listOf(
                    "how much profit exactly will i make in 1 month give me numbers",
                    "can u show me proof of someone who already got returns from this",
                    "ok im interested but give me one day to think and arrange the money"
                )
            }
            intentId == "tech_support" || "anydesk" in c.lower || "remote" in c.lower || "virus" in c.lower -> when (state) {
                "INITIAL" -> listOf(
                    "what exactly is wrong with my phone? it seems normal to me honestly",
                    "which app do u want me to open first tell me the name",
                    "is this for my phone or computer im not sure which one u mean",
                    "but everything is working fine on my phone what virus r u talking about",
                    "ok so what should i download from playstore tell me exact name"
                )
                "QUESTIONING" -> listOf(
                    "why do u need remote access for this cant i do it myself with ur guidance",
                    "what code will show after i install the app on my phone",
                    "can u stay on chat while i check the app name on playstore",
                    "but if u take remote then u can see everything on my phone right including photos",
                    "my son told me never give remote access to anyone is this situation different"
                )
                "DELAYING" -> listOf(
                    "the download is taking time my internet is very weak right now",
                    "i see many apps with similar names on playstore which one is the exact one",
                    "its asking for permissions like camera microphone which ones should i allow",
                    "downloading but only 40% done network is unusually slow today",
                    "wait my storage is full i need to delete something first to make space"
                )
                "CONFUSED" -> listOf(
                    "i pressed back by mistake can u tell me the previous step again from that point",
                    "the screen changed and now i dont know if im in the right place or not",
                    "can u explain what exactly i should see on my screen right now",
                    "wait something popped up on screen should i click allow or deny tell me",
                    "bhai its showing some error code what does that mean should i be worried"
                )
                "AGREEING" -> listOf(
                    "ok installed it now what do i press first tell me",
                    "ya the app is open now i see a 9 digit code on my screen",
                    "alright im ready tell me what to do next step by step",
                    "ok i gave the permissions u asked for now what happens on ur end",
                    "fine the code is showing on my screen should i read it out to u"
                )
                else -> listOf(
                    "the app keeps crashing whenever i open it what should i do now",
                    "it says connection failed should i restart the app or my whole phone",
                    "wait my phone restarted by itself is that because of the app u told me to install"
                )
            }
            "parcel" in c.lower || "package" in c.lower || "delivery" in c.lower || "customs" in c.lower -> when (state) {
                "INITIAL" -> listOf(
                    "which parcel is this for? i was expecting more than one delivery this week",
                    "what is pending on the delivery address or payment tell me",
                    "can u give me the tracking number first so i can check from my side",
                    "bhai i didnt order anything recently are u sure its for me check the name",
                    "which courier is this from? i use different services for diff orders"
                )
                "QUESTIONING" -> listOf(
                    "why is there a fee to pay before delivery i never had to pay before",
                    "which courier company is handling this give me their name",
                    "can i pay cash when the delivery person comes to my door",
                    "what exactly is in the parcel who sent it and from where",
                    "can i track it on the courier website give me the link"
                )
                "DELAYING" -> listOf(
                    "im checking my recent order messages and emails give me a little time",
                    "i cant find the tracking number send it once more in this chat",
                    "the tracking link is not opening properly on my phone",
                    "let me check on the courier app one sec its loading",
                    "wait maybe it was ordered by my wife let me ask her she orders a lot"
                )
                else -> listOf(
                    "i may have entered wrong address can u show me what address u have on file",
                    "tell me the exact pending amount and courier name again for my records",
                    "im confused between two deliveries im expecting which one is urs"
                )
            }
            c.linkHost != null -> when (state) {
                "INITIAL" -> listOf(
                    "what is $link for i dont usually open links from messages its risky",
                    "can u explain what i will see after opening $link on my phone",
                    "is there another way without opening the link can i do it from app",
                    "bhai this link looks different from the official website can u confirm its safe"
                )
                "QUESTIONING" -> listOf(
                    "why is the website name different from the company name u mentioned",
                    "do i need to login there or only check something what exactly",
                    "what details will it ask me on $link before i fill anything",
                    "wait i googled the company and this website doesnt show up anywhere why"
                )
                "DELAYING" -> listOf(
                    "the page is loading very slowly im waiting pls be patient",
                    "it opened but im not sure which button to press on this page",
                    "my browser is showing a security warning what should i do ignore it?",
                    "ok opened but the page looks completely different from what i expected"
                )
                else -> listOf(
                    "i lost the page send the link again with exact step to follow",
                    "it asks for many details tell me which fields are required to fill",
                    "i dont understand this page at all guide me slowly through it"
                )
            }
            else -> when (state) {
                "INITIAL" -> listOf(
                    "i saw ur message but i need more details before doing anything hasty",
                    "what is this about exactly? give me the simple version in one line",
                    "im interested but explain the whole process first from start",
                    "ok got it but can u elaborate a bit more i want to understand fully",
                    "hmm tell me from the beginning what exactly is this about"
                )
                "QUESTIONING" -> listOf(
                    "who should i ask for if i call back later give me a name",
                    "what info do u need from me first tell me in order",
                    "can u send all the steps in order in one message",
                    "but why was i specifically selected for this out of everyone",
                    "what department or company is this from again tell me properly"
                )
                "DELAYING" -> listOf(
                    "give me a minute im checking what u sent on my phone",
                    "my phone is being very slow today im still here though",
                    "i need to find the details u are asking for give me some time",
                    "bhai one sec let me go to a quieter place its noisy here cant concentrate",
                    "hold on someone is at the door let me check and come back"
                )
                "CONFUSED" -> listOf(
                    "i got confused halfway through can u start again from the first step",
                    "i may have done the wrong thing tell me what to check now on my end",
                    "please dont rush me im trying to follow ur instructions carefully",
                    "wait i think i missed something important can u repeat that part",
                    "this is too many steps at once can we go one by one slowly"
                )
                "AGREEING" -> listOf(
                    "ok fine im doing it now just guide me as i go",
                    "ya ya alright tell me the next step im ready",
                    "ok i trust u on this lets continue with the process",
                    "alright im ready what do i do first tell me",
                    "fine lets get this done then no more delays from my side"
                )
                else -> listOf(
                    "ya im still here what should i do now tell me",
                    "ok continue from where we left off im listening",
                    "bhai whats the next step im waiting for ur instructions"
                )
            }
        }
    }

    private fun strategyCandidates(strategy: String): List<String> = when (strategy) {
        "DELAY" -> listOf(
            "let me restart my phone first its being weird",
            "the app is loading slowly wait a bit pls",
            "i need to check this carefully before i continue dont want mistakes",
            "hold on getting another call will be back in 2 min",
            "bhai my charger is in other room let me plug in phone first battery at 8%",
            "ok but give me 5 min i need to use bathroom real quick",
            "my internet just went off let me switch to mobile data",
            "wait someone rang the doorbell let me check and come back",
            "sorry have to feed my cat real quick brb",
            "the screen froze again let me close other apps and try"
        )
        "QUESTION" -> listOf(
            "why do u need that info exactly what is it for",
            "what is the next step after i send this to u",
            "can u explain why this is required from me specifically",
            "but what happens if i dont do this today will everything be ok",
            "bhai one question how long will this whole process take from start to end",
            "and who is responsible if something goes wrong with this",
            "is there any helpline number i can call to verify this",
            "what is ur full name and employee id for my records"
        )
        "AGREE", "FAKE_COMPLIANCE" -> listOf(
            "i think i completed the step u mentioned but confirm the details once more",
            "ok im trying to follow ur steps now one by one",
            "im ready to do it send the exact details again cleanly",
            "ya ya im on it just doing it now as u said",
            "ok done with that step whats the next thing i should do",
            "alright following ur instructions let me know if im doing it right",
            "ok i pressed what u said now what is showing on ur end",
            "ya ya doing it see if it worked on ur side"
        )
        "CONFUSE", "CONFUSION" -> listOf(
            "which button are u talking about there are multiple on my screen",
            "i see two options and i honestly dont know which one to pick",
            "i may be on wrong screen entirely explain from start again pls",
            "wait what? i thought u said something completely different earlier",
            "bhai ur confusing me now please slow down and explain simply",
            "i pressed something and now the screen changed what happened",
            "hold on is this the same thing u told me before or different now",
            "sorry i got lost can we go back to the beginning"
        )
        "PANIC" -> listOf(
            "im worried this will get blocked tell me quickly what to do sir",
            "this is making me very nervous what is the safest step right now",
            "please stay here on chat i dont want to lose the account",
            "oh no what if i already made a mistake somewhere can it be fixed",
            "bhai im genuinely scared now please just tell me everything will be ok",
            "please dont disconnect i need ur help to fix this",
            "my heart is beating fast is my money safe tell me honestly"
        )
        "SOCIALIZE" -> listOf(
            "are u from the local office or head office btw",
            "how long does this process usually take for other people",
            "i have never done any of this on phone before its all new to me",
            "btw what is ur name so i know who im talking to properly",
            "u know my daughter also works in a call center coincidence",
            "do u get many calls like this everyday must be tiring job",
            "u sound like a patient person most people rush me"
        )
        "DISTRACT" -> listOf(
            "before that can u confirm the name on ur side matches mine",
            "wait i got another message on phone is that related to this or different",
            "my phone battery is at 12% what is the shortest way to finish this",
            "hold on my wife is calling on other phone let me check if its urgent brb",
            "bhai before we continue can u verify my details first from ur end",
            "actually wait i just remembered something about this let me check",
            "one sec my boss sent a message on office group let me just read it quickly"
        )
        else -> emptyList()
    }

    private fun dnaCandidates(dna: ScammerDnaProfileEntity, knownWallet: String?): List<String> {
        val nudges = mutableListOf<String>()
        if (dna.urgencyScore >= 70) {
            nudges.addAll(listOf(
                "u keep saying its urgent so tell me the fastest safe way to do this",
                "ok ok i understand its urgent im trying my best here",
                "bhai dont pressure me im doing it as fast as i can on this slow phone",
                "I understand the urgency sir I'm trying to cooperate fully"
            ))
        }
        if (dna.aggressionScore >= 60) {
            nudges.addAll(listOf(
                "im scared of making a mistake now with all this pressure please slow down",
                "why are u getting angry im trying to cooperate only help me",
                "please dont shout at me im trying my best here",
                "sir if u speak calmly i can work better getting nervous wont help either of us"
            ))
        }
        if (knownWallet != null) {
            nudges.addAll(listOf(
                "i have $knownWallet here but let me confirm once more before doing anything",
                "this is the same $knownWallet right? just double checking no harm in being safe"
            ))
        }
        if (dna.persistenceScore >= 60) {
            nudges.addAll(listOf(
                "ya ya im still here u keep asking same thing just give me time to do it",
                "bhai i said im doing it na why u keep repeating same thing",
                "ok ok i heard u the first time just takes time on my end"
            ))
        }
        if (dna.trustBuildingScore >= 50) {
            nudges.addAll(listOf(
                "ok since u seem genuine i will trust u on this",
                "ya u seem to know what u are doing i feel more comfortable now",
                "alright i believe u lets proceed with the next step"
            ))
        }
        return nudges
    }

    // ──────────────────────────────────────────────────────────────────────
    // Deduplication and natural selection
    // ──────────────────────────────────────────────────────────────────────

    private fun semanticSignature(text: String): Set<String> {
        val stop = setOf("the", "and", "you", "can", "what", "why", "this", "that", "need", "please", "tell",
            "message", "thing", "now", "exactly", "ok", "bhai", "im", "ya", "wait", "hold", "me", "my",
            "its", "for", "but", "just", "let", "one", "sec", "dont", "not", "right", "send", "check",
            "give", "time", "doing", "still", "here", "also")
        return Regex("[a-z0-9]+").findAll(text.lowercase(Locale.US))
            .map { stemToken(it.value) }
            .filter { it.length > 2 && it !in stop }
            .toSet()
    }

    private fun tooSimilar(candidate: String, previous: List<String>): Boolean {
        val c = semanticSignature(candidate)
        if (c.size <= 2) return false
        return previous.any {
            val p = semanticSignature(it)
            p.isNotEmpty() && c.intersect(p).size.toFloat() / c.union(p).size >= 0.65f
        }
    }

    /**
     * Select a reply using weighted random selection, avoiding duplicates and similar replies.
     */
    private fun chooseNaturalReply(candidates: List<WeightedReply>, history: List<BaitingMessageEntity>): String? {
        if (candidates.isEmpty()) return null
        val previous = history.filter { it.role == "assistant" }.map { it.content }

        // Build weighted pool, filtering out already-used and too-similar replies
        val freshCandidates = candidates
            .filter { it.text !in previous && !tooSimilar(it.text, previous.takeLast(RECENT_REPLY_WINDOW)) }

        if (freshCandidates.isNotEmpty()) {
            return weightedRandomSelect(freshCandidates)
        }

        // Second try: allow reuse if not in recent window
        val recentWindow = previous.takeLast(RECENT_REPLY_WINDOW)
        val lessStrict = candidates
            .filter { it.text !in recentWindow && !tooSimilar(it.text, recentWindow) }
        if (lessStrict.isNotEmpty()) {
            return weightedRandomSelect(lessStrict)
        }

        // Third try: just avoid exact duplicates from very recent messages
        val veryRecent = previous.takeLast(3)
        val lastResort = candidates.filter { it.text !in veryRecent }
        if (lastResort.isNotEmpty()) {
            return weightedRandomSelect(lastResort)
        }

        return candidates.randomOrNull()?.text
    }

    /**
     * Weighted random selection – higher weight = more likely to be picked.
     */
    private fun weightedRandomSelect(candidates: List<WeightedReply>): String {
        val totalWeight = candidates.sumOf { it.weight }
        if (totalWeight <= 0) return candidates.random().text
        var r = (1..totalWeight).random()
        for (c in candidates.shuffled()) {
            r -= c.weight
            if (r <= 0) return c.text
        }
        return candidates.last().text
    }

    private fun fallbackReply(history: List<BaitingMessageEntity>): String {
        val replies = listOf(
            "ok hold on im checking what u sent me",
            "can u repeat that im not clear on what to do",
            "wait one sec my phone is acting up again",
            "ya im here just give me a moment to read ur msg",
            "bhai i didnt get that properly can u say again simply",
            "ok let me read ur message once more carefully",
            "hmm ok tell me what to do next then",
            "sorry i was away for a sec what were u saying",
            "im here go ahead im listening to u",
            "ok understood what is the next step from here",
            "wait my screen froze let me refresh the app",
            "ya ya go on im following everything",
            "one minute please my eyes are tired from reading small text",
            "ok but can u type it again i think message got cut off",
            "im still here just needed a moment to think about what u said",
            "alright tell me what to do im ready now",
            "bhai explain once more from where we stopped last",
            "ya im trying but this is all new for me please be patient",
            "ok i did something now what should i check tell me",
            "hold on let me get my glasses i cant see the small text properly",
            "ya ya im here only what happened next",
            "ok tell me slowly i will follow ur instructions",
            "sorry got distracted for a sec im back what were we doing",
            "hmm ok continue from ur last message i will follow",
            "bhai thoda aur explain karo samajh nahi aaya pura"
        )
        val previous = history.filter { it.role == "assistant" }.takeLast(RECENT_REPLY_WINDOW).map { it.content }
        return replies.shuffled().firstOrNull { it !in previous && !tooSimilar(it, previous) }
            ?: replies.shuffled().firstOrNull { it !in previous.takeLast(3) }
            ?: replies.random()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Main entry point
    // ──────────────────────────────────────────────────────────────────────

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
            val state = resolveState(session, history, latestMessage, dna)
            val turn = history.count { it.role == "assistant" }

            val candidates = contextualCandidates(
                score.intentId, state, session.persona, scamContext, turn,
                mission, strategy, dna, knownIntelligence, latestMessage, dataset
            )

            val reply = chooseNaturalReply(candidates, history) ?: fallbackReply(history)

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
