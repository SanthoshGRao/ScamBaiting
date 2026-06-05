package com.scamshield.app.di

import com.scamshield.app.BuildConfig
import com.scamshield.app.data.remote.DetectionApiService
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Hilt module providing Retrofit API client and service.
 *
 * Configuration:
 * - Base URL: Configurable (defaults to emulator localhost)
 * - Timeout: generous read window for /detect/full and /bait/reply (LLM latency)
 * - Logging: Body-level in debug, none in release
 * - GSON: lenient parsing, snake_case field names
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private val BASE_URL = BuildConfig.BASE_URL

    /** Ngrok free tier returns an HTML interstitial unless this header is set — breaks JSON login/detection. */
    private val tunnelBypassInterceptor = Interceptor { chain ->
        val req = chain.request().newBuilder()
            .header("ngrok-skip-browser-warning", "true")
            .build()
        chain.proceed(req)
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level =
                if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                else HttpLoggingInterceptor.Level.NONE
        }

        return OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(tunnelBypassInterceptor)
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        val gson = GsonBuilder()
            .setLenient()
            .create()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    @Provides
    @Singleton
    fun provideDetectionApiService(retrofit: Retrofit): DetectionApiService {
        return retrofit.create(DetectionApiService::class.java)
    }
}
