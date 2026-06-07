package com.scamshield.app

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.scamshield.app.BuildConfig
import com.scamshield.app.data.local.ScamShieldDatabase
import com.scamshield.app.detection.DetectionRepository
import com.scamshield.app.data.local.entity.ScamCategoryEntity
import com.scamshield.app.data.local.entity.ScamKeywordEntity
import com.scamshield.app.service.NotificationListenerReviver
import com.scamshield.app.service.ProtectionKeepAliveService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ScamShield Application — Hilt entry point + database seeding.
 *
 * Seeds the Room database with keyword data from bundled
 * scam_categories.json on first launch. Uses SharedPreferences
 * flag to ensure seeding only happens once per DB version.
 */
@HiltAndroidApp
class ScamShieldApp : Application() {

    companion object {
        private const val TAG = "ScamShieldApp"
        private const val PREFS_NAME = "scamshield_prefs"
        private const val KEY_DB_SEEDED = "db_seeded_v1"
    }

    @Inject lateinit var database: ScamShieldDatabase
    @Inject lateinit var detectionRepository: DetectionRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Apply persisted theme mode before UI starts.
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        ensureRealtimeProtectionDefaults(prefs)
        val darkEnabled = prefs.getBoolean("dark_mode_enabled", true)
        AppCompatDelegate.setDefaultNightMode(
            if (darkEnabled) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )

        // Seed database on first launch
        if (!prefs.getBoolean(KEY_DB_SEEDED, false)) {
            appScope.launch {
                seedDatabase()
                prefs.edit().putBoolean(KEY_DB_SEEDED, true).apply()
            }
        }

        // After force-stop / clear cache, the NLS often stays unbound until rebind — do not require reinstall.
        NotificationListenerReviver.pingColdStart(this)
        ProtectionKeepAliveService.start(this)

        appScope.launch {
            val ok = try {
                detectionRepository.isBackendAvailable()
            } catch (e: Exception) {
                Log.e(TAG, "Backend health check threw", e)
                false
            }
            Log.i(TAG, "Backend ${BuildConfig.BASE_URL} reachable=$ok")
            if (!ok) {
                Log.w(
                    TAG,
                    "API unreachable from device — run: cd backend && uvicorn app.main:app --host 0.0.0.0 --port 8000 " +
                        "(and ngrok http 8000 if using BASE_URL tunnel). Toggle Off «On-device analysis» in Settings.",
                )
            }
        }
    }

    /** Fresh installs / cleared storage omit keys — nested prefs defaults alone do not persist realtime_protection. */
    private fun ensureRealtimeProtectionDefaults(prefs: SharedPreferences) {
        if (!prefs.contains("realtime_protection")) {
            prefs.edit()
                .putBoolean("realtime_protection", true)
                .putBoolean("privacy_mode", false)
                .apply()
            Log.i(TAG, "Initialized realtime_protection defaults after missing prefs")
        }
    }

    /**
     * Parse scam_categories.json from assets and populate Room DB.
     */
    private suspend fun seedDatabase() {
        try {
            val json = assets.open("scam_categories.json").bufferedReader().readText()
            val type = object : TypeToken<CategoriesWrapper>() {}.type
            val data: CategoriesWrapper = Gson().fromJson(json, type)

            val keywords = mutableListOf<ScamKeywordEntity>()
            val categories = mutableListOf<ScamCategoryEntity>()

            for (cat in data.categories) {
                categories.add(
                    ScamCategoryEntity(
                        categoryId = cat.id,
                        label = cat.label,
                        description = cat.description,
                        weightBoost = cat.weight_boost
                    )
                )

                for (kw in cat.keywords) {
                    keywords.add(
                        ScamKeywordEntity(
                            keyword = kw,
                            categoryId = cat.id,
                            weight = 0.6f,
                            isRegex = false
                        )
                    )
                }

                for (pat in cat.patterns) {
                    keywords.add(
                        ScamKeywordEntity(
                            keyword = pat,
                            categoryId = cat.id,
                            weight = 0.7f,
                            isRegex = true
                        )
                    )
                }
            }

            database.scamCategoryDao().insertAll(categories)
            database.scamKeywordDao().insertAll(keywords)

            Log.i(TAG, "Database seeded: ${keywords.size} keywords, ${categories.size} categories")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to seed database", e)
        }
    }

    /** JSON wrapper matching scam_categories.json structure */
    private data class CategoriesWrapper(val categories: List<CategoryJson>)
    private data class CategoryJson(
        val id: String,
        val label: String,
        val description: String,
        val keywords: List<String>,
        val patterns: List<String>,
        val weight_boost: Float
    )
}
