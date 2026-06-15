package com.scamshield.app.detection

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EdgeAiClassifier — On-device Machine Learning for scam detection.
 * 
 * Uses standard TensorFlow Lite Interpreter.
 * Requires "scam_detector.tflite" and "vocab.json" in the assets folder.
 */
@Singleton
class EdgeAiClassifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "EdgeAiClassifier"
        private const val MODEL_NAME = "scam_detector.tflite"
        private const val VOCAB_NAME = "vocab.json"
        private const val MAX_LENGTH = 256
    }

    private var interpreter: Interpreter? = null
    private val vocab = mutableMapOf<String, Int>()
    private var isInitialized = false

    init {
        initializeClassifier()
    }

    private fun initializeClassifier() {
        try {
            val assets = context.assets.list("")
            if (assets == null || !assets.contains(MODEL_NAME)) {
                Log.w(TAG, "Model $MODEL_NAME not found in assets. Edge AI will be disabled.")
                return
            }

            // Load Vocab
            if (assets.contains(VOCAB_NAME)) {
                val vocabJson = context.assets.open(VOCAB_NAME).bufferedReader().use { it.readText() }
                val jsonObject = JSONObject(vocabJson)
                val keys = jsonObject.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    vocab[key] = jsonObject.getInt(key)
                }
            } else {
                Log.w(TAG, "Vocab $VOCAB_NAME not found in assets.")
            }

            // Load Model
            val modelBuffer = loadModelFile(MODEL_NAME)
            val options = Interpreter.Options()
            interpreter = Interpreter(modelBuffer, options)
            
            isInitialized = true
            Log.i(TAG, "Edge AI Classifier initialized successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Edge AI Classifier", e)
        }
    }

    private fun loadModelFile(modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Classify the given text. Returns a confidence score [0.0, 1.0].
     * Returns null if the classifier isn't initialized or fails.
     */
    fun classify(text: String): Float? {
        if (!isInitialized || interpreter == null) {
            return null
        }
        
        return try {
            // Tokenize string -> IntArray (FloatArray for TFLite)
            val words = text.lowercase().replace(Regex("[^a-z0-9 ]"), "").split("\\s+".toRegex())
            val sequence = FloatArray(MAX_LENGTH) { 0f }
            
            for (i in words.indices) {
                if (i >= MAX_LENGTH) break
                val word = words[i]
                sequence[i] = (vocab[word] ?: vocab["<oov>"] ?: 0).toFloat()
            }
            
            val inputBuffer = Array(1) { sequence }
            val outputBuffer = Array(1) { FloatArray(1) }
            
            interpreter?.run(inputBuffer, outputBuffer)
            
            outputBuffer[0][0]
        } catch (e: Exception) {
            Log.e(TAG, "Edge AI Classification failed", e)
            null
        }
    }
}
