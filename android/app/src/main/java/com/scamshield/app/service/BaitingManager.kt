package com.scamshield.app.service

import android.app.PendingIntent
import android.app.RemoteInput
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.scamshield.app.BuildConfig
import com.scamshield.app.data.local.dao.BaitingDao
import com.scamshield.app.data.local.dao.IntelligenceDao
import com.scamshield.app.data.local.entity.IntelligenceItemEntity
import com.scamshield.app.data.local.entity.MissionEntity
import com.scamshield.app.data.local.entity.ScammerDnaProfileEntity
import com.scamshield.app.data.local.entity.BaitingMessageEntity
import com.scamshield.app.data.local.entity.BaitingSessionEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.scamshield.app.service.providers.CloudReplyProvider
import com.scamshield.app.detection.OfflineReplyEngine
import com.scamshield.app.intelligence.MissionPlanner
import com.scamshield.app.intelligence.EngagementEffectivenessEngine
import com.scamshield.app.intelligence.ScammerDnaEngine
import com.scamshield.app.intelligence.StrategyPlanner
import com.scamshield.app.intelligence.StrategyOutcomeTracker
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

@Singleton
class BaitingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val baitingDao: BaitingDao,
    private val intelligenceDao: IntelligenceDao,
    private val cloudReplyProvider: CloudReplyProvider,
    private val offlineReplyEngine: OfflineReplyEngine,
    private val scammerDnaEngine: ScammerDnaEngine,
    private val missionPlanner: MissionPlanner,
    private val strategyPlanner: StrategyPlanner,
    private val engagementEffectivenessEngine: EngagementEffectivenessEngine,
    private val strategyOutcomeTracker: StrategyOutcomeTracker
) {
    companion object {
        private const val TAG = "BaitingManager"

        /**
         * Default response delay in seconds. Used when the user hasn't changed the setting.
         * Higher = more human-like (scammer won't suspect AI). Lower = faster for testing.
         */
        private const val DEFAULT_RESPONSE_DELAY_SEC = 10

        private const val MIN_TYPING_DELAY_MS = 2000L
        private const val MAX_TYPING_DELAY_MS = 25000L
        private const val CHAR_DELAY_MS = 55L // ~55ms per character (human typing speed)
        /** Lower bound allows ~1s server-guided pauses between bubbles. */
        private const val MIN_REPLY_DELAY_MS = 900L
        /** Server can suggest up to ~12s pauses between bubbles. */
        private const val MAX_REPLY_DELAY_MS = 13000L
    }

    /**
     * Dynamic reply time multiplier derived from the user's "Response Delay" setting.
     *
     * - 0s  → 0.0  (instant, no delays — testing mode)
     * - 1s  → 0.1  (near-instant — fast demo)
     * - 5s  → 0.5  (moderate)
     * - 10s → 1.0  (default, human-like)
     * - 20s → 2.0  (extra slow, very cautious human)
     * - 30s → 3.0  (maximum realism)
     */
    private val replyTimeMultiplier: Float
        get() {
            val useDynamic = prefs.getBoolean("use_dynamic_delay", true)
            if (!useDynamic) return 1f
            
            val delaySec = prefs.getInt("response_delay", DEFAULT_RESPONSE_DELAY_SEC)
            return delaySec.toFloat() / DEFAULT_RESPONSE_DELAY_SEC.toFloat()
        }

    /** Applies dynamic [replyTimeMultiplier] without changing any other timing logic. */
    private fun scaleReplyDelayMs(ms: Long): Long =
        (ms.toDouble() * replyTimeMultiplier).toLong().coerceAtLeast(0L)

    // In-memory cache of the latest RemoteInput and PendingIntent for auto-replying
    data class ReplyAction(val intent: PendingIntent, val remoteInput: RemoteInput, val packageName: String)
    private val activeReplyActions = ConcurrentHashMap<String, ReplyAction>()
    private val activeReadActions = ConcurrentHashMap<String, PendingIntent>()
    private val senderMutexes = ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    private val lastPackageForSender = ConcurrentHashMap<String, String>()

    private val scope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.IO +
            CoroutineExceptionHandler { _, t ->
                Log.e(TAG, "Baiting worker failed", t)
            }
    )
    
    private val _engineStatus = MutableStateFlow(EngineStatus.ONLINE)
    val engineStatus: StateFlow<EngineStatus> = _engineStatus.asStateFlow()
    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    init {
        startNetworkStatusMonitor()
    }

    // Current Persona (read from SharedPreferences dynamically)
    private val currentPersona: String
        get() = context.getSharedPreferences("scamshield_prefs", 0)
            .getString("active_persona", "busy_professional") ?: "busy_professional"

    // Current prefs reader
    private val prefs get() = context.getSharedPreferences("scamshield_prefs", 0)

    fun updateLastPackage(sender: String, packageName: String) {
        val senderKey = canonicalSenderId(sender)
        lastPackageForSender[senderKey] = packageName
    }

    /**
     * Cache the latest notification reply action for a sender.
     */
    fun cacheReplyAction(sender: String, packageName: String, replyIntent: PendingIntent, remoteInput: RemoteInput) {
        val senderKey = canonicalSenderId(sender)
        activeReplyActions[senderKey] = ReplyAction(replyIntent, remoteInput, packageName)
        normalizeSenderKey(sender)?.let { alias ->
            if (alias != senderKey) activeReplyActions[alias] = ReplyAction(replyIntent, remoteInput, packageName)
        }
        Log.d(TAG, "Cached reply action for $senderKey (pkg=$packageName)")
    }

    fun cacheReadAction(sender: String, readIntent: PendingIntent) {
        val senderKey = canonicalSenderId(sender)
        activeReadActions[senderKey] = readIntent
        normalizeSenderKey(sender)?.let { alias ->
            if (alias != senderKey) activeReadActions[alias] = readIntent
        }
        Log.d(TAG, "Cached read action for $senderKey")
    }

    fun hasReplyAction(sender: String): Boolean = findReplyAction(sender) != null

    fun canReplyToSender(sender: String): Boolean = hasReplyAction(sender) || canSendSmsTo(sender)

    fun clearRuntimeState() {
        activeReplyActions.clear()
        activeReadActions.clear()
        lastPackageForSender.clear()
        senderMutexes.clear()
        Log.i(TAG, "Baiting runtime state cleared")
    }

    private fun startNetworkStatusMonitor() {
        refreshNetworkStatus()
        try {
            connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refreshNetworkStatus()
                override fun onLost(network: Network) = refreshNetworkStatus()
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refreshNetworkStatus()
                override fun onUnavailable() = refreshNetworkStatus()
            })
        } catch (e: Exception) {
            Log.w(TAG, "Network status monitor unavailable", e)
        }
    }

    private fun refreshNetworkStatus() {
        _engineStatus.value = if (isNetworkUsable(connectivityManager)) EngineStatus.ONLINE else EngineStatus.OFFLINE_ACTIVE
    }

    private fun hasUsableNetwork(): Boolean = isNetworkUsable(connectivityManager)

    private fun isNetworkUsable(cm: ConnectivityManager): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun normalizeSenderKey(sender: String?): String? {
        if (sender.isNullOrBlank()) return null
        return sender.replace(Regex("\\s+"), "").trim()
    }

    /**
     * Stable sender key used for session continuity even if sender label formatting changes.
     */
    private fun canonicalSenderId(sender: String): String {
        val trimmed = sender.trim().lowercase()
        // Keep common identifier chars (+, @, ., -, digits/letters), drop noisy decorations/emojis.
        val normalized = trimmed
            .replace(Regex("[^a-z0-9+@._-]"), "")
            .replace(Regex("\\s+"), "")
        return if (normalized.isNotBlank()) normalized else normalizeSenderKey(sender) ?: sender
    }

    private fun findReplyAction(sender: String): ReplyAction? {
        val expectedPackage = lastPackageForSender[canonicalSenderId(sender)]
        
        fun isValid(action: ReplyAction?): Boolean {
            return action != null && (expectedPackage == null || action.packageName == expectedPackage)
        }

        activeReplyActions[sender]?.let { if (isValid(it)) return it }
        normalizeSenderKey(sender)?.let { n ->
            activeReplyActions[n]?.let { if (isValid(it)) return it }
            activeReplyActions.entries.forEach { (key, action) ->
                if (normalizeSenderKey(key) == n && isValid(action)) return action
            }
        }
        // Fallback: try canonicalized key
        val canonical = canonicalSenderId(sender)
        if (canonical != sender) {
            activeReplyActions[canonical]?.let { if (isValid(it)) return it }
        }
        
        if (activeReplyActions.isNotEmpty()) {
            Log.w(TAG, "Reply action NOT FOUND or WRONG PACKAGE for '$sender' (expectedPkg=$expectedPackage).")
        } else {
            Log.w(TAG, "Reply action NOT FOUND for '$sender': NO reply actions cached at all.")
        }
        return null
    }

    private fun findReadAction(sender: String): PendingIntent? {
        activeReadActions[sender]?.let { return it }
        normalizeSenderKey(sender)?.let { n ->
            activeReadActions[n]?.let { return it }
            activeReadActions.entries.forEach { (key, action) ->
                if (normalizeSenderKey(key) == n) return action
            }
        }
        return null
    }

    /**
     * Start a baiting session triggered by the user from the alert notification.
     * Generates the first message and sends it.
     */
    suspend fun startBaitingSession(sender: String, initialScamMessage: String) {
        val senderKey = canonicalSenderId(sender)
        val plan = planEngagement(senderKey, initialScamMessage)
        // 1. Create session in DB
        baitingDao.createSession(
            BaitingSessionEntity(
                senderId = senderKey,
                persona = currentPersona,
                startTime = System.currentTimeMillis(),
                currentStrategy = plan.strategy
            )
        )

        // 2. Add the scammer's initial message to history
        baitingDao.addMessageAndUpdateSession(
            BaitingMessageEntity(
                senderId = senderKey,
                role = "user",
                content = initialScamMessage,
                timestamp = System.currentTimeMillis()
            )
        )

        // 3. Generate and send reply asynchronously
        Log.i(TAG, "Baiting session started for $senderKey. Generating reply asynchronously…")
        scope.launch {
            generateAndSendReply(senderKey)
        }
    }

    suspend fun forceStartOrResumeSession(sender: String, fallbackMessage: String) {
        val senderKey = canonicalSenderId(sender)
        val session = baitingDao.getSession(senderKey)
        if (session == null) {
            startBaitingSession(senderKey, fallbackMessage)
        } else {
            if (!session.isActive) reactivateBaitingSession(senderKey)
            
            // Check if we need to generate a reply
            val messages = baitingDao.getMessagesForSender(senderKey)
            if (messages.isNotEmpty() && messages.last().role == "user") {
                planEngagement(senderKey, messages.last().content)
                Log.i(TAG, "Resuming session for $senderKey. Generating reply for last user message…")
                scope.launch { generateAndSendReply(senderKey) }
            } else if (messages.isEmpty()) {
                planEngagement(senderKey, fallbackMessage)
                baitingDao.addMessageAndUpdateSession(
                    BaitingMessageEntity(
                        senderId = senderKey,
                        role = "user",
                        content = fallbackMessage,
                        timestamp = System.currentTimeMillis()
                    )
                )
                scope.launch { generateAndSendReply(senderKey) }
            }
        }
    }

    /**
     * Check if a sender has an active baiting session.
     */
    suspend fun isBaitingActive(sender: String): Boolean {
        val senderKey = canonicalSenderId(sender)
        val session = baitingDao.getSession(senderKey)
        return session?.isActive == true
    }

    /**
     * Get the time elapsed (in ms) since the last message received from the user (scammer) in the session.
     */
    suspend fun getTimeSinceLastUserMessage(sender: String): Long? {
        val senderKey = canonicalSenderId(sender)
        val history = baitingDao.getMessagesForSender(senderKey)
        val lastUserMessage = history.lastOrNull { it.role == "user" }
        return lastUserMessage?.let { System.currentTimeMillis() - it.timestamp }
    }

    /**
     * Handle an incoming message for an active baiting session.
     */
    suspend fun handleIncomingMessage(sender: String, messageText: String) {
        val senderKey = canonicalSenderId(sender)
        
        // Debounce: prevent duplicate notifications from causing duplicate DB entries and AI replies
        val history = baitingDao.getMessagesForSender(senderKey)
        val lastMessage = history.lastOrNull()
        if (lastMessage != null && lastMessage.role == "user" && 
            lastMessage.content == messageText && 
            System.currentTimeMillis() - lastMessage.timestamp < 2000L) {
            Log.i(TAG, "Skipping duplicate incoming message for $senderKey (debounced)")
            return
        }

        Log.i(TAG, "Intercepted message for active baiting session: $senderKey")
        planEngagement(senderKey, messageText)

        // Save incoming message
        baitingDao.addMessageAndUpdateSession(
            BaitingMessageEntity(
                senderId = senderKey,
                role = "user",
                content = messageText,
                timestamp = System.currentTimeMillis()
            )
        )

        scope.launch {
            generateAndSendReply(senderKey)
        }
    }

    /**
     * Stop an active baiting session.
     */
    suspend fun stopBaitingSession(sender: String) {
        val senderKey = canonicalSenderId(sender)
        baitingDao.endSession(senderKey)
        activeReplyActions.remove(senderKey)
        activeReadActions.remove(senderKey)
        Log.i(TAG, "Baiting session stopped for $senderKey")
    }

    /**
     * Reactivate a previously stopped session.
     */
    suspend fun reactivateBaitingSession(sender: String) {
        val senderKey = canonicalSenderId(sender)
        baitingDao.reactivateSession(senderKey)
        Log.i(TAG, "Baiting session reactivated for $senderKey")
    }

    /**
     * Calculate a human-like typing delay based on message length.
     * Simulates realistic texting speed so scammers don't suspect AI.
     *
     * @param scammerUserTurns Number of inbound scammer messages in this thread (1 = first contact).
     */
    private fun calculateTypingDelay(messageLength: Int, partIndex: Int, scammerUserTurns: Int): Long {
        val useDynamic = prefs.getBoolean("use_dynamic_delay", true)
        if (!useDynamic) {
            return if (partIndex == 0) prefs.getInt("response_delay", DEFAULT_RESPONSE_DELAY_SEC) * 1000L else 1500L
        }

        // Follow-up bubbles often typed faster after the "thinking" pause.
        val charMs = if (partIndex > 0) minOf(CHAR_DELAY_MS, 42L) else CHAR_DELAY_MS
        val baseTypingMs = messageLength * charMs
        val randomJitter = (800L..2500L).random()
        val responseDelaySetting =
            if (partIndex == 0) prefs.getInt("response_delay", 3) * 1000L else 0L

        // After the first scammer message, first bubble of each new reply should feel less "instant".
        val followUpTypingBoost = if (partIndex == 0 && scammerUserTurns >= 2) {
            val baseExtra = prefs.getInt("followup_first_bubble_extra_sec", 5).coerceIn(0, 120) * 1000L
            val stepExtra =
                (scammerUserTurns - 2).coerceAtLeast(0) * prefs.getInt("followup_first_bubble_step_sec", 2)
                    .coerceIn(0, 30) * 1000L
            baseExtra + stepExtra + (400L..2200L).random()
        } else {
            0L
        }

        val stealthJitter = if (prefs.getBoolean("stealth_mode", false)) {
            (0L..8000L).random()
        } else {
            0L
        }

        val totalDelay = baseTypingMs + randomJitter + responseDelaySetting + followUpTypingBoost + stealthJitter
        return totalDelay.coerceIn(MIN_TYPING_DELAY_MS, MAX_TYPING_DELAY_MS)
    }

    /**
     * Extra gap between bubble 2+ (humans rarely fire follow-ups instantly after sending).
     */
    private fun interBubbleHumanGap(partIndex: Int, partLength: Int, strategy: String, scammerUserTurns: Int): Long {
        if (partIndex == 0) return 0L
        val base = (2400L..5800L).random()
        val shortMsgBump = if (partLength < 32) (1000L..2800L).random() else 0L
        val strat = strategy.uppercase(Locale.US)
        val strategyBump = when (strat) {
            "DELAY" -> (900L..2200L).random()
            "FAKE_COMPLIANCE" -> (700L..1800L).random()
            "CONFUSION" -> (500L..1400L).random()
            else -> (350L..1100L).random()
        }
        val followUpGapBoost = if (scammerUserTurns >= 2) (800L..2400L).random() else 0L
        return base + shortMsgBump + strategyBump + followUpGapBoost
    }

    /**
     * Longer "reading / thinking" pause before we call the API for 2nd, 3rd, … scammer messages.
     * First reply uses only normal typing + [response_delay]; this adds perceived human delay between rounds.
     */
    private suspend fun delayBeforeReplyToFollowUpScammer(history: List<BaitingMessageEntity>) {
        val userTurns = history.count { it.role == "user" }
        if (userTurns <= 1) return
        val baseSec = prefs.getInt("followup_read_delay_base_sec", 14).coerceIn(4, 300)
        val stepSec = prefs.getInt("followup_read_delay_step_sec", 5).coerceIn(0, 120)
        val totalSec = (baseSec + (userTurns - 2) * stepSec).coerceAtMost(240)
        val ms = totalSec * 1000L + (900L..3800L).random()
        val scaled = scaleReplyDelayMs(ms)
        Log.i(TAG, "Follow-up scammer round (user messages=$userTurns): ${scaled}ms pre-reply pause (raw=${ms}ms)")
        delay(scaled)
    }

    private fun getSenderMutex(senderKey: String): kotlinx.coroutines.sync.Mutex {
        return senderMutexes.computeIfAbsent(senderKey) { kotlinx.coroutines.sync.Mutex() }
    }

    private data class EngagementPlan(
        val mission: MissionEntity,
        val strategy: String,
        val dna: ScammerDnaProfileEntity,
        val knownIntelligence: List<IntelligenceItemEntity>
    )

    private suspend fun planEngagement(senderKey: String, scammerMessage: String): EngagementPlan {
        val dna = scammerDnaEngine.updateProfile(senderKey, senderKey, scammerMessage)
        val mission = missionPlanner.assignMission(senderKey, senderKey, dna)
        val strategy = strategyPlanner.chooseStrategy(senderKey, currentPersona, dna, mission)
        baitingDao.updateSessionStrategy(senderKey, strategy)
        val knownIntelligence = intelligenceDao.getIntelligenceItems(senderKey)
        Log.i(TAG, "Engagement plan for $senderKey: mission=${mission.missionType}, strategy=$strategy, dnaCat=${dna.scamCategory}")
        return EngagementPlan(mission, strategy, dna, knownIntelligence)
    }

    private suspend fun generateAndSendReply(sender: String) {
        val senderKey = canonicalSenderId(sender)
        val mutex = getSenderMutex(senderKey)

        mutex.lock()
        try {
            val history = baitingDao.getMessagesForSender(senderKey)
            val scammerUserTurns = history.count { it.role == "user" }
            val latestMessage = history.lastOrNull { it.role == "user" }?.content ?: ""
            delayBeforeReplyToFollowUpScammer(history)
            
            val session = baitingDao.getSession(senderKey) ?: return
            val plan = if (latestMessage.isNotBlank()) planEngagement(senderKey, latestMessage) else null
            
            var replyText = ""
            val isDemoMode = prefs.getBoolean("demo_mode", false)
            val networkUsable = hasUsableNetwork()

            Log.i(
                TAG,
                "Reply provider selection for $senderKey: demoMode=$isDemoMode, networkUsable=$networkUsable, status=${_engineStatus.value}"
            )

            if (isDemoMode) {
                _engineStatus.value = EngineStatus.DEMO_MODE
                Log.i(TAG, "Using offline reply engine because demo mode is enabled")
                replyText = offlineReplyEngine.generateReply(
                    session, history, latestMessage,
                    plan?.mission ?: MissionEntity(sessionId = senderKey, senderId = senderKey, missionType = "WASTE_MAXIMUM_TIME"),
                    plan?.strategy ?: session.currentStrategy,
                    plan?.dna ?: ScammerDnaProfileEntity(senderId = senderKey),
                    plan?.knownIntelligence.orEmpty()
                )
            } else if (!networkUsable) {
                _engineStatus.value = EngineStatus.OFFLINE_ACTIVE
                Log.i(TAG, "Using offline reply engine because validated internet is unavailable")
                replyText = offlineReplyEngine.generateReply(
                    session, history, latestMessage,
                    plan?.mission ?: MissionEntity(sessionId = senderKey, senderId = senderKey, missionType = "WASTE_MAXIMUM_TIME"),
                    plan?.strategy ?: session.currentStrategy,
                    plan?.dna ?: ScammerDnaProfileEntity(senderId = senderKey),
                    plan?.knownIntelligence.orEmpty()
                )
            } else {
                try {
                    _engineStatus.value = EngineStatus.RECONNECTING
                    Log.i(TAG, "Requesting GPT/cloud bait reply for $senderKey")
                    replyText = cloudReplyProvider.generateReply(
                        session, history, latestMessage,
                        plan?.mission ?: MissionEntity(sessionId = senderKey, senderId = senderKey, missionType = "WASTE_MAXIMUM_TIME"),
                        plan?.strategy ?: session.currentStrategy,
                        plan?.dna ?: ScammerDnaProfileEntity(senderId = senderKey),
                        plan?.knownIntelligence.orEmpty()
                    )
                    _engineStatus.value = EngineStatus.ONLINE
                    Log.i(TAG, "GPT/cloud bait reply received for $senderKey (chars=${replyText.length})")
                } catch (e: Exception) {
                    Log.w(TAG, "Network failed, switching to Offline Engine", e)
                    _engineStatus.value = EngineStatus.OFFLINE_ACTIVE
                    replyText = offlineReplyEngine.generateReply(
                        session, history, latestMessage,
                        plan?.mission ?: MissionEntity(sessionId = senderKey, senderId = senderKey, missionType = "WASTE_MAXIMUM_TIME"),
                        plan?.strategy ?: session.currentStrategy,
                        plan?.dna ?: ScammerDnaProfileEntity(senderId = senderKey),
                        plan?.knownIntelligence.orEmpty()
                    )
                }
            }

            if (replyText.isBlank()) {
                Log.e(TAG, "Both providers failed to generate reply")
                return
            }

            val parts = splitReplyText(replyText)
            parts.forEachIndexed { index, part ->
                // Default pauses since we no longer parse DTO natively
                val pauseMs = pauseBeforeBubble(3) 
                val typingDelay = calculateTypingDelay(part.length, index, scammerUserTurns)
                val strategy = plan?.strategy ?: session.currentStrategy
                val betweenGap = interBubbleHumanGap(index, part.length, strategy, scammerUserTurns)
                val rawTotal = pauseMs + typingDelay + betweenGap
                val totalDelay = scaleReplyDelayMs(rawTotal)
                
                delay(totalDelay)

                // Removed markNotificationAsRead(senderKey) because marking as read
                // dismisses the notification and invalidates the reply PendingIntent
                // for apps like WhatsApp and SMS. Sending a reply will naturally mark it as read.

                val cleanedPart = part.replace(",", "")

                baitingDao.addMessageAndUpdateSession(
                    BaitingMessageEntity(
                        senderId = senderKey,
                        role = "assistant",
                        content = cleanedPart,
                        timestamp = System.currentTimeMillis()
                    )
                )

                sendAiReplyToApp(senderKey, cleanedPart)
                if (index == parts.lastIndex && plan != null) {
                    strategyPlanner.recordTurn(
                        sessionId = senderKey,
                        senderId = senderKey,
                        mission = plan.mission,
                        strategy = plan.strategy,
                        persona = session.persona,
                        scammerMessage = latestMessage,
                        assistantReply = cleanedPart
                    )
                    val effectiveness = engagementEffectivenessEngine.updateEffectiveness(senderKey, plan.mission)
                    strategyOutcomeTracker.recordOutcome(plan.mission, plan.strategy, session.persona, effectiveness)
                    Log.i(
                        TAG,
                        "Outcome updated for $senderKey: score=${effectiveness.effectivenessScore}, " +
                            "mission=${plan.mission.missionType}, strategy=${plan.strategy}"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating reply", e)
        } finally {
            mutex.unlock()
        }
    }

    private fun sendAiReplyToApp(sender: String, replyText: String, imageUri: android.net.Uri? = null) {
        val action = findReplyAction(sender)
        if (action == null) {
            if (sendSmsReplyIfPossible(sender, replyText)) return
            Log.e(
                TAG,
                "Cannot reply to $sender: No cached RemoteInput. Open the chat app's notification " +
                    "(with Reply) so ScamShield can capture the reply action."
            )
            return
        }

        try {
            val resultsBundle = Bundle().apply {
                putCharSequence(action.remoteInput.resultKey, replyText)
            }

            val fillInIntent = Intent().apply {
                RemoteInput.addResultsToIntent(arrayOf(action.remoteInput), this, resultsBundle)
                if (imageUri != null) {
                    val imageResults = mutableMapOf<String, android.net.Uri>()
                    imageResults["image/jpeg"] = imageUri
                    RemoteInput.addDataResultToIntent(action.remoteInput, this, imageResults)
                    // Grant read permission just in case
                    this.clipData = android.content.ClipData.newRawUri("image", imageUri)
                    this.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
            }

            action.intent.send(context, 0, fillInIntent)
            Log.i(TAG, ">>> Successfully sent AI auto-reply to $sender: '$replyText' (hasImage=${imageUri != null})")
        } catch (e: PendingIntent.CanceledException) {
            clearReplyAction(sender)
            if (sendSmsReplyIfPossible(sender, replyText)) return
            Log.e(TAG, "Failed to send reply: PendingIntent cancelled", e)
        }
    }

    private fun clearReplyAction(sender: String) {
        val canonical = canonicalSenderId(sender)
        activeReplyActions.remove(sender)
        activeReplyActions.remove(canonical)
        normalizeSenderKey(sender)?.let { activeReplyActions.remove(it) }
        Log.w(TAG, "Cleared stale reply action for $sender")
    }

    private fun canSendSmsTo(sender: String): Boolean {
        if (!looksLikePhoneNumber(sender)) return false
        return ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun looksLikePhoneNumber(sender: String): Boolean {
        val digits = sender.count { it.isDigit() }
        return digits >= 8 && sender.all { it.isDigit() || it == '+' || it == '-' || it == ' ' || it == '(' || it == ')' }
    }

    @Suppress("DEPRECATION")
    private fun sendSmsReplyIfPossible(sender: String, replyText: String): Boolean {
        if (!canSendSmsTo(sender)) return false
        return try {
            val sms = SmsManager.getDefault()
            val parts = sms.divideMessage(replyText)
            sms.sendMultipartTextMessage(sender, null, parts, null, null)
            Log.i(TAG, ">>> Successfully sent AI SMS reply to $sender: '$replyText'")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS reply to $sender", e)
            false
        }
    }

    private fun markNotificationAsRead(sender: String) {
        val readAction = findReadAction(sender) ?: return
        try {
            readAction.send()
            Log.i(TAG, "Marked notification as read for $sender")
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "Read action expired for $sender", e)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mark notification as read for $sender", e)
        }
    }

    /** Human pause before a bubble (server seconds + small jitter). */
    private fun pauseBeforeBubble(serverSuggestedSeconds: Int): Long {
        val suggestedMs = serverSuggestedSeconds.coerceIn(1, 14) * 1000L
        val jitterMs = (-400L..400L).random()
        return (suggestedMs + jitterMs).coerceIn(MIN_REPLY_DELAY_MS, MAX_REPLY_DELAY_MS)
    }

    private fun splitReplyText(replyText: String): List<String> {
        val normalized = replyText.trim()
        if (normalized.isEmpty()) return emptyList()
        if (normalized.length <= 70) return listOf(normalized)

        val sentenceChunks = normalized
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (sentenceChunks.size > 1) {
            return sentenceChunks.take(3)
        }

        val words = normalized.split(Regex("\\s+"))
        val chunks = mutableListOf<String>()
        val current = mutableListOf<String>()
        for (word in words) {
            current.add(word)
            if (current.joinToString(" ").length >= 40) {
                chunks.add(current.joinToString(" "))
                current.clear()
            }
            if (chunks.size >= 3) break
        }
        if (current.isNotEmpty() && chunks.size < 3) {
            chunks.add(current.joinToString(" "))
        }
        return if (chunks.isEmpty()) listOf(normalized) else chunks
    }
}
