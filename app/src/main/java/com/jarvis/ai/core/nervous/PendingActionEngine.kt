package com.jarvis.ai.core.nervous

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PendingActionType { CONTACT_SELECTION, CONFIRMATION, PERMISSION, RETRY }

data class PendingOption(
    val id: String,
    val label: String,
    val maskedValue: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class PendingAction(
    val actionId: String = UUID.randomUUID().toString(),
    val type: PendingActionType,
    val originalRequest: String,
    val parameters: Map<String, String> = emptyMap(),
    val options: List<PendingOption> = emptyList(),
    val requiredPermission: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val expiresAtMs: Long = createdAtMs + DEFAULT_EXPIRY_MS,
    val retryCount: Int = 0
) {
    companion object { const val DEFAULT_EXPIRY_MS = 5 * 60 * 1000L }
}

sealed interface ContextResolution {
    data object None : ContextResolution
    data class Selected(val action: PendingAction, val option: PendingOption, val index: Int) : ContextResolution
    data class Confirmed(val action: PendingAction) : ContextResolution
    data class Rejected(val action: PendingAction) : ContextResolution
    data class Retry(val action: PendingAction) : ContextResolution
    data class Repeat(val action: PendingAction) : ContextResolution
    data class Cancelled(val action: PendingAction) : ContextResolution
    data class InvalidSelection(val action: PendingAction, val allowed: IntRange) : ContextResolution
    data class Expired(val action: PendingAction) : ContextResolution
}

/** Context is checked before local intent detection and before every cloud call. */
class PendingActionEngine(private val now: () -> Long = System::currentTimeMillis) {
    private val mutable = MutableStateFlow<PendingAction?>(null)
    val pending: StateFlow<PendingAction?> = mutable.asStateFlow()

    @Synchronized
    fun set(action: PendingAction): PendingAction {
        require(action.actionId.isNotBlank())
        require(action.expiresAtMs > action.createdAtMs)
        mutable.value = action
        return action
    }

    @Synchronized
    fun clear(actionId: String? = null): PendingAction? {
        val current = mutable.value ?: return null
        if (actionId != null && current.actionId != actionId) return null
        mutable.value = null
        return current
    }

    @Synchronized
    fun resolve(rawInput: String): ContextResolution {
        val action = mutable.value ?: return ContextResolution.None
        if (now() >= action.expiresAtMs) {
            mutable.value = null
            return ContextResolution.Expired(action)
        }
        val input = normalize(rawInput)
        if (input in cancelWords) {
            mutable.value = null
            return ContextResolution.Cancelled(action)
        }
        if (input in retryWords) return ContextResolution.Retry(action)
        if (input in repeatWords) return ContextResolution.Repeat(action)

        if (action.type == PendingActionType.CONFIRMATION) {
            if (input in yesWords) {
                mutable.value = null
                return ContextResolution.Confirmed(action)
            }
            if (input in noWords) {
                mutable.value = null
                return ContextResolution.Rejected(action)
            }
            return ContextResolution.None
        }

        if (action.type == PendingActionType.CONTACT_SELECTION) {
            val index = selectionIndex(input)
                ?: return ContextResolution.InvalidSelection(action, 1..action.options.size)
            if (index !in action.options.indices) {
                return ContextResolution.InvalidSelection(action, 1..action.options.size)
            }
            mutable.value = null
            return ContextResolution.Selected(action, action.options[index], index)
        }
        return ContextResolution.None
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun selectionIndex(input: String): Int? {
        input.toIntOrNull()?.let { return it - 1 }
        return selectionWords.entries.firstOrNull { input in it.value }?.key
    }

    companion object {
        private val yesWords = setOf("yes", "yeah", "yep", "confirm", "do it", "haan", "ha", "han", "जी हाँ", "हाँ")
        private val noWords = setOf("no", "nope", "reject", "dont", "do not", "nahi", "nahin", "नहीं")
        private val cancelWords = setOf("cancel", "stop", "abort", "never mind", "rehne do", "band karo", "रद्द", "रुको")
        private val retryWords = setOf("retry", "try again", "again", "dobara", "phir se", "फिर से")
        private val repeatWords = setOf("repeat", "say again", "options", "repeat options", "दोहराओ")
        private val selectionWords = mapOf(
            0 to setOf("one", "first", "option one", "number one", "pehla", "pehli", "पहला", "पहली"),
            1 to setOf("two", "second", "option two", "number two", "dusra", "dusri", "दूसरा", "दूसरी"),
            2 to setOf("three", "third", "option three", "number three", "teesra", "teesri", "तीसरा", "तीसरी"),
            3 to setOf("four", "fourth", "option four", "number four", "chautha", "चौथा")
        )
    }
}
