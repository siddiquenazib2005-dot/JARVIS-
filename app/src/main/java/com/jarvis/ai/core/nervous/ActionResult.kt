package com.jarvis.ai.core.nervous

import java.util.UUID

enum class ActionStatus { SUCCESS, FAILED, CANCELLED, PENDING, UNAVAILABLE, DEGRADED }
enum class VerificationStatus { VERIFIED, PARTIALLY_VERIFIED, UNVERIFIED, UNKNOWN, NOT_SUPPORTED }

data class VerificationEvidence(
    val status: VerificationStatus,
    val verifier: String,
    val checkedAtMs: Long = System.currentTimeMillis(),
    val detail: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class ActionResult(
    val actionId: String = UUID.randomUUID().toString(),
    val actionType: String,
    val status: ActionStatus,
    val executor: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val verification: VerificationEvidence = VerificationEvidence(
        VerificationStatus.UNVERIFIED,
        verifier = "none",
        detail = "Execution completed without a verification hook"
    )
) {
    val isVerifiedSuccess: Boolean
        get() = status == ActionStatus.SUCCESS && verification.status == VerificationStatus.VERIFIED
}

fun interface ActionVerificationHook {
    suspend fun verify(execution: ActionResult): VerificationEvidence
}

/** Execution and verification are deliberately separate operations. */
class ActionVerificationEngine {
    private val hooks = mutableMapOf<String, ActionVerificationHook>()

    @Synchronized
    fun register(actionType: String, hook: ActionVerificationHook) {
        require(actionType.isNotBlank())
        hooks[actionType] = hook
    }

    @Synchronized
    fun remove(actionType: String) { hooks.remove(actionType) }

    suspend fun verify(execution: ActionResult): ActionResult {
        if (execution.status == ActionStatus.CANCELLED || execution.status == ActionStatus.FAILED) {
            return execution
        }
        val hook = synchronized(this) { hooks[execution.actionType] }
            ?: return execution.copy(
                status = if (execution.status == ActionStatus.SUCCESS) ActionStatus.PENDING else execution.status,
                verification = VerificationEvidence(
                    VerificationStatus.NOT_SUPPORTED,
                    "verification-engine",
                    detail = "No verification hook registered for ${execution.actionType}"
                )
            )
        val evidence = runCatching { hook.verify(execution) }.getOrElse {
            VerificationEvidence(
                VerificationStatus.UNKNOWN,
                "verification-engine",
                detail = it.message ?: it::class.java.simpleName
            )
        }
        val finalStatus = when (evidence.status) {
            VerificationStatus.VERIFIED -> ActionStatus.SUCCESS
            VerificationStatus.PARTIALLY_VERIFIED -> ActionStatus.DEGRADED
            VerificationStatus.UNVERIFIED,
            VerificationStatus.UNKNOWN,
            VerificationStatus.NOT_SUPPORTED -> ActionStatus.PENDING
        }
        return execution.copy(status = finalStatus, verification = evidence)
    }
}
