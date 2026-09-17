package com.jarvis.ai.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SpeechRecognitionManager(private val context: Context) {

    val isAvailable: Boolean
        get() = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var onPartial: ((String) -> Unit)? = null
    private var onFinal: ((String) -> Unit)? = null
    private var onError: ((Int) -> Unit)? = null

    fun start(
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (Int) -> Unit,
    ): Boolean {
        if (!isAvailable) return false
        this.onPartial = onPartial
        this.onFinal = onFinal
        this.onError = onError
        val engine = obtainRecognizer() ?: return false
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Prevent premature silence cutoff: keep the recognizer open for a
            // minimum window so a short pause mid-phrase doesn't end the session
            // and surface ERROR_SPEECH_TIMEOUT / ERROR_NO_MATCH.
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                MIN_SILENCE_WINDOW_MS.toLong()
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                MIN_SILENCE_WINDOW_MS.toLong()
            )
        }
        val started = runCatching {
            engine.setRecognitionListener(listener)
            // Vendor recognition services keep stale error/results state; without a
            // cancel() the next session can re-deliver the previous onError
            // (e.g. ERROR_NO_MATCH) instead of listening again.
            runCatching { engine.cancel() }
            engine.startListening(intent)
            true
        }.getOrDefault(false)
        if (!started) {
            runCatching { engine.destroy() }
            if (recognizer === engine) recognizer = null
        }
        return started
    }

    fun stop() {
        mainHandler.post {
            recognizer?.let { engine ->
                runCatching { engine.stopListening() }
            }
        }
    }

    fun destroy() {
        // Tear down on the main thread. If already on the main thread, do it
        // synchronously so a queued mainHandler.post from a prior destroy() cannot
        // interleave with (and kill) a recognizer we just created synchronously.
        // Only fall back to a posted runnable when called from a background thread.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroyOnMain()
        } else {
            mainHandler.post { destroyOnMain() }
        }
    }

    private fun destroyOnMain() {
        recognizer?.let { engine ->
            runCatching { engine.destroy() }
        }
        recognizer = null
        onPartial = null
        onFinal = null
        onError = null
    }

    private fun obtainRecognizer(): SpeechRecognizer? {
        recognizer?.let { return it }
        val created = if (Looper.myLooper() == Looper.getMainLooper()) {
            // SpeechRecognizer must be created on the main thread; when already there,
            // create synchronously (do NOT post + await a latch — that would deadlock
            // the main looper). destroy() is equally synchronous on the main thread,
            // so create and destroy naturally serialize.
            createRecognizer()
        } else {
            // Block the caller briefly so start() can report success synchronously.
            val latch = CountDownLatch(1)
            var createdOnMain: SpeechRecognizer? = null
            mainHandler.post {
                createdOnMain = createRecognizer()
                latch.countDown()
            }
            runCatching { latch.await(CREATE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            createdOnMain
        }
        recognizer = created
        return created
    }

    private fun createRecognizer(): SpeechRecognizer? =
        runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()

    // Vendor implementations may deliver recognition callbacks off-main; re-post so the
    // consumer callbacks always observe the main thread.
    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            dispatchError(error)
        }

        override fun onResults(results: Bundle?) {
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val best = texts.firstOrNull { it.isNotBlank() }?.trim()
            if (best != null) {
                dispatchFinal(best)
            } else {
                dispatchError(SpeechRecognizer.ERROR_NO_MATCH)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val partial = texts.firstOrNull { it.isNotBlank() }?.trim()
            if (partial != null) {
                dispatchPartial(partial)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun dispatchPartial(text: String) {
        mainHandler.post { onPartial?.invoke(text) }
    }

    private fun dispatchFinal(text: String) {
        mainHandler.post { onFinal?.invoke(text) }
    }

    private fun dispatchError(code: Int) {
        mainHandler.post { onError?.invoke(code) }
    }

    private companion object {
        const val CREATE_TIMEOUT_MS = 2000L
        // 2.5s min window before the recognizer treats silence as end-of-speech.
        const val MIN_SILENCE_WINDOW_MS = 2500
    }
}
