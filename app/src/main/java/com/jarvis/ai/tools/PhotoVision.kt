package com.jarvis.ai.tools

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

/**
 * Pulls the most recent photo off the device and prepares it for the vision
 * pipeline ([com.jarvis.ai.orchestrator.VisionAnalyzer]).
 *
 * Why this exists: the vision BACKEND was already complete (provider routing,
 * fallback, Gemini + OpenAI), but nothing ever fed it an image. This is the
 * missing ingest half, and it deliberately avoids a picker UI so "what is in
 * my last photo" works as a pure voice command.
 *
 * Design notes:
 * - The bitmap is downscaled before encoding. A modern phone photo is 8-12 MB;
 *   base64 inflates it by ~33% and most vision endpoints reject payloads that
 *   large, so sending the original would fail on exactly the devices the
 *   feature targets.
 * - Nothing throws. Missing permission, empty gallery and unreadable files all
 *   return a typed [Outcome] the chat layer can render verbatim.
 */
class PhotoVision(context: Context) {

    private val app: Context = context.applicationContext

    sealed class Outcome {
        data class Ready(val base64: String, val mimeType: String) : Outcome()
        data class Unavailable(val reason: String) : Outcome()
    }

    /** The runtime permission needed to read the gallery on this API level. */
    fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, requiredPermission()) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Loads, downscales and base64-encodes the newest image in the gallery. */
    fun latestPhoto(): Outcome {
        if (!hasPermission()) {
            return Outcome.Unavailable(
                "I need photo access first, sir - grant Photos permission and ask again."
            )
        }
        val uri = newestImageUri()
            ?: return Outcome.Unavailable("I could not find any photos on this device, sir.")
        return encode(uri)
    }

    /** Loads, downscales and base64-encodes a specific image [uri]. */
    fun encode(uri: Uri): Outcome = runCatching {
        val bitmap = decodeScaled(uri)
            ?: return@runCatching Outcome.Unavailable("That image could not be read, sir.")
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        bitmap.recycle()
        val encoded = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        Outcome.Ready(encoded, "image/jpeg") as Outcome
    }.getOrElse { Outcome.Unavailable("That image could not be read, sir.") }

    /** Newest entry in MediaStore images, or null when the gallery is empty. */
    private fun newestImageUri(): Uri? = runCatching {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        app.contentResolver.query(collection, projection, null, null, sort)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
            ContentUris.withAppendedId(collection, id)
        }
    }.getOrNull()

    /**
     * Two-pass decode: measure first with inJustDecodeBounds so a 108 MP photo
     * is never fully materialised in memory, then decode at a sample size that
     * fits [MAX_EDGE].
     */
    private fun decodeScaled(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null

        var sample = 1
        while (longest / sample > MAX_EDGE) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return app.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    companion object {
        /** Longest edge after downscaling. Enough detail for description work. */
        private const val MAX_EDGE = 1280
        private const val JPEG_QUALITY = 85
    }
}
