package com.jarvis.ai.voice

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.jarvis.ai.MainActivity
import kotlin.math.min

/**
 * Lightweight Gemini-style assistant panel hosted by Android's
 * VoiceInteraction framework. It deliberately does not use Accessibility.
 */
class AurixVoiceInteractionSession(
    private val appContext: Context
) : VoiceInteractionSession(appContext) {

    override fun onCreateContentView(): View = AurixAssistantView(appContext) {
        val launch = Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_VOICE_MODE, true)
        }
        runCatching { appContext.startActivity(launch) }
        hide()
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
    }
}

/** Animated bottom assistant card; tapping it opens AURIX voice mode. */
private class AurixAssistantView(
    context: Context,
    private val onOpenVoice: () -> Unit
) : View(context) {

    private val density = resources.displayMetrics.density
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(13, 10, 12) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(84, 32, 42)
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 5f * density
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 23, 68) }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f * density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(181, 170, 173)
        textSize = 12f * density
    }

    private var phase = 0f
    private var downX = 0f
    private var downY = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_250L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        minimumHeight = (180f * density).toInt()
        setPadding((18f * density).toInt(), (16f * density).toInt(), (18f * density).toInt(), (20f * density).toInt())
        contentDescription = "AURIX assistant. Tap to start voice mode."
        isClickable = true
        animator.start()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = (190f * density).toInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = 10f * density
        val card = RectF(inset, inset, width - inset, height - inset)
        val radius = 32f * density
        canvas.drawRoundRect(card, radius, radius, backgroundPaint)
        canvas.drawRoundRect(card, radius, radius, borderPaint)

        val orbX = 67f * density
        val orbY = height / 2f
        val baseRadius = min(37f * density, height * 0.27f)
        val pulse = 0.92f + phase * 0.16f

        ringPaint.color = Color.rgb(255, 107, 0)
        ringPaint.alpha = (140 + phase * 90).toInt()
        canvas.drawCircle(orbX, orbY, baseRadius * pulse, ringPaint)
        ringPaint.color = Color.rgb(255, 23, 68)
        ringPaint.alpha = 255
        canvas.drawArc(
            RectF(
                orbX - baseRadius * 0.72f,
                orbY - baseRadius * 0.72f,
                orbX + baseRadius * 0.72f,
                orbY + baseRadius * 0.72f
            ),
            -75f + phase * 35f,
            230f,
            false,
            ringPaint
        )
        canvas.drawCircle(orbX, orbY, baseRadius * (0.28f + phase * 0.05f), corePaint)

        val textX = 125f * density
        canvas.drawText("AURIX", textX, orbY - 5f * density, titlePaint)
        canvas.drawText("Tap to speak", textX, orbY + 20f * density, subtitlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val moved = kotlin.math.abs(event.x - downX) + kotlin.math.abs(event.y - downY)
                if (moved < 24f * density) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onOpenVoice()
        return true
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }
}
