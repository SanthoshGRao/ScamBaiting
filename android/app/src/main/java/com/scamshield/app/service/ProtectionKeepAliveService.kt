package com.scamshield.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.scamshield.app.R

class ProtectionKeepAliveService : Service() {
    companion object {
        private const val TAG = "ProtectionKeepAlive"
        private const val CHANNEL_ID = "scamshield_protection"
        private const val NOTIFICATION_ID = 42
        private const val REBIND_INTERVAL_MS = 60_000L

        fun start(context: Context) {
            val intent = Intent(context, ProtectionKeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to start protection keep-alive service", e)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val rebindRunnable = object : Runnable {
        override fun run() {
            NotificationListenerReviver.pingKeepAlive(this@ProtectionKeepAliveService)
            handler.postDelayed(this, REBIND_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        handler.post(rebindRunnable)
        Log.i(TAG, "Protection keep-alive service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationListenerReviver.pingKeepAlive(this)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(rebindRunnable)
        Log.i(TAG, "Protection keep-alive service destroyed")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "Task removed — restarting protection keep-alive service")
        start(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ScamShield Protection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps real-time scam protection active"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_shield)
        .setContentTitle("ScamShield protection active")
        .setContentText("Monitoring messages and notifications for scams")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()
}
