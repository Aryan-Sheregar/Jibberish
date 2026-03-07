package com.example.jibberish.managers

import android.content.Context
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

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
    private val processingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var accumulatedTranscription = StringBuilder()

    // MediaRecorder.maxAmplitude range: 0–32767
    // Ambient noise in a quiet room: ~200–800. Normal speech: ~1500–10000.
    // 1500 is a safe floor that ignores typical background noise.
    var silenceThreshold: Int = 1500
        set(value) { field = value.coerceIn(0, 32767) }

    // Wait 1.5 s of continuous silence before closing a chunk.
    // Increased from 1000ms to allow natural mid-sentence pauses.
    var silenceDurationMs: Long = 1500L
        set(value) { field = value.coerceAtLeast(100L) }

    var pollingIntervalMs: Long = 100L
    var maxChunkDurationMs: Long = 15000L   // force chunk after 15 s regardless of silence
    var minChunkDurationMs: Long = 1500L    // discard chunks shorter than 1.5 s

    // Minimum number of 100ms polling intervals that must have detected speech
    // before a chunk is forwarded to Whisper.
    // 8 intervals = at least 800 ms of actual speech in the chunk.
    private val minSpeechIntervals: Int = 8

    private var currentChunkStartTime: Long = 0L
    private var chunkHadSpeech = false
    private var speechIntervalCount: Int = 0  // intervals where amplitude >= silenceThreshold
    private var totalIntervalCount: Int = 0   // total polling intervals for current chunk

    private val recordingsDir: File by lazy {
        File(context.cacheDir, "audio_recordings").apply {
            if (!exists()) mkdirs()
        }
    }

    fun startRecording() {
        if (shouldBeRecording) return

        shouldBeRecording = true
        _isRecording.value = true
        // Clear previous session's accumulated text so each session starts fresh
        accumulatedTranscription.clear()
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
            speechIntervalCount = 0
            totalIntervalCount = 0

            mediaRecorder =
                MediaRecorder(context)
                    .apply {
                // Use MIC to capture all ambient audio including other devices
                setAudioSource(MediaRecorder.AudioSource.MIC)

                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(32000)
                setAudioSamplingRate(16000)
                setAudioChannels(1)
                setOutputFile(currentRecordingFile?.absolutePath)

                prepare()
                start()
            }

            startSilenceDetection()

        } catch (e: Exception) {
            e.printStackTrace()
            shouldBeRecording = false
            _isRecording.value = false
            _transcribedText.value = "Recording error: ${e.message}"
        }
    }

    private fun startSilenceDetection() {
        silenceDetectionJob?.cancel()
        silenceDetectionJob = processingScope.launch {
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
                    totalIntervalCount++

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
                        speechIntervalCount++
                    }
                } catch (_: Exception) {
                    // Ignore polling errors
                }
            }
        }
    }

    private fun processCurrentChunk() {
        try {
            val chunkDuration = System.currentTimeMillis() - currentChunkStartTime

            mediaRecorder?.apply {
                stop()
                reset()
            }

            val recordedFile = currentRecordingFile
            val hadSpeech = chunkHadSpeech
            val speechCount = speechIntervalCount

            // Start new recording FIRST to minimize gap
            if (shouldBeRecording) {
                startNewRecording()
            }

            // Then dispatch chunk processing in background (non-blocking)
            val hasMeaningfulSpeech = hadSpeech && speechCount >= minSpeechIntervals
            if (recordedFile != null && recordedFile.exists() && recordedFile.length() > 0
                && hasMeaningfulSpeech && chunkDuration >= minChunkDurationMs
            ) {
                processingScope.launch {
                    try {
                        onAudioChunkReady(recordedFile)
                    } finally {
                        recordedFile.delete()
                    }
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
            if (shouldBeRecording) {
                startNewRecording()
            }
        }
    }

    suspend fun stopRecording() {
        shouldBeRecording = false
        _isRecording.value = false

        silenceDetectionJob?.cancel()
        silenceDetectionJob = null

        withContext(Dispatchers.IO) {
            try {
                mediaRecorder?.apply {
                    stop()
                    reset()
                }

                val recordedFile = currentRecordingFile
                val hasMeaningfulSpeech = chunkHadSpeech && speechIntervalCount >= minSpeechIntervals
                if (recordedFile != null && recordedFile.exists() && recordedFile.length() > 0
                    && hasMeaningfulSpeech
                ) {
                    try {
                        onAudioChunkReady(recordedFile)
                    } finally {
                        recordedFile.delete()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                releaseMediaRecorder()
            }

            // Drain all in-flight chunk processing jobs
            processingScope.coroutineContext[Job]?.children?.forEach { it.join() }
        }
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
    }

    fun destroy() {
        release()
        processingScope.cancel()
        try {
            recordingsDir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}