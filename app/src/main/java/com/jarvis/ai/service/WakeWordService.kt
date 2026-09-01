package com.jarvis.ai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import com.jarvis.ai.R
import com.jarvis.ai.core.JarvisRuntime
import com.jarvis.ai.core.VoiceSessionGate
import com.jarvis.ai.orchestrator.OrchestratorUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Always-on wake-word listener ("Hey Jarvis").
 *
 * Design note: Android's [SpeechRecognizer] does not offer a true low-power
 * always-listening mode for third-party apps (that needs an on-device
 * keyword-spotting engine like Picovoice Porcupine, which requires an access
 * key + a custom .ppn wake-word file — a good phase-2 upgrade). This
 * implementation instead keeps restarting short recognition sessions back to
 * back ("listen -> check -> listen again"), which works fully offline-free
 * and needs no new dependency, at the cost of higher battery use than a
 * dedicated keyword spotter. Acceptable for a personal-device assistant.
 *
 * Flow:
 *   loop: listen for [WAKE_PHRASE] in short bursts
 *   -> heard it -> speak short ack -> listen ONCE for the actual command
 *   -> hand the command to MasterOrchestrator.processRequest()
 *   -> speak the reply -> resume the wake-word loop
 */
class WakeWordService : Service() {

    companion object {
        private const val CHANNEL_ID = "jarvis_wake_word"
        private const val NOTIFICATION_ID = 1001
        private const val WAKE_PHRASE = "jarvis"
        private const val RESTART_DELAY_MS = 400L
    }

    private lateinit var speechManager: SpeechRecognitionManager
    private lateinit var ttsEngine: TtsEngine
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val mainHandler = Handler(Looper.getMainLooper())

    /** True while we're in "listening for the command after wake-up" mode. */
    private var awaitingCommand = false
    private var running = false

    override fun onCreate() {
        super.onCreate()
        speechManager = SpeechRecognitionManager(this)
        ttsEngine = TtsEngine(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Listening for \"Jarvis\"…"))
        if (!running) {
            running = true
            startWakeWordLoop()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        runCatching { speechManager.destroy() }
        runCatching { ttsEngine.shutdown() }
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Wake-word loop
    // ------------------------------------------------------------------

    private fun startWakeWordLoop() {
        if (!running || awaitingCommand) return

        // A chat voice session owns the mic — never start a competing recognizer.
        // Keep the loop alive (restart cadence) so we resume as soon as it ends.
        if (VoiceSessionGate.active) {
            scheduleRestart()
            return
        }

        val started = speechManager.start(
            onPartial = { partial -> checkForWakePhrase(partial) },
            onFinal = { final ->
                checkForWakePhrase(final)
                if (running && !awaitingCommand) scheduleRestart()
            },
            onError = { errorCode ->
                // NO_MATCH / SPEECH_TIMEOUT are expected in a listen-loop (silence between
                // utterances) — just restart. Anything else still restarts, but after the
                // same short delay, so a flaky mic never becomes a hard crash loop.
                if (running && !awaitingCommand) scheduleRestart()
            }
        )
        if (!started && running) {
            scheduleRestart()
        }
    }

    private fun scheduleRestart() {
        mainHandler.postDelayed({ startWakeWordLoop() }, RESTART_DELAY_MS)
    }

    private fun checkForWakePhrase(text: String) {
        if (awaitingCommand) return
        if (text.lowercase().contains(WAKE_PHRASE)) {
            awaitingCommand = true
            runCatching { speechManager.stop() }
            onWakeWordDetected()
        }
    }

    // ------------------------------------------------------------------
    // Command capture + execution (after wake-up)
    // ------------------------------------------------------------------

    private fun onWakeWordDetected() {
        if (VoiceSessionGate.active) {
            awaitingCommand = false
            scheduleRestart()
            return
        }
        updateNotification("Yes, sir…")
        ttsEngine.speak("Yes, sir.")
        // Small delay so TTS playback doesn't bleed into the mic recording.
        mainHandler.postDelayed({ listenForCommand() }, 700L)
    }

    private fun listenForCommand() {
        // A chat session grabbed the mic while we waited to listen — stand down.
        if (VoiceSessionGate.active) {
            resumeWakeWordLoop()
            return
        }
        val started = speechManager.start(
            onPartial = { /* no-op; we only act on the final command */ },
            onFinal = { command ->
                if (command.isNotBlank()) {
                    handleCommand(command)
                } else {
                    resumeWakeWordLoop()
                }
            },
            onError = { _ -> resumeWakeWordLoop() }
        )
        if (!started) resumeWakeWordLoop()
    }

    private fun handleCommand(command: String) {
        updateNotification("Working on: $command")
        serviceScope.launch {
            val runtime = JarvisRuntime.get(this@WakeWordService)
            var lastReply = ""
            runCatching {
                runtime.masterOrchestrator.processRequest(command).collect { update ->
                    when (update) {
                        is OrchestratorUpdate.Delta -> lastReply = update.text
                        is OrchestratorUpdate.Confirmation ->
                            lastReply = "${update.message} Say \"Jarvis confirm\" to proceed."
                        is OrchestratorUpdate.Completed -> { /* terminal event, nothing to do here */ }
                    }
                }
            }
            if (lastReply.isNotBlank()) {
                ttsEngine.speak(lastReply)
            }
            resumeWakeWordLoop()
        }
    }

    private fun resumeWakeWordLoop() {
        awaitingCommand = false
        updateNotification("Listening for \"Jarvis\"…")
        scheduleRestart()
    }

    // ------------------------------------------------------------------
    // Foreground notification (required for a long-running listening service)
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "JARVIS Wake Word", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps JARVIS listening for its wake word" }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(text))
    }
}