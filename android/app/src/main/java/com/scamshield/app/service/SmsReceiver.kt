package com.scamshield.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.scamshield.app.data.ScammerRepository
import com.scamshield.app.detection.DetectionRepository
import com.scamshield.app.detection.DetectionResult
import com.scamshield.app.detection.model.InputSource
import com.scamshield.app.detection.model.RuleVerdict
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * SmsReceiver — Receives incoming SMS via android.provider.Telephony.SMS_RECEIVED
 * for precise detection of scam messages at the SMS layer.
 *
 * This is more accurate than notification-based detection because it provides
 * the exact message body and originating address directly from the PDU.
 *
 * Note: Requires RECEIVE_SMS and READ_SMS permissions.
 */
@AndroidEntryPoint
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
        private const val DEDUP_WINDOW_MS = 5 * 60 * 1000L
        private const val MAX_DEDUP = 200
        private const val ALERT_COOLDOWN_MS = 120_000L
    }

    @Inject lateinit var detectionRepository: DetectionRepository
    @Inject lateinit var scammerRepository: ScammerRepository
    @Inject lateinit var alertManager: AlertNotificationManager
    @Inject lateinit var baitingManager: BaitingManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Static dedup maps (survive across broadcasts within the process)
    private val recentHashes = ConcurrentHashMap<String, Long>()
    private val lastAlertBySender = ConcurrentHashMap<String, Long>()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val prefs = context.getSharedPreferences("scamshield_prefs", 0)
        if (!prefs.getBoolean("realtime_protection", true)) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Group message parts by originating address (multi-part SMS)
        val grouped = mutableMapOf<String, StringBuilder>()
        messages.forEach { sms ->
            val sender = sms.originatingAddress ?: "unknown"
            grouped.getOrPut(sender) { StringBuilder() }.append(sms.messageBody ?: "")
        }

        grouped.forEach { (sender, bodyBuilder) ->
            val body = bodyBuilder.toString().trim()
            if (body.isBlank()) return@forEach

            Log.i(TAG, "SMS received from $sender, len=${body.length}")

            val pendingResult = goAsync()
            scope.launch {
                try {
                    processSms(context, sender, body)
                } catch (e: Exception) {
                    Log.e(TAG, "SMS processing error", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private suspend fun processSms(context: Context, sender: String, body: String) {
        val senderKey = canonicalSenderId(sender)
        val prefs = context.getSharedPreferences("scamshield_prefs", 0)
        val soundEnabled = prefs.getBoolean("sound_alerts", true)
        val vibrateEnabled = prefs.getBoolean("vibration_alerts", true)
        val confidenceThreshold = prefs.getInt("confidence_threshold", 25) / 100f

        // Dedup
        val hash = hashContent(senderKey, body)
        if (isDuplicate(hash)) {
            Log.d(TAG, "Dedup: skipping duplicate SMS from $senderKey")
            return
        }
        markSeen(hash)

        // Check if active baiting session — forward to AI
        if (baitingManager.isBaitingActive(senderKey)) {
            Log.i(TAG, "Active baiting session for $senderKey — forwarding SMS to AI")
            baitingManager.handleIncomingMessage(senderKey, body)
            return
        }

        // Check known scammer with AI enabled
        val knownScammer = scammerRepository.getScammer(senderKey)
        if (knownScammer?.aiEnabled == true) {
            Log.i(TAG, "Known scammer with AI enabled: $senderKey — starting baiting")
            baitingManager.startBaitingSession(senderKey, body)
            return
        }

        // Run quick analysis
        val quickResult = detectionRepository.analyzeQuick(text = body, sender = sender)
        Log.i(TAG, "SMS quick: conf=%.2f, suspicious=${quickResult.isSuspicious}, rules=${quickResult.matchedRules.size}"
            .format(quickResult.confidence))

        var alertShown = false

        if (quickResult.isSuspicious || quickResult.matchedRules.isNotEmpty()) {
            if (quickResult.confidence >= confidenceThreshold || quickResult.matchedRules.size >= 2) {
                val result = DetectionResult(ruleVerdict = quickResult, isOffline = true)
                if (result.shouldAlert && !isSenderInCooldown(senderKey)) {
                    val parsed = ParsedNotification(
                        packageName = "sms",
                        sender = sender,
                        text = body,
                        source = InputSource.SMS,
                        timestamp = System.currentTimeMillis()
                    )
                    alertManager.showScamAlert(result, parsed, soundEnabled, vibrateEnabled, senderKey)
                    markSenderAlert(senderKey)
                    scammerRepository.upsertHighRisk(senderKey, body)
                    alertShown = true
                    Log.i(TAG, "SMS ALERT for $senderKey: conf=%.2f".format(quickResult.confidence))
                }
            }
        }

        // Full analysis
        try {
            val fullResult = detectionRepository.analyzeMessage(
                text = body,
                source = InputSource.SMS,
                sender = sender,
                privacyMode = prefs.getBoolean("on_device_only", false),
                skipLocalCache = true,
            )
            if (fullResult.shouldAlert && !alertShown && !isSenderInCooldown(senderKey)) {
                val parsed = ParsedNotification(
                    packageName = "sms",
                    sender = sender,
                    text = body,
                    source = InputSource.SMS,
                    timestamp = System.currentTimeMillis()
                )
                alertManager.showScamAlert(fullResult, parsed, soundEnabled, vibrateEnabled, senderKey)
                markSenderAlert(senderKey)
                scammerRepository.upsertHighRisk(senderKey, body)
                Log.i(TAG, "SMS FULL ALERT for $senderKey: conf=%.2f".format(fullResult.bestConfidence))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Full SMS analysis failed: ${e.message}")
        }
    }

    private fun canonicalSenderId(sender: String): String {
        val trimmed = sender.trim().lowercase()
        val normalized = trimmed
            .replace(Regex("[^a-z0-9+@._-]"), "")
            .replace(Regex("\\s+"), "")
        return if (normalized.isNotBlank()) normalized else trimmed
    }

    private fun hashContent(sender: String, text: String): String {
        val input = "$sender|$text"
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(12)
    }

    private fun isDuplicate(hash: String): Boolean {
        val ts = recentHashes[hash] ?: return false
        return System.currentTimeMillis() - ts < DEDUP_WINDOW_MS
    }

    private fun markSeen(hash: String) {
        recentHashes[hash] = System.currentTimeMillis()
        if (recentHashes.size > MAX_DEDUP) {
            val cutoff = System.currentTimeMillis() - DEDUP_WINDOW_MS
            recentHashes.entries.filter { it.value < cutoff }.forEach { recentHashes.remove(it.key) }
        }
    }

    private fun isSenderInCooldown(senderKey: String): Boolean {
        val last = lastAlertBySender[senderKey] ?: return false
        return System.currentTimeMillis() - last < ALERT_COOLDOWN_MS
    }

    private fun markSenderAlert(senderKey: String) {
        lastAlertBySender[senderKey] = System.currentTimeMillis()
        if (lastAlertBySender.size > MAX_DEDUP) {
            val cutoff = System.currentTimeMillis() - ALERT_COOLDOWN_MS
            lastAlertBySender.entries.filter { it.value < cutoff }.forEach { lastAlertBySender.remove(it.key) }
        }
    }
}
