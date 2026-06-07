package com.scamshield.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import com.scamshield.app.R
import com.scamshield.app.ui.MainActivity

/**
 * Shows a system overlay alert for high-risk detections.
 *
 * Runs as a **short foreground service** so it can be started reliably from
 * [ScamShieldNotificationService] / background (required on modern Android).
 * Inflates views with [Theme.ScamShield] so Material components resolve correctly.
 */
class ScamOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ensureOverlayChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            // Required when started via ContextCompat.startForegroundService (e.g. from NLS / background).
            startAsForegroundService()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Log.w(TAG, "Overlay permission not granted; skipping floating alert")
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }

            val title = intent?.getStringExtra("EXTRA_TITLE") ?: "Scam Detected"
            val message = intent?.getStringExtra("EXTRA_MESSAGE") ?: "Suspicious activity detected."
            val isHighRisk = intent?.getBooleanExtra("EXTRA_HIGH_RISK", true) ?: true
            val sender = intent?.getStringExtra("EXTRA_SENDER") ?: "Unknown"

            showOverlay(title, message, isHighRisk, sender)
        } catch (e: SecurityException) {
            Log.e(TAG, "Overlay permission missing or addView denied", e)
            stopForegroundAndSelf()
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "Invalid window token for overlay", e)
            stopForegroundAndSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start/show overlay", e)
            stopForegroundAndSelf()
        }

        return START_NOT_STICKY
    }

    private fun startAsForegroundService() {
        val notification = buildForegroundNotification()
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun ensureOverlayChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Scam overlay",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Temporary status while a scam alert is shown on screen"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildForegroundNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.settings_overlay))
            .setContentText(getString(R.string.overlay_foreground_status))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
    }

    private fun showOverlay(title: String, message: String, isHighRisk: Boolean, sender: String) {
        detachOverlayView()

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100
        }

        val themedContext = ContextThemeWrapper(this, R.style.Theme_ScamShield)
        val inflater = LayoutInflater.from(this).cloneInContext(themedContext)
        val view = inflater.inflate(R.layout.layout_overlay_alert, null)
        overlayView = view

        val tvTitle = view.findViewById<TextView>(R.id.tvOverlayTitle)
        val tvMessage = view.findViewById<TextView>(R.id.tvOverlayMessage)
        val tvPackage = view.findViewById<TextView>(R.id.tvOverlayPackage)
        val ivIcon = view.findViewById<ImageView>(R.id.ivOverlayIcon)
        val layoutBg = view.findViewById<LinearLayout>(R.id.layoutOverlayBg)
        val btnClose = view.findViewById<ImageView>(R.id.btnCloseOverlay)
        val btnIgnore = view.findViewById<AppCompatButton>(R.id.btnOverlayIgnore)
        val btnBait = view.findViewById<AppCompatButton>(R.id.btnOverlayBait)

        tvTitle.text = title
        tvMessage.text = message
        tvPackage.text = "From: $sender"

        if (isHighRisk) {
            tvTitle.setTextColor(ContextCompat.getColor(this, R.color.risk_high))
            ivIcon.setColorFilter(ContextCompat.getColor(this, R.color.risk_high))
            layoutBg.setBackgroundColor(android.graphics.Color.parseColor("#20F85149"))
        } else {
            tvTitle.setTextColor(ContextCompat.getColor(this, R.color.risk_medium))
            ivIcon.setColorFilter(ContextCompat.getColor(this, R.color.risk_medium))
            layoutBg.setBackgroundColor(android.graphics.Color.parseColor("#20E3B341"))
        }

        btnClose.setOnClickListener { stopForegroundAndSelf() }
        btnIgnore.setOnClickListener { stopForegroundAndSelf() }
        btnBait.setOnClickListener {
            val baitIntent = Intent(this, ScamActionReceiver::class.java).apply {
                action = "com.scamshield.ACTION_BAIT"
                putExtra("sender", sender)
            }
            sendBroadcast(baitIntent)
            stopForegroundAndSelf()
        }

        windowManager.addView(view, layoutParams)
    }

    private fun detachOverlayView() {
        val v = overlayView ?: return
        try {
            if (v.parent != null) {
                windowManager.removeView(v)
            }
        } catch (_: IllegalArgumentException) {
            // Already removed from window manager
        }
        overlayView = null
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        // Short FGS (e.g. shortService) can hit a system time limit; tear down cleanly.
        Log.w(TAG, "Foreground service timeout (startId=$startId); stopping overlay")
        stopForegroundAndSelf()
    }

    override fun onDestroy() {
        detachOverlayView()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScamOverlayService"
        private const val CHANNEL_ID = "scam_overlay_fg"
        private const val NOTIFICATION_ID = 4100
    }
}
