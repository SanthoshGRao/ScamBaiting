package com.scamshield.app.service

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.TextUtils
import android.util.Log

/**
 * Rebinds [ScamShieldNotificationService] after process death, force-stop, or cache/storage clears.
 *
 * On many OEM ROMs the listener stays disconnected until the user opens the app or toggles access.
 * [NotificationListenerService.requestRebind] (API 24+) asks the system to attach again without reinstall.
 */
object NotificationListenerReviver {

    private const val TAG = "NLSReviver"
    private val lock = Any()
    private var lastUiPingMs = 0L
    private const val UI_DEBOUNCE_MS = 25_000L

    /** Call once when the Application process starts (cold start). */
    fun pingColdStart(context: Context) {
        requestRebindInner(context.applicationContext, "cold_start")
    }

    /**
     * Call when the main UI becomes visible — covers “swiped away then reopened” without full reinstall.
     * Debounced so rapid fragment switches do not spam the system.
     */
    fun pingFromUi(context: Context) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (now - lastUiPingMs < UI_DEBOUNCE_MS) return
            lastUiPingMs = now
        }
        requestRebindInner(context.applicationContext, "ui_resume")
    }

    /** Boot / package-update paths — no debounce. */
    fun pingAfterPackageEvent(context: Context) {
        requestRebindInner(context.applicationContext, "package_event")
    }

    private fun requestRebindInner(context: Context, reason: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.d(TAG, "requestRebind skipped (API < 24), reason=$reason")
            return
        }
        if (!isOurListenerEnabled(context)) {
            Log.w(TAG, "Notification listener permission off — enable ScamShield in notification access settings ($reason)")
            return
        }
        try {
            val cn = ComponentName(context, ScamShieldNotificationService::class.java)
            NotificationListenerService.requestRebind(cn)
            Log.i(TAG, "requestRebind ok ($reason)")
        } catch (e: Exception) {
            Log.e(TAG, "requestRebind failed ($reason)", e)
        }
    }

    private fun isOurListenerEnabled(context: Context): Boolean {
        val cn = ComponentName(context, ScamShieldNotificationService::class.java)
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(flat)
        for (component in splitter) {
            val parsed = ComponentName.unflattenFromString(component.trim()) ?: continue
            if (parsed.packageName == cn.packageName && parsed.className == cn.className) return true
        }
        return false
    }
}
