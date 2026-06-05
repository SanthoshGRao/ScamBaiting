package com.scamshield.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.scamshield.app.detection.DetectionRepository
import com.scamshield.app.data.ScammerRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.scamshield.app.detection.model.InputSource
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * ScamShieldAccessibilityService — Reads messages directly from chat screens.
 *
 * Monitors WhatsApp, Telegram, SMS apps for:
 * - Text messages on screen
 * - Media indicators (Photo, Video, Document, APK, Voice)
 * - Links shared in chats
 *
 * Works alongside the NotificationListenerService for complete coverage.
 * Deduplicates with notification pipeline via content hashing.
 */
class ScamShieldAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ScamShieldA11y"
        private const val DEDUP_WINDOW_MS = 60_000L // 1 minute dedup window
        private const val MAX_DEDUP_ENTRIES = 300
        private const val MIN_TEXT_LENGTH = 10 // Skip very short text
        private const val PROCESS_COOLDOWN_MS = 1500L // Don't process same app too frequently
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ServiceEntryPoint {
        fun detectionRepository(): DetectionRepository
        fun scammerRepository(): ScammerRepository
        fun alertManager(): AlertNotificationManager
        fun baitingManager(): BaitingManager
    }

    private var detectionRepository: DetectionRepository? = null
    private var scammerRepository: ScammerRepository? = null
    private var alertManager: AlertNotificationManager? = null
    private var baitingManager: BaitingManager? = null
    private var isInjected = false

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("ScamShieldA11y")
    )

    // Deduplication: hash -> timestamp
    private val recentHashes = ConcurrentHashMap<String, Long>()
    // Per-app cooldown
    private val lastProcessTime = ConcurrentHashMap<String, Long>()

    // Media type indicators found in accessibility node descriptions
    private val MEDIA_INDICATORS = mapOf(
        "photo" to "image",
        "image" to "image",
        "video" to "video",
        "gif" to "image",
        "document" to "document",
        "pdf" to "document",
        "apk" to "apk",
        "voice message" to "audio",
        "audio" to "audio",
        "sticker" to "image",
        "contact card" to "document",
    )

    // File extension detection in text nodes
    private val FILE_EXTENSION_REGEX = Regex(
        """[\w\-. ]+\.(apk|exe|pdf|doc|docx|xls|xlsx|zip|rar|7z|bat|cmd|msi|jar|js|vbs)""",
        RegexOption.IGNORE_CASE
    )

    // URL detection in text nodes
    private val URL_REGEX = Regex(
        """https?://\S+|www\.\S+""",
        RegexOption.IGNORE_CASE
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "AccessibilityService connected — screen reading active")
        injectDependencies()

        // Configure service
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                         AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 500
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    private fun injectDependencies() {
        if (isInjected) return
        try {
            val ep = EntryPointAccessors.fromApplication(
                applicationContext, ServiceEntryPoint::class.java
            )
            detectionRepository = ep.detectionRepository()
            scammerRepository = ep.scammerRepository()
            alertManager = ep.alertManager()
            baitingManager = ep.baitingManager()
            isInjected = true
            Log.i(TAG, "Dependencies injected successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Dependency injection failed: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isInjected) return

        val packageName = event.packageName?.toString() ?: return

        // Cooldown per app to avoid processing every frame
        val now = System.currentTimeMillis()
        val lastTime = lastProcessTime[packageName] ?: 0L
        if (now - lastTime < PROCESS_COOLDOWN_MS) return
        lastProcessTime[packageName] = now

        // Process the event
        val rootNode = rootInActiveWindow ?: return
        try {
            processNode(rootNode, packageName)
        } catch (e: Exception) {
            Log.w(TAG, "Error processing accessibility event: ${e.message}")
        } finally {
            rootNode.recycle()
        }
    }

    private fun processNode(node: AccessibilityNodeInfo, packageName: String) {
        val extractedTexts = mutableListOf<String>()
        val mediaIndicators = mutableListOf<Pair<String, String>>() // (type, description)
        val detectedFiles = mutableListOf<String>()
        val detectedLinks = mutableListOf<String>()

        // Traverse the view tree
        traverseNode(node, extractedTexts, mediaIndicators, detectedFiles, detectedLinks)

        // Process chat messages (last few visible messages in the chat)
        val combinedText = extractedTexts
            .filter { it.length >= MIN_TEXT_LENGTH }
            .takeLast(5) // Only process last 5 visible messages
            .joinToString("\n")

        if (combinedText.isNotBlank()) {
            val hash = hashText(combinedText)
            if (!isDuplicate(hash)) {
                serviceScope.launch {
                    processScreenText(combinedText, packageName, hash)
                }
            }
        }

        // Process media indicators
        for ((mediaType, description) in mediaIndicators) {
            val mediaHash = hashText("media:$mediaType:$description")
            if (!isDuplicate(mediaHash)) {
                serviceScope.launch {
                    processMediaDetection(mediaType, description, combinedText, packageName)
                }
            }
        }

        // Process detected file names (APK, EXE, etc.)
        for (filename in detectedFiles) {
            val fileHash = hashText("file:$filename")
            if (!isDuplicate(fileHash)) {
                serviceScope.launch {
                    processFileDetection(filename, combinedText, packageName)
                }
            }
        }

        // Process detected links
        for (link in detectedLinks) {
            val linkHash = hashText("link:$link")
            if (!isDuplicate(linkHash)) {
                serviceScope.launch {
                    processLinkDetection(link, combinedText, packageName)
                }
            }
        }
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        texts: MutableList<String>,
        media: MutableList<Pair<String, String>>,
        files: MutableList<String>,
        links: MutableList<String>,
    ) {
        // Extract text content
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()

        if (!text.isNullOrBlank()) {
            texts.add(text)

            // Check for file extensions in text
            FILE_EXTENSION_REGEX.findAll(text).forEach { match ->
                files.add(match.value)
            }

            // Check for URLs
            URL_REGEX.findAll(text).forEach { match ->
                links.add(match.value)
            }
        }

        // Check content descriptions for media indicators
        if (!desc.isNullOrBlank()) {
            val descLower = desc.lowercase()
            for ((indicator, type) in MEDIA_INDICATORS) {
                if (indicator in descLower) {
                    media.add(type to desc)
                    break
                }
            }

            // Also check descriptions for file names
            FILE_EXTENSION_REGEX.findAll(desc).forEach { match ->
                files.add(match.value)
            }
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                traverseNode(child, texts, media, files, links)
            } finally {
                child.recycle()
            }
        }
    }

    // --- Processing Methods ---

    private suspend fun processScreenText(text: String, packageName: String, hash: String) {
        Log.d(TAG, "Processing screen text from $packageName (${text.length} chars)")
        try {
            val repo = detectionRepository ?: return
            val result = repo.analyzeMessage(
                text = text,
                sender = "screen:$packageName",
                source = InputSource.NOTIFICATION,
                skipLocalCache = true,
            )

            if (result.shouldAlert) {
                Log.w(TAG, "SCAM detected on screen from $packageName: ${result.bestCategory}")
                alertManager?.showScamDetectedNotification(
                    sender = getAppLabel(packageName),
                    messageText = text.take(200),
                    result = result,
                    originalPackage = packageName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Screen text analysis failed: ${e.message}")
        }
    }

    private suspend fun processMediaDetection(
        mediaType: String, description: String, context: String, packageName: String
    ) {
        Log.d(TAG, "Media detected on screen: type=$mediaType, desc=$description")
        try {
            val repo = detectionRepository ?: return
            // Send to backend with media metadata
            val analysisText = if (context.isNotBlank()) {
                "[$mediaType shared] $context"
            } else {
                "[$mediaType shared: $description]"
            }
            val result = repo.analyzeMessage(
                text = analysisText,
                sender = "screen:$packageName",
                source = InputSource.NOTIFICATION,
                mediaType = mediaType,
                mediaFilename = description,
                skipLocalCache = true,
            )

            if (result.shouldAlert) {
                Log.w(TAG, "Suspicious media on screen: $mediaType from $packageName")
                alertManager?.showScamDetectedNotification(
                    sender = getAppLabel(packageName),
                    messageText = "⚠️ Suspicious $mediaType detected: $description",
                    result = result,
                    originalPackage = packageName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Media analysis failed: ${e.message}")
        }
    }

    private suspend fun processFileDetection(
        filename: String, context: String, packageName: String
    ) {
        Log.w(TAG, "Suspicious file detected on screen: $filename from $packageName")
        try {
            val repo = detectionRepository ?: return
            val result = repo.analyzeMessage(
                text = "[file shared: $filename] $context",
                sender = "screen:$packageName",
                source = InputSource.NOTIFICATION,
                mediaType = if (filename.lowercase().endsWith(".apk")) "apk" else "document",
                mediaFilename = filename,
                skipLocalCache = true,
            )

            // APKs should ALWAYS trigger alert
            if (result.shouldAlert || filename.lowercase().endsWith(".apk")) {
                alertManager?.showScamDetectedNotification(
                    sender = getAppLabel(packageName),
                    messageText = "🚨 Dangerous file detected: $filename",
                    result = result,
                    originalPackage = packageName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "File analysis failed: ${e.message}")
        }
    }

    private suspend fun processLinkDetection(
        link: String, context: String, packageName: String
    ) {
        Log.d(TAG, "Link detected on screen: $link from $packageName")
        try {
            val repo = detectionRepository ?: return
            val result = repo.analyzeMessage(
                text = "$link $context",
                sender = "screen:$packageName",
                source = InputSource.NOTIFICATION,
                mediaType = "link",
                mediaFilename = link,
                skipLocalCache = true,
            )

            if (result.shouldAlert) {
                alertManager?.showScamDetectedNotification(
                    sender = getAppLabel(packageName),
                    messageText = "⚠️ Suspicious link: $link",
                    result = result,
                    originalPackage = packageName
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Link analysis failed: ${e.message}")
        }
    }

    // --- Utilities ---

    private fun getAppLabel(packageName: String): String {
        return try {
            val pm = applicationContext.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast(".")
        }
    }

    private fun hashText(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray())
        return bytes.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun isDuplicate(hash: String): Boolean {
        val now = System.currentTimeMillis()
        // Cleanup old entries
        if (recentHashes.size > MAX_DEDUP_ENTRIES) {
            recentHashes.entries.removeIf { now - it.value > DEDUP_WINDOW_MS }
        }
        val existing = recentHashes[hash]
        if (existing != null && now - existing < DEDUP_WINDOW_MS) {
            return true
        }
        recentHashes[hash] = now
        return false
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.i(TAG, "AccessibilityService destroyed")
    }
}
