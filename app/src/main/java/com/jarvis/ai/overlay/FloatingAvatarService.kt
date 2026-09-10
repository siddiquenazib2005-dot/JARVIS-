package com.jarvis.ai.overlay

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import com.jarvis.ai.MainActivity
import kotlin.math.abs

/**
 * Draggable floating AURIX bubble that stays on top of other apps.
 *
 * Tap opens the assistant, drag repositions it, and a long drag to the bottom
 * of the screen is not required — the bubble is dismissed from chat with
 * "hide bubble". Runs as a foreground service so Android keeps it alive.
 */
class FloatingAvatarService : Service() {

    private var windowManager: WindowManager? = null
    private var bubble: View? = null

    /** Kept so the pulse animation can recolour the bubble without rebuilding it. */
    private var bubbleSkin: GradientDrawable? = null

    /** The single running pulse; replaced whenever the state changes. */
    private var pulse: ValueAnimator? = null

    /**
     * Named `avatarState`, not `state`: inside a `GradientDrawable.apply { }`
     * block a plain `state` resolves to `Drawable.getState()` (an IntArray),
     * which silently shadows this field and fails to compile.
     */
    private var avatarState: AvatarState = AvatarState.IDLE

    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        avatarState = AvatarStateBus.state()
        startForeground(NOTIFICATION_ID, buildNotification())
        runCatching { showBubble() }

        // The chat UI publishes states from the main thread, but a background
        // tool chain may also publish, so every update is posted to main.
        AvatarStateBus.observe { next ->
            main.post { runCatching { applyState(next) } }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        AvatarStateBus.stopObserving()
        main.removeCallbacksAndMessages(null)
        runCatching { pulse?.cancel() }
        pulse = null
        runCatching { bubble?.let { windowManager?.removeView(it) } }
        bubble = null
        bubbleSkin = null
        super.onDestroy()
    }

    /**
     * Switches the bubble to a new state: colours, glyph, pulse speed and the
     * foreground notification text all follow [AvatarState].
     *
     * Only visuals change here. Touch handling, window params and the
     * foreground-service lifecycle are deliberately left untouched.
     */
    private fun applyState(next: AvatarState) {
        avatarState = next
        val view = bubble
        val skin = bubbleSkin

        if (view is TextView) view.text = next.glyph
        skin?.colors = intArrayOf(next.innerColor, next.outerColor)

        runCatching { pulse?.cancel() }
        if (view == null) return

        pulse = ValueAnimator.ofFloat(next.minScale, next.maxScale).apply {
            duration = next.periodMs / 2
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val scale = anim.animatedValue as Float
                view.scaleX = scale
                view.scaleY = scale
                // Idle stays dim so it does not compete with the app in front;
                // active states brighten as they grow.
                view.alpha = if (next == AvatarState.IDLE) 0.85f else 1f
            }
            start()
        }

        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun showBubble() {
        if (bubble != null) return
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = manager

        val size = (56 * resources.displayMetrics.density).toInt()
        val current = avatarState
        val skin = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            colors = intArrayOf(current.innerColor, current.outerColor)
            setStroke((2 * resources.displayMetrics.density).toInt(), 0x55FFFFFF)
        }
        bubbleSkin = skin

        val view = TextView(this).apply {
            text = current.glyph
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            background = skin
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - 24
            y = resources.displayMetrics.heightPixels / 3
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragged = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) dragged = true
                    params.x = startX + dx
                    params.y = startY + dy
                    runCatching { manager.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) openAssistant()
                    true
                }
                else -> false
            }
        }

        manager.addView(view, params)
        bubble = view

        // Start whatever state was already published, so a bubble opened in the
        // middle of a voice turn does not appear idle.
        runCatching { applyState(current) }
    }

    private fun openAssistant() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "AURIX bubble",
                    NotificationManager.IMPORTANCE_MIN
                )
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("AURIX is " + avatarState.label)
            .setContentText("Tap the bubble any time, sir.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "aurix_bubble"
        private const val NOTIFICATION_ID = 4801

        /** True when "Display over other apps" has been granted. */
        fun canDrawOverlay(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        /** Starts the bubble, or guides the user to the permission screen. */
        fun show(context: Context): String {
            if (!canDrawOverlay(context)) {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + context.packageName)
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                return "The floating bubble needs \"display over other apps\", sir. " +
                    "I opened the setting — allow it, then say \"show bubble\" again."
            }
            return runCatching {
                val intent = Intent(context, FloatingAvatarService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                "Bubble is up, sir. Tap it any time to reach me."
            }.getOrElse { "I could not start the bubble, sir." }
        }

        /** Removes the bubble. */
        fun hide(context: Context): String = runCatching {
            context.stopService(Intent(context, FloatingAvatarService::class.java))
            "Bubble hidden, sir."
        }.getOrElse { "The bubble was not running, sir." }
    }
}
