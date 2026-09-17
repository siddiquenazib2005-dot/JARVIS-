package com.jarvis.ai.provider.adapters

import com.jarvis.ai.provider.HttpTransport
import com.jarvis.ai.provider.HttpTransports
import com.jarvis.ai.provider.Jsons
import com.jarvis.ai.provider.SecretsSource
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

// ===========================================================================
// STT — Deepgram prerecorded transcription
// ===========================================================================

interface SttProvider {
    val providerId: String
    suspend fun transcribe(audio: ByteArray, contentType: String, language: String? = null): String
}

@Serializable
internal data class DgAlternative(val transcript: String? = null)

@Serializable
internal data class DgChannel(val alternatives: List<DgAlternative> = emptyList())

@Serializable
internal data class DgResults(val channels: List<DgChannel> = emptyList())

class DeepgramStt(
    private val secrets: SecretsSource,
    private val transport: HttpTransport = HttpTransports.default
) : SttProvider {

    override val providerId = "deepgram"

    override suspend fun transcribe(
        audio: ByteArray,
        contentType: String,
        language: String?
    ): String {
        val key = secrets.get("DEEPGRAM_API_KEY#1") ?: throw IOException("deepgram: key missing")
        val langParam = language?.let { "&language=$it" }.orEmpty()
        val url = "https://api.deepgram.com/v1/listen?model=nova-2&smart_format=true$langParam"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Token $key")
            .header("Content-Type", contentType)
            .post(audio.toRequestBody(contentType.toMediaType()))
            .build()
        transport.execute(request).use { response ->
            if (!response.isSuccessful) {
                throw IOException("deepgram HTTP ${response.code}: ${response.body?.string()?.take(200)}")
            }
            val decoded = Jsons.lenient.decodeFromString(
                DgResults.serializer(),
                response.body?.string().orEmpty()
            )
            return decoded.channels.firstOrNull()?.alternatives?.firstOrNull()?.transcript
                ?: throw IOException("deepgram: empty transcript")
        }
    }
}

// ===========================================================================
// TTS — ElevenLabs synthesis (registered disabled until a key is provided)
// ===========================================================================

interface TtsSynthesizer {
    val providerId: String
    suspend fun synthesize(text: String, voiceId: String): ByteArray
}

class ElevenLabsTts(
    private val secrets: SecretsSource,
    private val defaultVoiceId: String = "21m00Tcm4TlvDq8ikWAM",
    private val transport: HttpTransport = HttpTransports.default
) : TtsSynthesizer {

    override val providerId = "elevenlabs"

    override suspend fun synthesize(text: String, voiceId: String): ByteArray {
        val key = secrets.get("ELEVENLABS_API_KEY#1")
            ?: throw IOException("elevenlabs: key missing")
        val body = Jsons.lenient.encodeToString(
            ElQuery.serializer(),
            ElQuery(text = text, model_id = "eleven_multilingual_v2")
        )
        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/${voiceId.ifBlank { defaultVoiceId }}")
            .header("xi-api-key", key)
            .header("Accept", "audio/mpeg")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        transport.execute(request).use { response ->
            if (!response.isSuccessful) {
                throw IOException("elevenlabs HTTP ${response.code}")
            }
            return response.body?.bytes() ?: throw IOException("elevenlabs: empty audio")
        }
    }
}

@Serializable
internal data class ElQuery(val text: String, val model_id: String)
