package com.jarvis.ai.provider

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderManagerP1Test {

    private fun secretsWith(vararg pairs: Pair<String, String>) =
        MapSecretsSource().also { s -> pairs.forEach { (k, v) -> s.put("$k#1", v) } }

    private val transport200 = HttpTransport { req ->
        FakeResponses.json(200, """{"data":[{"id":"m"}]}""")
    }
    private val transport429 = HttpTransport { req ->
        FakeResponses.json(429, """{"error":{"message":"rate limited"}}""")
    }
    private val transport401 = HttpTransport { req ->
        FakeResponses.json(401, """{"error":"unauthorized"}""")
    }

    @Test
    fun `bootstrap registers slot for every configured secret`() {
        val secrets = secretsWith(
            "GROQ_API_KEY" to "gsk_x",
            "OPENROUTER_API_KEY" to "sk-or-x",
            "GROQ_API_KEY" to "gsk_y"
        )
        secrets.put("GROQ_API_KEY#2", "gsk_second")
        val keys = KeyPoolManager(secrets)
        val mgr = ProviderManager(keys, ProviderHealthManager(), secrets)
        mgr.bootstrapFromSecrets()

        assertTrue(keys.slots("groq").size == 2)
        assertTrue(keys.slots("openrouter").size >= 1)
        assertNotNull(keys.pick("groq", nowMs = 0L))
    }

    @Test
    fun `providers without secrets get no slots`() {
        val secrets = MapSecretsSource() // empty
        val keys = KeyPoolManager(secrets)
        val mgr = ProviderManager(keys, ProviderHealthManager(), secrets)
        mgr.bootstrapFromSecrets()
        assertNull(keys.pick("groq", nowMs = 0L))
    }

    @Test
    fun `selectPrimary prefers healthy provider over auth-failed one`() = runBlocking {
        val secrets = secretsWith(
            "GROQ_API_KEY" to "gsk_a",
            "MISTRAL_API_KEY" to "mistral_a"
        )
        val keys = KeyPoolManager(secrets)
        keys.addSlot("groq", "GROQ_API_KEY")
        keys.addSlot("mistral", "MISTRAL_API_KEY")
        val health = ProviderHealthManager()
        health.recordFailure("groq", FailureCategory.AUTH)
        val mgr = ProviderManager(keys, health, secrets, transport401)

        val primary = mgr.selectPrimary(Capability.CHAT)
        assertNotNull(primary)
        assertEquals("mistral", primary!!.providerId)
    }

    @Test
    fun `selectPrimary favours low latency after proven successes`() = runBlocking {
        val secrets = secretsWith(
            "GROQ_API_KEY" to "gsk_a",
            "CEREBRAS_API_KEY" to "csk_a"
        )
        val keys = KeyPoolManager(secrets)
        keys.addSlot("groq", "GROQ_API_KEY")
        keys.addSlot("cerebras", "CEREBRAS_API_KEY")
        val health = ProviderHealthManager()
        // groq: proven but slow; cerebras: proven and fast
        repeat(3) {
            health.recordSuccess("groq", 9000)
            health.recordSuccess("cerebras", 250)
        }
        val mgr = ProviderManager(keys, health, secrets, transport200)
        assertEquals("cerebras", mgr.selectPrimary(Capability.CHAT)!!.providerId)
    }

    @Test
    fun `healthCheck marks rate-limited and healthy providers`() = runBlocking {
        // groq → 429 (scripted), mistral → 200
        val scripted = HttpTransport { req ->
            if (req.url.host.contains("groq")) transport429.execute(req)
            else transport200.execute(req)
        }
        val secrets = secretsWith("GROQ_API_KEY" to "gsk_a", "MISTRAL_API_KEY" to "mst_a")
        val keys = KeyPoolManager(secrets)
        keys.addSlot("groq", "GROQ_API_KEY")
        keys.addSlot("mistral", "MISTRAL_API_KEY")
        val health = ProviderHealthManager()
        val mgr = ProviderManager(keys, health, secrets, scripted)

        val statuses = mgr.healthCheck().associateBy { it.providerId }
        assertFalse(statuses.getValue("groq").reachable)
        assertEquals(HealthState.RATE_LIMITED, statuses.getValue("groq").stateHint)
        assertTrue(statuses.getValue("mistral").reachable)
        // definitive 429 mutates health so routing avoids it immediately
        assertEquals(HealthState.RATE_LIMITED, health.runtime("groq").state)
    }

    @Test
    fun `healthCheck reports disabled state when key missing`() = runBlocking {
        val mgr = ProviderManager(KeyPoolManager(MapSecretsSource()), ProviderHealthManager(), MapSecretsSource(), transport200)
        val status = mgr.healthCheck().firstOrNull { it.providerId == "openai" }!!
        assertFalse(status.reachable)
        assertEquals(HealthState.DISABLED, status.stateHint)
    }
}

object FakeResponses {
    private val jsonMt = "application/json".toMediaType()

    fun json(code: Int, body: String): okhttp3.Response =
        okhttp3.Response.Builder()
            .code(code)
            .message("t")
            .request(okhttp3.Request.Builder().url("https://fake.test/x").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .body(okhttp3.ResponseBody.create(jsonMt, body))
            .build()
}
