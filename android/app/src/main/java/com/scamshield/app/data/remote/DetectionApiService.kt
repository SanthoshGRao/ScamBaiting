package com.scamshield.app.data.remote

import com.scamshield.app.detection.model.DetectionRequest
import com.scamshield.app.detection.model.DetectionResponse
import com.scamshield.app.detection.model.FeedbackRequest
import com.scamshield.app.data.remote.AnalyticsSummaryDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Retrofit API interface for ScamShield backend.
 */
interface DetectionApiService {

    @POST("api/v1/detect")
    suspend fun detectScam(
        @Header("Authorization") bearerToken: String,
        @Body request: DetectionRequest
    ): Response<DetectionResponse>

    @POST("api/v1/detect/full")
    suspend fun detectScamFull(
        @Header("Authorization") bearerToken: String,
        @Body request: DetectionRequest
    ): Response<DetectionResponse>

    @GET("api/v1/detect/health")
    suspend fun healthCheck(): Response<Map<String, Any>>

    @POST("api/v1/bait/reply")
    suspend fun generateBaitingReply(
        @Header("Authorization") bearerToken: String,
        @Body request: BaitingRequestDto
    ): Response<BaitingResponseDto>

    @POST("api/v1/auth/login")
    suspend fun login(@Body request: LoginRequestDto): Response<TokenResponseDto>

    @POST("api/v1/detect/feedback")
    suspend fun submitFeedback(
        @Header("Authorization") bearerToken: String,
        @Body request: FeedbackRequest
    ): Response<Map<String, String>>

    @GET("api/v2/analytics/db/summary")
    suspend fun getAnalyticsSummary(
        @Header("Authorization") bearerToken: String
    ): Response<AnalyticsSummaryDto>

    @POST("api/v1/scammer/register")
    suspend fun registerScammer(
        @Header("Authorization") bearerToken: String,
        @Body request: ScammerRegisterRequestDto
    ): Response<Map<String, String>>

    @POST("api/v1/scammer/{scammerId}/activate_ai")
    suspend fun activateScammerAi(
        @Header("Authorization") bearerToken: String,
        @Path("scammerId") scammerId: String,
        @Body request: ActivateAiRequestDto
    ): Response<Map<String, String>>

    @GET("api/v1/scammer/{scammerId}/history")
    suspend fun getScammerHistory(
        @Header("Authorization") bearerToken: String,
        @Path("scammerId") scammerId: String
    ): Response<ScammerHistoryResponseDto>
}
