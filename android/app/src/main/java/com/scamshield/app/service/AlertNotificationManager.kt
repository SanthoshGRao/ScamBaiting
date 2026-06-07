package com.scamshield.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.scamshield.app.R
import java.security.MessageDigest
import com.scamshield.app.detection.DetectionResult
import com.scamshield.app.util.formatCategoryLabel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AlertNotificationManager — Creates and manages scam alert notifications.
 *
 * Tiered priority system:
 * - HIGH risk → heads-up notification, visible on lockscreen
 * - MEDIUM risk → normal priority notification
 * - LOW risk → silent notification
 *
 * Action buttons: Details, Block (when sender known), Bait (engage scammer).
 * [senderIdForActions] must match scammers.phoneNumber (canonical sender key).
 *
 * Uses Android notification channels for user-controllable priorities.
 */
@Singleton
class AlertNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AlertNotificationMgr"

        // Notification channels
        const val CHANNEL_HIGH = "scam_alert_high"
        const val CHANNEL_MEDIUM = "scam_alert_medium"
        const val CHANNEL_LOW = "scam_alert_low"

        // Intent actions
        const val ACTION_DISMISS = "com.scamshield.ACTION_DISMISS"
        const val ACTION_VIEW = "com.scamshield.ACTION_VIEW_DETAILS"
        const val ACTION_BLOCK = "com.scamshield.ACTION_BLOCK"
        const val ACTION_BAIT = "com.scamshield.ACTION_BAIT"

        // Intent extras
        const val EXTRA_MESSAGE_HASH = "message_hash"
        const val EXTRA_SENDER = "sender"
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_CONFIDENCE = "confidence"
        const val EXTRA_TEXT = "text"

        private var notificationId = 1000

        private const val PREFS_NAME = "scamshield_prefs"
        private const val PREF_PENDING_ALERT_PREFIX = "pending_scam_alert_id_"
    }

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    init {
        createNotificationChannels()
    }

    /** Align with [com.scamshield.app.data.ScammerRepository] canonical ids for DB / toggles / bait. */
    private fun canonicalSenderId(sender: String): String {
        val trimmed = sender.trim().lowercase()
        val normalized = trimmed
            .replace(Regex("[^a-z0-9+@._-]"), "")
            .replace(Regex("\\s+"), "")
        return if (normalized.isNotBlank()) normalized else trimmed
    }

    private fun digestKey(senderId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(senderId.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun pendingPrefKey(senderId: String) = PREF_PENDING_ALERT_PREFIX + digestKey(senderId)

    /** Remember which notification id belongs to this scammer so the UI toggle can dismiss it. */
    private fun registerPendingScamAlert(senderId: String, notificationId: Int) {
        if (senderId.isBlank()) return
        val key = pendingPrefKey(senderId)
        val prev = prefs.getInt(key, -1)
        if (prev != -1 && prev != notificationId) {
            notificationManager.cancel(prev)
        }
        prefs.edit().putInt(key, notificationId).apply()
    }

    /** Cancel the pending scam alert for this sender (e.g. user enabled AI from Scammers screen). */
    fun dismissPendingScamAlertForSender(senderId: String) {
        if (senderId.isBlank()) return
        val key = pendingPrefKey(senderId)
        val id = prefs.getInt(key, -1)
        if (id != -1) notificationManager.cancel(id)
        prefs.edit().remove(key).apply()
    }

    /** Clear tracking after the notification was dismissed by id (receiver actions). */
    fun clearPendingScamAlertTracking(senderId: String) {
        if (senderId.isBlank()) return
        prefs.edit().remove(pendingPrefKey(senderId)).apply()
    }

    /**
     * Show a scam alert notification based on detection result.
     *
     * @param result Detection result from the pipeline.
     * @param parsed Parsed notification data (sender, app).
     * @param soundEnabled Whether to play alert sound (from settings).
     * @param vibrateEnabled Whether to vibrate on alert (from settings).
     */
    fun showScamAlert(
        result: DetectionResult,
        parsed: ParsedNotification,
        soundEnabled: Boolean = true,
        vibrateEnabled: Boolean = true,
        senderIdForActions: String? = null
    ) {
        if (!result.shouldAlert) {
            Log.d(TAG, "Below alert threshold (%.2f), skipping".format(result.bestConfidence))
            return
        }

        val riskTier = getRiskTier(result.bestConfidence)
        val channelId = getChannelForRisk(riskTier)
        if (!canPostNotifications(channelId)) return

        val nId = nextNotificationId()

        val actionSenderId = senderIdForActions?.takeIf { it.isNotBlank() }
            ?: canonicalSenderId(parsed.sender ?: "").ifBlank { "unknown" }

        val title = buildTitle(riskTier, parsed.sender)
        val body = buildBody(result, parsed)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_shield)
            .setLargeIcon(android.graphics.BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher_foreground))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setGroup("scam_alerts")

        // === Sound & Vibration from user settings ===
        if (!soundEnabled) {
            builder.setSound(null)
            builder.setSilent(true)
        }
        if (!vibrateEnabled) {
            builder.setVibrate(longArrayOf(0))
        } else {
            // Custom vibration pattern for different risk levels
            when (riskTier) {
                RiskTier.HIGH -> builder.setVibrate(longArrayOf(0, 300, 200, 300))
                RiskTier.MEDIUM -> builder.setVibrate(longArrayOf(0, 200, 100, 200))
                RiskTier.LOW -> builder.setVibrate(longArrayOf(0, 100))
            }
        }

        // Set priority based on risk tier
        when (riskTier) {
            RiskTier.HIGH -> {
                builder.setPriority(NotificationCompat.PRIORITY_HIGH)
                builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            }
            RiskTier.MEDIUM -> {
                builder.setPriority(NotificationCompat.PRIORITY_DEFAULT)
                builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            }
            RiskTier.LOW -> {
                builder.setPriority(NotificationCompat.PRIORITY_LOW)
                builder.setVisibility(NotificationCompat.VISIBILITY_SECRET)
            }
        }

        // (Overlay popup removed — notification is sufficient)

        // Add action buttons
        addActionButtons(builder, nId, parsed, result, actionSenderId)

        try {
            registerPendingScamAlert(actionSenderId, nId)
            notificationManager.notify(nId, builder.build())
            Log.i(
                TAG,
                "Scam alert shown: id=$nId, risk=$riskTier, " +
                    "conf=%.2f, sender=${parsed.sender}".format(result.bestConfidence)
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot show notification — permission denied", e)
        }
    }

    /**
     * Add action buttons to the notification.
     * Dismiss, View Details, Block Sender, Bait
     */
    private fun addActionButtons(
        builder: NotificationCompat.Builder,
        notificationId: Int,
        parsed: ParsedNotification,
        result: DetectionResult,
        senderIdForActions: String
    ) {
        val baseExtras = mapOf(
            EXTRA_MESSAGE_HASH to result.ruleVerdict.matchedRules.hashCode().toString(),
            EXTRA_SENDER to senderIdForActions,
            EXTRA_CATEGORY to result.bestCategory,
            EXTRA_CONFIDENCE to result.bestConfidence.toString(),
            EXTRA_TEXT to parsed.text
        )

        // View Details
        builder.addAction(
            android.R.drawable.ic_menu_info_details,
            "Details",
            createActionIntent(ACTION_VIEW, notificationId, baseExtras)
        )

        // Block Sender
        if (parsed.sender != null) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Block",
                createActionIntent(ACTION_BLOCK, notificationId, baseExtras)
            )
        }

        builder.addAction(
            R.drawable.ic_chat,
            "Bait",
            createActionIntent(ACTION_BAIT, notificationId, baseExtras)
        )
    }

    /**
     * Create a PendingIntent for a notification action button.
     */
    private fun createActionIntent(
        action: String,
        notificationId: Int,
        extras: Map<String, String>
    ): PendingIntent {
        val intent = Intent(context, ScamActionReceiver::class.java).apply {
            this.action = action
            extras.forEach { (key, value) -> putExtra(key, value) }
            putExtra("notification_id", notificationId)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        return PendingIntent.getBroadcast(
            context, notificationId + action.hashCode(), intent, flags
        )
    }

    // --- Channel Management ---

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channels = listOf(
            NotificationChannel(
                CHANNEL_HIGH,
                "Scam Alerts — High Risk",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical scam detection alerts"
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            },
            NotificationChannel(
                CHANNEL_MEDIUM,
                "Scam Alerts — Medium Risk",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Suspicious message alerts"
                setShowBadge(true)
            },
            NotificationChannel(
                CHANNEL_LOW,
                "Scam Alerts — Low Risk",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Low confidence scam alerts"
                setShowBadge(false)
            }
        )

        channels.forEach { notificationManager.createNotificationChannel(it) }
        Log.d(TAG, "Notification channels created: ${channels.size}")
    }

    private fun canPostNotifications(channelId: String): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.e(TAG, "Cannot show scam alert — app notifications are disabled or POST_NOTIFICATIONS is denied")
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = notificationManager.getNotificationChannel(channelId)
            if (channel?.importance == NotificationManager.IMPORTANCE_NONE) {
                Log.e(TAG, "Cannot show scam alert — notification channel $channelId is disabled")
                return false
            }
        }
        return true
    }

    // --- Helpers ---

    private fun buildTitle(risk: RiskTier, sender: String?): String {
        val level = when (risk) {
            RiskTier.HIGH -> "SCAM DETECTED"
            RiskTier.MEDIUM -> "Suspicious Message"
            RiskTier.LOW -> "Possible Scam"
        }
        return if (sender != null) "$level — $sender" else level
    }

    private fun buildBody(result: DetectionResult, parsed: ParsedNotification): String {
        val category = result.bestCategory.formatCategoryLabel()
        val conf = (result.bestConfidence * 100).toInt()
        val source = parsed.packageName.substringAfterLast(".")
        val mode = result.statusMessage

        return "Category: $category\nConfidence: $conf%\nSource: $source\n$mode"
    }

    private fun getRiskTier(confidence: Float): RiskTier = when {
        confidence >= 0.70f -> RiskTier.HIGH
        confidence >= 0.45f -> RiskTier.MEDIUM
        else -> RiskTier.LOW
    }

    private fun getChannelForRisk(tier: RiskTier): String = when (tier) {
        RiskTier.HIGH -> CHANNEL_HIGH
        RiskTier.MEDIUM -> CHANNEL_MEDIUM
        RiskTier.LOW -> CHANNEL_LOW
    }

    @Synchronized
    private fun nextNotificationId(): Int = ++notificationId

    /**
     * Convenience method for AccessibilityService-based alerts.
     * Creates a synthetic ParsedNotification and delegates to showScamAlert.
     *
     * @param sender Display name of the sender / source app.
     * @param messageText Summary text to show in the notification.
     * @param result Detection result from the pipeline.
     * @param originalPackage Source app package name.
     */
    fun showScamDetectedNotification(
        sender: String,
        messageText: String,
        result: DetectionResult,
        originalPackage: String
    ) {
        val syntheticParsed = ParsedNotification(
            packageName = originalPackage,
            sender = sender,
            text = messageText,
            source = com.scamshield.app.detection.model.InputSource.NOTIFICATION,
            timestamp = System.currentTimeMillis()
        )
        val actionId = canonicalSenderId(sender).ifBlank { "screen:$originalPackage" }
        showScamAlert(result = result, parsed = syntheticParsed, senderIdForActions = actionId)
    }

    /** Dismiss a specific alert notification */
    fun dismiss(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }
}

/** Risk tier for notification priority */
enum class RiskTier { HIGH, MEDIUM, LOW }
