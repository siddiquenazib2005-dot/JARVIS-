package com.jarvis.ai.voice

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.jarvis.ai.diagnostics.CrashGuard

/**
 * Recognition component required by Android/OEM assistant discovery.
 *
 * It forwards the platform RecognitionService contract to the phone's current
 * speech recognizer. AURIX's in-app hands-free mode remains the owner of its
 * normal speech lifecycle; this service exists for system assistant sessions.
 */
class AurixRecognitionService : RecognitionService() {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var activeCallback: Callback? = null

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        main.post {
            destroyRecognizer()
            activeCallback = listener
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                listener.error(SpeechRecognizer.ERROR_CLIENT)
                activeCallback = null
                return@post
            }
            runCatching {
                SpeechRecognizer.createSpeechRecognizer(this).also { speech ->
                    recognizer = speech
                    speech.setRecognitionListener(Forwarder(listener))
                    speech.startListening(recognizerIntent)
                }
            }.onFailure {
                CrashGuard.record(applicationContext, it)
                listener.error(SpeechRecognizer.ERROR_CLIENT)
                activeCallback = null
                destroyRecognizer()
            }
        }
    }

    override fun onStopListening(listener: Callback) {
        main.post {
            if (listener === activeCallback) recognizer?.stopListening()
        }
    }

    override fun onCancel(listener: Callback) {
        main.post {
            if (listener === activeCallback) {
                recognizer?.cancel()
                activeCallback = null
                destroyRecognizer()
            }
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        destroyRecognizer()
        super.onDestroy()
    }

    private fun destroyRecognizer() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private inner class Forwarder(private val target: Callback) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = target.readyForSpeech(params)
        override fun onBeginningOfSpeech() = target.beginningOfSpeech()
        override fun onRmsChanged(rmsdB: Float) = target.rmsChanged(rmsdB)
        override fun onBufferReceived(buffer: ByteArray?) = target.bufferReceived(buffer)
        override fun onEndOfSpeech() = target.endOfSpeech()
        override fun onError(error: Int) {
            target.error(error)
            activeCallback = null
            destroyRecognizer()
        }
        override fun onResults(results: Bundle?) {
            target.results(results)
            activeCallback = null
            destroyRecognizer()
        }
        override fun onPartialResults(partialResults: Bundle?) = target.partialResults(partialResults)
        override fun onEvent(eventType: Int, params: Bundle?) = target.event(eventType, params)
    }
}
