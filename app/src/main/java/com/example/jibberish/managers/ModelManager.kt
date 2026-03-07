package com.example.jibberish.managers

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ModelManager handles the selection and initialization of LLM models.
 * Supports both Android AICore (Gemini Nano) and local MediaPipe models (Gemma 2B).
 */
class ModelManager(private val context: Context) {

    // DataStore for model preference
    private val Context.modelDataStore: DataStore<Preferences> by preferencesDataStore(name = "model_settings")

    companion object {
        private val MODEL_PREFERENCE_KEY = stringPreferencesKey("selected_model")
        const val MODEL_AICORE = "aicore"
        const val MODEL_MEDIAPIPE = "mediapipe"
    }

    sealed class ModelType {
        object AICore : ModelType()
        object MediaPipe : ModelType()
    }

    sealed class ModelStatus {
        object Checking : ModelStatus()
        data class Ready(val modelType: ModelType) : ModelStatus()
        data class Error(val message: String) : ModelStatus()
    }

    private val _modelStatus = MutableStateFlow<ModelStatus>(ModelStatus.Checking)
    val modelStatus: StateFlow<ModelStatus> = _modelStatus

    private val _currentModelType = MutableStateFlow<ModelType?>(null)
    val currentModelType: StateFlow<ModelType?> = _currentModelType

    private val modelMutex = Mutex()
    private var aiCoreModel: GenerativeModel? = null
    private var mediaPipeService: MediaPipeLLMService? = null

    /**
     * Check if Android AICore (Gemini Nano) is supported on this device.
     * AICore is available on Android 14+ with Google Play Services support.
     */
    fun isAICoreSupported(): Boolean {
        return try {
            // Check if device is running Android 14 (API 34) or higher
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return false
            }

            // Try to create a GenerativeModel to check AICore availability
            // This will fail gracefully if AICore is not available
            Generation.getClient()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Get the current model preference from DataStore.
     */
    fun getModelPreference(): Flow<String?> {
        return context.modelDataStore.data.map { preferences ->
            preferences[MODEL_PREFERENCE_KEY]
        }
    }

    /**
     * Save the model preference to DataStore.
     */
    suspend fun setModelPreference(modelType: String) {
        context.modelDataStore.edit { preferences ->
            preferences[MODEL_PREFERENCE_KEY] = modelType
        }
    }

    /**
     * Initialize the appropriate model based on device support and user preference.
     * If AICore is not supported, automatically fallback to MediaPipe.
     */
    private fun cleanupCurrentBackend() {
        aiCoreModel = null
        mediaPipeService?.cleanup()
        mediaPipeService = null
    }

    suspend fun initializeModel(modelPath: String? = null) = modelMutex.withLock {
        _modelStatus.value = ModelStatus.Checking
        cleanupCurrentBackend()

        try {
            val aiCoreSupported = isAICoreSupported()

            // Get user preference (first() completes immediately unlike collect)
            val preference = getModelPreference().first()

            // Determine which model to use
            val useAICore = when {
                !aiCoreSupported -> false
                preference == MODEL_AICORE -> true
                preference == MODEL_MEDIAPIPE -> false
                else -> aiCoreSupported
            }

            if (useAICore) {
                initializeAICore()
            } else {
                if (modelPath.isNullOrEmpty()) {
                    _modelStatus.value = ModelStatus.Error(
                        "MediaPipe model file not found. Download Gemma 2B from the Settings screen."
                    )
                    return@withLock
                }
                initializeMediaPipe(modelPath)
            }
        } catch (e: Exception) {
            val modelType = if (_currentModelType.value is ModelType.AICore) "AICore" else "MediaPipe"
            _modelStatus.value = ModelStatus.Error(
                "Could not initialize $modelType model. Check that the model file exists and is not corrupted. Details: ${e.message}"
            )
        }
    }

    /**
     * Initialize AICore (Gemini Nano) model.
     */
    private fun initializeAICore() {
        try {
            aiCoreModel = Generation.getClient()
            _currentModelType.value = ModelType.AICore
            _modelStatus.value = ModelStatus.Ready(ModelType.AICore)
        } catch (e: Exception) {
            _currentModelType.value = null
            _modelStatus.value = ModelStatus.Error("Failed to initialize AICore: ${e.message}")
        }
    }

    /**
     * Initialize MediaPipe (Gemma 2B) model.
     */
    private suspend fun initializeMediaPipe(modelPath: String) {
        try {
            mediaPipeService = MediaPipeLLMService(context)
            mediaPipeService?.initialize(modelPath)
            _currentModelType.value = ModelType.MediaPipe
            _modelStatus.value = ModelStatus.Ready(ModelType.MediaPipe)
        } catch (e: Exception) {
            _currentModelType.value = null
            _modelStatus.value = ModelStatus.Error("Failed to initialize MediaPipe: ${e.message}")
        }
    }

    /**
     * Generate text using the currently active model.
     */
    suspend fun generateText(prompt: String): String = modelMutex.withLock {
        when (_currentModelType.value) {
            is ModelType.AICore -> {
                val model = aiCoreModel ?: throw IllegalStateException("AICore model not initialized")
                val response = model.generateContent(prompt)
                response.candidates.firstOrNull()?.text ?: ""
            }
            is ModelType.MediaPipe -> {
                mediaPipeService?.generateText(prompt) ?: throw IllegalStateException("MediaPipe model not initialized")
            }
            null -> throw IllegalStateException("No model initialized")
        }
    }

    /**
     * Release resources when done.
     */
    fun cleanup() {
        aiCoreModel = null
        mediaPipeService?.cleanup()
        mediaPipeService = null
    }
}
