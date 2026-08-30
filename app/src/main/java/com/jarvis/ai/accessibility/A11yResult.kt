package com.jarvis.ai.accessibility

/**
 * Structured outcome for every device-automation action.
 *
 * Replaces ad-hoc `Boolean`/`null` returns so the orchestration layer can decide
 * between RETRY / RE_RESOLVE / WAIT / ASK_USER / ABORT based on [errorCode]
 * and [retryable].
 */
enum class A11yStatus { SUCCESS, FAILURE, NEEDS_CONFIRMATION }

/** Machine-readable failure reasons. */
enum class A11yErrorCode {
    SERVICE_UNAVAILABLE,
    ACCESSIBILITY_DISABLED,
    USER_ACTION_REQUIRED,
    APP_NOT_FOUND,
    NODE_NOT_FOUND,
    MULTIPLE_MATCHES,
    NODE_NOT_VISIBLE,
    NODE_DISABLED,
    INVALID_ACTION,
    GESTURE_FAILED,
    GESTURE_CANCELLED,
    INPUT_FAILED,
    TIMEOUT,
    WINDOW_CHANGED,
    PACKAGE_NOT_FOUND,
    APP_NOT_INSTALLED,
    VERIFICATION_FAILED,
    PERMISSION_REQUIRED,
    HIERARCHY_UNAVAILABLE,
    UNKNOWN
}

enum class VerificationStatus { NOT_CHECKED, VERIFIED, FAILED }

data class A11yResult(
    val status: A11yStatus,
    val action: String = "",
    val target: String = "",
    val packageName: String? = null,
    val errorCode: A11yErrorCode? = null,
    val message: String? = null,
    val retryable: Boolean = false,
    val verificationStatus: VerificationStatus = VerificationStatus.NOT_CHECKED,
    val attempts: Int = 1,
    val data: Map<String, String> = emptyMap()
) {
    val isSuccess: Boolean get() = status == A11yStatus.SUCCESS
    val isFailure: Boolean get() = status == A11yStatus.FAILURE
    val needsConfirmation: Boolean get() = status == A11yStatus.NEEDS_CONFIRMATION

    companion object {
        fun success(
            action: String = "",
            target: String = "",
            packageName: String? = null,
            message: String? = null,
            verificationStatus: VerificationStatus = VerificationStatus.NOT_CHECKED,
            attempts: Int = 1,
            data: Map<String, String> = emptyMap()
        ) = A11yResult(
            status = A11yStatus.SUCCESS,
            action = action, target = target, packageName = packageName,
            message = message, verificationStatus = verificationStatus,
            attempts = attempts, data = data
        )

        fun failure(
            code: A11yErrorCode,
            action: String = "",
            target: String = "",
            packageName: String? = null,
            message: String? = null,
            retryable: Boolean = false,
            attempts: Int = 1
        ) = A11yResult(
            status = A11yStatus.FAILURE,
            action = action, target = target, packageName = packageName,
            errorCode = code, message = message, retryable = retryable, attempts = attempts
        )

        fun needsConfirmation(
            action: String = "",
            target: String = "",
            message: String
        ) = A11yResult(
            status = A11yStatus.NEEDS_CONFIRMATION,
            action = action, target = target, message = message
        )
    }
}
