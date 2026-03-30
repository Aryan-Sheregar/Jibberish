package com.example.jibberish.managers

import android.content.Context
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

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
    private val shouldBeRecording = AtomicBoolean(false)
    private var silenceDetectionJob: Job? = null
    private val processingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var accumulatedTranscription = StringBuilder()

    // Guards MediaRecorder lifecycle to prevent concurrent start/stop races
    private val recorderMutex = Mutex()

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
    // before a chunk is forwarded to STT.
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
        if (shouldBeRecording.get()) return

        shouldBeRecording.set(true)
        _isRecording.value = true
        // Clear previous session's accumulated text so each session starts fresh
        accumulatedTranscription.clear()
        _transcribedText.value = "Listening..."

        processingScope.launch {
            startNewRecordingLocked()
        }
    }

    private suspend fun startNewRecordingLocked(): Unit = recorderMutex.withLock {
        if (!shouldBeRecording.get()) return

        try {
            releaseMediaRecorderInternal()

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
            Log.e("AudioRecorderManager", "Recording error", e)
            shouldBeRecording.set(false)
            _isRecording.value = false
            _transcribedText.value = "Recording error. Please try again."
        }
    }

    private fun startSilenceDetection() {
        silenceDetectionJob?.cancel()
        silenceDetectionJob = processingScope.launch {
            var silenceStartTime: Long? = null

            while (isActive && shouldBeRecording.get()) {
                delay(pollingIntervalMs)

                // Re-check after delay — stop may have been called during the delay
                if (!shouldBeRecording.get()) break

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

    private suspend fun processCurrentChunk() {
        recorderMutex.withLock {
            // Double-check: if stop was called, don't process or restart
            val stillRecording = shouldBeRecording.get()

            try {
                val chunkDuration = System.currentTimeMillis() - currentChunkStartTime

                mediaRecorder?.apply {
                    stop()
                    reset()
                }

                val recordedFile = currentRecordingFile
                val hadSpeech = chunkHadSpeech
                val speechCount = speechIntervalCount

                // Start new recording FIRST to minimize gap — only if still recording
                if (stillRecording) {
                    releaseMediaRecorderInternal()

                    currentRecordingFile = File(recordingsDir, "recording_${System.currentTimeMillis()}.m4a")
                    currentChunkStartTime = System.currentTimeMillis()
                    chunkHadSpeech = false
                    speechIntervalCount = 0
                    totalIntervalCount = 0

                    mediaRecorder =
                        MediaRecorder(context)
                            .apply {
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
                } else {
                    // Stop was called — clean up recorder, don't restart
                    releaseMediaRecorderInternal()
                }

                // Dispatch chunk processing in background (non-blocking)
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
                } else {
                    // Discard chunk file for non-meaningful audio
                    recordedFile?.delete()
                }

            } catch (e: Exception) {
                Log.e("AudioRecorderManager", "Recording error", e)
                if (stillRecording) {
                    releaseMediaRecorderInternal()
                    // Attempt to recover by starting fresh outside the mutex
                }
            }
        }

        // If we need to recover after an error, restart outside the mutex
        if (shouldBeRecording.get() && mediaRecorder == null) {
            startNewRecordingLocked()
        }
    }

    suspend fun stopRecording() {
        // Set flag first — this immediately prevents any new recordings from starting
        shouldBeRecording.set(false)
        _isRecording.value = false

        // Cancel silence detection so it doesn't call processCurrentChunk concurrently
        silenceDetectionJob?.cancel()
        silenceDetectionJob?.join()
        silenceDetectionJob = null

        recorderMutex.withLock {
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
                    } else {
                        // Clean up non-meaningful final chunk
                        currentRecordingFile?.delete()
                    }
                } catch (e: Exception) {
                    Log.e("AudioRecorderManager", "Recording error", e)
                    // Clean up file on error too
                    currentRecordingFile?.delete()
                } finally {
                    releaseMediaRecorderInternal()
                    currentRecordingFile = null
                }
            }
        }

        // Drain all in-flight chunk processing jobs
        processingScope.coroutineContext[Job]?.children?.forEach { it.join() }
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

    // Internal release — caller must hold recorderMutex
    private fun releaseMediaRecorderInternal() {
        try {
            mediaRecorder?.apply {
                reset()
                release()
            }
        } catch (e: Exception) {
            Log.e("AudioRecorderManager", "Recording error", e)
        }
        mediaRecorder = null
    }

    fun release() {
        shouldBeRecording.set(false)
        silenceDetectionJob?.cancel()
        try {
            mediaRecorder?.apply {
                reset()
                release()
            }
        } catch (_: Exception) {}
        mediaRecorder = null
    }

    fun destroy() {
        release()
        processingScope.cancel()
        try {
            recordingsDir.listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.e("AudioRecorderManager", "Recording error", e)
        }
    }
}
