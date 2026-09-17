package com.jarvis.ai.overlay

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import android.view.animation.LinearInterpolator
import com.jarvis.ai.MainActivity
import com.jarvis.ai.diagnostics.CrashGuard
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * User-enabled, system-level AURIX companion.
 *
 * It is deliberately independent from Accessibility: Android's overlay API is
 * the correct mechanism for a draggable assistant that remains visible over
 * the launcher and other apps. The collapsed orb is passive; tapping expands
 * an explicit control panel. Voice capture only starts after the user taps the
 * MIC action, so merely displaying the companion never opens the microphone.
 */
class FloatingAvatarService : Service() {

    private lateinit var windowManager: WindowManager
    private var companionView: AurixCompanionView? = null
    private var params: WindowManager.LayoutParams? = null
    private var avatarState = AvatarState.IDLE
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        avatarState = AvatarStateBus.state()
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!canDrawOverlay(this)) {
            stopSelf()
            return
        }
        runCatching { showCompanion() }
            .onFailure { CrashGuard.record(applicationContext, it) }
        AvatarStateBus.observe { next ->
            main.post {
                avatarState = next
                companionView?.setAvatarState(next)
                notifyState()
            }
        }
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
        }
        return START_STICKY
    }

    override fun onDestroy() {
        AvatarStateBus.stopObserving()
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
        val display = resources.displayMetrics

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
            x = saved.getInt(KEY_X, display.widthPixels - collapsed - (16 * density).toInt())
            y = saved.getInt(KEY_Y, display.heightPixels / 3)
        }
        params = layout

        val view = AurixCompanionView(
            context = this,
            initialState = avatarState,
            onDrag = { dx, dy -> moveBy(dx, dy) },
            onDragFinished = { snapAndPersist() },
            onExpandedChanged = { expanded -> resize(expanded) },
            onVoice = { openAssistant(voice = true) },
            onOpen = { openAssistant(voice = false) },
            onHide = {
                setEnabled(this, false)
                stopSelf()
            }
        )
        companionView = view
        windowManager.addView(view, layout)
    }

    private fun moveBy(dx: Int, dy: Int) {
        val current = params ?: return
        val view = companionView ?: return
        current.x += dx
        current.y += dy
        clamp(current)
        runCatching { windowManager.updateViewLayout(view, current) }
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
        val display = resources.displayMetrics
        value.x = value.x.coerceIn(0, (display.widthPixels - value.width).coerceAtLeast(0))
        value.y = value.y.coerceIn(0, (display.heightPixels - value.height).coerceAtLeast(0))
    }

    private fun snapAndPersist() {
        val current = params ?: return
        val display = resources.displayMetrics
        val middle = current.x + current.width / 2
        current.x = if (middle < display.widthPixels / 2) 0
        else (display.widthPixels - current.width).coerceAtLeast(0)
        clamp(current)
        companionView?.let { runCatching { windowManager.updateViewLayout(it, current) } }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_X, current.x)
            .putInt(KEY_Y, current.y)
            .apply()
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

/** Original AURIX energy companion; no third-party character assets are used. */
private class AurixCompanionView(
    context: Context,
    initialState: AvatarState,
    private val onDrag: (Int, Int) -> Unit,
    private val onDragFinished: () -> Unit,
    private val onExpandedChanged: (Boolean) -> Unit,
    private val onVoice: () -> Unit,
    private val onOpen: () -> Unit,
    private val onHide: () -> Unit
) : View(context) {

    private val d = resources.displayMetrics.density
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(12, 8, 10) }
    private val panelBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(105, 28, 42); style = Paint.Style.STROKE; strokeWidth = 1.4f * d
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 4.5f * d
    }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
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
    private var phase = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragged = false

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
        start()
    }

    init {
        contentDescription = "AURIX floating companion. Tap to expand, drag to move."
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        elevation = 12f * d
    }

    fun setAvatarState(next: AvatarState) {
        state = next
        contentDescription = "AURIX is ${next.label}. Tap to expand, drag to move."
        invalidate()
    }

    fun collapse() {
        if (!expanded) return
        expanded = false
        onExpandedChanged(false)
        invalidate()
    }

    fun stopAnimation() = animator.cancel()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (expanded) drawPanel(canvas) else drawOrb(canvas, width / 2f, height / 2f, min(width, height) * 0.42f)
    }

    private fun drawPanel(canvas: Canvas) {
        val bounds = RectF(2f * d, 2f * d, width - 2f * d, height - 2f * d)
        canvas.drawRoundRect(bounds, 26f * d, 26f * d, panel)
        canvas.drawRoundRect(bounds, 26f * d, 26f * d, panelBorder)
        drawOrb(canvas, 47f * d, 48f * d, 31f * d)
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

    private fun drawOrb(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val energy = when (state) {
            AvatarState.IDLE -> 0.30f
            AvatarState.LISTENING -> 1.00f
            AvatarState.THINKING -> 0.72f
            AvatarState.SPEAKING -> 0.90f
        }
        val wave = (sin(phase * Math.PI * 2).toFloat() + 1f) / 2f
        glow.color = state.outerColor
        glow.alpha = (35 + 75 * wave * energy).toInt()
        canvas.drawCircle(cx, cy, radius * (1.10f + 0.20f * wave), glow)

        ring.color = state.outerColor
        ring.alpha = 210
        canvas.drawCircle(cx, cy, radius * (0.82f + 0.05f * wave), ring)
        ring.color = state.innerColor
        ring.alpha = 255
        val orbit = RectF(cx - radius * 0.64f, cy - radius * 0.64f, cx + radius * 0.64f, cy + radius * 0.64f)
        canvas.drawArc(orbit, phase * 360f, 205f, false, ring)

        core.color = Color.WHITE
        canvas.drawCircle(cx, cy, radius * (0.18f + 0.06f * wave * energy), core)
        core.color = state.innerColor
        core.alpha = 220
        canvas.drawCircle(
            cx + cos(phase * Math.PI * 2).toFloat() * radius * 0.34f,
            cy + sin(phase * Math.PI * 2).toFloat() * radius * 0.34f,
            radius * 0.07f,
            core
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                dragged = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val total = abs(event.rawX - downX) + abs(event.rawY - downY)
                if (total > 10f * d) dragged = true
                if (dragged) {
                    onDrag((event.rawX - lastRawX).toInt(), (event.rawY - lastRawY).toInt())
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragged) {
                    onDragFinished()
                } else if (expanded) {
                    when {
                        event.x in 84f * d..142f * d && event.y >= 68f * d -> onVoice()
                        event.x in 151f * d..209f * d && event.y >= 68f * d -> onOpen()
                        event.x in 218f * d..276f * d && event.y >= 68f * d -> onHide()
                        else -> collapse()
                    }
                } else {
                    expanded = true
                    onExpandedChanged(true)
                    invalidate()
                }
                performClick()
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
