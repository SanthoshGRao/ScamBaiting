package com.scamshield.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
 * Action buttons: Dismiss, View Details, Block Sender, Bait (engage scammer)
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
    }

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannels()
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
        vibrateEnabled: Boolean = true
    ) {
        if (!result.shouldAlert) {
            Log.d(TAG, "Below alert threshold (%.2f), skipping".format(result.bestConfidence))
            return
        }

        val riskTier = getRiskTier(result.bestConfidence)
        val channelId = getChannelForRisk(riskTier)
        val nId = nextNotificationId()

        val title = buildTitle(riskTier, parsed.sender)
        val body = buildBody(result, parsed)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert) // TODO: custom icon
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
                builder.priority = NotificationCompat.PRIORITY_HIGH
                builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            }
            RiskTier.MEDIUM -> {
                builder.priority = NotificationCompat.PRIORITY_DEFAULT
                builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            }
            RiskTier.LOW -> {
                builder.priority = NotificationCompat.PRIORITY_LOW
                builder.setVisibility(NotificationCompat.VISIBILITY_SECRET)
            }
        }

        // Trigger Floating Overlay if enabled and risk is HIGH
        if (riskTier == RiskTier.HIGH) {
            val prefs = context.getSharedPreferences("scamshield_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("floating_overlay", false) && android.provider.Settings.canDrawOverlays(context)) {
                val overlayIntent = Intent(context, ScamOverlayService::class.java).apply {
                    putExtra("EXTRA_TITLE", title)
                    putExtra("EXTRA_MESSAGE", body)
                    putExtra("EXTRA_HIGH_RISK", true)
                    putExtra("EXTRA_SENDER", parsed.sender ?: "Unknown")
                }
                try {
                    ContextCompat.startForegroundService(context, overlayIntent)
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "Cannot start overlay foreground service", e)
                }
            }
        }

        // Add action buttons
        addActionButtons(builder, nId, parsed, result)

        try {
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
        result: DetectionResult
    ) {
        val baseExtras = mapOf(
            EXTRA_MESSAGE_HASH to result.ruleVerdict.matchedRules.hashCode().toString(),
            EXTRA_SENDER to (parsed.sender ?: "unknown"),
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

        // Bait (engage scammer) — only for HIGH/MEDIUM confidence
        if (result.bestConfidence >= 0.5f) {
            builder.addAction(
                android.R.drawable.ic_menu_send,
                "Bait",
                createActionIntent(ACTION_BAIT, notificationId, baseExtras)
            )
        }
    }

    /**
     * Create a PendingIntent for a notification action button.
     */
    private fun createActionIntent(
        action: String,
        notificationId: Int,
        extras: Map<String, String>
    ): PendingIntent {
        val intent = Intent(action).apply {
            setPackage(context.packageName)
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

    /** Dismiss a specific alert notification */
    fun dismiss(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }
}

/** Risk tier for notification priority */
enum class RiskTier { HIGH, MEDIUM, LOW }
