package com.example.jibberish.managers

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

private val Context.sarvamDataStore: DataStore<Preferences> by preferencesDataStore(name = "sarvam_settings")

/**
 * Speech-to-text service using Sarvam Saaras-V3 cloud API.
 * Requires an API key configured via Settings (temporary for testing).
 */
class SarvamSTTService(private val context: Context) {

    sealed class SttStatus {
        data object NotConfigured : SttStatus()
        data object Ready : SttStatus()
        data object Transcribing : SttStatus()
        data class Error(val message: String) : SttStatus()
    }

    private val _status = MutableStateFlow<SttStatus>(SttStatus.NotConfigured)
    val status: StateFlow<SttStatus> = _status.asStateFlow()

    private var apiKey: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val API_URL = "https://api.sarvam.ai/speech-to-text"
        private const val MODEL = "saaras:v3"
        private val API_KEY_PREF = stringPreferencesKey("sarvam_api_key")
    }

    /**
     * Loads the saved API key from DataStore.
     * Call once at startup (e.g. in onCreate).
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        val savedKey = context.sarvamDataStore.data.first()[API_KEY_PREF]
        if (!savedKey.isNullOrBlank()) {
            apiKey = savedKey
            _status.value = SttStatus.Ready
        }
    }

    fun getApiKey(): String = apiKey ?: ""

    suspend fun setApiKey(key: String) {
        withContext(Dispatchers.IO) {
            context.sarvamDataStore.edit { it[API_KEY_PREF] = key }
        }
        apiKey = key.trim()
        _status.value = if (key.isNotBlank()) SttStatus.Ready else SttStatus.NotConfigured
    }

    /**
     * Sends an M4A audio file to Sarvam Saaras-V3 for transcription.
     * Returns Result.success(transcript) or Result.failure(exception).
     */
    suspend fun transcribeAudio(audioFile: File): Result<String> = withContext(Dispatchers.IO) {
        val key = apiKey
        if (key.isNullOrBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Sarvam API key not configured. Add it in Settings.")
            )
        }

        _status.value = SttStatus.Transcribing
        try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", audioFile.name,
                    audioFile.asRequestBody("audio/mp4".toMediaType())
                )
                .addFormDataPart("model", MODEL)
                .build()

            val request = Request.Builder()
                .url(API_URL)
                .header("api-subscription-key", key)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body.string()
                _status.value = SttStatus.Ready
                return@withContext Result.failure(
                    RuntimeException("Sarvam API error ${response.code}: $errorBody")
                )
            }

            val responseBody = response.body.string()

            val json = JSONObject(responseBody)
            val transcript = json.getString("transcript")

            _status.value = SttStatus.Ready
            Result.success(transcript)
        } catch (e: Exception) {
            _status.value = SttStatus.Ready
            Result.failure(e)
        }
    }

    fun close() {
        // No resources to release
    }
}
