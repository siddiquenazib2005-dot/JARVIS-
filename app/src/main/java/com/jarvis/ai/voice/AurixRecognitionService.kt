package com.jarvis.ai.voice

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * System registration companion for the AURIX VoiceInteractionService.
 * AURIX starts its existing in-app speech pipeline from the assistant session;
 * it does not recursively delegate back into Android's recognizer service.
 */
class AurixRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback) = Unit

    override fun onCancel(listener: Callback) = Unit
}
