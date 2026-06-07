package com.scamshield.app.detection

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textclassifier.TextClassifier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EdgeAiClassifier — On-device Machine Learning for scam detection.
 * 
 * Uses MediaPipe Tasks Text Classification to evaluate messages offline.
 * Requires a "scam_detector.tflite" model file in the assets folder.
 */
@Singleton
class EdgeAiClassifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "EdgeAiClassifier"
        // The expected name of the model file in the assets folder
        private const val MODEL_NAME = "scam_detector.tflite"
    }

    private var textClassifier: TextClassifier? = null
    private var isInitialized = false

    init {
        initializeClassifier()
    }

    private fun initializeClassifier() {
        try {
            // Gracefully check if the model exists in assets to prevent crashes
            val assets = context.assets.list("")
            if (assets == null || !assets.contains(MODEL_NAME)) {
                Log.w(TAG, "Model $MODEL_NAME not found in assets. Edge AI will be disabled.")
                return
            }

            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_NAME)
                .build()
            
            val options = TextClassifier.TextClassifierOptions.builder()
                .setBaseOptions(baseOptions)
                .build()
            
            textClassifier = TextClassifier.createFromOptions(context, options)
            isInitialized = true
            Log.i(TAG, "Edge AI Classifier initialized successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Edge AI Classifier", e)
        }
    }

    /**
     * Classify the given text. Returns a confidence score [0.0, 1.0].
     * Returns null if the classifier isn't initialized or fails.
     */
    fun classify(text: String): Float? {
        if (!isInitialized || textClassifier == null) {
            return null
        }
        
        return try {
            val results = textClassifier?.classify(text)
            var scamScore = 0f
            var foundRelevantCategory = false

            results?.classificationResult()?.classifications()?.firstOrNull()?.categories()?.forEach { category ->
                val name = category.categoryName()?.lowercase() ?: ""
                val index = category.index()
                
                // Typical models use "scam", "spam", "phishing", "1", or just index 1 for the positive class.
                if (name.contains("scam") || name.contains("spam") || name.contains("phish") || name == "1" || index == 1) {
                    scamScore = maxOf(scamScore, category.score())
                    foundRelevantCategory = true
                }
            }
            
            // If the model just outputs a single score without specific names, we'll return it if it's high enough.
            if (!foundRelevantCategory) {
                // Just grab the highest score that isn't explicitly "safe" or "0"
                results?.classificationResult()?.classifications()?.firstOrNull()?.categories()?.forEach { category ->
                     val name = category.categoryName()?.lowercase() ?: ""
                     if (!name.contains("safe") && !name.contains("legit") && name != "0") {
                         scamScore = maxOf(scamScore, category.score())
                     }
                }
            }
            
            scamScore
        } catch (e: Exception) {
            Log.e(TAG, "Edge AI Classification failed", e)
            null
        }
    }
}
