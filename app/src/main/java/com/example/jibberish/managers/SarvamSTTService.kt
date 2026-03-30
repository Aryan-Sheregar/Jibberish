package com.example.jibberish.managers

import android.content.Context
import android.util.Log
import com.example.jibberish.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Speech-to-text service using Sarvam Saaras-V3.
 *
 * Credentials are supplied at build time, NOT entered by the user:
 *
 *   • Development: set SARVAM_API_KEY in local.properties (direct API calls).
 *   • Production:  deploy the Ktor proxy (server/ module) and set STT_PROXY_URL
 *                  in local.properties or as an env var.  The API key stays
 *                  server-side where it cannot be reverse-engineered from the APK.
 *
 * When STT_PROXY_URL is set, all requests are routed through the proxy and no
 * API key is included in app traffic.
 */
class SarvamSTTService(private val context: Context) {

    sealed class SttStatus {
        data object Ready : SttStatus()
        data object Transcribing : SttStatus()
        data class Error(val message: String) : SttStatus()
    }

    private val _status = MutableStateFlow<SttStatus>(SttStatus.Ready)
    val status: StateFlow<SttStatus> = _status.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    companion object {
        private const val TAG = "SarvamSTTService"
        private const val SARVAM_DIRECT_URL = "https://api.sarvam.ai/speech-to-text"
        private const val MODEL = "saaras:v3"

        private val useProxy = BuildConfig.STT_PROXY_URL.isNotBlank()
        private val effectiveUrl =
            if (useProxy) "${BuildConfig.STT_PROXY_URL.trimEnd('/')}/api/transcribe"
            else SARVAM_DIRECT_URL
    }

    /**
     * Validates that at least one credential source is configured.
     * Call once at startup.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        _status.value = when {
            useProxy -> SttStatus.Ready  // proxy handles auth
            BuildConfig.SARVAM_API_KEY.isNotBlank() -> SttStatus.Ready
            else -> SttStatus.Error(
                "STT not configured. Add SARVAM_API_KEY to local.properties and rebuild."
            )
        }
    }

    /**
     * Sends an M4A audio file for transcription.
     */
    suspend fun transcribeAudio(audioFile: File): Result<String> = withContext(Dispatchers.IO) {
        if (_status.value is SttStatus.Error) {
            return@withContext Result.failure(
                IllegalStateException("STT not configured. Rebuild with SARVAM_API_KEY or STT_PROXY_URL.")
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
                .url(effectiveUrl)
                .apply {
                    if (!useProxy) {
                        header("api-subscription-key", BuildConfig.SARVAM_API_KEY)
                    }
                }
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorCode = response.code
                response.body.string() // consume body
                _status.value = SttStatus.Ready
                return@withContext Result.failure(
                    RuntimeException("Transcription failed (HTTP $errorCode). Please try again.")
                )
            }

            val responseBody = response.body.string()
            val json = JSONObject(responseBody)
            val transcript = json.getString("transcript")

            _status.value = SttStatus.Ready
            Result.success(transcript)
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed", e)
            _status.value = SttStatus.Ready
            Result.failure(RuntimeException("Transcription failed: ${e.message}"))
        }
    }

    fun close() {
        // No resources to release
    }
}
