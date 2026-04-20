package com.scamshield.app.service

import android.app.Notification
import android.os.Bundle
import android.util.Log
import com.scamshield.app.detection.model.InputSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NotificationParser — Extracts message content from Android notifications.
 *
 * Parses notification extras to extract sender, text, and metadata.
 * Implements smart filtering: skips media-only, calls, group messages,
 * and system alerts.
 *
 * Privacy: Never stores or logs raw message text.
 */
@Singleton
class NotificationParser @Inject constructor() {

    companion object {
        private const val TAG = "NotificationParser"

        /** Target app packages for scam detection */
        val MONITORED_PACKAGES = setOf(
            // WhatsApp
            "com.whatsapp",
            "com.whatsapp.w4b",
            // Telegram
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            // Signal / Messenger / Viber
            "org.thoughtcrime.securesms",
            "com.facebook.orca",
            "com.viber.voip",
            // SMS / Messages
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.mms",
            "com.android.messaging",
            // Email
            "com.google.android.gm",           // Gmail
            "com.microsoft.office.outlook",
            // Instagram
            "com.instagram.android",
        )

        /** Notification categories to SKIP */
        private val SKIP_CATEGORIES = setOf(
            Notification.CATEGORY_CALL,
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_SYSTEM,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_ALARM,
        )

        /** Known group indicators in notification text */
        private val GROUP_INDICATORS = listOf(
            " in ", // "User in GroupName"
            " @ ",  // Telegram groups
        )

        /** Line is the user's own message (WhatsApp/Telegram style). */
        private val SELF_MESSAGE_LINE = Regex("(?is)^\\s*you\\s*:.+")

        private val SELF_CHAT_TITLES = setOf("you", "me")
    }

    /**
     * True when the notification represents the user's outgoing message, not an inbound scam.
     */
    private fun isOutgoingOrSelfPreview(title: String?, text: String): Boolean {
        val t = title?.trim()?.lowercase().orEmpty()
        if (t.isNotBlank() && t in SELF_CHAT_TITLES) return true

        val body = text.trim()
        if (body.matches(SELF_MESSAGE_LINE)) return true

        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isNotEmpty() && lines.last().matches(SELF_MESSAGE_LINE)) return true

        val prefixSender = extractSenderFromTextPrefix(body)
        if (prefixSender != null && prefixSender.trim().equals("you", ignoreCase = true)) return true

        return false
    }

    /**
     * Parse a notification into a structured result.
     *
     * @param packageName Source app package.
     * @param notification Android notification object.
     * @return Parsed result, or null if notification should be skipped.
     */
    fun parse(packageName: String, notification: Notification, isSandbox: Boolean = false): ParsedNotification? {
        // Check if monitored app or likely user-message app (fallback)
        if (!isSandbox && packageName !in MONITORED_PACKAGES && !isLikelyMessageNotification(notification)) {
            return null
        }

        // Check category filter
        if (!isSandbox && notification.category in SKIP_CATEGORIES) {
            Log.d(TAG, "Skipped: category=${notification.category}")
            return null
        }

        val extras = notification.extras ?: return null

        // Extract text content
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val effectiveTitle = conversationTitle ?: title
        val text = extractText(extras)

        if (text.isNullOrBlank()) {
            Log.d(TAG, "Skipped: no text content from $packageName")
            return null
        }

        // Smart filter: skip group messages (Phase 1)
        if (isGroupMessage(effectiveTitle, text, packageName)) {
            Log.d(TAG, "Skipped: group message from $packageName")
            return null
        }

        // Outgoing / self preview (title "You", body "You: …", last line in expanded text, etc.)
        if (isOutgoingOrSelfPreview(effectiveTitle, text)) {
            Log.d(TAG, "Skipped: outgoing/self preview from $packageName")
            return null
        }

        // Smart filter: native check for outgoing message via MessagingStyle
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messages != null && messages.isNotEmpty()) {
            try {
                val parsedMessages = android.app.Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages)
                val lastMessage = parsedMessages.lastOrNull()
                if (lastMessage != null) {
                    val lastBody = lastMessage.text?.toString()?.trim().orEmpty()
                    if (lastBody.matches(SELF_MESSAGE_LINE)) {
                        Log.d(TAG, "Skipped: MessagingStyle last line is outgoing (You:) in $packageName")
                        return null
                    }
                    // If senderPerson is null, it typically means the message is from the local user (outgoing)
                    if (lastMessage.senderPerson == null) {
                        Log.d(TAG, "Skipped: local user's outgoing message in $packageName")
                        return null
                    }
                }
            } catch (e: Exception) {
                // Ignore parsing errors, fall through to text regex
            }
        }

        // Strict: only "You:" style (avoid skipping "You won a prize" scams — no colon after You)
        if (text.trim().matches(SELF_MESSAGE_LINE)) {
            Log.d(TAG, "Skipped: self-message line (You:) from $packageName")
            return null
        }

        // Skip media-only notifications
        if (isMediaOnly(text)) {
            Log.d(TAG, "Skipped: media-only from $packageName")
            return null
        }

        val sender = extractSender(effectiveTitle, text)
        val source = mapSource(packageName)

        // Extract actions for auto-replying and mark-as-read support.
        var replyIntent: android.app.PendingIntent? = null
        var remoteInput: android.app.RemoteInput? = null
        var readIntent: android.app.PendingIntent? = null
        var fallbackIntent: android.app.PendingIntent? = null
        var fallbackInput: android.app.RemoteInput? = null

        notification.actions?.forEach { action ->
            if (
                action.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ &&
                readIntent == null
            ) {
                readIntent = action.actionIntent
            }
            action.remoteInputs?.forEach riLoop@{ input ->
                if (input.resultKey.isNullOrBlank()) return@riLoop
                if (input.allowFreeFormInput) {
                    replyIntent = action.actionIntent
                    remoteInput = input
                } else if (fallbackInput == null) {
                    fallbackIntent = action.actionIntent
                    fallbackInput = input
                }
            }
        }
        if (remoteInput == null && fallbackInput != null) {
            replyIntent = fallbackIntent
            remoteInput = fallbackInput
        }

        Log.d(
            TAG,
            "Parsed: pkg=$packageName, sender=$sender, " +
                "textLen=${text.length}, source=${source.value}, hasReply=${replyIntent != null}"
        )

        return ParsedNotification(
            packageName = packageName,
            sender = sender,
            text = text,
            source = source,
            timestamp = System.currentTimeMillis(),
            replyIntent = replyIntent,
            remoteInput = remoteInput,
            readIntent = readIntent
        )
    }

    /**
     * Extract message text from notification extras.
     * Handles BigTextStyle and InboxStyle notifications.
     */
    private fun extractText(extras: Bundle): String? {
        // Try big text first (expanded notification)
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        if (!bigText.isNullOrBlank()) return bigText.trim()

        // Fall back to regular text
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (!text.isNullOrBlank()) return text.trim()

        // Try summary text
        val summary = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
        if (!summary.isNullOrBlank()) return summary.trim()

        // Try text lines (InboxStyle / MessagingStyle)
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (lines.isNotEmpty()) return lines.last()

        return null
    }

    /**
     * Extract sender name from notification title.
     */
    private fun extractSender(title: String?, text: String): String? {
        val textPrefixSender = extractSenderFromTextPrefix(text)

        // WhatsApp: title is sender name directly
        // Telegram: title is sender name or "Telegram"
        // Gmail: title is sender or subject
        val normalizedTitle = title?.trim()
        return when {
            normalizedTitle.isNullOrBlank() -> textPrefixSender
            normalizedTitle.equals("WhatsApp", ignoreCase = true) -> textPrefixSender
            normalizedTitle.equals("Telegram", ignoreCase = true) -> textPrefixSender
            normalizedTitle.equals("Gmail", ignoreCase = true) -> textPrefixSender
            normalizedTitle.equals("Messages", ignoreCase = true) -> textPrefixSender
            else -> normalizedTitle
        }
    }

    /**
     * Common fallback for notifications where sender is prefixed in body:
     * "John Doe: message text"
     */
    private fun extractSenderFromTextPrefix(text: String): String? {
        val separator = text.indexOf(':')
        if (separator <= 1 || separator > 48) return null
        val candidate = text.substring(0, separator).trim()
        if (candidate.isBlank()) return null
        // Avoid false positives for time-like prefixes, e.g. "10:30"
        if (candidate.matches(Regex("\\d{1,2}"))) return null
        return candidate
    }

    /**
     * Check if this is a group message (skip in Phase 1).
     */
    private fun isGroupMessage(title: String?, @Suppress("UNUSED_PARAMETER") text: String, packageName: String): Boolean {
        if (title == null) return false

        // WhatsApp groups: "Sender @ GroupName" or notification has group key
        if (packageName.startsWith("com.whatsapp")) {
            if (GROUP_INDICATORS.any { title.contains(it) }) return true
        }

        // Telegram groups often have "in" indicator
        if (packageName.startsWith("org.telegram")) {
            if (" in " in title) return true
        }

        return false
    }

    /**
     * Check if notification is media-only (photo, video, sticker, etc.).
     */
    private fun isMediaOnly(text: String): Boolean {
        val mediaIndicators = listOf(
            "📷", "📹", "🎵", "📎", "📄", "🎤",
            "photo", "video", "sticker", "gif",
            "voice message", "audio", "document",
            "\uD83D\uDCF7", // camera emoji
        )
        val lowerText = text.lowercase().trim()
        return lowerText.length < 15 &&
            mediaIndicators.any { lowerText.contains(it.lowercase()) }
    }

    /**
     * Map package name to InputSource.
     */
    private fun mapSource(packageName: String): InputSource {
        return when {
            packageName.contains("messaging") || packageName.contains("mms") -> InputSource.SMS
            packageName.contains("gm") || packageName.contains("outlook") -> InputSource.EMAIL
            else -> InputSource.NOTIFICATION
        }
    }

    /**
     * Check if a package is in the monitored list.
     */
    fun isMonitored(packageName: String): Boolean = packageName in MONITORED_PACKAGES

    /**
     * Fallback detector for messaging-style notifications from apps
     * not explicitly listed in MONITORED_PACKAGES.
     */
    private fun isLikelyMessageNotification(notification: Notification): Boolean {
        val extras = notification.extras ?: return false
        val hasText = !extractText(extras).isNullOrBlank()
        val hasConversation = !extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE).isNullOrBlank()
        val hasTitle = !extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank()
        val hasReplyAction = notification.actions?.any { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        } == true
        val isMessageCategory = notification.category == Notification.CATEGORY_MESSAGE
        val textLooksLikeChat = extractText(extras)?.contains(": ") == true
        return hasText && (hasConversation || hasReplyAction || isMessageCategory || (hasTitle && textLooksLikeChat))
    }
}

data class ParsedNotification(
    val packageName: String,
    val sender: String?,
    val text: String,
    val source: InputSource,
    val timestamp: Long,
    val replyIntent: android.app.PendingIntent? = null,
    val remoteInput: android.app.RemoteInput? = null,
    val readIntent: android.app.PendingIntent? = null
)
