package com.scamshield.app.service

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.scamshield.app.BuildConfig
import com.scamshield.app.data.local.dao.BaitingDao
import com.scamshield.app.data.local.entity.BaitingMessageEntity
import com.scamshield.app.data.local.entity.BaitingSessionEntity
import com.scamshield.app.data.remote.BaitingRequestDto
import com.scamshield.app.data.remote.ChatMessageDto
import com.scamshield.app.data.remote.DetectionApiService
import com.scamshield.app.data.remote.LoginRequestDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

@Singleton
class BaitingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val baitingDao: BaitingDao,
    private val apiService: DetectionApiService
) {
    companion object {
        private const val TAG = "BaitingManager"

        /**
         * Single knob for all AI reply waits (typing, server pauses, gaps, follow-up “reading” delays).
         * 1.0f = current/default behavior. Lower = faster (e.g. 0.3f for demos); higher = slower.
         */
        private const val REPLY_TIME_MULTIPLIER = 0.35f

        private const val MIN_TYPING_DELAY_MS = 2000L
        private const val MAX_TYPING_DELAY_MS = 25000L
        private const val CHAR_DELAY_MS = 55L // ~55ms per character (human typing speed)
        /** Lower bound allows ~1s server-guided pauses between bubbles. */
        private const val MIN_REPLY_DELAY_MS = 900L
        /** Server can suggest up to ~12s pauses between bubbles. */
        private const val MAX_REPLY_DELAY_MS = 13000L
    }

    /** Applies [REPLY_TIME_MULTIPLIER] without changing any other timing logic. */
    private fun scaleReplyDelayMs(ms: Long): Long =
        (ms.toDouble() * REPLY_TIME_MULTIPLIER).toLong().coerceAtLeast(0L)

    // In-memory cache of the latest RemoteInput and PendingIntent for auto-replying
    data class ReplyAction(val intent: PendingIntent, val remoteInput: RemoteInput)
    private val activeReplyActions = ConcurrentHashMap<String, ReplyAction>()
    private val activeReadActions = ConcurrentHashMap<String, PendingIntent>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bearerToken: String? = null

    // Current Persona (read from SharedPreferences dynamically)
    private val currentPersona: String
        get() = context.getSharedPreferences("scamshield_prefs", 0)
            .getString("active_persona", "busy_professional") ?: "busy_professional"

    // Current prefs reader
    private val prefs get() = context.getSharedPreferences("scamshield_prefs", 0)

    /**
     * Cache the latest notification reply action for a sender.
     */
    fun cacheReplyAction(sender: String, replyIntent: PendingIntent, remoteInput: RemoteInput) {
        val senderKey = canonicalSenderId(sender)
        activeReplyActions[senderKey] = ReplyAction(replyIntent, remoteInput)
        normalizeSenderKey(sender)?.let { alias ->
            if (alias != senderKey) activeReplyActions[alias] = ReplyAction(replyIntent, remoteInput)
        }
        Log.d(TAG, "Cached reply action for $senderKey")
    }

    fun cacheReadAction(sender: String, readIntent: PendingIntent) {
        val senderKey = canonicalSenderId(sender)
        activeReadActions[senderKey] = readIntent
        normalizeSenderKey(sender)?.let { alias ->
            if (alias != senderKey) activeReadActions[alias] = readIntent
        }
        Log.d(TAG, "Cached read action for $senderKey")
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
        activeReplyActions[sender]?.let { return it }
        normalizeSenderKey(sender)?.let { n ->
            activeReplyActions[n]?.let { return it }
            activeReplyActions.entries.forEach { (key, action) ->
                if (normalizeSenderKey(key) == n) return action
            }
        }
        // Fallback: try canonicalized key
        val canonical = canonicalSenderId(sender)
        if (canonical != sender) {
            activeReplyActions[canonical]?.let { return it }
        }
        // Log all available keys for debugging
        if (activeReplyActions.isNotEmpty()) {
            Log.w(TAG, "Reply action NOT FOUND for '$sender' (canonical='$canonical'). " +
                "Available keys: ${activeReplyActions.keys.joinToString()}")
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
        // 1. Create session in DB
        baitingDao.createSession(
            BaitingSessionEntity(
                senderId = senderKey,
                persona = currentPersona,
                startTime = System.currentTimeMillis()
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

        // 3. Generate and send reply asynchronously so we don't block callers (like BroadcastReceivers that ANR after 10s)
        Log.i(TAG, "Baiting session started for $senderKey. Generating reply asynchronously…")
        scope.launch {
            generateAndSendReply(senderKey)
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
     * Handle an incoming message for an active baiting session.
     */
    suspend fun handleIncomingMessage(sender: String, messageText: String) {
        val senderKey = canonicalSenderId(sender)
        Log.i(TAG, "Intercepted message for active baiting session: $senderKey")

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
        // Follow-up bubbles often typed faster after the "thinking" pause.
        val charMs = if (partIndex > 0) minOf(CHAR_DELAY_MS, 42L) else CHAR_DELAY_MS
        val baseTypingMs = messageLength * charMs
        val randomJitter = (800L..2500L).random()
        val responseDelaySetting =
            if (partIndex == 0) prefs.getInt("response_delay", 5) * 1000L else 0L

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

    private suspend fun generateAndSendReply(sender: String) {
        val senderKey = canonicalSenderId(sender)

        val history = baitingDao.getMessagesForSender(senderKey)
        val scammerUserTurns = history.count { it.role == "user" }
        delayBeforeReplyToFollowUpScammer(history)
        val session = baitingDao.getSession(senderKey)
        val strategyForRequest = session?.currentStrategy?.takeIf { it.isNotBlank() } ?: "CONFUSION"
        val request = BaitingRequestDto(
            sender_id = senderKey,
            session_id = senderKey,
            persona = currentPersona,
            current_strategy = strategyForRequest,
            scam_category = "unknown",
            goal = "waste_time",
            history = history.map { ChatMessageDto(role = it.role, content = it.content) }
        )

        try {
            var token = ensureToken()
            if (token == null) {
                Log.e(TAG, "Bait API skipped: login failed (no token). Check BASE_URL and admin credentials.")
                return
            }
            Log.i(TAG, "Calling bait/reply: historySize=${history.size}, sender=$senderKey")
            var response = apiService.generateBaitingReply("Bearer $token", request)
            if (response.code() == 401) {
                Log.w(TAG, "Bait API401 — refreshing token and retrying once")
                bearerToken = null
                token = ensureToken()
                if (token != null) {
                    response = apiService.generateBaitingReply("Bearer $token", request)
                }
            }

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val parts = body.reply_parts
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .ifEmpty { splitReplyText(body.reply_text) }

                if (parts.isEmpty()) {
                    Log.e(TAG, "Bait API returned empty reply parts")
                    return
                }

                parts.forEachIndexed { index, part ->
                    val pauseSeconds = body.part_delay_seconds
                        ?.getOrNull(index)
                        ?.takeIf { it > 0 }
                        ?: body.response_delay_seconds
                    val pauseMs = pauseBeforeBubble(pauseSeconds)
                    val typingDelay = calculateTypingDelay(part.length, index, scammerUserTurns)
                    val betweenGap = interBubbleHumanGap(index, part.length, body.strategy_used, scammerUserTurns)
                    val rawTotal = pauseMs + typingDelay + betweenGap
                    val totalDelay = scaleReplyDelayMs(rawTotal)
                    Log.i(
                        TAG,
                        "Simulating delay: ${totalDelay}ms (pause=$pauseMs typing=$typingDelay " +
                            "betweenGap=$betweenGap part=$index, raw=${rawTotal}ms)"
                    )
                    delay(totalDelay)

                    // Mark original incoming notification as read before sending the first AI chunk.
                    if (index == 0) {
                        markNotificationAsRead(senderKey)
                    }

                    // Save AI reply chunk to DB
                    baitingDao.addMessageAndUpdateSession(
                        BaitingMessageEntity(
                            senderId = senderKey,
                            role = "assistant",
                            content = part,
                            timestamp = System.currentTimeMillis()
                        )
                    )

                    // Send each chunk via Android RemoteInput
                    sendAiReplyToApp(senderKey, part)
                }

                body.session_strategy.takeIf { it.isNotBlank() }?.let { st ->
                    baitingDao.updateSessionStrategy(senderKey, st)
                }
            } else {
                val err = response.errorBody()?.string()
                Log.e(
                    TAG,
                    "Bait API failed: code=${response.code()}, body=$err"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network failure generating reply", e)
        }
    }

    private suspend fun ensureToken(): String? {
        bearerToken?.let { return it }
        val response = apiService.login(
            LoginRequestDto(
                username = BuildConfig.AUTH_USERNAME,
                password = BuildConfig.AUTH_PASSWORD
            )
        )
        if (!response.isSuccessful) {
            Log.e(TAG, "Login failed before baiting call: ${response.code()}")
            return null
        }
        val token = response.body()?.access_token
        bearerToken = token
        return token
    }

    private fun sendAiReplyToApp(sender: String, replyText: String) {
        val action = findReplyAction(sender)
        if (action == null) {
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
            }

            action.intent.send(context, 0, fillInIntent)
            Log.i(TAG, ">>> Successfully sent AI auto-reply to $sender: '$replyText'")
        } catch (e: PendingIntent.CanceledException) {
            Log.e(TAG, "Failed to send reply: PendingIntent cancelled", e)
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
