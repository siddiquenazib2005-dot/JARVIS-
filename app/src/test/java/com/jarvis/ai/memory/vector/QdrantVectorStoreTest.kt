package com.jarvis.ai.memory.vector

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.jarvis.ai.provider.HttpTransport
import com.jarvis.ai.provider.MapSecretsSource
import java.io.IOException

private val JSON_MT = "application/json".toMediaType()

fun okResponse(request: Request, body: String = "{}"): Response = Response.Builder()
    .request(request)
    .protocol(Protocol.HTTP_1_1)
    .code(200)
    .message("OK")
    .body(body.toResponseBody(JSON_MT))
    .build()

fun errorResponse(request: Request, code: Int, body: String = "{}"): Response = Response.Builder()
    .request(request)
    .protocol(Protocol.HTTP_1_1)
    .code(code)
    .message("E")
    .body(body.toResponseBody(JSON_MT))
    .build()

fun requestBodyText(body: okhttp3.RequestBody?): String {
    if (body == null) return ""
    val buffer = okio.Buffer()
    body.writeTo(buffer)
    return buffer.readUtf8()
}

/** Records every request and serves scripted responses. */
class FakeTransport(
    var handler: (Request, Int) -> Response = { req, _ -> okResponse(req) }
) : HttpTransport {
    val requests = mutableListOf<Request>()
    var calls = 0

    override fun execute(request: Request): Response {
        calls += 1
        requests += request
        return handler(request, calls)
    }
}

class QdrantVectorStoreTest {

    private fun secrets(url: String? = "https://qdrant.test") = MapSecretsSource().apply {
        if (url != null) put("QDRANT_URL#1", url)
        put("QDRANT_API_KEY#1", "test-key")
    }

    @Test
    fun `get uses points-get endpoint with ids body`() = runBlocking {
        val transport = FakeTransport { req, _ ->
            if (req.url.encodedPath.endsWith("/points/get")) {
                okResponse(req, """{"result":[{"id":"m1","payload":{"content":"hello world","memoryType":"FACT"}}]}""")
            } else {
                okResponse(req, """{"config":{"params":{"vectors":{"size":8,"distance":"Cosine"}}}}""")
            }
        }
        val store = QdrantVectorStore(secrets(), transport)
        val record = store.get("jarvis_global", "m1")

        assertEquals("hello world", record?.content)
        assertEquals(MemoryType.FACT, record?.metadata?.memoryType)
        assertTrue(transport.requests.last().url.encodedPath.endsWith("/points/get"))
        val text = requestBodyText(transport.requests.last().body)
        assertTrue(text.contains("\"ids\""))
        assertTrue(text.contains("m1"))
    }

    @Test
    fun `type filter uses match-any semantics`() {
        val store = QdrantVectorStore(secrets())
        val filter = store.qdrantFilter(
            MemoryFilter(types = setOf(MemoryType.FACT, MemoryType.PREFERENCE))
        )!!
        assertEquals(1, filter.must.size)
        assertEquals("memoryType", filter.must.first().key)
        assertEquals(
            listOf("FACT", "PREFERENCE"),
            filter.must.first().match.any!!.map { it.toString().trim('"') }
        )
    }

    @Test
    fun `transient io failures are retried then succeed`() = runBlocking {
        val transport = FakeTransport { req, call ->
            if (call <= 2) throw IOException("flaky network")
            okResponse(req)
        }
        val store = QdrantVectorStore(secrets(), transport)
        store.delete("ns", "some-id")
        assertEquals(3, transport.calls)
    }

    @Test
    fun `http errors map to structured exceptions`() = runBlocking {
        val transport = FakeTransport { req, _ -> errorResponse(req, 429) }
        val store = QdrantVectorStore(secrets(), transport)
        try {
            store.delete("ns", "id")
            throw AssertionError("expected failure")
        } catch (e: VectorStoreException) {
            assertEquals(com.jarvis.ai.provider.FailureCategory.RATE_LIMIT, e.category)
            assertEquals(429, e.httpCode)
        }
    }

    @Test
    fun `dimension mismatch against existing collection is rejected`() = runBlocking {
        val transport = FakeTransport { req, _ ->
            okResponse(req, """{"config":{"params":{"vectors":{"size":1024,"distance":"Cosine"}}}}""")
        }
        val store = QdrantVectorStore(secrets(), transport)
        try {
            store.upsert(
                "jarvis_global",
                VectorRecord(
                    memoryId = "x", content = "c", embedding = List(64) { 0.1f },
                    metadata = MemoryMetadata(memoryType = MemoryType.FACT)
                )
            )
            throw AssertionError("expected dimension mismatch")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("1024"))
        }
    }
}
