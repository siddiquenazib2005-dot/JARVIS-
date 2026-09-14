package com.jarvis.ai.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityUsePolicyTest {

    @Test
    fun `system SMS apps never use accessibility to send`() {
        val packages = listOf(
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.android.mms",
            "com.android.messaging"
        )

        packages.forEach { packageName ->
            assertEquals(
                AccessibilityUsePolicy.Channel.USER_CONTROLLED_INTENT,
                AccessibilityUsePolicy.channelForUiSend(packageName)
            )
            assertFalse(
                AccessibilityUsePolicy.mayUseAccessibilityForUiSend(packageName)
            )
        }
    }

    @Test
    fun `third party UI flow can explicitly use accessibility`() {
        assertEquals(
            AccessibilityUsePolicy.Channel.ACCESSIBILITY_UI,
            AccessibilityUsePolicy.channelForUiSend("com.whatsapp")
        )
        assertTrue(
            AccessibilityUsePolicy.mayUseAccessibilityForUiSend("com.whatsapp")
        )
    }

    @Test
    fun `package matching is case insensitive`() {
        assertFalse(
            AccessibilityUsePolicy.mayUseAccessibilityForUiSend(
                "COM.GOOGLE.ANDROID.APPS.MESSAGING"
            )
        )
    }
}
