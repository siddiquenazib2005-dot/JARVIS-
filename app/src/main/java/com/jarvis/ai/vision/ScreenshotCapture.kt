package com.jarvis.ai.vision

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.coroutines.resume

class ScreenshotCapture(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var isCapturing = false
    private var displayWidth = 0
    private var displayHeight = 0
    private var displayDensity = 0

    fun startCapture(activity: Activity): Boolean {
        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = projectionManager.createScreenCaptureIntent()
        activity.startActivityForResult(intent, 1001)
        return true
    }

    fun initialize(projection: MediaProjection): Boolean {
        release()

        val windowManager = context.getSystemService(WindowManager::class.java)
        val display = windowManager.defaultDisplay
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)

        displayWidth = metrics.widthPixels
        displayHeight = metrics.heightPixels
        displayDensity = metrics.densityDpi

        handlerThread = HandlerThread("ScreenshotThread").apply { start() }
        handler = Handler(handlerThread!!.looper)

        imageReader = ImageReader.newInstance(
            displayWidth,
            displayHeight,
            PixelFormat.RGBA_8888,
            2
        )

        mediaProjection = projection
        isCapturing = true

        return true
    }

    suspend fun capture(): Bitmap? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!isCapturing) return@withContext null

        val reader = imageReader ?: return@withContext null
        val surface = reader.surface ?: return@withContext null
        val projection = mediaProjection ?: return@withContext null

        // A VirtualDisplay renders asynchronously and holds the ImageReader's
        // surface alive until released. Creating one per capture and releasing it
        // in the finally below keeps repeated captures from leaking displays
        // (each unreleased display would otherwise pin memory until the process dies).
        var display: android.hardware.display.VirtualDisplay? = null
        var image: android.media.Image? = null
        try {
            display = projection.createVirtualDisplay(
                "Screenshot",
                displayWidth,
                displayHeight,
                displayDensity,
                0,
                surface,
                null,
                handler
            )

            // The first frame is NOT available the instant createVirtualDisplay
            // returns; polling briefly avoids a chronic null capture that the old
            // single acquireLatestImage() call produced on fast returns.
            val deadline = System.currentTimeMillis() + CAPTURE_WAIT_MS
            while (image == null && System.currentTimeMillis() < deadline) {
                image = reader.acquireLatestImage()
                if (image == null) kotlinx.coroutines.delay(FRAME_POLL_MS)
            }

            val img = image ?: return@withContext null
            val planes = img.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val width = img.width
            val height = img.height
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            // Dimensions were captured above on purpose: the Image must be closed
            // before we return, and reading fields after close is undefined.
            image?.close()
            image = null

            if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, width, height)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        } finally {
            image?.close()
            display?.release()
        }
    }

    fun release() {
        isCapturing = false
        mediaProjection?.stop()
        mediaProjection = null
        imageReader?.close()
        imageReader = null
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }

    companion object {
        /** How long to wait for the virtual display to produce its first frame. */
        private const val CAPTURE_WAIT_MS = 1500L
        /** Poll interval while waiting for that first frame. */
        private const val FRAME_POLL_MS = 50L

        @Volatile
        private var instance: ScreenshotCapture? = null

        fun getInstance(context: Context): ScreenshotCapture {
            return instance ?: synchronized(this) {
                instance ?: ScreenshotCapture(context.applicationContext).also { instance = it }
            }
        }
    }
}