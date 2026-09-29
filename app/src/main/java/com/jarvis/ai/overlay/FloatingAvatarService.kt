package com.jarvis.ai.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.jarvis.ai.MainActivity
import com.jarvis.ai.diagnostics.CrashGuard
import com.jarvis.ai.overlay.ambient.AmbientAnimationClock
import com.jarvis.ai.overlay.ambient.AmbientContextController
import com.jarvis.ai.overlay.ambient.AmbientEntityPositioner
import com.jarvis.ai.overlay.ambient.AmbientEntityRenderer
import com.jarvis.ai.overlay.ambient.AmbientEventBus
import com.jarvis.ai.overlay.ambient.AmbientEventBusContract
import com.jarvis.ai.overlay.ambient.AmbientForm
import com.jarvis.ai.overlay.ambient.AmbientFormMorph
import com.jarvis.ai.overlay.ambient.AmbientFrame
import com.jarvis.ai.overlay.ambient.AmbientGestureClassifier
import com.jarvis.ai.overlay.ambient.AmbientMotionProfile
import com.jarvis.ai.overlay.ambient.AmbientPhase
import com.jarvis.ai.overlay.ambient.AmbientPoint
import com.jarvis.ai.overlay.ambient.AmbientPresenceController
import com.jarvis.ai.overlay.ambient.AmbientScreenModel
import com.jarvis.ai.overlay.ambient.AmbientSettings
import com.jarvis.ai.overlay.ambient.AmbientSpringPosition
import com.jarvis.ai.overlay.ambient.AmbientTransitionPlan
import com.jarvis.ai.overlay.ambient.AudioLevelBus
import com.jarvis.ai.overlay.ambient.AudioReactiveController
import com.jarvis.ai.overlay.ambient.WakeWordBus
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min

// File-level constant: velocity tracker smoothing time constant, milliseconds.
private const val VELOCITY_TAU_MS = 80f

/**
 * User-enabled, system-level AURIX companion — now the host window of the
 * ambient AI entity.
 *
 * It is deliberately independent from Accessibility: Android's overlay API is
 * the correct mechanism for a draggable assistant that remains visible over
 * the launcher and other apps. Voice capture only starts after an explicit
 * user action (panel MIC button / hold gesture); merely displaying the
 * companion never opens the microphone, and the only audio this service sees
 * is the already-computed amplitude published on [AudioLevelBus] by the app.
 *
 * Ambient layer (presentation only — MissionEngine routing is untouched):
 *  - [AmbientSpringPosition] + [AmbientEntityPositioner]: dynamic, collision-safe
 *    positioning with anticipation → travel → settle motion (never teleports).
 *  - [AmbientPresenceController]: decides phase/intensity; transient pulses decay.
 *  - [AmbientAnimationClock]: single Choreographer loop, low-power frame pacing.
 *  - [AmbientEntityRenderer]: procedural 2.5D holographic drawing.
 *  - [AudioReactiveController]: smoothed mic/TTS amplitude driving the visuals.
 */
class FloatingAvatarService : Service() {

    private lateinit var windowManager: WindowManager
    private var companionView: AurixCompanionView? = null
    private var params: WindowManager.LayoutParams? = null
    private var avatarState = AvatarState.IDLE
    private val main = Handler(Looper.getMainLooper())

    // --- Ambient entity layer ------------------------------------------------
    private val presence = AmbientPresenceController()
    private val renderer = AmbientEntityRenderer(presence)
    private val spring = AmbientSpringPosition(AmbientMotionProfile())
    private val audioReactive = AudioReactiveController()
    private val morph = AmbientFormMorph()
    private val context = AmbientContextController()
    private var frame = AmbientFrame.initial()
    private var screen = AmbientScreenModel(widthPx = 1, heightPx = 1)
    private var lastRenderedPhase: AmbientPhase? = null
    private var lastAppliedX = Int.MIN_VALUE
    private var lastAppliedY = Int.MIN_VALUE
    private var audioListener: ((Float) -> Unit)? = null
    private var wakeListener: (() -> Unit)? = null
    private var avatarStateListener: ((AvatarState) -> Unit)? = null
    private var eventListener: (() -> Unit)? = null
    /** §13 user toggle, cached; refreshed on start and APPLY_SETTINGS. */
    private var audioReactiveEnabled = true

    private val clock = AmbientAnimationClock { timeMs, deltaMs -> onAmbientFrame(timeMs, deltaMs) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        avatarState = AvatarStateBus.state()
        presence.onAssistantState(avatarState)
        // §13: restore the user's ambient controls on every start.
        presence.animationIntensity = AmbientSettings.animationIntensity(this)
        audioReactiveEnabled = AmbientSettings.audioReactiveEnabled(this)
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!canDrawOverlay(this)) {
            stopSelf()
            return
        }
        runCatching { showCompanion() }
            .onFailure { CrashGuard.record(applicationContext, it) }
        val stateListener: (AvatarState) -> Unit = { next ->
            main.post {
                avatarState = next
                presence.onAssistantState(next)
                companionView?.setAvatarState(next)
                notifyState()
            }
        }
        avatarStateListener = stateListener
        AvatarStateBus.observe(stateListener)
        // Real amplitude (mic RMS + TTS envelope) computed inside the app and
        // bridged here; the overlay never captures audio itself.
        audioReactive.reset()
        val listener: (Float) -> Unit = { audioReactive.updateSource(it) }
        audioListener = listener
        AudioLevelBus.reset()
        AudioLevelBus.observe(listener)

        // Phase G: the EXISTING WakeWordService announces detections here;
        // the orb plays AWAKENING and the app still handles the interaction
        // exactly as before. Debounce lives in the bus.
        WakeWordBus.reset()
        val onWake: () -> Unit = {
            presence.setMinimized(false)
            context.setSleeping(false)
            presence.pulse(AmbientPhase.AWAKENING, AWAKENING_PULSE_MS)
        }
        wakeListener = onWake
        WakeWordBus.observe(onWake)

        // Phase E: important AURIX-internal events briefly boost presence.
        val onImportantEvent: () -> Unit = { context.importantEvent() }
        eventListener = onImportantEvent
        AmbientEventBus.observe(onImportantEvent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_VOICE -> openAssistant(voice = true)
            ACTION_OPEN -> openAssistant(voice = false)
            ACTION_HIDE -> {
                setEnabled(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_COLLAPSE -> collapse()
            // Phase E/F: presence controls (notification + PendingIntent only,
            // no new UI).
            ACTION_MINIMIZE -> {
                presence.setMinimized(true)
                context.setSleeping(true)
            }
            ACTION_WAKE_UP -> {
                presence.setMinimized(false)
                context.setSleeping(false)
                presence.pulse(AmbientPhase.AWAKENING, AWAKENING_PULSE_MS)
            }
            // Phase E: transient alert — bigger, brighter, then decays.
            AmbientEventBusContract.ACTION_IMPORTANT_EVENT -> context.importantEvent()
            // Phase F: drag ended with velocity — glide in the fling direction.
            ACTION_DRAG_RELEASE -> handleDragRelease(
                DragRelease(
                    dx = intent.getFloatExtra(EXTRA_DRAG_DX, 0f),
                    dy = intent.getFloatExtra(EXTRA_DRAG_DY, 0f),
                    dtMs = intent.getLongExtra(EXTRA_DRAG_DT, 16L)
                )
            )
            // §13: live settings application without recreating the service.
            ACTION_APPLY_SETTINGS -> {
                presence.animationIntensity = AmbientSettings.animationIntensity(this)
                audioReactiveEnabled = AmbientSettings.audioReactiveEnabled(this)
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation / density change: refresh geometry and glide to a safe spot
        // instead of leaving the entity stranded off-screen.
        main.post {
            refreshScreenModel()
            val size = entitySize()
            val plan = AmbientEntityPositioner.plan(
                from = spring.current,
                desiredX = spring.target.x,
                desiredY = spring.target.y,
                screen = screen,
                entitySizePx = size
            )
            spring.startTransition(plan)
        }
    }

    override fun onDestroy() {
        avatarStateListener?.let { AvatarStateBus.stopObserving(it) }
        avatarStateListener = null
        audioListener?.let { AudioLevelBus.stopObserving(it) }
        audioListener = null
        AudioLevelBus.reset()
        wakeListener?.let { WakeWordBus.stopObserving(it) }
        wakeListener = null
        eventListener?.let { AmbientEventBus.stopObserving(it) }
        eventListener = null
        clock.stop()
        main.removeCallbacksAndMessages(null)
        companionView?.stopAnimation()
        runCatching { companionView?.let(windowManager::removeView) }
        companionView = null
        params = null
        super.onDestroy()
    }

    private fun showCompanion() {
        if (companionView != null) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val collapsed = (76 * density).toInt()
        val saved = getSharedPreferences(PREFS, MODE_PRIVATE)
        refreshScreenModel()

        val layout = WindowManager.LayoutParams(
            collapsed,
            collapsed,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val restored = AmbientEntityPositioner.resolveTarget(
                rawX = saved.getInt(KEY_X, displayWidth() - collapsed - (16 * density).toInt()).toFloat(),
                rawY = saved.getInt(KEY_Y, displayHeight() / 3).toFloat(),
                screen = screen,
                entitySizePx = collapsed
            )
            x = restored.x.toInt()
            y = restored.y.toInt()
        }
        params = layout
        spring.snapTo(AmbientPoint(layout.x.toFloat(), layout.y.toFloat()))
        lastAppliedX = layout.x
        lastAppliedY = layout.y

        val view = AurixCompanionView(
            context = this,
            initialState = avatarState,
            renderer = renderer,
            onDrag = { dx, dy ->
                spring.translate(dx.toFloat(), dy.toFloat())
            },
            onTouchActive = { active -> context.setInteracting(active) },
            onDragFinished = { dx: Float, dy: Float, dtMs: Long ->
                handleDragRelease(DragRelease(dx, dy, dtMs))
            },
            onExpandedChanged = { expanded -> resize(expanded) },
            onVoice = {
                presence.pulse(AmbientPhase.AWAKENING, AWAKENING_PULSE_MS)
                openAssistant(voice = true)
            },
            onOpen = { openAssistant(voice = false) },
            onHide = {
                setEnabled(this, false)
                stopSelf()
            },
            onHold = {
                presence.pulse(AmbientPhase.AWAKENING, AWAKENING_PULSE_MS)
                openAssistant(voice = true)
            },
            onDoubleTap = {
                presence.pulse(AmbientPhase.AWAKENING, AWAKENING_PULSE_MS)
                openAssistant(voice = true)
            },
            onMinimize = {
                presence.setMinimized(true)
                context.setSleeping(true)
            }
        )
        companionView = view
        windowManager.addView(view, layout)
        clock.start(lowPowerPhase = presence.currentPhase().lowPower)
    }

    /** One animation tick: physics → window position → redraw. */
    private fun onAmbientFrame(timeMs: Long, deltaMs: Long) {
        val view = companionView ?: return
        val current = params ?: return

        val phase = presence.currentPhase(timeMs)
        if (phase != lastRenderedPhase) {
            lastRenderedPhase = phase
            // Context follows the RENDERED phase (transient pulses included),
            // so presence visuals stay in one coherent story.
            context.onPhase(phase)
            // Phase D: retarget the shell morph; parameters glide, no hard cut.
            morph.target(AmbientForm.from(phase))
            clock.setLowPower(phase.lowPower)
        }
        spring.advance(deltaMs)
        morph.advance(deltaMs)
        val audio = if (audioReactiveEnabled) audioReactive.sample(timeMs) else 0f
        frame = AmbientFrame(
            phase = phase,
            form = morph.snapshot(),
            context = context.profile(timeMs),
            audio = audio,
            timeMs = timeMs
        )

        // Apply the spring position to the window only when it actually moved;
        // updateViewLayout on every frame is what we must avoid.
        val point = spring.current
        val nx = point.x.toInt()
        val ny = point.y.toInt()
        if (nx != lastAppliedX || ny != lastAppliedY) {
            lastAppliedX = nx
            lastAppliedY = ny
            current.x = nx
            current.y = ny
            clamp(current)
            runCatching { windowManager.updateViewLayout(view, current) }
        }
        view.invalidateWith(frame)
    }

    /** Drag-release data handed over from the view's gesture handler. */
    private data class DragRelease(val dx: Float, val dy: Float, val dtMs: Long)

    /**
     * Drag released. Fast releases keep their momentum (Phase F: the entity
     * glides in the fling direction and settles collision-safe); slow releases
     * snap to the nearest screen edge as before. Both paths persist the spot.
     */
    private fun handleDragRelease(release: DragRelease) {
        val dt = release.dtMs.coerceAtLeast(1L)
        val speedPxPerSec = hypot(release.dx, release.dy) / dt * 1000f
        if (speedPxPerSec < FLING_THRESHOLD_PX_PER_SEC) {
            glideToEdgeAndPersist()
            return
        }
        // ~350ms of carried momentum, resolved into a safe target.
        val position = spring.current
        val desiredX = position.x + release.dx / dt * 1000f * FLING_MOMENTUM_FRACTION
        val desiredY = position.y + release.dy / dt * 1000f * FLING_MOMENTUM_FRACTION
        val plan = AmbientEntityPositioner.plan(
            from = spring.current,
            desiredX = desiredX,
            desiredY = desiredY,
            screen = screen,
            entitySizePx = entitySize()
        )
        spring.startTransition(plan)
        persistPosition(plan.to)
    }

    private fun persistPosition(point: AmbientPoint) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_X, point.x.toInt())
            .putInt(KEY_Y, point.y.toInt())
            .apply()
    }

    /** Drag ended without momentum: glide to the nearest screen edge, then persist. */
    private fun glideToEdgeAndPersist() {
        val current = params ?: return
        val size = entitySize()
        val position = point()
        val middle = position.x + size / 2f
        val desiredX = if (middle < displayWidth() / 2f) 0f
        else (displayWidth() - size).coerceAtLeast(0).toFloat()
        val desiredY = (position.y - size / 2f + current.height / 2f)
            .coerceAtLeast(0f).toFloat()
        val plan: AmbientTransitionPlan = AmbientEntityPositioner.plan(
            from = spring.current,
            desiredX = desiredX,
            desiredY = desiredY,
            screen = screen,
            entitySizePx = size
        )
        spring.startTransition(plan)
        persistPosition(plan.to)
    }

    // Helpers over the live spring position (the window layout params lag a
    // frame behind the physics, so decisions use the physics values).
    private fun point(): AmbientPoint = spring.current

    private fun entitySize(): Int =
        params?.width ?: (76 * resources.displayMetrics.density).toInt()

    private fun displayWidth(): Int = resources.displayMetrics.widthPixels

    private fun displayHeight(): Int = resources.displayMetrics.heightPixels

    /** Real display geometry + status-bar guard, refreshed on config changes. */
    private fun refreshScreenModel() {
        val statusGuard = statusBarHeightPx()
        screen = AmbientScreenModel(
            widthPx = displayWidth(),
            heightPx = displayHeight(),
            statusGuardPx = statusGuard
        )
    }

    private fun statusBarHeightPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) {
            runCatching { resources.getDimensionPixelSize(id) }.getOrDefault(0)
        } else 0
    }

    private fun resize(expanded: Boolean) {
        val current = params ?: return
        val view = companionView ?: return
        val density = resources.displayMetrics.density
        current.width = ((if (expanded) 296 else 76) * density).toInt()
        current.height = ((if (expanded) 118 else 76) * density).toInt()
        clamp(current)
        runCatching { windowManager.updateViewLayout(view, current) }
    }

    private fun collapse() {
        companionView?.collapse()
    }

    private fun clamp(value: WindowManager.LayoutParams) {
        val resolved = AmbientEntityPositioner.resolveTarget(
            rawX = value.x.toFloat(),
            rawY = value.y.toFloat(),
            screen = screen,
            entitySizePx = value.width
        )
        value.x = resolved.x.toInt()
        value.y = resolved.y.toInt()
    }

    private fun openAssistant(voice: Boolean) {
        val launch = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (voice) putExtra(MainActivity.EXTRA_OPEN_VOICE_MODE, true)
        }
        runCatching { startActivity(launch) }
            .onFailure { CrashGuard.record(applicationContext, it) }
        collapse()
    }

    private fun notifyState() {
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, FloatingAvatarService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AURIX companion", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps the user-enabled AURIX companion available on screen"
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this,
            20,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("AURIX · ${avatarState.label}")
            .setContentText("Floating companion is active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_btn_speak_now, "Speak", serviceIntent(ACTION_VOICE, 21))
            .addAction(android.R.drawable.ic_menu_manage, "Minimize", serviceIntent(ACTION_MINIMIZE, 23))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hide", serviceIntent(ACTION_HIDE, 22))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "aurix_companion"
        private const val NOTIFICATION_ID = 4801
        private const val PREFS = "aurix_companion"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"

        private const val ACTION_VOICE = "com.aurix.ai.companion.VOICE"
        private const val ACTION_OPEN = "com.aurix.ai.companion.OPEN"
        private const val ACTION_HIDE = "com.aurix.ai.companion.HIDE"
        private const val ACTION_COLLAPSE = "com.aurix.ai.companion.COLLAPSE"

        // Phase E/F presence + gesture actions.
        private const val ACTION_MINIMIZE = "com.aurix.ai.companion.MINIMIZE"
        private const val ACTION_WAKE_UP = "com.aurix.ai.companion.WAKE_UP"
        private const val ACTION_APPLY_SETTINGS = "com.aurix.ai.companion.APPLY_SETTINGS"
        private const val ACTION_DRAG_RELEASE = "com.aurix.ai.companion.DRAG_RELEASE"
        private const val EXTRA_DRAG_DX = "dragDx"
        private const val EXTRA_DRAG_DY = "dragDy"
        private const val EXTRA_DRAG_DT = "dragDtMs"

        /** Releases slower than this snap to an edge; faster ones keep momentum. */
        private const val FLING_THRESHOLD_PX_PER_SEC = 1400f
        /** Fraction of one second of fling velocity carried into the glide. */
        private const val FLING_MOMENTUM_FRACTION = 0.35f

        /** How long the AWAKENING pulse shows before settling into LISTENING. */
        private const val AWAKENING_PULSE_MS = 900L

        /** Applies [AmbientSettings] to a running companion (user controls). */
        fun applySettings(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, FloatingAvatarService::class.java)
                        .setAction(ACTION_APPLY_SETTINGS)
                )
            }
        }

        /** Phase E: parks the companion (SLEEPING) without hiding the service. */
        fun minimize(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, FloatingAvatarService::class.java)
                        .setAction(ACTION_MINIMIZE)
                )
            }
        }

        /** Phase E: wakes a minimized companion with the AWAKENING pulse. */
        fun wakeUp(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, FloatingAvatarService::class.java)
                        .setAction(ACTION_WAKE_UP)
                )
            }
        }

        fun canDrawOverlay(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        private fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)

        fun requestPermission(context: Context): String {
            setEnabled(context, true)
            val opened = runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.isSuccess
            return if (opened) {
                "Allow Display over other apps, then return to AURIX. The companion will start automatically."
            } else {
                "Open Settings → Special access → Display over other apps and allow AURIX."
            }
        }

        fun show(context: Context): String {
            setEnabled(context, true)
            if (!canDrawOverlay(context)) return requestPermission(context)
            return runCatching {
                val intent = Intent(context, FloatingAvatarService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                "AURIX companion is active. Tap the orb for voice and controls."
            }.getOrElse {
                CrashGuard.record(context.applicationContext, it)
                "I could not start the floating companion: ${it::class.java.simpleName}."
            }
        }

        /** Called when the owner returns from Android's overlay settings. */
        fun resumeIfRequested(context: Context) {
            if (isEnabled(context) && canDrawOverlay(context)) show(context)
        }

        fun hide(context: Context): String {
            setEnabled(context, false)
            return runCatching {
                context.stopService(Intent(context, FloatingAvatarService::class.java))
                "AURIX companion hidden."
            }.getOrElse { "The companion was not running." }
        }
    }
}

/**
 * Original AURIX energy companion view; no third-party character assets.
 *
 * Owns gestures and the expanded control panel only — the orb itself is drawn
 * by [AmbientEntityRenderer] (holographic 2.5D), and frames come from the
 * service's shared [AmbientAnimationClock], so this view keeps no animator of
 * its own. Gesture set: drag to reposition, tap to expand the panel, tap again
 * on the orb for voice, hold to wake AURIX listening, double-tap for a quick
 * voice open. All gestures stay inside the orb's bounds and never interfere
 * with Android navigation.
 */
private class AurixCompanionView(
    context: Context,
    initialState: AvatarState,
    private val renderer: AmbientEntityRenderer,
    private val onDrag: (Int, Int) -> Unit,
    private val onTouchActive: (Boolean) -> Unit,
    private val onDragFinished: (Float, Float, Long) -> Unit,
    private val onMinimize: () -> Unit,
    private val onExpandedChanged: (Boolean) -> Unit,
    private val onVoice: () -> Unit,
    private val onOpen: () -> Unit,
    private val onHide: () -> Unit,
    private val onHold: () -> Unit,
    private val onDoubleTap: () -> Unit
) : View(context) {

    private val d = resources.displayMetrics.density
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(12, 8, 10) }
    private val panelBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(105, 28, 42); style = Paint.Style.STROKE; strokeWidth = 1.4f * d
    }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 16f * d; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val subtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(190, 177, 181); textSize = 10.5f * d
    }
    private val buttonText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 10f * d; typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val button = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(51, 18, 25) }

    private var state = initialState
    private var expanded = false

    // Latest frame handed over by the ambient clock.
    private var frame: AmbientFrame = AmbientFrame.initial()

    // Gesture bookkeeping.
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downAtMs = 0L
    private var lastMoveAtMs = 0L
    private var velocityX = 0f
    private var velocityY = 0f
    private var dragged = false
    private var holdFired = false
    private var lastTapAtMs = 0L
    private val holdTimeoutMs = 480L
    private val doubleTapWindowMs = 280L

    private val holdRunnable = Runnable {
        if (expanded || dragged) return@Runnable
        holdFired = true
        onHold()
    }

    /** Fast swipe up: wake AURIX listening (same path as the hold gesture). */
    private fun onSwipeWake() {
        velocityX = 0f
        velocityY = 0f
        onHold()
    }

    /** Fast swipe down: minimize the entity ("swipe away to dismiss"). */
    private fun onSwipeMinimize() {
        velocityX = 0f
        velocityY = 0f
        onMinimize()
    }

    init {
        contentDescription = "AURIX floating companion. Tap to expand, hold or swipe up to wake, swipe down to minimize, drag to move."
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        elevation = 12f * d
    }

    fun setAvatarState(next: AvatarState) {
        state = next
        contentDescription = "AURIX is ${next.label}. Tap to expand, hold or swipe up to wake, swipe down to minimize, drag to move."
        invalidate()
    }

    /** Called by the shared ambient clock: refresh frame data and redraw. */
    fun invalidateWith(newFrame: AmbientFrame) {
        frame = newFrame
        invalidate()
    }

    fun collapse() {
        if (!expanded) return
        expanded = false
        onExpandedChanged(false)
        invalidate()
    }

    fun stopAnimation() {
        // Kept for API compatibility: the view no longer owns an animator;
        // the shared clock is stopped by the service.
        removeCallbacks(holdRunnable)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (expanded) {
            drawPanel(canvas)
        } else {
            renderer.render(
                canvas = canvas,
                cx = width / 2f,
                cy = height / 2f,
                radius = min(width, height) * 0.42f,
                frame = frame
            )
        }
    }

    private fun drawPanel(canvas: Canvas) {
        val bounds = RectF(2f * d, 2f * d, width - 2f * d, height - 2f * d)
        canvas.drawRoundRect(bounds, 26f * d, 26f * d, panel)
        canvas.drawRoundRect(bounds, 26f * d, 26f * d, panelBorder)
        renderer.render(canvas, 47f * d, 48f * d, 31f * d, frame)
        canvas.drawText("AURIX", 88f * d, 34f * d, title)
        canvas.drawText(state.label.uppercase(), 88f * d, 52f * d, subtitle)

        val top = 70f * d
        val bottom = 105f * d
        val labels = listOf("MIC", "OPEN", "HIDE")
        labels.forEachIndexed { index, label ->
            val left = (84 + index * 67).toFloat() * d
            val rect = RectF(left, top, left + 58f * d, bottom)
            canvas.drawRoundRect(rect, 13f * d, 13f * d, button)
            canvas.drawText(label, rect.centerX(), rect.centerY() + 3.5f * d, buttonText)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                downAtMs = event.eventTime
                lastMoveAtMs = event.eventTime
                velocityX = 0f
                velocityY = 0f
                dragged = false
                holdFired = false
                onTouchActive(true)
                postDelayed(holdRunnable, holdTimeoutMs)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val total = abs(event.rawX - downX) + abs(event.rawY - downY)
                if (total > 10f * d) {
                    dragged = true
                    removeCallbacks(holdRunnable)
                }
                if (dragged) {
                    val dx = event.rawX - lastRawX
                    val dy = event.rawY - lastRawY
                    onDrag(dx.toInt(), dy.toInt())
                    // Exponential velocity tracker (no VelocityTracker allocation).
                    val dt = (event.eventTime - lastMoveAtMs).coerceAtLeast(1L)
                    val blend = 1f - exp(-dt / VELOCITY_TAU_MS)
                    velocityX += (dx / dt * 1000f - velocityX) * blend
                    velocityY += (dy / dt * 1000f - velocityY) * blend
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    lastMoveAtMs = event.eventTime
                }
                return true
            }
                        MotionEvent.ACTION_UP -> {
                removeCallbacks(holdRunnable)
                onTouchActive(false)
                if (holdFired) {
                    performClick()
                    return true
                }
                if (dragged) {
                    val releaseDt = (event.eventTime - lastMoveAtMs).coerceAtLeast(1L)
                    when (AmbientGestureClassifier.classify(velocityX, velocityY)) {
                        AmbientGestureClassifier.Gesture.WAKE -> onSwipeWake()
                        AmbientGestureClassifier.Gesture.MINIMIZE -> onSwipeMinimize()
                        AmbientGestureClassifier.Gesture.FLING ->
                            onDragFinished(velocityX * releaseDt / 1000f, velocityY * releaseDt / 1000f, releaseDt)
                        AmbientGestureClassifier.Gesture.REST -> onDragFinished(0f, 0f, releaseDt)
                    }
                } else if (expanded) {
                    when {
                        event.x in 84f * d..142f * d && event.y >= 68f * d -> onVoice()
                        event.x in 151f * d..209f * d && event.y >= 68f * d -> onOpen()
                        event.x in 218f * d..276f * d && event.y >= 68f * d -> onHide()
                        else -> collapse()
                    }
                } else {
                    // Quick double-tap opens voice directly; a single tap
                    // expands the panel (no added latency for single taps).
                    val now = System.currentTimeMillis()
                    if (now - lastTapAtMs <= doubleTapWindowMs) {
                        lastTapAtMs = 0L
                        onDoubleTap()
                    } else {
                        lastTapAtMs = now
                        expanded = true
                        onExpandedChanged(true)
                        invalidate()
                    }
                }
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdRunnable)
                holdFired = false
                onTouchActive(false)
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
