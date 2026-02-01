package com.example.jibberish.managers

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * MediaPipeLLMService handles local LLM inference using Google MediaPipe GenAI.
 * Uses Gemma 2B GPU-INT4 quantized model for on-device inference.
 */
class MediaPipeLLMService(private val context: Context) {

    private var llmInference: LlmInference? = null
    private var isInitialized = false

    /**
     * Initialize the MediaPipe LLM with the specified model file path.
     *
     * @param modelPath Absolute path to the model file (e.g., gemma-2b-it-gpu-int4.bin)
     */
    suspend fun initialize(modelPath: String) = withContext(Dispatchers.IO) {
        try {
            // Verify model file exists
            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                throw IllegalArgumentException("Model file not found at: $modelPath")
            }

            // Configure LlmInference options
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(512) // Maximum tokens to generate
                .build()

            // Create LlmInference instance
            llmInference = LlmInference.createFromOptions(context, options)
            isInitialized = true

        } catch (e: Exception) {
            isInitialized = false
            throw Exception("Failed to initialize MediaPipe LLM: ${e.message}", e)
        }
    }

    /**
     * Generate text response using the local LLM.
     * Includes post-processing to remove hallucinated "thank you" repetitions.
     *
     * @param prompt The input prompt/question for the model
     * @return Generated text response (cleaned)
     */
    suspend fun generateText(prompt: String): String = withContext(Dispatchers.IO) {
        if (!isInitialized || llmInference == null) {
            throw IllegalStateException("MediaPipe LLM not initialized. Call initialize() first.")
        }

        try {
            // Generate response from model
            val response = llmInference!!.generateResponse(prompt)

            // Post-process to remove hallucinated "thank you" repetitions
            val cleanedResponse = cleanResponse(response)

            cleanedResponse
        } catch (e: Exception) {
            throw Exception("Text generation failed: ${e.message}", e)
        }
    }

    /**
     * Generate text response with streaming callback.
     * Note: MediaPipe GenAI may not support true streaming in all versions.
     * This method falls back to generating the full response.
     *
     * @param prompt The input prompt
     * @param onPartialResult Callback invoked with the result
     * @return Final generated text (cleaned)
     */
    suspend fun generateTextStream(
        prompt: String,
        onPartialResult: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        if (!isInitialized || llmInference == null) {
            throw IllegalStateException("MediaPipe LLM not initialized. Call initialize() first.")
        }

        try {
            // Generate full response
            val response = llmInference!!.generateResponse(prompt)
            val cleaned = cleanResponse(response)

            // Invoke callback with full result
            onPartialResult(cleaned)

            cleaned
        } catch (e: Exception) {
            throw Exception("Text generation failed: ${e.message}", e)
        }
    }

    /**
     * Post-processing filter to remove hallucinated "thank you" repetitions.
     * This addresses the issue where the model appends "thank you" repeatedly.
     *
     * Patterns cleaned:
     * - "thank you thank you thank you..."
     * - Trailing "thank you" at end of sentences
     * - Excessive politeness artifacts
     *
     * @param response Raw model output
     * @return Cleaned response
     */
    private fun cleanResponse(response: String): String {
        var cleaned = response.trim()

        // Remove repetitive "thank you" patterns (case-insensitive)
        // Pattern 1: Multiple consecutive "thank you"
        cleaned = cleaned.replace(Regex("(thank\\s+you[\\s,.]*)(?:\\1)+", RegexOption.IGNORE_CASE), "$1")

        // Pattern 2: Trailing "thank you" at end of response (common hallucination)
        cleaned = cleaned.replace(Regex("(\\.)\\s*thank\\s+you[\\s.]*$", RegexOption.IGNORE_CASE), "$1")

        // Pattern 3: Multiple "thank you" separated by punctuation
        cleaned = cleaned.replace(Regex("(thank\\s+you[,.]?\\s*){2,}", RegexOption.IGNORE_CASE), "thank you ")

        // Pattern 4: Remove standalone trailing "thank you" loops
        cleaned = cleaned.replace(Regex("\\s+(thank\\s+you\\s*)+$", RegexOption.IGNORE_CASE), "")

        // Remove excessive whitespace
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()

        return cleaned
    }

    /**
     * Get model information and metadata.
     */
    fun getModelInfo(): String? {
        return if (isInitialized && llmInference != null) {
            "MediaPipe LLM (Gemma 2B GPU-INT4)"
        } else {
            null
        }
    }

    /**
     * Check if the service is initialized and ready.
     */
    fun isReady(): Boolean = isInitialized

    /**
     * Release resources and cleanup.
     * Call this when the service is no longer needed.
     */
    fun cleanup() {
        try {
            llmInference?.close()
            llmInference = null
            isInitialized = false
        } catch (e: Exception) {
            // Silently handle cleanup errors
        }
    }
}
