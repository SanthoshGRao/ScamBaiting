package com.scamshield.app.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.scamshield.app.data.local.dao.SenderHistoryDao
import com.scamshield.app.service.BaitingManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ScamActionReceiver — Handles notification action button clicks.
 *
 * Actions:
 * - DISMISS: Cancel the alert notification
 * - VIEW_DETAILS: Open detail screen with detection info
 * - BLOCK: Mark sender as blocked (scam score → 1.0)
 * - BAIT: Initiate scam-baiting engagement (future StrategyAgent)
 *
 * Uses goAsync() for database operations in broadcast receiver context.
 * Thread-safe via coroutines with SupervisorJob.
 */
@AndroidEntryPoint
class ScamActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScamActionReceiver"
    }

    @Inject lateinit var senderHistoryDao: SenderHistoryDao
    @Inject lateinit var baitingManager: BaitingManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val notificationId = intent.getIntExtra("notification_id", -1)
        val sender = intent.getStringExtra(AlertNotificationManager.EXTRA_SENDER) ?: "unknown"
        val category = intent.getStringExtra(AlertNotificationManager.EXTRA_CATEGORY) ?: "unknown"
        val confidence = intent.getStringExtra(AlertNotificationManager.EXTRA_CONFIDENCE) ?: "0"
        val text = intent.getStringExtra(AlertNotificationManager.EXTRA_TEXT) ?: ""

        Log.i(TAG, "Action: $action, sender=$sender, category=$category")

        when (action) {
            AlertNotificationManager.ACTION_DISMISS -> {
                dismissNotification(context, notificationId)
            }

            AlertNotificationManager.ACTION_VIEW -> {
                dismissNotification(context, notificationId)
                openDetailScreen(context, sender, category, confidence)
            }

            AlertNotificationManager.ACTION_BLOCK -> {
                dismissNotification(context, notificationId)
                blockSender(context, sender)
            }

            AlertNotificationManager.ACTION_BAIT -> {
                dismissNotification(context, notificationId)
                initiateBaiting(context, sender, category, text)
            }

            else -> {
                Log.w(TAG, "Unknown action: $action")
            }
        }
    }

    /**
     * Dismiss the alert notification.
     */
    private fun dismissNotification(context: Context, notificationId: Int) {
        if (notificationId != -1) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notificationId)
            Log.d(TAG, "Dismissed notification: $notificationId")
        }
    }

    /**
     * Open the detection detail screen.
     */
    private fun openDetailScreen(
        context: Context,
        sender: String,
        category: String,
        confidence: String
    ) {
        try {
            // Launch detail activity with detection data
            val intent = Intent(context, Class.forName("com.scamshield.app.ui.DetailActivity")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("sender", sender)
                putExtra("category", category)
                putExtra("confidence", confidence)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open detail screen", e)
            Toast.makeText(context, "Category: $category • Confidence: $confidence", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Block a sender by setting their scam score to maximum.
     * Uses goAsync() pattern for safe DB operations in broadcast receiver.
     */
    private fun blockSender(context: Context, sender: String) {
        val pendingResult = goAsync()

        scope.launch {
            try {
                val existing = senderHistoryDao.getSender(sender)
                if (existing != null) {
                    senderHistoryDao.upsert(
                        existing.copy(
                            scamScore = 1.0f,
                            flaggedMessages = existing.flaggedMessages + 1
                        )
                    )
                } else {
                    senderHistoryDao.upsert(
                        com.scamshield.app.data.local.entity.SenderHistoryEntity(
                            senderId = sender,
                            scamScore = 1.0f,
                            totalMessages = 1,
                            flaggedMessages = 1
                        )
                    )
                }

                Log.i(TAG, "Sender blocked: $sender")

                // Show confirmation on main thread
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Sender \"$sender\" blocked",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to block sender: $sender", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Initiate scam-baiting engagement.
     */
    private fun initiateBaiting(context: Context, sender: String, category: String, initialText: String) {
        val pendingResult = goAsync()

        scope.launch {
            try {
                Log.i(TAG, "Bait initiated: sender=$sender, category=$category")
                
                // Start the baiting session
                baitingManager.startBaitingSession(sender, initialText)

                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Scam-baiting session activated for \"$sender\"",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initiate baiting", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
