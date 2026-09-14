package com.jarvis.ai.core.nervous

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionVerificationEngineTest {
    @Test fun unsupportedVerificationNeverProducesSuccess() = runTest {
        val engine = ActionVerificationEngine()
        val result = engine.verify(ActionResult(
            actionId = "x",
            actionType = "open_app",
            status = ActionStatus.SUCCESS,
            executor = "intent"
        ))
        assertEquals(ActionStatus.PENDING, result.status)
        assertEquals(VerificationStatus.NOT_SUPPORTED, result.verification.status)
        assertFalse(result.isVerifiedSuccess)
    }

    @Test fun verifiedEvidenceProducesSuccess() = runTest {
        val engine = ActionVerificationEngine()
        engine.register("torch") {
            VerificationEvidence(VerificationStatus.VERIFIED, "torch-callback")
        }
        val result = engine.verify(ActionResult(
            actionType = "torch",
            status = ActionStatus.PENDING,
            executor = "camera-manager"
        ))
        assertTrue(result.isVerifiedSuccess)
    }
}
