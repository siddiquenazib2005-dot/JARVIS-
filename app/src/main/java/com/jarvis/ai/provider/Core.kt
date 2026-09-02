package com.jarvis.ai.provider

import java.util.UUID

/** Every routable capability in the JARVIS operating core. */
enum class Capability {
    CHAT, REASONING, CODING, VISION, SEARCH, NEWS, WEATHER, MAPS,
    FINANCE, SPACE, STT, TTS, EMBEDDING, RAG, CALCULATION,
    LOCAL_DEVICE, SYSTEM_ACTION
}

/** Distinct lifecycle states tracked per provider. */
enum class HealthState {
    HEALTHY, DEGRADED, RATE_LIMITED, AUTH_FAILED,
    NETWORK_FAILED, TIMEOUT, COOLDOWN, DISABLED
}

/** Failures are categorised before any retry/cooldown decision is made. */
enum class FailureCategory { AUTH, RATE_LIMIT, TIMEOUT, SERVER, NETWORK, MALFORMED_RESPONSE, UNKNOWN }

object FailureClassifier {

    /** Classifies a throwable message (and optional http status) into a category. */
    fun classify(message: String?, httpStatus: Int? = null): FailureCategory {
        httpStatus?.let { return fromStatus(it) }
        val m = message?.lowercase().orEmpty()
        if (m.isEmpty()) return FailureCategory.UNKNOWN
        return when {
            m.contains("http 401") || m.contains("http 403") || m.contains("unauthorized") ||
                m.contains("forbidden") || m.contains("invalid api key") || m.contains("authentication") ->
                FailureCategory.AUTH
            m.contains("http 429") || m.contains("rate limit") || m.contains("quota") ->
                FailureCategory.RATE_LIMIT
            m.contains("timeout") || m.contains("timed out") -> FailureCategory.TIMEOUT
            m.contains("http 5") && m.contains("server") ||
                Regex("http 5\\d\\d").containsMatchIn(m) -> FailureCategory.SERVER
            m.contains("unable to resolve") || m.contains("econnrefused") ||
                m.contains("failed to connect") || m.contains("network") ->
                FailureCategory.NETWORK
            m.contains("no content") || m.contains("malformed") || m.contains("empty") ->
                FailureCategory.MALFORMED_RESPONSE
            else -> FailureCategory.UNKNOWN
        }
    }

    private fun fromStatus(code: Int): FailureCategory = when (code) {
        401, 403 -> FailureCategory.AUTH
        429 -> FailureCategory.RATE_LIMIT
        in 500..599 -> FailureCategory.SERVER
        408 -> FailureCategory.TIMEOUT
        else -> FailureCategory.UNKNOWN
    }
}

/** Immutable request identity carried through the whole routing pipeline. */
data class RequestMetadata(
    val requestId: String = UUID.randomUUID().toString(),
    val capability: Capability,
    val priority: Int = 5,
    val provider: String = "",
    val model: String = "",
    val latencyMs: Long = 0L,
    val retryCount: Int = 0,
    val fallbackCount: Int = 0
)

/** Final structured outcome of a routed request. */
data class ExecutionReport(
    val success: Boolean,
    val metadata: RequestMetadata,
    val attempts: List<String> = emptyList(),
    val error: String? = null
)

/** Thin transport seam so every adapter can be unit-tested with fakes. */
fun interface HttpTransport {
    fun execute(request: okhttp3.Request): okhttp3.Response
}

object HttpTransports {
    private val shared by lazy {
        OkHttpClientHolder.client
    }

    val default: HttpTransport = HttpTransport { req -> shared.newCall(req).execute() }

    private object OkHttpClientHolder {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Secret resolution seam. Production binds [SecureSecretsSource] (SecureStore),
 * tests bind in-memory maps. Values are never logged.
 */
interface SecretsSource {
    fun get(reference: String): String?
}

class MapSecretsSource(private val backing: MutableMap<String, String> = mutableMapOf()) : SecretsSource {
    override fun get(reference: String): String? = backing[reference]
    fun put(reference: String, value: String) { backing[reference] = value }
}

/** Shared JSON configuration for every adapter in this package. */
object Jsons {
    val lenient: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
}

/**
 * Removes any secret-looking material before a string may reach logs,
 * audit entries, or error surfaces.
 */
object SecretRedactor {
    private val patterns = listOf(
        Regex("sk-or-v1-[A-Za-z0-9]+"),
        Regex("gsk_[A-Za-z0-9]+"),
        Regex("csk-[A-Za-z0-9]+"),
        Regex("tvly-[A-Za-z0-9\\-]+"),
        Regex("pcsk_[A-Za-z0-9_\\-]+"),
        // JWTs (Qdrant cluster keys etc.) — three base64url segments.
        Regex("eyJ[A-Za-z0-9_\\-]+\\.eyJ[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]+"),
        Regex("Bearer\\s+[A-Za-z0-9._\\-]+"),
        Regex("Token\\s+[A-Za-z0-9._\\-]+"),
        Regex("(?i)(api[_-]?key|token|secret)\\s*[=:]\\s*\\S+")
    )

    private const val MASK = "***REDACTED***"

    fun redact(input: String?): String =
        if (input.isNullOrBlank()) "" else patterns.fold(input) { acc, regex ->
            regex.replace(acc, MASK)
        }
}

/** Redacted telemetry event — safe by construction. */
data class ObsEvent(
    val requestId: String,
    val capability: Capability,
    val provider: String,
    val model: String,
    val latencyMs: Long,
    val status: String,
    val retryCount: Int,
    val fallbackCount: Int,
    val errorCategory: String?,
    val timestamp: Long = System.currentTimeMillis()
)

/** In-memory ring buffer of recent events (never contains secrets). */
object ObsLog {
    private const val CAPACITY = 250
    private val buffer = ArrayDeque<ObsEvent>(CAPACITY)

    @Synchronized
    fun record(event: ObsEvent) {
        if (buffer.size >= CAPACITY) buffer.removeFirst()
        buffer.addLast(event)
    }

    @Synchronized
    fun recent(count: Int): List<ObsEvent> = buffer.toList().takeLast(count)

    @Synchronized
    fun clear() = buffer.clear()
}
