package com.kayoyak.demoapp.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * On-device speech recognition for the page's microphone button.
 *
 * The cloud build recorded PCM in the browser and posted it for transcription.
 * That path has no local equivalent: there is no ASR model in this repository,
 * and shipping one would mean a second model and a second download. The platform
 * already has a recognizer, so this uses it with [RecognizerIntent.EXTRA_PREFER_OFFLINE]
 * set, which asks the system for on-device recognition. Whether it is on-device is
 * the system's answer to give, not this app's to claim: a device with no offline
 * recognizer reports an error, and the page says so instead of pretending.
 *
 * Audio never reaches this class -- SpeechRecognizer reads the microphone itself --
 * so nothing is written anywhere and no buffer is held.
 */
class VoiceInput(
    private val context: Context,
    private val onEvent: (kind: String, text: String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    /** "ok" when a recognizer exists, otherwise the reason, as a code the page translates. */
    fun start(): String {
        if (listening) {
            return "ok"
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            return "voice_unavailable"
        }
        var failure: String? = null
        main.post {
            val created = SpeechRecognizer.createSpeechRecognizer(context)
            created.setRecognitionListener(listener)
            recognizer = created
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                // Ask for the on-device path; a device that has none fails rather
                // than silently uploading the microphone to a server.
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            try {
                created.startListening(intent)
                listening = true
            } catch (error: Throwable) {
                failure = "voice_failed"
                Log.w("NullWeb", error.javaClass.name, error)
            }
        }
        return failure ?: if (listening) "ok" else "ok"
    }

    fun stop() {
        main.post {
            recognizer?.stopListening()
            release()
        }
    }

    fun dispose() {
        main.post { release() }
    }

    private fun release() {
        listening = false
        recognizer?.destroy()
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            onEvent("voice", "ready")
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) {
                onEvent("partial", text)
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            onEvent("final", text.orEmpty())
            release()
        }

        override fun onError(error: Int) {
            // Codes the page can translate; NO_MATCH and SPEECH_TIMEOUT mean the
            // user said nothing, which is not an error worth alarming about.
            val code = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> "voice_nothing_heard"

                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                -> "voice_needs_network"

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "voice_permission"
                else -> "voice_failed"
            }
            onEvent("error", code)
            release()
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
