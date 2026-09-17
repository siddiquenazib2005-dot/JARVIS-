package com.jarvis.ai.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * Centralized wait/retry engine (phase 10). Dispatcher-safe, polled (no busy loops),
 * with timeout, polling interval and stale-node protection.
 */
class WaitEngine(
    private val rootProvider: () -> AccessibilityNodeInfo?,
    private val textProvider: () -> String
) {

    suspend fun waitForText(text: String, timeoutMs: Long, pollMs: Long = 250): A11yResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        return try {
            withTimeout(timeoutMs) {
                while (System.currentTimeMillis() < deadline) {
                    if (textProvider().contains(text, ignoreCase = true)) {
                        return@withTimeout A11yResult.success(
                            action = "wait_for_text", target = text,
                            verificationStatus = VerificationStatus.VERIFIED
                        )
                    }
                    delay(pollMs)
                }
                A11yResult.failure(
                    A11yErrorCode.TIMEOUT, action = "wait_for_text", target = text,
                    message = "Timed out waiting for '$text'"
                )
            }
        } catch (e: Exception) {
            A11yResult.failure(A11yErrorCode.TIMEOUT, action = "wait_for_text", target = text)
        }
    }

    suspend fun waitForNode(target: String, timeoutMs: Long, pollMs: Long = 250): A11yResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = runCatching { rootProvider() }.getOrNull()
            if (root != null) {
                val node = runCatching { NodeResolver.resolve(root, target) }.getOrNull()
                if (node != null)
                    return A11yResult.success(action = "wait_for_node", target = target)
            }
            delay(pollMs)
        }
        return A11yResult.failure(
            A11yErrorCode.TIMEOUT, action = "wait_for_node", target = target,
            message = "Timed out waiting for node '$target'"
        )
    }

    suspend fun waitForPackage(packageName: String, timeoutMs: Long, pollMs: Long = 300): A11yResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val root = runCatching { rootProvider() }.getOrNull()
            val pkg = root?.packageName?.toString()
            if (pkg == packageName)
                return A11yResult.success(
                    action = "wait_for_package", target = packageName, packageName = packageName,
                    verificationStatus = VerificationStatus.VERIFIED
                )
            delay(pollMs)
        }
        return A11yResult.failure(
            A11yErrorCode.TIMEOUT, action = "wait_for_package", target = packageName,
            message = "Timed out waiting for package '$packageName'"
        )
    }
}
