package com.scamshield.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BootReceiver — Ensures ScamShield service starts after device reboot.
 *
 * NotificationListenerService is automatically restarted by the system
 * if the user has granted permission. This receiver logs the event
 * and can perform any post-boot initialization needed.
 *
 * Also handles MY_PACKAGE_REPLACED for app updates.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.i(TAG, "Device booted — ScamShield NLS will auto-restart by system")
                // NotificationListenerService auto-restarts if permission is granted
                // No explicit start needed
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "App updated — ScamShield NLS will re-bind")
            }
            else -> {
                Log.d(TAG, "Received: ${intent.action}")
            }
        }
    }
}
