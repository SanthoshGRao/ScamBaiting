package com.scamshield.app.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    private val handler = Handler(Looper.getMainLooper())
    private var lastUiPingMs = 0L
    private var lastComponentNudgeMs = 0L
    private const val UI_DEBOUNCE_MS = 8_000L
    private const val COMPONENT_NUDGE_COOLDOWN_MS = 2 * 60_000L
    private const val LISTENER_STALE_MS = 45_000L

    /** Call once when the Application process starts (cold start). */
    fun pingColdStart(context: Context) {
        requestRebindBurst(context.applicationContext, "cold_start")
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
        requestRebindBurst(context.applicationContext, "ui_resume")
    }

    /** Boot / package-update paths — no debounce. */
    fun pingAfterPackageEvent(context: Context) {
        requestRebindBurst(context.applicationContext, "package_event")
    }

    fun pingKeepAlive(context: Context) {
        val appContext = context.applicationContext
        requestRebindInner(appContext, "keep_alive")
        refreshComponentIfListenerStale(appContext, "keep_alive")
    }

    fun markListenerActive(context: Context, reason: String) {
        context.getSharedPreferences("scamshield_prefs", Context.MODE_PRIVATE)
            .edit()
            .putLong("nls_last_active_at", System.currentTimeMillis())
            .putString("nls_last_active_reason", reason)
            .apply()
    }

    private fun requestRebindBurst(context: Context, reason: String) {
        requestRebindInner(context, reason)
        handler.postDelayed({ requestRebindInner(context, "$reason+1s") }, 1_000L)
        handler.postDelayed({ requestRebindInner(context, "$reason+5s") }, 5_000L)
        handler.postDelayed({ requestRebindInner(context, "$reason+15s") }, 15_000L)
        handler.postDelayed({ refreshComponentIfListenerStale(context, "$reason+stale_check") }, 20_000L)
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

    /**
     * MIUI can leave a listener enabled in Settings but absent from the live listener list after
     * OneKeyClean. Re-enabling our own component forces NotificationManagerService to rescan it.
     */
    private fun refreshComponentIfListenerStale(context: Context, reason: String) {
        if (!isOurListenerEnabled(context)) return

        val lastActiveAt = context.getSharedPreferences("scamshield_prefs", Context.MODE_PRIVATE)
            .getLong("nls_last_active_at", 0L)
        val nowWall = System.currentTimeMillis()
        if (lastActiveAt > 0L && nowWall - lastActiveAt < LISTENER_STALE_MS) return

        val nowElapsed = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (nowElapsed - lastComponentNudgeMs < COMPONENT_NUDGE_COOLDOWN_MS) return
            lastComponentNudgeMs = nowElapsed
        }

        val cn = ComponentName(context, ScamShieldNotificationService::class.java)
        try {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                cn,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            handler.postDelayed({
                try {
                    pm.setComponentEnabledSetting(
                        cn,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    requestRebindInner(context, "$reason+component_nudge")
                    Log.w(TAG, "Notification listener component refreshed ($reason)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to re-enable notification listener component ($reason)", e)
                }
            }, 700L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh notification listener component ($reason)", e)
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
