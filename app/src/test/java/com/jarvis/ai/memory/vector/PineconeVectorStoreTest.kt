package com.jarvis.ai.memory.vector

import com.jarvis.ai.provider.MapSecretsSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PineconeVectorStoreTest {

    private fun secrets(host: String?) = MapSecretsSource().apply {
        put("PINECONE_API_KEY#1", "pc-test")
        host?.let { put("PINECONE_INDEX_HOST#1", it) }
    }

    @Test
    fun `host resolves via control plane when secret absent`() = runBlocking {
        val transport = FakeTransport { req, _ ->
            when {
                req.url.host == "cp.test" -> okResponse(
                    req,
                    """{"indexes":[{"name":"jarvis-memory","host":"idx.svc.pinecone.io","dimension":1024}]}"""
                )
                else -> okResponse(req, """{"upsertedCount":1}""")
            }
        }
        val store = PineconeVectorStore(
            secrets(host = null),
            transport,
            controlPlaneUrl = "https://cp.test"
        )
        store.upsert(
            "jarvis_global",
            VectorRecord(
                memoryId = "m1", content = "hello", embedding = List(8) { 0.1f },
                metadata = MemoryMetadata(memoryType = MemoryType.FACT)
            )
        )
        val upsert = transport.requests.last()
        assertEquals("idx.svc.pinecone.io", upsert.url.host)
        assertEquals("pc-test", upsert.header("Api-Key"))
        val body = requestBodyText(upsert.body)
        assertTrue(body.contains("\"namespace\":\"jarvis_global\""))
        assertTrue(body.contains("\"id\":\"m1\""))
    }

    @Test
    fun `upsert validates embedding dimension against index`() = runBlocking {
        val transport = FakeTransport { req, _ ->
            when (req.url.host) {
                "cp.test" -> okResponse(req, """{"name":"jarvis-memory","dimension":1024,"host":"h"}""")
                else -> okResponse(req, """{"upsertedCount":1}""")
            }
        }
        val store = PineconeVectorStore(secrets(host = "https://idx.test"), transport, controlPlaneUrl = "https://cp.test")
        try {
            store.upsert(
                "ns",
                VectorRecord(
                    memoryId = "m", content = "c", embedding = List(64) { 0.2f },
                    metadata = MemoryMetadata(memoryType = MemoryType.SEMANTIC)
                )
            )
            throw AssertionError("expected dimension mismatch")
        } catch (e: VectorStoreException) {
            assertTrue(e.message!!.contains("dimension"))
        }
    }

    @Test
    fun `search filter uses dollar-in for type lists`() = runBlocking {
        val transport = FakeTransport { req, _ ->
            if (req.url.encodedPath.endsWith("/query")) {
                okResponse(
                    req,
                    """{"matches":[{"id":"a","score":0.91,"metadata":{"content":"alpha","memoryType":"FACT"}}]}"""
                )
            } else okResponse(req)
        }
        val store = PineconeVectorStore(secrets(host = "https://idx.test"), transport)
        val hits = store.search(
            namespace = "jarvis_global",
            queryEmbedding = List(8) { 0.5f },
            topK = 3,
            filter = MemoryFilter(types = setOf(MemoryType.FACT, MemoryType.PREFERENCE))
        )

        val queryBody = requestBodyText(transport.requests.last().body)
        assertTrue(queryBody.contains("\"memoryType\":{\"\$in\":[\"FACT\",\"PREFERENCE\"]}"))
        assertEquals(1, hits.size)
        assertEquals("alpha", hits.first().record.content)
        assertEquals(0.91f, hits.first().score, 1e-6f)
    }

    @Test
    fun `fetch returns mapped record`() = runBlocking {
        val transport = FakeTransport { req, _ ->
            assertTrue(req.url.encodedPath.endsWith("/vectors/fetch"))
            okResponse(
                req,
                """{"vectors":{"m9":{"id":"m9","values":[0.1],"metadata":{"content":"stored text","memoryType":"PREFERENCE","importance":0.8}}}}"""
            )
        }
        val store = PineconeVectorStore(secrets(host = "https://idx.test"), transport)
        val record = store.get("jarvis_global", "m9")
        assertEquals("stored text", record?.content)
        assertEquals(MemoryType.PREFERENCE, record?.metadata?.memoryType)
        assertNull(store.get("jarvis_global", "missing"))
    }

    @Test
    fun `empty filter delete becomes deleteAll without filter field`() = runBlocking {
        var lastBody = ""
        val transport = FakeTransport { req, _ ->
            if (req.url.encodedPath.endsWith("/vectors/delete")) {
                lastBody = requestBodyText(req.body)
                okResponse(req)
            } else okResponse(req)
        }
        val store = PineconeVectorStore(secrets(host = "https://idx.test"), transport)
        store.deleteByFilter("jarvis_global", MemoryFilter())

        assertTrue(lastBody.contains("\"deleteAll\":true"))
        assertTrue(!lastBody.contains("\"filter\""))
    }

    @Test
    fun `auth failures surface as structured exceptions`() = runBlocking {
        val transport = FakeTransport { req, _ -> errorResponse(req, 401) }
        val store = PineconeVectorStore(secrets(host = "https://idx.test"), transport)
        try {
            store.delete("ns", "x")
            throw AssertionError("expected auth failure")
        } catch (e: VectorStoreException) {
            assertEquals(com.jarvis.ai.provider.FailureCategory.AUTH, e.category)
        }
    }
}
