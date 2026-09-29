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
import com.jarvis.ai.core.VoiceSessionGate
import com.jarvis.ai.overlay.AvatarState
import com.jarvis.ai.overlay.AvatarStateBus
import com.jarvis.ai.overlay.ambient.AmbientPhase
import com.jarvis.ai.overlay.ambient.AmbientPhaseBus
import com.jarvis.ai.service.wakeword.OpenWakeWordProvider
import com.jarvis.ai.service.wakeword.WakeWordConfig
import com.jarvis.ai.service.wakeword.WakeWordProvider

/**
 * Always-on on-device wake-word listener.
 *
 * Wake-word phase redesign (directive: WAKE WORD ENGINE INTEGRATION):
 * - The engine is a [WakeWordProvider] (default: [OpenWakeWordProvider] over
 *   openWakeWord's ONNX models — fully on-device, no network, no API key).
 *   This service is the ONLY consumer; nothing else in AURIX touches the
 *   provider beyond [WakeWordConfig] defaults.
 * - Audio ownership: the provider owns the single always-on AudioRecord while
 *   armed. When a chat voice session (push-to-talk/hands-free) takes the mic
 *   ([VoiceSessionGate.active]) or the assistant is THINKING/SPEAKING, the
 *   provider is PAUSED and resumed when the mic is free again. There is never
 *   a second live capture alongside another listener.
 * - Phases: a detection announces the (already debounced) WakeWordBus and
 *   opens MainActivity exactly as before. Duplicate activations are blocked
 *   by the [WakeWordBus] debounce, the [ACTIVATION_COOLDOWN_MS] cooldown and
 *   the phase gate below; the visual AWAKENING -> LISTENING sequence remains
 *   owned by the ambient presence layer.
 * - Recovery: failures (mic denied, model unavailable, engine start errors)
 *   back off exponentially ([BASE_BACKOFF_MS] doubling to [MAX_BACKOFF_MS])
 *   instead of spinning. A permanently failing engine degrades to "wake word
 *   unavailable" — manual activation, chat and existing voice paths keep the
 *   app fully usable. Nothing here can crash the app.
 */
class WakeWordService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var provider: WakeWordProvider? = null
    private var running = false
    private var backoffMs = BASE_BACKOFF_MS

    /** Epoch ms of the last accepted activation (state-gate half of debounce). */
    private var lastActivationAtMs = 0L

    /** Re-arms the provider after a pause window (chat session, TTS turn). */
    private val resumeRun = Runnable { resumeProvider() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        provider = OpenWakeWordProvider(this, WakeWordConfig.DEFAULT)
        provider?.setListener { event -> onWakeDetected(event.score) }
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
            backoffMs = BASE_BACKOFF_MS
            startProvider()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        provider?.let { engine ->
            runCatching { engine.setListener(null) }
            runCatching { engine.release() }
        }
        provider = null
        Listening.set(false)
        super.onDestroy()
    }

    /** Arms the engine. On failure, backs off and retries while running. */
    private fun startProvider() {
        if (!running) return
        val started = runCatching { provider?.start() }.getOrDefault(false)
        Listening.set(started)
        if (started) {
            backoffMs = BASE_BACKOFF_MS
        } else {
            scheduleRetry()
        }
    }

    private fun resumeProvider() {
        if (!running) return
        if (mustStayPaused()) return
        val ok = runCatching { provider?.resume() }.getOrDefault(false)
        Listening.set(ok)
        if (!ok) scheduleRetry()
    }

    /**
     * Pauses while another pipeline owns the mic, then re-checks after a
     * short window instead of tight-looping.
     */
    private fun pauseProvider() {
        main.removeCallbacks(resumeRun)
        runCatching { provider?.pause() }
        Listening.set(false)
        main.postDelayed(resumeRun, PAUSE_RECHECK_MS)
    }

    /** Retry with exponential backoff; reset on every success. */
    private fun scheduleRetry() {
        if (!running) return
        main.postDelayed({ startProvider() }, backoffMs)
        backoffMs = minOf(backoffMs * 2, MAX_BACKOFF_MS)
    }

    /**
     * True while the mic must NOT be ours: a chat STT session is active, the
     * assistant is thinking, or it is speaking. Thinking/speaking suppressions
     * also prevent wake events from fighting a TTS turn mid-flight.
     */
    private fun mustStayPaused(): Boolean =
        WakeWordService.gateBlocked(
            phase = AmbientPhaseBus.current(),
            voiceSessionActive = VoiceSessionGate.active,
            avatar = AvatarStateBus.state()
        )

    private fun onWakeDetected(score: Float) {
        if (!running) return
        // Duplicate-activation gate: while a voice session holds the mic or a
        // turn is running, a detection can never start another one. (During
        // those phases the provider is paused anyway — this also covers the
        // race before pause lands.) The cooldown bounds false re-triggers; the
        // WakeWordBus debounce bounds repeated engine events.
        if (mustStayPaused()) return
        val now = System.currentTimeMillis()
        if (WakeWordService.cooldownActive(now, lastActivationAtMs)) return
        lastActivationAtMs = now
        wake()
    }

    /** Brings the assistant forward. */
    private fun wake() {
        // Let the ambient entity play its AWAKENING animation. The bus
        // debounces repeated events; this triggers ONLY the visual awakening —
        // interaction/execution still flows exactly as before (MainActivity ->
        // existing routing -> MissionEngine). No second pipeline exists.
        com.jarvis.ai.overlay.ambient.WakeWordBus.announce()
        // Yield the mic: the chat session that now opens owns it until the
        // turn ends and the phase returns to IDLE (resumeProvider re-arms us).
        pauseProvider()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // Reuse the existing voice-mode handoff: MainActivity's collector
            // opens the chat and starts the CURRENT hands-free/STT session.
            // No second execution pipeline is created.
            putExtra(MainActivity.EXTRA_OPEN_VOICE_MODE, true)
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
            .setContentText(wakeNotificationText)
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

        private const val CHANNEL_ID = "aurix_wake_word"
        private const val NOTIFICATION_ID = 4711
        private const val BASE_BACKOFF_MS = 600L
        private const val MAX_BACKOFF_MS = 30_000L
        /**
         * Post-activation cooldown (directive §DEBOUNCE): one wake word may
         * open at most one session per window even if the engine fires twice.
         */
        internal const val ACTIVATION_COOLDOWN_MS = 4_000L
        /** How often a paused provider re-checks whether the mic is free. */
        internal const val PAUSE_RECHECK_MS = 700L

        /**
         * Pure duplicate-activation gate (directive §DEBOUNCE). A wake event
         * is ignored while a voice session holds the mic, while the assistant
         * THINKS/SPEAKS, and during ANY non-idle chat phase (AWAKENING and
         * LISTENING included) — one wake word can never start a second session
         * on top of the one it just opened.
         */
        internal fun gateBlocked(
            phase: AmbientPhase,
            voiceSessionActive: Boolean,
            avatar: AvatarState
        ): Boolean =
            voiceSessionActive ||
                avatar == AvatarState.THINKING ||
                avatar == AvatarState.SPEAKING ||
                phase != AmbientPhase.IDLE

        /** Pure cooldown check (kept side-effect free for tests). */
        internal fun cooldownActive(nowMs: Long, lastActivationAtMs: Long): Boolean =
            nowMs - lastActivationAtMs < ACTIVATION_COOLDOWN_MS

        /** The phrase lives in WakeWordConfig.DEFAULT; never duplicated here. */
        private val wakeNotificationText: String
            get() = "Say \"${WakeWordConfig.DEFAULT.phrase.lowercase()}\" to wake the assistant"

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
