package com.jarvis.ai.core

/**
 * Cooperative "the chat UI is actively holding the microphone" flag.
 *
 * The chat voice session sets this while its SpeechRecognizer is listening so
 * competing listeners back off instead of running a second recognition client
 * against the same system speech service — competing listeners make the
 * recognizer hear silence and time out with ERROR_SPEECH_TIMEOUT /
 * ERROR_NO_MATCH.
 */
object VoiceSessionGate {
    @Volatile
    var active: Boolean = false
}