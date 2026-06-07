package com.scamshield.app.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.scamshield.app.data.local.dao.DetectionCacheDao
import com.scamshield.app.data.local.dao.ScamKeywordDao
import com.scamshield.app.data.local.dao.BaitingDao
import com.scamshield.app.data.local.dao.SenderHistoryDao
import com.scamshield.app.data.ScammerRepository
import com.scamshield.app.data.remote.AnalyticsSummaryDto
import com.scamshield.app.data.local.entity.BaitingSessionEntity
import com.scamshield.app.data.local.entity.DetectionCacheEntity
import com.scamshield.app.detection.DetectionRepository
import com.scamshield.app.detection.DetectionResult
import com.scamshield.app.detection.KeywordMatcher
import com.scamshield.app.detection.model.InputSource
import com.scamshield.app.service.BaitingManager
import com.scamshield.app.data.local.entity.OfflineAnalyticsEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MainViewModel — Shared ViewModel for all fragments.
 *
 * Handles:
 * - Quick test message analysis (Dashboard)
 * - Detection history queries (Analytics)
 * - Keyword count and cache management (Settings)
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val detectionRepository: DetectionRepository,
    private val cacheDao: DetectionCacheDao,
    private val keywordDao: ScamKeywordDao,
    private val baitingDao: BaitingDao,
    private val senderHistoryDao: SenderHistoryDao,
    private val scammerRepository: ScammerRepository,
    private val keywordMatcher: KeywordMatcher,
    private val baitingManager: BaitingManager
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "MainViewModel"
        private const val PREFS = "feedback_store"
        /** Bump so [ScamShieldNotificationService] clears in-memory dedup / alert cooldown maps. */
        private const val PREFS_APP = "scamshield_prefs"
        private const val KEY_DETECTION_RUNTIME_GENERATION = "detection_runtime_generation"
    }

    // --- Dashboard ---
    private val _analysisResult = MutableLiveData<DetectionResult?>()
    val analysisResult: LiveData<DetectionResult?> = _analysisResult

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    // --- Analytics ---
    private val _detectionHistory = MutableLiveData<List<DetectionCacheEntity>>(emptyList())
    val detectionHistory: LiveData<List<DetectionCacheEntity>> = _detectionHistory

    private val _baitingSessions = MutableLiveData<List<BaitingSessionEntity>>(emptyList())
    val baitingSessions: LiveData<List<BaitingSessionEntity>> = _baitingSessions

    // --- Settings ---
    private val _keywordCount = MutableLiveData(0)
    val keywordCount: LiveData<Int> = _keywordCount
    private val _analyticsSummary = MutableLiveData<AnalyticsSummaryDto?>()
    val analyticsSummary: LiveData<AnalyticsSummaryDto?> = _analyticsSummary

    val engineStatus: StateFlow<com.scamshield.app.service.EngineStatus> = baitingManager.engineStatus.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = com.scamshield.app.service.EngineStatus.ONLINE
    )

    private val _offlineAnalytics = MutableLiveData<List<OfflineAnalyticsEntity>>(emptyList())
    val offlineAnalytics: LiveData<List<OfflineAnalyticsEntity>> = _offlineAnalytics

    /**
     * Analyze a message using the full detection pipeline.
     */
    fun analyzeMessage(text: String, demoMode: Boolean = false) {
        _isLoading.value = true
        _analysisResult.value = null

        viewModelScope.launch {
            try {
                val result = if (demoMode) {
                    val quick = detectionRepository.analyzeQuick(text)
                    DetectionResult(ruleVerdict = quick, isOffline = true)
                } else {
                    detectionRepository.analyzeMessage(
                        text = text,
                        source = InputSource.MANUAL,
                        skipLocalCache = true,
                    )
                }
                _analysisResult.postValue(result)
            } catch (e: Exception) {
                Log.e(TAG, "Analysis failed", e)
                // On failure, run quick analysis (rule-only)
                val quickVerdict = detectionRepository.analyzeQuick(text)
                _analysisResult.postValue(
                    DetectionResult(ruleVerdict = quickVerdict, isOffline = true)
                )
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    /**
     * Load detection history from cache table.
     * Shows ALL sessions (active + stopped) so user can manage them.
     */
    fun loadAnalytics() {
        viewModelScope.launch {
            try {
                val history = cacheDao.getAll()
                _detectionHistory.postValue(history)
                
                val allBaiting = baitingDao.getAllSessions()
                _baitingSessions.postValue(allBaiting)
                _analyticsSummary.postValue(detectionRepository.fetchAnalyticsSummary())
                
                // Fetch offline analytics for the stats popup
                val offlineData = baitingDao.getUnsyncedAnalytics()
                _offlineAnalytics.postValue(offlineData)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load history or baiting logs", e)
            }
        }
    }

    /**
     * Load keyword count for settings display.
     */
    fun loadKeywordCount() {
        viewModelScope.launch {
            try {
                val count = keywordDao.getAllKeywords().size
                _keywordCount.postValue(count)
            } catch (e: Exception) {
                _keywordCount.postValue(keywordMatcher.getCachedKeywordCount())
            }
        }
    }

    /**
     * Clear detection cache, sender reputation, verified scammer list, and baiting history.
     */
    fun clearCache() {
        viewModelScope.launch {
            val appPrefs = getApplication<Application>().getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
            appPrefs.edit()
                .putLong(
                    KEY_DETECTION_RUNTIME_GENERATION,
                    appPrefs.getLong(KEY_DETECTION_RUNTIME_GENERATION, 0L) + 1
                )
                .apply()

            detectionRepository.clearCache()
            scammerRepository.clearAllLocal()
            senderHistoryDao.deleteAll()
            baitingDao.deleteAllSessions()
            baitingDao.deleteAllMessages()
            baitingManager.clearRuntimeState()
            
            _detectionHistory.postValue(emptyList())
            _baitingSessions.postValue(emptyList())
        }
    }

    /**
     * Reload keyword database by invalidating cache.
     */
    fun reloadKeywords() {
        viewModelScope.launch {
            keywordMatcher.invalidateCache()
            loadKeywordCount()
        }
    }

    /**
     * Adaptive Learning: Mark as Safe
     */
    fun markAsSafe(messageId: String) {
        viewModelScope.launch {
            saveFeedbackLocal(messageId, "safe")
            val ok = detectionRepository.submitFeedback(messageId, "safe")
            Log.d(TAG, "Marked message $messageId as SAFE, backend=$ok")
        }
    }

    /**
     * Adaptive Learning: Mark as Scam
     */
    fun markAsScam(messageId: String) {
        viewModelScope.launch {
            saveFeedbackLocal(messageId, "scam")
            val ok = detectionRepository.submitFeedback(messageId, "scam")
            Log.d(TAG, "Marked message $messageId as SCAM, backend=$ok")
        }
    }

    private fun saveFeedbackLocal(messageId: String, label: String) {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(messageId, label).apply()
    }

    /**
     * Manually stop a baiting session from the UI.
     */
    fun stopBaiting(senderId: String) {
        viewModelScope.launch {
            try {
                baitingDao.endSession(senderId)
                Log.i(TAG, "Stopped baiting for $senderId")
                loadAnalytics() // Refresh
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop baiting for $senderId", e)
            }
        }
    }

    /**
     * Manually start/reactivate a baiting session from the UI.
     */
    fun startBaiting(senderId: String) {
        viewModelScope.launch {
            try {
                baitingDao.reactivateSession(senderId)
                Log.i(TAG, "Reactivated baiting for $senderId")
                loadAnalytics() // Refresh
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start baiting for $senderId", e)
            }
        }
    }
}
