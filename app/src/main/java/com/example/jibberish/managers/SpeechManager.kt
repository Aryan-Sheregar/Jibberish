package com.example.jibberish.managers

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class SpeechManager(
    private val context: Context,
    private val onTranscriptionComplete: (suspend (String) -> Unit)? = null
) {

    private val _transcribedText = MutableStateFlow("Press Start...")
    val transcribedText: StateFlow<String> = _transcribedText.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val speechRecognizer: SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }

    private var shouldBeListening = false

    // Variables to store user's original volume levels
    private var originalNotificationVol = 0
    private var originalMusicVol = 0
    private var originalSystemVol = 0

    init {
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                // If error is No Match (silence) or similar, restart if toggle is ON
                if (shouldBeListening) {
                    startListeningInternal()
                } else {
                    shutdown()
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val transcription = matches[0]
                    _transcribedText.value = transcription
                    // Call the callback to analyze the transcription
                    onTranscriptionComplete?.let { callback ->
                        CoroutineScope(Dispatchers.IO).launch {
                            callback(transcription)
                        }
                    }
                }
                if (shouldBeListening) {
                    startListeningInternal()
                } else {
                    shutdown()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    _transcribedText.value = matches[0]
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    // Public method called by UI
    fun startListening() {
        if (shouldBeListening) return // Already running

        shouldBeListening = true
        _isListening.value = true

        // 1. Save current volume
        originalNotificationVol = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        originalMusicVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        originalSystemVol = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)

        // 2. Mute everything continuously
        muteSystemAudio()

        // 3. Start the recognizer
        startListeningInternal()
    }

    // Internal helper to just trigger the recognizer without re-muting
    private fun startListeningInternal() {
        try {
            speechRecognizer.startListening(speechIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Public method called by UI
    fun stopListening() {
        shutdown()
    }

    private fun shutdown() {
        shouldBeListening = false
        _isListening.value = false

        try {
            speechRecognizer.stopListening()
        } catch (e: Exception) {
            // Ignore
        }

        // 4. Restore volume only when we are completely done
        unmuteSystemAudio()
    }

    private fun muteSystemAudio() {
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun unmuteSystemAudio() {
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, originalNotificationVol, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalMusicVol, 0)
            audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, originalSystemVol, 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun release() {
        shutdown()
        try {
            speechRecognizer.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}