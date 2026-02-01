package com.example.jibberish.managers

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

class AudioRecorderManager(
    private val context: Context,
    private val onAudioChunkReady: suspend (File) -> Unit
) {
    private val _transcribedText = MutableStateFlow("Press start to begin")
    val transcribedText: StateFlow<String> = _transcribedText.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null
    private var shouldBeRecording = false
    private var silenceDetectionJob: Job? = null
    private var accumulatedTranscription = StringBuilder()

    // --- FIX 1: Property Setters replace the conflicting functions ---
    var silenceThreshold: Int = 500
        set(value) {
            // Logic moved here to avoid "Platform declaration clash"
            field = value.coerceIn(0, 32767)
        }

    var silenceDurationMs: Long = 1000L
        set(value) {
            field = value.coerceAtLeast(100L)
        }

    var pollingIntervalMs: Long = 100L

    // Maximum duration for a single chunk (15 seconds)
    // This ensures long conversations get chunked even without silence
    var maxChunkDurationMs: Long = 15000L

    // Minimum chunk duration to avoid sending near-silent chunks to STT
    // Whisper hallucinates "Thank you." etc. on very short/silent audio
    var minChunkDurationMs: Long = 1500L

    private var currentChunkStartTime: Long = 0L
    private var chunkHadSpeech = false

    private val recordingsDir: File by lazy {
        File(context.cacheDir, "audio_recordings").apply {
            if (!exists()) mkdirs()
        }
    }

    fun startRecording() {
        if (shouldBeRecording) return

        shouldBeRecording = true
        _isRecording.value = true
        _transcribedText.value = "Listening..."

        startNewRecording()
    }

    private fun startNewRecording() {
        if (!shouldBeRecording) return

        try {
            releaseMediaRecorder()

            currentRecordingFile = File(recordingsDir, "recording_${System.currentTimeMillis()}.m4a")
            currentChunkStartTime = System.currentTimeMillis()
            chunkHadSpeech = false

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                // Use MIC to capture all ambient audio including other devices
                setAudioSource(MediaRecorder.AudioSource.MIC)

                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(currentRecordingFile?.absolutePath)

                prepare()
                start()
            }

            startSilenceDetection()

        } catch (e: IOException) {
            e.printStackTrace()
            _transcribedText.value = "Recording error: ${e.message}"
        }
    }

    private fun startSilenceDetection() {
        silenceDetectionJob?.cancel()
        silenceDetectionJob = CoroutineScope(Dispatchers.IO).launch {
            var silenceStartTime: Long? = null

            while (isActive && shouldBeRecording) {
                delay(pollingIntervalMs)

                try {
                    // Check if max chunk duration has been reached
                    val chunkDuration = System.currentTimeMillis() - currentChunkStartTime
                    if (chunkDuration >= maxChunkDurationMs) {
                        processCurrentChunk()
                        silenceStartTime = null
                        continue
                    }

                    val amplitude = mediaRecorder?.maxAmplitude ?: 0

                    if (amplitude < silenceThreshold) {
                        if (silenceStartTime == null) {
                            silenceStartTime = System.currentTimeMillis()
                        } else {
                            val silenceDuration = System.currentTimeMillis() - silenceStartTime
                            if (silenceDuration >= silenceDurationMs) {
                                processCurrentChunk()
                                silenceStartTime = null
                            }
                        }
                    } else {
                        silenceStartTime = null
                        chunkHadSpeech = true
                    }
                } catch (e: Exception) {
                    // Ignore polling errors
                }
            }
        }
    }

    private suspend fun processCurrentChunk() {
        try {
            val chunkDuration = System.currentTimeMillis() - currentChunkStartTime

            mediaRecorder?.apply {
                stop()
                reset()
            }

            val recordedFile = currentRecordingFile
            // Only send chunk if it had speech and meets minimum duration
            // This prevents Whisper from hallucinating on silent/short audio
            if (recordedFile != null && recordedFile.exists() && recordedFile.length() > 0
                && chunkHadSpeech && chunkDuration >= minChunkDurationMs
            ) {
                onAudioChunkReady(recordedFile)
            }

            if (shouldBeRecording) {
                startNewRecording()
            }

        } catch (e: Exception) {
            e.printStackTrace()
            if (shouldBeRecording) {
                startNewRecording()
            }
        }
    }

    fun stopRecording() {
        shouldBeRecording = false
        _isRecording.value = false

        silenceDetectionJob?.cancel()
        silenceDetectionJob = null

        CoroutineScope(Dispatchers.IO).launch {
            try {
                mediaRecorder?.apply {
                    stop()
                    reset()
                }

                val recordedFile = currentRecordingFile
                if (recordedFile != null && recordedFile.exists() && recordedFile.length() > 0) {
                    onAudioChunkReady(recordedFile)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                releaseMediaRecorder()
            }
        }
    }

    fun clearTranscription() {
        accumulatedTranscription.clear()
        _transcribedText.value = "Listening..."
    }

    fun updateTranscription(text: String) {
        if (text.isNotBlank()) {
            if (accumulatedTranscription.isNotEmpty()) {
                accumulatedTranscription.append(" ")
            }
            accumulatedTranscription.append(text)
            _transcribedText.value = accumulatedTranscription.toString()
        }
    }

    private fun releaseMediaRecorder() {
        try {
            mediaRecorder?.apply {
                reset()
                release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaRecorder = null
    }

    fun release() {
        shouldBeRecording = false
        silenceDetectionJob?.cancel()
        releaseMediaRecorder()
        try {
            recordingsDir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // REMOVED: setSilenceThreshold() and setSilenceDuration() functions
    // (Their logic is now handled by the property setters at the top)
}