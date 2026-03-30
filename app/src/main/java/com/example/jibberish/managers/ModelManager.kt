package com.example.jibberish.managers

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ModelManager handles the selection and initialization of LLM models.
 * Supports both Android AICore (Gemini Nano) and local MediaPipe models (Gemma 2B).
 *
 * AICore availability is checked via GenerativeModel.checkStatus() which returns
 * the real runtime state (AVAILABLE / DOWNLOADING / DOWNLOADABLE / UNAVAILABLE).
 * When DOWNLOADABLE, the manager triggers the system download automatically.
 */
class ModelManager(private val context: Context) {

    private val Context.modelDataStore: DataStore<Preferences> by preferencesDataStore(name = "model_settings")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "ModelManager"
        private val MODEL_PREFERENCE_KEY = stringPreferencesKey("selected_model")
        const val MODEL_AICORE = "aicore"
        const val MODEL_MEDIAPIPE = "mediapipe"

        // FeatureStatus int constants from com.google.mlkit.genai.prompt
        private const val AICORE_UNAVAILABLE = 0
        private const val AICORE_DOWNLOADABLE = 1
        private const val AICORE_DOWNLOADING = 2
        private const val AICORE_AVAILABLE = 3
    }

    sealed class ModelType {
        object AICore : ModelType()
        object MediaPipe : ModelType()
    }

    sealed class ModelStatus {
        object Checking : ModelStatus()
        data class Ready(val modelType: ModelType) : ModelStatus()
        data class Downloading(val modelType: ModelType, val progressPercent: Int = -1) : ModelStatus()
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
     * Synchronous hardware/OS check — safe for Compose `remember {}`.
     * Does NOT verify that Gemini Nano is downloaded.
     */
    fun isAICoreHardwareSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    }

    /**
     * Authoritative async check of Gemini Nano runtime availability.
     */
    private suspend fun checkAICoreFeatureStatus(): Int {
        if (!isAICoreHardwareSupported()) return AICORE_UNAVAILABLE
        return try {
            Generation.getClient().checkStatus()
        } catch (e: Exception) {
            Log.w(TAG, "AICore status check failed", e)
            AICORE_UNAVAILABLE
        }
    }

    fun getModelPreference(): Flow<String?> {
        return context.modelDataStore.data.map { preferences ->
            preferences[MODEL_PREFERENCE_KEY]
        }
    }

    suspend fun setModelPreference(modelType: String) {
        context.modelDataStore.edit { preferences ->
            preferences[MODEL_PREFERENCE_KEY] = modelType
        }
    }

    private fun cleanupCurrentBackend() {
        aiCoreModel = null
        mediaPipeService?.cleanup()
        mediaPipeService = null
    }

    suspend fun initializeModel(modelPath: String? = null) = modelMutex.withLock {
        _modelStatus.value = ModelStatus.Checking
        cleanupCurrentBackend()

        try {
            val preference = getModelPreference().first()
            val hardwareSupported = isAICoreHardwareSupported()

            val preferAICore = when {
                !hardwareSupported -> false
                preference == MODEL_MEDIAPIPE -> false
                preference == MODEL_AICORE -> true
                else -> true
            }

            if (preferAICore) {
                when (checkAICoreFeatureStatus()) {
                    AICORE_AVAILABLE -> initializeAICore()
                    AICORE_DOWNLOADABLE, AICORE_DOWNLOADING -> {
                        _modelStatus.value = ModelStatus.Downloading(ModelType.AICore)
                        // Launch download outside the mutex to avoid blocking generateText()
                        startAICoreDownload(modelPath)
                    }
                    else -> {
                        tryInitializeMediaPipeOrError(
                            modelPath,
                            "Gemini Nano is not supported on this device. Switch to Gemma 2B in Settings."
                        )
                    }
                }
            } else {
                tryInitializeMediaPipeOrError(modelPath)
            }
        } catch (e: Exception) {
            Log.e(TAG, "initializeModel failed", e)
            _modelStatus.value = ModelStatus.Error("Could not initialize model. Check settings.")
        }
    }

    /**
     * Triggers system download of Gemini Nano and monitors progress.
     * Runs in a background coroutine so the modelMutex is released first.
     * On completion, re-calls initializeModel() which will see AVAILABLE status.
     */
    private fun startAICoreDownload(modelPath: String?) {
        scope.launch {
            try {
                val model = Generation.getClient()
                model.download().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted -> {
                            _modelStatus.value = ModelStatus.Downloading(ModelType.AICore, 0)
                            Log.i(TAG, "Gemini Nano download started (${status.bytesToDownload} bytes)")
                        }
                        is DownloadStatus.DownloadProgress -> {
                            // System-managed download — keep indeterminate
                            _modelStatus.value = ModelStatus.Downloading(ModelType.AICore, -1)
                        }
                        is DownloadStatus.DownloadCompleted -> {
                            Log.i(TAG, "Gemini Nano download completed")
                            initializeModel(modelPath)
                        }
                        is DownloadStatus.DownloadFailed -> {
                            Log.e(TAG, "Gemini Nano download failed", status.e)
                            _modelStatus.value = ModelStatus.Error(
                                "Gemini Nano download failed. Switch to Gemma 2B in Settings."
                            )
                        }
                        else -> {} // unknown status subtype
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "AICore download error", e)
                _modelStatus.value = ModelStatus.Error(
                    "Could not download Gemini Nano: ${e.message}"
                )
            }
        }
    }

    private suspend fun tryInitializeMediaPipeOrError(modelPath: String?, fallbackReason: String? = null) {
        if (modelPath.isNullOrEmpty()) {
            _modelStatus.value = ModelStatus.Error(
                fallbackReason ?: "MediaPipe model file not found. Download Gemma 2B from the Settings screen."
            )
        } else {
            initializeMediaPipe(modelPath)
        }
    }

    private fun initializeAICore() {
        try {
            aiCoreModel = Generation.getClient()
            _currentModelType.value = ModelType.AICore
            _modelStatus.value = ModelStatus.Ready(ModelType.AICore)
            Log.i(TAG, "AICore initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "AICore initialization failed", e)
            _currentModelType.value = null
            _modelStatus.value = ModelStatus.Error("Failed to initialize AICore. Ensure Google Play Services are up to date.")
        }
    }

    private suspend fun initializeMediaPipe(modelPath: String) {
        try {
            mediaPipeService = MediaPipeLLMService(context)
            mediaPipeService?.initialize(modelPath)
            _currentModelType.value = ModelType.MediaPipe
            _modelStatus.value = ModelStatus.Ready(ModelType.MediaPipe)
            Log.i(TAG, "MediaPipe initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "MediaPipe initialization failed", e)
            _currentModelType.value = null
            _modelStatus.value = ModelStatus.Error("Failed to initialize MediaPipe. Try re-downloading the model.")
        }
    }

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

    fun cleanup() {
        scope.cancel()
        aiCoreModel = null
        mediaPipeService?.cleanup()
        mediaPipeService = null
    }
}
