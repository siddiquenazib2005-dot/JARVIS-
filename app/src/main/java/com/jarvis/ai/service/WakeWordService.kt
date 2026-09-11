package com.jarvis.ai.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.jarvis.ai.MainActivity

/**
 * Always-on "Aurix / Jarvis" wake-word listener (MYRA parity).
 *
 * Design notes:
 * - Uses the platform [SpeechRecognitionManager] rather than Porcupine or
 *   Vosk. Those need either a paid key or a ~40 MB model download, and the
 *   APK is already ~61 MB. The trade-off is stated honestly in the docs:
 *   platform recognition is less power-efficient than a real DSP hotword
 *   engine, so this service is OPT-IN and never auto-starts.
 * - The recognizer stops itself after every utterance, so the loop restarts
 *   it. Restarts are backed off ([BASE_BACKOFF_MS] doubling to [MAX_BACKOFF_MS])
 *   because a permanently failing recognizer -- no network for the vendor
 *   service, mic held by a call -- would otherwise spin in a tight loop and
 *   flatten the battery.
 * - Only transcripts containing the wake word are acted on; [WakeWordDetector]
 *   already owns that matching logic and its phonetic variants.
 */
class WakeWordService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var speech: SpeechRecognitionManager? = null
    private var running = false
    private var backoffMs = BASE_BACKOFF_MS

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        speech = SpeechRecognitionManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasMicPermission()) {
            // No permission means no possible progress; exiting is honest and
            // cheap, and the UI already surfaces the permission row.
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            listenOnce()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        runCatching { speech?.destroy() }
        speech = null
        Listening.set(false)
        super.onDestroy()
    }

    /** One recognition session; the callbacks schedule the next one. */
    private fun listenOnce() {
        if (!running) return
        val engine = speech ?: return
        val started = engine.start(
            onPartial = { transcript -> handleTranscript(transcript, final = false) },
            onFinal = { transcript ->
                handleTranscript(transcript, final = true)
                scheduleNext(reset = true)
            },
            onError = { scheduleNext(reset = false) }
        )
        Listening.set(started)
        if (!started) scheduleNext(reset = false)
    }

    /**
     * Restarts the loop. [reset] true means the last session worked, so the
     * backoff returns to its floor; false means it failed and the delay grows.
     */
    private fun scheduleNext(reset: Boolean) {
        if (!running) return
        backoffMs = if (reset) BASE_BACKOFF_MS else minOf(backoffMs * 2, MAX_BACKOFF_MS)
        main.postDelayed({ listenOnce() }, backoffMs)
    }

    /**
     * Acts only on a transcript containing the wake phrase. Partial results
     * are accepted so the app opens the moment the name is heard instead of
     * waiting for end-of-speech.
     */
    private fun handleTranscript(transcript: String, final: Boolean) {
        if (!WakeWordDetector.containsWakeWord(transcript)) return
        val command = WakeWordDetector.stripWakeWord(transcript)
        // A partial hit with no command yet is worth opening on; waiting for
        // the final result costs roughly a second of perceived latency.
        if (!final && command.isNullOrBlank()) return
        wake(command)
    }

    /** Brings the assistant forward, pre-filling the spoken command if any. */
    private fun wake(command: String?) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_WAKE_COMMAND, command.orEmpty())
        }
        runCatching { startActivity(intent) }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AURIX wake word",
                NotificationManager.IMPORTANCE_MIN
            )
            runCatching { manager.createNotificationChannel(channel) }
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("AURIX is listening")
            .setContentText("Say Aurix to wake the assistant")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    /** Tiny observable flag so the chat UI can show a listening indicator. */
    object Listening {
        @Volatile
        private var active = false

        fun set(value: Boolean) {
            active = value
        }

        fun isActive(): Boolean = active
    }

    companion object {
        const val ACTION_STOP = "com.jarvis.ai.WAKE_WORD_STOP"
        const val EXTRA_WAKE_COMMAND = "wake_command"

        private const val CHANNEL_ID = "aurix_wake_word"
        private const val NOTIFICATION_ID = 4711
        private const val BASE_BACKOFF_MS = 600L
        private const val MAX_BACKOFF_MS = 30_000L

        /** Starts the listener. Safe to call repeatedly. */
        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        /** Stops the listener. Safe to call when it is not running. */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, WakeWordService::class.java))
            }
        }
    }
}
