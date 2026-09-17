package com.jarvis.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyPoolManagerTest {

    private fun poolWith(vararg refs: Pair<String, String>): Pair<KeyPoolManager, MapSecretsSource> {
        val secrets = MapSecretsSource()
        val pool = KeyPoolManager(secrets)
        refs.forEachIndexed { index, (env, value) ->
            secrets.put("${env}#1", value) // slot #1 secret
            secrets.put("${env}#${index + 1}", value)
            pool.addSlot("prov", env, slotIndex = index + 1)
        }
        return pool to secrets
    }

    @Test
    fun `pick resolves secret for healthy slot`() {
        val (pool, _) = poolWith("TEST_API_KEY" to "secret-1")
        val picked = pool.pick("prov", nowMs = 1000L)
        assertNotNull(picked)
        assertEquals("secret-1", picked!!.second)
    }

    @Test
    fun `failed slot rotates to next healthy key`() {
        val (pool, _) = poolWith(
            "TEST_API_KEY" to "key-a",
            "TEST_API_KEY" to "key-b"
        )
        val first = pool.pick("prov", nowMs = 0L)!!.first
        pool.markFailure(first, FailureCategory.RATE_LIMIT, nowMs = 0L)

        val second = pool.pick("prov", nowMs = 1000L)
        assertNotNull(second)
        assertEquals(2, second!!.first.slotIndex)
    }

    @Test
    fun `all slots cooling returns null`() {
        val (pool, _) = poolWith(
            "TEST_API_KEY" to "k1",
            "TEST_API_KEY" to "k2"
        )
        repeat(2) { idx ->
            val slot = pool.pick("prov", nowMs = 0L)!!.first
            pool.markFailure(slot, FailureCategory.RATE_LIMIT, nowMs = 0L)
        }
        assertNull(pool.pick("prov", nowMs = 5000L))
        // recovery after cooldown window
        assertNotNull(pool.pick("prov", nowMs = 61_000L))
    }

    @Test
    fun `success resets slot health`() {
        val (pool, _) = poolWith("TEST_API_KEY" to "only-key")
        val slot = pool.pick("prov", nowMs = 0L)!!.first
        pool.markFailure(slot, FailureCategory.TIMEOUT, nowMs = 0L)
        assertFalse(pool.hasHealthySlot("prov", nowMs = 100L))
        pool.markSuccess(slot)
        assertTrue(pool.hasHealthySlot("prov", nowMs = 100L))
    }
}

class ProviderHealthManagerTest {

    private var fakeNow = 10_000L

    @Test
    fun `success records latency and keeps healthy`() {
        val mgr = ProviderHealthManager { fakeNow }
        mgr.recordSuccess("groq", 320)
        val r = mgr.runtime("groq")
        assertEquals(HealthState.HEALTHY, r.state)
        assertEquals(320L, r.emaLatencyMs)
        assertEquals(1L, r.successCount)
    }

    @Test
    fun `rate limit sets cooldown state`() {
        val mgr = ProviderHealthManager { fakeNow }
        val cd = mgr.recordFailure("openai", FailureCategory.RATE_LIMIT)
        assertEquals(60_000L, cd)
        val r = mgr.runtime("openai")
        assertEquals(HealthState.RATE_LIMITED, r.state)
        assertFalse(mgr.isSelectable("openai", enabled = true, poolHasHealthyKey = false))
        fakeNow += 61_000L
        assertTrue(mgr.isSelectable("openai", enabled = true, poolHasHealthyKey = false))
    }

    @Test
    fun `auth failure applies long cooldown but recovers`() {
        val mgr = ProviderHealthManager { fakeNow }
        mgr.recordFailure("mistral", FailureCategory.AUTH)
        assertFalse(mgr.isSelectable("mistral", true, poolHasHealthyKey = false))
        fakeNow += 900_001L
        assertTrue(mgr.isSelectable("mistral", true, poolHasHealthyKey = false))
    }

    @Test
    fun `timeout backoff grows then caps`() {
        val mgr = ProviderHealthManager { fakeNow }
        val c1 = mgr.recordFailure("cerebras", FailureCategory.TIMEOUT)
        val c2 = mgr.recordFailure("cerebras", FailureCategory.TIMEOUT)
        val c3 = mgr.recordFailure("cerebras", FailureCategory.TIMEOUT)
        assertTrue(c2 > c1)
        assertTrue(c3 >= c2)
        assertTrue(c3 <= ProviderHealthManager.PROVIDER_MAX_BACKOFF_MS)
    }

    @Test
    fun `healthy key keeps provider selectable during provider cooldown`() {
        val mgr = ProviderHealthManager { fakeNow }
        mgr.recordFailure("gemini", FailureCategory.NETWORK)
        assertTrue(
            "rotation key should keep provider alive",
            mgr.isSelectable("gemini", true, poolHasHealthyKey = true)
        )
    }
}

class ModelRoutingTest {

    @Test
    fun `coding routes to specialised model on groq`() {
        // Pinned to the production table (ModelRouting.groq.CODING): the
        // qwen3.8-27b entry intentionally replaced the older coder model.
        assertEquals(
            "qwen/qwen3.8-27b",
            ModelRouting.resolveModel("groq", Capability.CODING, "llama-3.3-70b-versatile")
        )
    }

    @Test
    fun `reasoning routes to reasoning model on openrouter`() {
        assertEquals(
            "deepseek/deepseek-r1",
            ModelRouting.resolveModel(
                "openrouter", Capability.REASONING, "meta-llama/llama-3.3-70b-instruct"
            )
        )
    }

    @Test
    fun `chat uses balanced default on groq`() {
        // Pinned to the production table (ModelRouting.groq.CHAT): groq's
        // balanced default is gpt-oss-120b; the llama default moved off-table.
        assertEquals(
            "openai/gpt-oss-120b",
            ModelRouting.resolveModel("groq", Capability.CHAT, "llama-3.3-70b-versatile")
        )
    }

    @Test
    fun `unknown provider falls back to its default`() {
        assertEquals(
            "whatever-default",
            ModelRouting.resolveModel("mystery-box", Capability.CODING, "whatever-default")
        )
    }
}
