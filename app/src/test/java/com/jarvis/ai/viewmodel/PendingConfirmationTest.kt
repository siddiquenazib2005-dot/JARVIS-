package com.jarvis.ai.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingConfirmationTest {
    @Test
    fun communicationRequestsUseTheApprovalGatedPath() {
        assertTrue(isApprovalGatedCommunication("send whatsapp to Rahul saying hello"))
        assertTrue(isApprovalGatedCommunication("send sms to Rahul saying hello"))
        assertTrue(isApprovalGatedCommunication("call Mom"))
        assertFalse(isApprovalGatedCommunication("what is WhatsApp"))
    }

    @Test
    fun onlyTheSamePendingCommandConfirms() {
        assertTrue(matchesPendingConfirmation("Call Mom", "Call Mom"))
        assertTrue(matchesPendingConfirmation("  Send SMS  ", "send sms"))
        assertFalse(matchesPendingConfirmation("Call Mom", "Call Dad"))
        assertFalse(matchesPendingConfirmation(null, "Call Mom"))
        assertFalse(matchesPendingConfirmation("", ""))
    }
}
