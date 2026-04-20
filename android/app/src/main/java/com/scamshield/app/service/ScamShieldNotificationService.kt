package com.scamshield.app.service

import android.content.ComponentName
import android.content.SharedPreferences
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.scamshield.app.detection.DetectionRepository
import com.scamshield.app.detection.DetectionResult
import com.scamshield.app.data.ScammerRepository
import com.scamshield.app.detection.model.RuleVerdict
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * ScamShieldNotificationService — Intercepts notifications from target apps
 * and triggers the scam detection pipeline.
 *
 * IMPORTANT: Uses manual Hilt EntryPoint injection instead of @AndroidEntryPoint
 * because NotificationListenerService is system-managed and @AndroidEntryPoint
 * may fail to inject dependencies reliably on all devices.
 *
 * Processing flow:
 * 1. Notification arrives → parse with NotificationParser
 * 2. Smart filter (skip media, calls, groups, duplicates)
 * 3. Run RuleEngine for instant rule verdict
 * 4. If suspicious → show alert immediately
 * 5. Try full analysis in background (upgrade alert if better)
 */
class ScamShieldNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "ScamShieldNLS"
        private const val DEDUP_WINDOW_MS = 5 * 60 * 1000L
        private const val MAX_DEDUP_ENTRIES = 500
        /** Min seconds between scam *notifications* for the same sender (stops quick+full double fire + spam updates). */
        private const val DEFAULT_ALERT_COOLDOWN_SEC = 120
    }

    /**
     * Hilt EntryPoint for manual dependency injection.
     * This is the reliable way to get dependencies in system services.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ServiceEntryPoint {
        fun parser(): NotificationParser
        fun detectionRepository(): DetectionRepository
        fun scammerRepository(): ScammerRepository
        fun alertManager(): AlertNotificationManager
        fun baitingManager(): BaitingManager
    }

    // Dependencies (manually injected in onCreate)
    private var parser: NotificationParser? = null
    private var detectionRepository: DetectionRepository? = null
    private var scammerRepository: ScammerRepository? = null
    private var alertManager: AlertNotificationManager? = null
    private var baitingManager: BaitingManager? = null
    private var isInjected = false

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("ScamShieldNLS")
    )

    private val recentHashes = ConcurrentHashMap<String, Long>()
    private val lastScamAlertBySender = ConcurrentHashMap<String, Long>()

    // --- Per-sender conversation memory for multi-message correlation ---
    private data class TimestampedMessage(val text: String, val timestamp: Long)
    private val senderMessageBuffer = ConcurrentHashMap<String, MutableList<TimestampedMessage>>()
    private val CONTEXT_WINDOW_MS = 10 * 60 * 1000L // 10 minutes
    private val MAX_CONTEXT_MESSAGES = 3

    private fun addToSenderBuffer(senderKey: String, text: String) {
        val buffer = senderMessageBuffer.getOrPut(senderKey) { mutableListOf() }
        val now = System.currentTimeMillis()
        // Evict expired messages
        buffer.removeAll { now - it.timestamp > CONTEXT_WINDOW_MS }
        buffer.add(TimestampedMessage(text, now))
        // Keep only last N messages
        while (buffer.size > MAX_CONTEXT_MESSAGES) buffer.removeAt(0)
    }

    private fun getPreviousMessages(senderKey: String, currentText: String): List<String> {
        val buffer = senderMessageBuffer[senderKey] ?: return emptyList()
        val now = System.currentTimeMillis()
        return buffer
            .filter { now - it.timestamp <= CONTEXT_WINDOW_MS && it.text != currentText }
            .map { it.text }
    }

    // --- Lifecycle ---

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ScamShield NotificationListener created")
        injectDependencies()
    }

    /**
     * Manually inject dependencies using Hilt EntryPoint.
     * This works reliably for system-managed services.
     */
    private fun injectDependencies() {
        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                ServiceEntryPoint::class.java
            )
            parser = entryPoint.parser()
            detectionRepository = entryPoint.detectionRepository()
            scammerRepository = entryPoint.scammerRepository()
            alertManager = entryPoint.alertManager()
            baitingManager = entryPoint.baitingManager()
            isInjected = true
            Log.i(TAG, "Dependencies injected successfully")
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Failed to inject dependencies!", e)
            isInjected = false
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NotificationListener CONNECTED — monitoring active")
        if (!isInjected) {
            Log.w(TAG, "Re-attempting dependency injection...")
            injectDependencies()
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "NotificationListener disconnected")
        requestRebind(ComponentName(this, ScamShieldNotificationService::class.java))
    }

    override fun onDestroy() {
        serviceScope.cancel("Service destroyed")
        recentHashes.clear()
        lastScamAlertBySender.clear()
        Log.i(TAG, "ScamShield NotificationListener destroyed")
        super.onDestroy()
    }

    // --- Notification Processing ---

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            if (sbn == null) return
            if (!isInjected || parser == null) {
                Log.e(TAG, "Dependencies not injected, attempting re-injection...")
                injectDependencies()
                if (!isInjected) return
            }

            // === REAL-TIME PROTECTION TOGGLE ===
            val prefs = applicationContext.getSharedPreferences("scamshield_prefs", 0)
            val realtimeEnabled = prefs.getBoolean(
                "realtime_protection",
                !prefs.getBoolean("privacy_mode", false)
            )
            if (!realtimeEnabled) {
                return // Real-time protection disabled by user
            }

            val packageName = sbn.packageName ?: return
            val notification = sbn.notification ?: return
            
            // Bypass filters for the internal Sandbox Demo notification
            val isSandbox = (notification.channelId == "scam_sandbox" || sbn.id == 999)

            if (!isSandbox) {
                // Don't process our own notifications
                if (packageName == applicationContext.packageName) return
            }

            val parsed = parser!!.parse(packageName, notification, isSandbox) ?: return

            Log.i(
                TAG,
                ">>> PROCESSING: pkg=$packageName, sender=${parsed.sender}, " +
                    "textLen=${parsed.text.length}, text_preview=\"${parsed.text.take(50)}...\""
            )

            serviceScope.launch {
                processNotification(parsed)
            }
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL ERROR in onNotificationPosted", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No action needed
    }

    // --- Detection Pipeline ---

    private suspend fun processNotification(parsed: ParsedNotification) {
        try {
            val repo = detectionRepository ?: run {
                Log.e(TAG, "detectionRepository is null!")
                return
            }
            val alert = alertManager ?: run {
                Log.e(TAG, "alertManager is null!")
                return
            }
            val scammerRepo = scammerRepository ?: run {
                Log.e(TAG, "scammerRepository is null!")
                return
            }
            val baiting = baitingManager ?: run {
                Log.e(TAG, "baitingManager is null!")
                return
            }

            val prefs = applicationContext.getSharedPreferences("scamshield_prefs", 0)
            val soundEnabled = prefs.getBoolean("sound_alerts", true)
            val vibrateEnabled = prefs.getBoolean("vibration_alerts", true)
            val confidenceThreshold = prefs.getInt("confidence_threshold", 25) / 100f
            val autoBait = prefs.getBoolean("auto_bait", false)

            val senderCandidates = buildSenderCandidates(parsed.sender, parsed.text)
            val senderKey = primarySenderKey(senderCandidates, parsed.text)

            // ALWAYS cache reply/read actions FIRST — before any early returns.
            // This ensures we never lose a reply action even if we skip detection.
            if (parsed.replyIntent != null && parsed.remoteInput != null) {
                baiting.cacheReplyAction(senderKey, parsed.replyIntent, parsed.remoteInput)
                Log.d(TAG, "Reply action cached for $senderKey (early)")
            }
            if (parsed.readIntent != null) {
                baiting.cacheReadAction(senderKey, parsed.readIntent)
            }

            // Never score pure "You" / self labels with no remote phone or handle in the body (defensive).
            if (senderCandidates.isNotEmpty() &&
                senderCandidates.all { isSelfLikeSenderLabel(it) } &&
                extractPhoneLikeTokens(parsed.text).isEmpty()
            ) {
                Log.i(TAG, "Skipping detection: self-like sender only, no phone in text")
                return
            }

            // Add to conversation buffer for multi-message correlation
            addToSenderBuffer(senderKey, parsed.text)

            // Step 0: Intercept if an active baiting session exists
            // This prevents redundant detection alerts for active sessions
            if (baiting.isBaitingActive(senderKey)) {
                Log.i(TAG, "Baiting session active for $senderKey. Forwarding to AI...")
                baiting.handleIncomingMessage(senderKey, parsed.text)
                return // Skip normal detection — no duplicate alerts
            }

            val knownScammer = findKnownScammer(scammerRepo, senderCandidates)
            if (knownScammer != null) {
                Log.i(TAG, "Message from known scammer $senderKey; running detection for new content.")
                if (knownScammer.aiEnabled && parsed.replyIntent != null && parsed.remoteInput != null) {
                    baiting.startBaitingSession(knownScammer.phoneNumber, parsed.text)
                    // Baiting session owns this conversation; skip scam alerts for this post (avoids spam + double pipeline).
                    return
                }
            }

            // Dedup: same sender + same body within a short window (known senders included).
            val hash = hashContent(senderKey, parsed.text)
            if (isDuplicate(hash)) {
                Log.d(TAG, "Dedup: skipping duplicate from ${parsed.packageName}")
                return
            }
            markSeen(hash)

            var scamAlertShownThisPass = false

            // Step 1: Quick local analysis WITH multi-message context
            val previousMessages = getPreviousMessages(senderKey, parsed.text)
            val quickResult = if (previousMessages.isNotEmpty()) {
                repo.analyzeQuickWithContext(
                    text = parsed.text,
                    previousMessages = previousMessages,
                    sender = parsed.sender
                )
            } else {
                repo.analyzeQuick(
                    text = parsed.text,
                    sender = parsed.sender
                )
            }

            Log.i(
                TAG,
                "QUICK RESULT: suspicious=${quickResult.isSuspicious}, " +
                    "conf=%.2f, rules=${quickResult.matchedRules.size}, ".format(
                        quickResult.confidence
                    ) + "matched=${quickResult.matchedRules.map { it.rule }}, " +
                    "categories=${quickResult.categoryHints}, contextMsgs=${previousMessages.size}"
            )

            // Step 2: Alert quickly if any local signal matched
            if (quickResult.isSuspicious || quickResult.matchedRules.isNotEmpty()) {
                val ruleResult = DetectionResult(
                    ruleVerdict = quickResult,
                    isOffline = true
                )

                if (quickResult.confidence >= confidenceThreshold || quickResult.matchedRules.size >= 2) {
                    if (tryShowScamAlert(
                            alert, ruleResult, parsed, soundEnabled, vibrateEnabled,
                            senderKey, prefs, "quick"
                        )
                    ) {
                        scamAlertShownThisPass = true
                        scammerRepo.upsertHighRisk(senderKey, parsed.text)
                        Log.i(TAG, ">>> ALERT SHOWN: conf=%.2f, cat=${quickResult.categoryHints}".format(quickResult.confidence))
                    } else if (ruleResult.shouldAlert && isSenderAlertWithinCooldown(senderKey, prefs)) {
                        scammerRepo.upsertHighRisk(senderKey, parsed.text)
                    }

                    // === AUTO-BAIT: Start baiting automatically if enabled ===
                    if (autoBait && parsed.replyIntent != null && parsed.remoteInput != null &&
                        !baiting.isBaitingActive(senderKey)
                    ) {
                        Log.i(TAG, "Auto-bait enabled. Starting baiting session for $senderKey")
                        baiting.startBaitingSession(senderKey, parsed.text)
                    }
                } else {
                    Log.w(TAG, "Below alert threshold: conf=%.2f, rules=${quickResult.matchedRules.size}".format(quickResult.confidence))
                }

            } else {
                Log.d(TAG, "No strong local indicators. Escalating to backend analysis.")
            }

            // Step 3: Always try full analysis (unless on-device mode is enabled)
            // This prevents missed detections when local rules are weak.
            try {
                val fullResult = repo.analyzeMessage(
                    text = parsed.text,
                    source = parsed.source,
                    sender = parsed.sender,
                    privacyMode = prefs.getBoolean("on_device_only", false)
                )
                if (fullResult.shouldAlert) {
                    // Always register scammer regardless of alert cooldown
                    scammerRepo.upsertHighRisk(senderKey, parsed.text)

                    if (!scamAlertShownThisPass &&
                        tryShowScamAlert(
                            alert, fullResult, parsed, soundEnabled, vibrateEnabled,
                            senderKey, prefs, "full"
                        )
                    ) {
                        Log.i(
                            TAG,
                            ">>> FULL ALERT SHOWN: conf=%.2f, enhanced=${fullResult.isEnhanced}, offline=${fullResult.isOffline}"
                                .format(fullResult.bestConfidence)
                        )
                    } else if (scamAlertShownThisPass) {
                        Log.d(TAG, "Full analysis also above threshold; skipping duplicate alert (already shown quick path).")
                    } else if (isSenderAlertWithinCooldown(senderKey, prefs)) {
                        Log.i(TAG, "Full analysis scam signal recorded; alert suppressed by per-sender cooldown.")
                    }

                    // === AUTO-BAIT from full analysis: Start baiting if not already active ===
                    if (autoBait && !baiting.isBaitingActive(senderKey)) {
                        Log.i(TAG, "Auto-bait from full analysis. Starting baiting session for $senderKey")
                        baiting.startBaitingSession(senderKey, parsed.text)
                    }
                } else {
                    Log.d(
                        TAG,
                        "Full analysis returned safe: conf=%.2f, enhanced=${fullResult.isEnhanced}, offline=${fullResult.isOffline}"
                            .format(fullResult.bestConfidence)
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Full analysis failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Detection pipeline error", e)
        }
    }

    // --- Dedup & alert throttling ---

    private fun senderAlertCooldownMs(prefs: SharedPreferences): Long {
        val sec = prefs.getInt("scam_alert_cooldown_sec", DEFAULT_ALERT_COOLDOWN_SEC).coerceIn(15, 600)
        return sec * 1000L
    }

    private fun isSenderAlertWithinCooldown(senderKey: String, prefs: SharedPreferences): Boolean {
        val last = lastScamAlertBySender[senderKey] ?: return false
        return System.currentTimeMillis() - last < senderAlertCooldownMs(prefs)
    }

    private fun markSenderScamAlertShown(senderKey: String) {
        lastScamAlertBySender[senderKey] = System.currentTimeMillis()
        if (lastScamAlertBySender.size > MAX_DEDUP_ENTRIES) {
            val cutoff = System.currentTimeMillis() - 600 * 1000L
            lastScamAlertBySender.entries.filter { it.value < cutoff }
                .forEach { lastScamAlertBySender.remove(it.key) }
        }
    }

    /**
     * @return true if a notification was actually posted.
     */
    private fun tryShowScamAlert(
        alert: AlertNotificationManager,
        result: DetectionResult,
        parsed: ParsedNotification,
        soundEnabled: Boolean,
        vibrateEnabled: Boolean,
        senderKey: String,
        prefs: SharedPreferences,
        pathLabel: String
    ): Boolean {
        if (!result.shouldAlert) return false
        if (isSenderAlertWithinCooldown(senderKey, prefs)) {
            Log.i(TAG, "Scam alert skipped ($pathLabel): per-sender cooldown for $senderKey")
            return false
        }
        alert.showScamAlert(result, parsed, soundEnabled, vibrateEnabled)
        markSenderScamAlertShown(senderKey)
        Log.i(TAG, "Scam alert posted ($pathLabel) for $senderKey")
        return true
    }

    private fun hashContent(sender: String?, text: String): String {
        val input = "${sender ?: ""}|$text"
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(12)
    }

    private fun isDuplicate(hash: String): Boolean {
        val timestamp = recentHashes[hash] ?: return false
        return (System.currentTimeMillis() - timestamp) < DEDUP_WINDOW_MS
    }

    private fun markSeen(hash: String) {
        recentHashes[hash] = System.currentTimeMillis()
        if (recentHashes.size > MAX_DEDUP_ENTRIES) {
            val cutoff = System.currentTimeMillis() - DEDUP_WINDOW_MS
            recentHashes.entries.filter { it.value < cutoff }.forEach { recentHashes.remove(it.key) }
        }
    }

    /**
     * Normalize sender IDs to keep continuity across formatting variants:
     * "+1 234 567 890" vs "+1234567890", mixed casing, etc.
     */
    private fun canonicalSenderId(sender: String): String {
        val trimmed = sender.trim().lowercase()
        val normalized = trimmed
            .replace(Regex("[^a-z0-9+@._-]"), "")
            .replace(Regex("\\s+"), "")
        return if (normalized.isNotBlank()) normalized else trimmed
    }

    /** Notification title/body sometimes resolves to "you" for outgoing messages — never treat as primary id. */
    private fun isSelfLikeSenderLabel(raw: String): Boolean {
        val c = canonicalSenderId(raw)
        return c == "you" || c == "me" || c == "u"
    }

    /**
     * Prefer a stable remote id (phone) so the same scammer does not get separate alerts/cooldowns
     * per display name ("sbibankalert" vs +91…).
     */
    private fun primarySenderKey(candidates: List<String>, messageText: String): String {
        for (x in candidates) {
            normalizePhoneOnly(x)?.let { return it }
        }
        extractPhoneLikeTokens(messageText).forEach { token ->
            normalizePhoneOnly(token)?.let { return it }
        }
        val nonSelf = candidates.filterNot { isSelfLikeSenderLabel(it) }
        val pool = if (nonSelf.isNotEmpty()) nonSelf else candidates
        return pool.maxByOrNull { it.length } ?: pool.firstOrNull() ?: "unknown"
    }

    private suspend fun findKnownScammer(
        scammerRepo: ScammerRepository,
        senderCandidates: List<String>
    ): com.scamshield.app.data.local.entity.ScammerEntity? {
        senderCandidates.forEach { candidate ->
            scammerRepo.getScammer(candidate)?.let { return it }
        }
        return null
    }

    private fun buildSenderCandidates(sender: String?, text: String): List<String> {
        val values = linkedSetOf<String>()
        sender?.takeIf { it.isNotBlank() }?.let {
            values.add(canonicalSenderId(it))
            normalizePhoneOnly(it)?.let(values::add)
        }

        extractPhoneLikeTokens(text).forEach { token ->
            values.add(canonicalSenderId(token))
            normalizePhoneOnly(token)?.let(values::add)
        }
        return values.filter { it.isNotBlank() }
    }

    private fun extractPhoneLikeTokens(text: String): List<String> {
        val regex = Regex("(?:\\+?\\d[\\d\\s\\-()]{7,}\\d)")
        return regex.findAll(text)
            .map { it.value.trim() }
            .distinct()
            .take(3)
            .toList()
    }

    private fun normalizePhoneOnly(input: String): String? {
        val digits = input.filter { it.isDigit() || it == '+' }
        if (digits.isBlank()) return null
        // Keep a minimum length to avoid matching tiny numeric fragments.
        return if (digits.count { it.isDigit() } >= 8) digits else null
    }
}
