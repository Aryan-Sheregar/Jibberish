package com.example.jibberish.managers

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads on-device model files when not found on disk.
 * Models are saved to external files dir (user-accessible).
 */
class ModelDownloadManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var gemmaCall: okhttp3.Call? = null

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(
            val progressPercent: Int,
            val downloadedMb: Float,
            val totalMb: Float
        ) : DownloadState()
        data class Completed(val modelPath: String) : DownloadState()
        data class Failed(val error: String) : DownloadState()
    }

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()
    private var downloadJob: Job? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // No read timeout — large file download
        .build()

    companion object {
        private const val GEMMA_DOWNLOAD_URL =
            "https://storage.googleapis.com/mediapipe-models/llm_inference/gemma-2b-it-gpu-int4/float32/1/gemma-2b-it-gpu-int4.bin"
        const val GEMMA_FILENAME = "gemma-2b-it-gpu-int4.bin"
    }

    private fun getModelDir(): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    /**
     * Returns the absolute path to the Gemma model file if it already exists on disk, null otherwise.
     */
    fun getModelPath(): String? {
        val file = getModelFile()
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    /**
     * Starts downloading the Gemma 2B model with streaming progress updates.
     * Safe to call multiple times — ignores if already downloading.
     */
    fun startDownload() {
        if (_downloadState.value is DownloadState.Downloading) return

        downloadJob = scope.launch {
            val destFile = getModelFile()
            val tempFile = File(destFile.parent, "${GEMMA_FILENAME}.tmp")

            try {
                val request = Request.Builder().url(GEMMA_DOWNLOAD_URL).build()
                val call = client.newCall(request)
                gemmaCall = call
                val response = call.execute()

                if (!response.isSuccessful) {
                    val code = response.code
                    val body = response.message
                    _downloadState.value = DownloadState.Failed(
                        "Download failed: $code $body. Check your internet connection and try again."
                    )
                    return@launch
                }

                val body = response.body

                val contentLength = body.contentLength()
                val totalMb = if (contentLength > 0) contentLength / 1_048_576f else 0f

                body.source().use { source ->
                    tempFile.outputStream().use { out ->
                        val buffer = okio.Buffer()
                        var bytesRead = 0L

                        while (true) {
                            ensureActive()
                            val read = source.read(buffer, 8192L)
                            if (read == -1L) break
                            val bytes = buffer.readByteArray()
                            out.write(bytes)
                            bytesRead += read

                            if (contentLength > 0) {
                                val downloadedMb = bytesRead / 1_048_576f
                                val percent = ((bytesRead * 100) / contentLength).toInt()
                                _downloadState.value = DownloadState.Downloading(
                                    progressPercent = percent,
                                    downloadedMb = downloadedMb,
                                    totalMb = totalMb
                                )
                            }
                        }
                    }
                }

                if (!tempFile.renameTo(destFile)) {
                    throw IOException("Failed to move downloaded file to final location")
                }
                _downloadState.value = DownloadState.Completed(destFile.absolutePath)

            } catch (e: CancellationException) {
                tempFile.delete()
                throw e
            } catch (e: Exception) {
                tempFile.delete()
                _downloadState.value = DownloadState.Failed(
                    "Download failed: ${e.message}. Check your internet connection and try again."
                )
            } finally {
                gemmaCall = null
            }
        }
    }

    /**
     * Cancels an in-progress Gemma download and resets state to Idle.
     */
    fun cancelDownload() {
        gemmaCall?.cancel()
        gemmaCall = null
        downloadJob?.cancel()
        downloadJob = null
        _downloadState.value = DownloadState.Idle
        File(getModelDir(), "${GEMMA_FILENAME}.tmp").delete()
    }

    fun close() {
        gemmaCall?.cancel()
        downloadJob?.cancel()
        scope.cancel()
    }

    private fun getModelFile(): File = File(getModelDir(), GEMMA_FILENAME)
}
