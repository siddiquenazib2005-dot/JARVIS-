package com.jarvis.ai.service

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class TtsEngine(context: Context) {

    private val appContext: Context = context.applicationContext

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _envelope = MutableStateFlow(0f)
    val envelope: StateFlow<Float> = _envelope.asStateFlow()

    var muted: Boolean = false

    @Volatile
    private var engine: TextToSpeech? = null

    @Volatile
    private var languageReady: Boolean = false

    @Volatile
    private var initRequested: Boolean = false

    // Total character count of the active utterance; onRange reports absolute offsets,
    // so progress must be normalized against the length captured at speak() time.
    private var utteranceLength: Int = 0

    val available: Boolean
        get() {
            ensureInitialized()
            return languageReady
        }

    fun speak(text: String) {
        if (muted || text.isBlank()) return
        ensureInitialized()
        val tts = engine ?: return
        if (!languageReady) return
        val cleaned = preprocess(text)
        val maxLength = runCatching { TextToSpeech.getMaxSpeechInputLength() }
            .getOrDefault(DEFAULT_MAX_SPEECH_INPUT_LENGTH)
        val spoken = if (cleaned.length > maxLength) cleaned.substring(0, maxLength) else cleaned
        if (spoken.isBlank()) return
        utteranceLength = spoken.length
        // TextToSpeech.speak signals failures via its return code, not an exception.
        // If it fails, onStart/onDone/onError/onStop never fire, so _isSpeaking would
        // be left forever true and the consume loop would stall. Reset on any failure.
        val rc = runCatching {
            tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID_REPLY)
        }.getOrDefault(TextToSpeech.ERROR)
        if (rc != TextToSpeech.SUCCESS) {
            resetPlayback()
        }
    }

    fun stop() {
        runCatching { engine?.stop() }
        resetPlayback()
    }

    fun shutdown() {
        runCatching { engine?.shutdown() }
        engine = null
        languageReady = false
        resetPlayback()
    }

    private fun ensureInitialized() {
        if (initRequested) return
        initRequested = true
        runCatching {
            val created = TextToSpeech(appContext) { status -> onInit(status) }
            engine = created
        }.onFailure {
            languageReady = false
        }
    }

    private fun onInit(status: Int) {
        val tts = engine
        if (tts == null || status != TextToSpeech.SUCCESS) {
            languageReady = false
            return
        }
        runCatching {
            tts.setPitch(PITCH)
            tts.setSpeechRate(RATE)
            var result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                result = tts.setLanguage(Locale.getDefault())
            }
            languageReady = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            tts.setOnUtteranceProgressListener(progressListener)
        }.onFailure {
            languageReady = false
        }
    }

    private fun preprocess(raw: String): String =
        raw
            .replace(FENCED_CODE_BLOCK, " ")
            .replace(UNTERMINATED_FENCED_CODE_BLOCK, " ")
            .replace("`", "")
            .replace(MARKDOWN_MARKERS, " ")
            .replace(WHITESPACE_RUNS, " ")
            .trim()

    // Derived envelope synchronized via onRange progress; TTS output cannot feed an AnalyserNode.
    // TTS callbacks arrive on binder threads; MutableStateFlow writes are atomic and safe there.
    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            _isSpeaking.value = true
            _envelope.value = ENVELOPE_BASE
            speakingStartMs = SystemClock.elapsedRealtime()
        }

        override fun onDone(utteranceId: String?) {
            resetPlayback()
        }

        override fun onError(utteranceId: String?) {
            resetPlayback()
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            resetPlayback()
        }
    }

    private var speakingStartMs: Long = 0

    private fun edgeFade(progress: Float): Float {
        val fadeIn = (progress / ENVELOPE_FADE_FRACTION).coerceIn(0f, 1f)
        val fadeOut = ((1f - progress) / ENVELOPE_FADE_FRACTION).coerceIn(0f, 1f)
        return minOf(fadeIn, fadeOut)
    }

    private fun resetPlayback() {
        _envelope.value = 0f
        _isSpeaking.value = false
    }

    private companion object {
        const val PITCH = 0.95f
        const val RATE = 1.05f
        const val UTTERANCE_ID_REPLY = "jarvis_reply"
        const val ENVELOPE_BASE = 0.25f
        const val ENVELOPE_AMPLITUDE = 0.55f
        const val ENVELOPE_PULSES = 7
        const val ENVELOPE_FADE_FRACTION = 0.05f
        const val DEFAULT_MAX_SPEECH_INPUT_LENGTH = 4000

        val FENCED_CODE_BLOCK = Regex("```[\\s\\S]*?```")
        val UNTERMINATED_FENCED_CODE_BLOCK = Regex("```[\\s\\S]*$")
        val MARKDOWN_MARKERS = Regex("[#>*_~\\[\\]()]+")
        val WHITESPACE_RUNS = Regex("\\s+")
    }
}
