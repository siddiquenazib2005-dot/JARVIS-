package com.jarvis.ai.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * Reusable gesture engine. All coordinates are validated against real display
 * metrics (phase 18) and every dispatch is bounded by a timeout so a missing
 * callback can never hang the action queue (phase 6/7).
 */
class GestureEngine(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())

    fun inBounds(x: Int, y: Int): Boolean {
        val m = service.resources.displayMetrics
        return x in 0..m.widthPixels && y in 0..m.heightPixels
    }

    suspend fun tap(x: Int, y: Int): Boolean {
        if (!inBounds(x, y)) return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 1))
                .build()
        )
    }

    suspend fun longPress(x: Int, y: Int, durationMs: Long = 600): Boolean {
        if (!inBounds(x, y)) return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
        )
    }

    suspend fun swipe(sx: Int, sy: Int, ex: Int, ey: Int, durationMs: Long = 300): Boolean {
        if (!inBounds(sx, sy) || !inBounds(ex, ey)) return false
        val path = Path().apply {
            moveTo(sx.toFloat(), sy.toFloat())
            lineTo(ex.toFloat(), ey.toFloat())
        }
        return dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
        )
    }

    private suspend fun dispatch(gesture: GestureDescription): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            withTimeout(2000) {
                suspendCancellableCoroutine { cont ->
                    handler.post {
                        val ok = try {
                            service.dispatchGesture(
                                gesture,
                                object : AccessibilityService.GestureResultCallback() {
                                    override fun onCompleted(g: GestureDescription) {
                                        if (cont.isActive) cont.resume(true)
                                    }

                                    override fun onCancelled(g: GestureDescription) {
                                        if (cont.isActive) cont.resume(false)
                                    }
                                },
                                null
                            )
                        } catch (e: Exception) {
                            false
                        }
                        if (!ok && cont.isActive) cont.resume(false)
                    }
                    cont.invokeOnCancellation { /* gesture already dispatched; ignore */ }
                }
            }
        }.getOrDefault(false)
    }
}
