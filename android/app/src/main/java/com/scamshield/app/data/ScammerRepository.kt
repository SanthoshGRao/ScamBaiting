package com.scamshield.app.data

import android.util.Log
import com.scamshield.app.BuildConfig
import com.scamshield.app.data.local.dao.BaitingDao
import com.scamshield.app.data.local.dao.ScammerDao
import com.scamshield.app.data.local.entity.BaitingMessageEntity
import com.scamshield.app.data.local.entity.ScammerEntity
import com.scamshield.app.data.remote.ActivateAiRequestDto
import com.scamshield.app.data.remote.DetectionApiService
import com.scamshield.app.data.remote.LoginRequestDto
import com.scamshield.app.data.remote.ScammerRegisterRequestDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class ScammerRepository @Inject constructor(
    private val scammerDao: ScammerDao,
    private val baitingDao: BaitingDao,
    private val apiService: DetectionApiService
) {
    companion object {
        private const val TAG = "ScammerRepository"
    }

    private var bearerToken: String? = null

    suspend fun upsertHighRisk(phone: String, message: String) = withContext(Dispatchers.IO) {
        val normalizedPhone = canonicalSenderId(phone)
        if (normalizedPhone.isBlank()) return@withContext
        scammerDao.upsert(
            ScammerEntity(
                phoneNumber = normalizedPhone,
                lastMessage = message,
                riskLevel = "HIGH",
                isActive = true
            )
        )
        syncRegister(normalizedPhone, message)
    }

    suspend fun getAllScammers(): List<ScammerEntity> = withContext(Dispatchers.IO) { scammerDao.getAll() }

    suspend fun getScammer(phone: String): ScammerEntity? = withContext(Dispatchers.IO) {
        scammerDao.getByPhone(canonicalSenderId(phone))
    }

    suspend fun clearAllLocal() = withContext(Dispatchers.IO) {
        scammerDao.deleteAll()
    }

    suspend fun setAiEnabled(phone: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val normalizedPhone = canonicalSenderId(phone)
        scammerDao.setAiEnabled(normalizedPhone, enabled)
        val token = ensureToken() ?: return@withContext
        try {
            apiService.activateScammerAi("Bearer $token", normalizedPhone, ActivateAiRequestDto(enabled))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync AI state", e)
        }
    }

    suspend fun syncHistory(phone: String) = withContext(Dispatchers.IO) {
        val normalizedPhone = canonicalSenderId(phone)
        val token = ensureToken() ?: return@withContext
        try {
            val response = apiService.getScammerHistory("Bearer $token", normalizedPhone)
            val items = response.body()?.history.orEmpty()
            items.forEach { item ->
                baitingDao.insertMessage(
                    BaitingMessageEntity(
                        senderId = normalizedPhone,
                        role = item.role,
                        content = item.content,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync history", e)
        }
    }

    private fun canonicalSenderId(sender: String): String {
        val trimmed = sender.trim().lowercase()
        val normalized = trimmed
            .replace(Regex("[^a-z0-9+@._-]"), "")
            .replace(Regex("\\s+"), "")
        return if (normalized.isNotBlank()) normalized else trimmed
    }

    private suspend fun syncRegister(phone: String, message: String) {
        val token = ensureToken() ?: return
        try {
            apiService.registerScammer(
                "Bearer $token",
                ScammerRegisterRequestDto(
                    phone_number = phone,
                    last_message = message,
                    risk_level = "HIGH",
                    is_active = true
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Scammer register sync failed", e)
        }
    }

    private suspend fun ensureToken(): String? {
        bearerToken?.let { return it }
        return try {
            val login = apiService.login(
                LoginRequestDto(
                    username = BuildConfig.AUTH_USERNAME,
                    password = BuildConfig.AUTH_PASSWORD
                )
            )
            if (!login.isSuccessful) return null
            login.body()?.access_token?.also { bearerToken = it }
        } catch (e: Exception) {
            null
        }
    }
}
