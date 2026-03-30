package com.example.jibberish.managers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Downloads on-device model files when not found on disk.
 * Models are saved to internal app storage (app-sandboxed, not user-accessible).
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
        .readTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    companion object {
        private const val TAG = "ModelDownloadManager"
        private const val GEMMA_DOWNLOAD_URL =
            "https://storage.googleapis.com/mediapipe-models/llm_inference/gemma-2b-it-gpu-int4/float32/latest/gemma-2b-it-gpu-int4.bin"
        const val GEMMA_FILENAME = "gemma-2b-it-gpu-int4.bin"
    }

    private fun getModelDir(): File =
        File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    fun getModelPath(): String? {
        val file = getModelFile()
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

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
                    val msg = response.message
                    Log.e(TAG, "Download failed: HTTP $code $msg")
                    response.body.close()
                    _downloadState.value = DownloadState.Failed(
                        "Server returned HTTP $code ($msg). Try again later."
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
                                _downloadState.value = DownloadState.Downloading(
                                    progressPercent = ((bytesRead * 100) / contentLength).toInt(),
                                    downloadedMb = bytesRead / 1_048_576f,
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
                Log.e(TAG, "Model download failed", e)
                _downloadState.value = DownloadState.Failed(describeDownloadError(e))
            } finally {
                gemmaCall = null
            }
        }
    }

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

    private fun describeDownloadError(e: Exception): String = when (e) {
        is UnknownHostException ->
            "DNS lookup failed — check your internet connection."
        is SocketTimeoutException ->
            "Connection timed out. Try again on a stronger network."
        is SSLException ->
            "SSL/TLS error: ${e.message}. Try again later."
        is IOException ->
            "Network error: ${e.message ?: "I/O failure"}. Try again."
        else ->
            "Download failed: ${e.message ?: "Unknown error"}. Try again."
    }
}
