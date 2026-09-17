package com.jarvis.ai.tools

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import com.jarvis.ai.data.local.SecureStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Item 9 v1: text to image.
 *
 * Design notes:
 *  - Two providers are tried in order (Gemini Imagen, then OpenAI). The first
 *    one that actually has a key wins. This mirrors the text routing rule that
 *    a missing key must never look like a broken feature.
 *  - The result is written to the device gallery so the owner can use it like
 *    any other photo. No FileProvider is needed for that path.
 *  - This class returns plain sentences, never throws for expected problems,
 *    so the command router can print the reply as-is.
 *
 * Deliberately NOT in v1: image editing, size/aspect options, multiple
 * samples, and streaming progress. Those need UI work, and this release is
 * about proving the pipeline end to end.
 */
class ImageGenerator(context: Context) {

    private val app = context.applicationContext
    private val secure = SecureStore(app)

    /** Generates one image and returns a human sentence describing the result. */
    fun generate(rawPrompt: String): String {
        val prompt = rawPrompt.trim()
        if (prompt.length < 3) {
            return "Tell me what to draw, sir - for example: generate image of a red sports car."
        }

        val gemini = key("GEMINI_API_KEY")
        val openai = key("OPENAI_API_KEY")
        if (gemini == null && openai == null) {
            return "Image generation needs an image-capable key, sir. " +
                "Add GEMINI_API_KEY or OPENAI_API_KEY in Settings, then try again."
        }

        val attempts = mutableListOf<String>()

        if (gemini != null) {
            val result = runCatching { viaGemini(prompt, gemini) }
                .getOrElse { Outcome.Failed(it.message ?: "Gemini request failed") }
            when (result) {
                is Outcome.Image -> return save(result.bytes, prompt)
                is Outcome.Failed -> attempts.add("Gemini: " + result.reason)
            }
        }

        if (openai != null) {
            val result = runCatching { viaOpenAi(prompt, openai) }
                .getOrElse { Outcome.Failed(it.message ?: "OpenAI request failed") }
            when (result) {
                is Outcome.Image -> return save(result.bytes, prompt)
                is Outcome.Failed -> attempts.add("OpenAI: " + result.reason)
            }
        }

        return "I could not generate that image, sir. " + attempts.joinToString("; ")
    }

    /** True when at least one image-capable key is present. */
    fun isConfigured(): Boolean =
        key("GEMINI_API_KEY") != null || key("OPENAI_API_KEY") != null

    // ------------------------------------------------------------------
    // Providers
    // ------------------------------------------------------------------

    private sealed interface Outcome {
        data class Image(val bytes: ByteArray) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    private fun viaGemini(prompt: String, apiKey: String): Outcome {
        val body = JSONObject()
            .put("instances", JSONArray().put(JSONObject().put("prompt", prompt)))
            .put("parameters", JSONObject().put("sampleCount", 1))
            .toString()

        val request = Request.Builder()
            .url(GEMINI_URL + "?key=" + apiKey)
            .post(body.toRequestBody(JSON))
            .build()

        HTTP.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) return Outcome.Failed(reasonFrom(text, response.code))
            val encoded = JSONObject(text)
                .optJSONArray("predictions")
                ?.optJSONObject(0)
                ?.optString("bytesBase64Encoded")
                .orEmpty()
            if (encoded.isBlank()) return Outcome.Failed("no image in response")
            return Outcome.Image(Base64.decode(encoded, Base64.DEFAULT))
        }
    }

    private fun viaOpenAi(prompt: String, apiKey: String): Outcome {
        val body = JSONObject()
            .put("model", "gpt-image-1")
            .put("prompt", prompt)
            .put("n", 1)
            .put("size", "1024x1024")
            .toString()

        val request = Request.Builder()
            .url(OPENAI_URL)
            .addHeader("Authorization", "Bearer " + apiKey)
            .post(body.toRequestBody(JSON))
            .build()

        HTTP.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) return Outcome.Failed(reasonFrom(text, response.code))
            val encoded = JSONObject(text)
                .optJSONArray("data")
                ?.optJSONObject(0)
                ?.optString("b64_json")
                .orEmpty()
            if (encoded.isBlank()) return Outcome.Failed("no image in response")
            return Outcome.Image(Base64.decode(encoded, Base64.DEFAULT))
        }
    }

    /** Pulls the provider message out of an error body so failures are useful. */
    private fun reasonFrom(body: String, code: Int): String {
        val message = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")
        return if (message.isBlank()) "HTTP " + code else message.take(160)
    }

    // ------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------

    /**
     * Writes the image to the gallery on API 29+, where no storage permission
     * is required. Older devices would need WRITE_EXTERNAL_STORAGE, so they
     * fall back to app-private storage instead of asking for a broad
     * permission just for this one feature.
     */
    private fun save(bytes: ByteArray, prompt: String): String {
        if (bytes.size < 512) return "The provider returned an empty image, sir."
        val name = "AURIX_" + System.currentTimeMillis() + ".png"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AURIX")
            }
            val uri = runCatching {
                app.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                )
            }.getOrNull()

            if (uri != null) {
                val written = runCatching {
                    app.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    true
                }.getOrDefault(false)
                if (written) {
                    return "Done, sir. Saved to Pictures/AURIX as " + name +
                        " - \"" + prompt.take(60) + "\". Open the gallery to see it."
                }
            }
        }

        val dir = File(app.filesDir, "images").apply { mkdirs() }
        val file = File(dir, name)
        return runCatching {
            file.writeBytes(bytes)
            "Done, sir. Saved inside AURIX storage as " + name +
                " (this Android version does not allow gallery writes without extra permission)."
        }.getOrElse { "I generated the image but could not save it, sir: " + it.message }
    }

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    /** Checks the rotation slot first, then the plain key name. */
    private fun key(envName: String): String? {
        val slot = secure.get(envName + "#1")?.takeIf { it.isNotBlank() }
        if (slot != null) return slot
        return secure.get(envName)?.takeIf { it.isNotBlank() }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        val HTTP = okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        const val GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/imagen-3.0-generate-002:predict"

        const val OPENAI_URL = "https://api.openai.com/v1/images/generations"
    }
}
