package com.example.spotifyvoice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class VoiceController(
    private val context: Context,
    private val onStateChanged: (Boolean) -> Unit,
    private val onErrorState: (String) -> Unit,
    private val onCommandRecognized: (String) -> Unit
) {

    private val TAG = "VoiceController"
    private var speechRecognizer: SpeechRecognizer? = null

    fun startListening() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    onStateChanged(false)
                }
                
                override fun onError(error: Int) {
                    Log.e(TAG, "Speech recognition error: $error")
                    onStateChanged(false)
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "Nichts erkannt"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Zeitüberschreitung"
                        else -> "Fehler: $error"
                    }
                    onErrorState(errorMsg)
                }

                override fun onResults(results: Bundle?) {
                    onStateChanged(false)
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val command = matches[0]
                        Log.d(TAG, "Command recognized: $command")
                        onCommandRecognized(command)
                    } else {
                        onErrorState("Nichts erkannt")
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE") // Defaulting to German as user requested
            }
            speechRecognizer?.startListening(intent)
        } else {
            Log.e(TAG, "Speech recognition not available on this device.")
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        onStateChanged(false)
    }

    fun destroy() {
        speechRecognizer?.destroy()
    }
}
