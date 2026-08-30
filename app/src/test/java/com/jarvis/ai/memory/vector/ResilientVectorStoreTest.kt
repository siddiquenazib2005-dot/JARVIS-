package com.jarvis.ai.memory.vector

import com.jarvis.ai.provider.MapSecretsSource
import com.jarvis.ai.provider.HttpTransport
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Verifies the REAL failover chain: Pinecone → Qdrant → InMemory.
 * Both remote stores are driven through a transport that always fails,
 * so persistence must land in the local store and later calls must skip
 * the cooled-down rements entirely.
 */
class ResilientVectorStoreTest {

    private class FailingTransport : HttpTransport {
        var calls = 0
        override fun execute(request: Request): Response {
            calls += 1
            throw IOException("simulated outage")
        }
    }

    private fun manager(transport: HttpTransport): VectorMemoryManager {
        val secrets = MapSecretsSource().apply {
            put("PINECONE_API_KEY#1", "pc-key")
            put("PINECONE_INDEX_HOST#1", "https://idx.test")
            put("QDRANT_URL#1", "https://qdrant.test")
            put("QDRANT_API_KEY#1", "qd-key")
        }
        return VectorMemoryManager(
            embedder = HashEmbeddingProvider(64),
            secrets = secrets,
            config = VectorMemoryConfig(similarityThreshold = 0.05f, storeCooldownMs = 60_000L),
            transport = transport
        )
    }

    @Test
    fun `remote outages fall back to local store`() = runBlocking {
        val transport = FailingTransport()
        val mgr = manager(transport)

        val write = mgr.remember(
            "JARVIS resilience test writes survive remote outages",
            type = MemoryType.FACT,
            source = "unit-test"
        )
        assertEquals(WriteStatus.STORED, write.status)
        assertEquals("inmemory", mgr.activeStoreId)

        // Data must be retrievable from the surviving local store.
        val hits = mgr.recall("resilience outage survival")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.first().record.content.contains("outages"))
    }

    @Test
    fun `cooled-down stores are skipped on subsequent calls`() = runBlocking {
        val transport = FailingTransport()
        val mgr = manager(transport)

        mgr.remember("first write during full outage", type = MemoryType.FACT)
        val callsAfterFirst = transport.calls

        mgr.remember("second write while remotes are cooling down", type = MemoryType.FACT)
        // Remote stores are cooling: only the local store is touched → no new HTTP attempts.
        assertEquals(callsAfterFirst, transport.calls)
    }
}
