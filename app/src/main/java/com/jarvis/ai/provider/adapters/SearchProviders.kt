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

data class WebResult(
    val title: String,
    val url: String,
    val snippet: String
)

/** Common contract for every search backend behind the router. */
interface SearchProvider {
    val providerId: String
    suspend fun search(query: String, maxResults: Int): List<WebResult>
}

internal fun searchRequestFailure(providerId: String, code: Int, body: String?): Nothing =
    throw IOException("$providerId HTTP $code: ${body?.take(200)}")

internal fun searchRequest(
    url: String,
    method: String = "POST",
    headers: Map<String, String>,
    jsonBody: String? = null
): Request {
    val builder = Request.Builder().url(url)
    headers.forEach { (k, v) -> builder.header(k, v) }
    when (method.uppercase()) {
        "GET" -> builder.get()
        else -> builder.post(
            (jsonBody ?: "{}").toRequestBody("application/json".toMediaType())
        )
    }
    return builder.build()
}

// ---------------------------------------------------------------------------
// Serper — POST https://google.serper.dev/search   header: X-API-KEY
// ---------------------------------------------------------------------------

@Serializable
internal data class SerperOrganic(
    val title: String? = null,
    val link: String? = null,
    val snippet: String? = null
)

@Serializable
internal data class SerperResponse(val organic: List<SerperOrganic> = emptyList())

class SerperSearch(
    private val secrets: SecretsSource,
    private val transport: HttpTransport = HttpTransports.default
) : SearchProvider {

    override val providerId = "serper"

    override suspend fun search(query: String, maxResults: Int): List<WebResult> {
        val key = secrets.get("SERPER_API_KEY#1")
            ?: throw IOException("serper: key missing")
        val body = Jsons.lenient.encodeToString(
            SerperQuery.serializer(),
            SerperQuery(q = query, num = maxResults)
        )
        val request = searchRequest(
            url = "https://google.serper.dev/search",
            headers = mapOf("X-API-KEY" to key),
            jsonBody = body
        )
        transport.execute(request).use { response ->
            if (!response.isSuccessful) {
                searchRequestFailure(providerId, response.code, response.body?.string())
            }
            val decoded = Jsons.lenient.decodeFromString(
                SerperResponse.serializer(),
                response.body?.string().orEmpty()
            )
            return decoded.organic.mapNotNull {
                val t = it.title ?: return@mapNotNull null
                WebResult(t, it.link.orEmpty(), it.snippet.orEmpty())
            }
        }
    }
}

@Serializable
internal data class SerperQuery(val q: String, val num: Int)

// ---------------------------------------------------------------------------
// Tavily — POST https://api.tavily.com/search   header: Authorization Bearer
// ---------------------------------------------------------------------------

@Serializable
internal data class TavilyQuery(
    val query: String,
    val max_results: Int,
    val search_depth: String = "basic"
)

@Serializable
internal data class TavilyResultItem(
    val title: String? = null,
    val url: String? = null,
    val content: String? = null
)

@Serializable
internal data class TavilySearchResponse(val results: List<TavilyResultItem> = emptyList())

class TavilySearch(
    private val secrets: SecretsSource,
    private val transport: HttpTransport = HttpTransports.default
) : SearchProvider {

    override val providerId = "tavily"

    override suspend fun search(query: String, maxResults: Int): List<WebResult> {
        val key = secrets.get("TAVILY_API_KEY#1")
            ?: throw IOException("tavily: key missing")
        val body = Jsons.lenient.encodeToString(
            TavilyQuery.serializer(),
            TavilyQuery(query = query, max_results = maxResults)
        )
        val request = searchRequest(
            url = "https://api.tavily.com/search",
            headers = mapOf("Authorization" to "Bearer $key"),
            jsonBody = body
        )
        transport.execute(request).use { response ->
            if (!response.isSuccessful) {
                searchRequestFailure(providerId, response.code, response.body?.string())
            }
            val decoded = Jsons.lenient.decodeFromString(
                TavilySearchResponse.serializer(),
                response.body?.string().orEmpty()
            )
            return decoded.results.mapNotNull {
                val t = it.title ?: return@mapNotNull null
                WebResult(t, it.url.orEmpty(), it.content.orEmpty())
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Exa — POST https://api.exa.ai/search   header: x-api-key
// ---------------------------------------------------------------------------

@Serializable
internal data class ExaQuery(
    val query: String,
    val numResults: Int,
    val contents: ExaContents = ExaContents()
)

@Serializable
internal data class ExaContents(val text: Boolean = true)

@Serializable
internal data class ExaResultItem(
    val title: String? = null,
    val url: String? = null,
    val text: String? = null
)

@Serializable
internal data class ExaSearchResponse(val results: List<ExaResultItem> = emptyList())

class ExaSearch(
    private val secrets: SecretsSource,
    private val transport: HttpTransport = HttpTransports.default
) : SearchProvider {

    override val providerId = "exa"

    override suspend fun search(query: String, maxResults: Int): List<WebResult> {
        val key = secrets.get("EXA_API_KEY#1")
            ?: throw IOException("exa: key missing")
        val body = Jsons.lenient.encodeToString(
            ExaQuery.serializer(),
            ExaQuery(query = query, numResults = maxResults)
        )
        val request = searchRequest(
            url = "https://api.exa.ai/search",
            headers = mapOf("x-api-key" to key),
            jsonBody = body
        )
        transport.execute(request).use { response ->
            if (!response.isSuccessful) {
                searchRequestFailure(providerId, response.code, response.body?.string())
            }
            val decoded = Jsons.lenient.decodeFromString(
                ExaSearchResponse.serializer(),
                response.body?.string().orEmpty()
            )
            return decoded.results.mapNotNull {
                val t = it.title ?: return@mapNotNull null
                WebResult(t, it.url.orEmpty(), it.text.orEmpty())
            }
        }
    }
}
