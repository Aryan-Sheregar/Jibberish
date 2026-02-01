package com.example.jibberish.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class GroqApiService(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val GROQ_TRANSCRIPTION_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val MODEL = "whisper-large-v3"
    }

    /**
     * Transcribes an audio file using Groq's Whisper API
     * @param audioFile The audio file to transcribe (supports m4a, mp3, wav, etc.)
     * @return The transcribed text
     */
    suspend fun transcribeAudio(audioFile: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!audioFile.exists()) {
                return@withContext Result.failure(Exception("Audio file does not exist"))
            }

            if (audioFile.length() == 0L) {
                return@withContext Result.failure(Exception("Audio file is empty"))
            }

            // Build multipart request
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    audioFile.name,
                    audioFile.asRequestBody("audio/m4a".toMediaType())
                )
                .addFormDataPart("model", MODEL)
                .addFormDataPart("language", "en")
                .addFormDataPart("response_format", "json")
                .build()

            val request = Request.Builder()
                .url(GROQ_TRANSCRIPTION_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (responseBody != null) {
                    val json = JSONObject(responseBody)
                    val text = json.optString("text", "")

                    if (text.isNotEmpty()) {
                        Result.success(text)
                    } else {
                        Result.failure(Exception("No transcription returned"))
                    }
                } else {
                    Result.failure(Exception("Empty response from API"))
                }
            } else {
                val errorBody = response.body?.string() ?: "Unknown error"
                Result.failure(Exception("API Error (${response.code}): $errorBody"))
            }

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
