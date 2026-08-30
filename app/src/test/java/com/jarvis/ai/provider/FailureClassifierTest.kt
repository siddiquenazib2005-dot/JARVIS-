package com.jarvis.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureClassifierTest {

    @Test
    fun `429 maps to rate limit`() {
        assertEquals(FailureCategory.RATE_LIMIT, FailureClassifier.classify("HTTP 429: too many requests"))
        assertEquals(FailureCategory.RATE_LIMIT, FailureClassifier.classify(null, httpStatus = 429))
    }

    @Test
    fun `401 and 403 map to auth`() {
        assertEquals(FailureCategory.AUTH, FailureClassifier.classify("HTTP 401 invalid key"))
        assertEquals(FailureCategory.AUTH, FailureClassifier.classify("HTTP 403 forbidden"))
    }

    @Test
    fun `timeout wording maps to timeout`() {
        assertEquals(
            FailureCategory.TIMEOUT,
            FailureClassifier.classify("read timed out after 90s")
        )
    }

    @Test
    fun `5xx maps to server`() {
        assertEquals(FailureCategory.SERVER, FailureClassifier.classify(null, httpStatus = 503))
    }

    @Test
    fun `dns and refused map to network`() {
        assertEquals(
            FailureCategory.NETWORK,
            FailureClassifier.classify("Unable to resolve host api.groq.com")
        )
        assertEquals(
            FailureCategory.NETWORK,
            FailureClassifier.classify("Failed to connect to api.deepseek.com")
        )
    }

    @Test
    fun `unknown message maps to unknown`() {
        assertEquals(FailureCategory.UNKNOWN, FailureClassifier.classify(null))
        assertEquals(FailureCategory.UNKNOWN, FailureClassifier.classify("something odd"))
    }

    @Test
    fun `redactor masks every known secret family`() {
        val raw = "key=sk-or-v1-abc123 gsk_abc123 csk-abc123 tvly-abc-123 AQ.Ab8R Bearer tok123 api_key=zzz"
        val out = SecretRedactor.redact(raw)
        assertTrue(!out.contains("sk-or-v1"))
        assertTrue(!out.contains("gsk_"))
        assertTrue(!out.contains("csk-"))
        assertTrue(!out.contains("tvly-"))
        assertTrue(!out.contains("AQ.Ab8R"))
        assertTrue(!out.contains("tok123"))
        assertTrue(out.contains("***REDACTED***"))
    }

    @Test
    fun `redactor keeps normal text intact`() {
        val normal = "Battery at 80 percent, sir."
        assertEquals(normal, SecretRedactor.redact(normal))
    }
}
