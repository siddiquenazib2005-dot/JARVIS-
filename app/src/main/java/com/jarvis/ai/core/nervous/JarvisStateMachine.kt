package com.jarvis.ai.core.nervous

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One authoritative lifecycle for voice, planning, actions, verification and visuals. */
enum class JarvisPhase {
    IDLE,
    LISTENING,
    UNDERSTANDING,
    PLANNING,
    WAITING_FOR_CONFIRMATION,
    WAITING_FOR_PERMISSION,
    EXECUTING,
    VERIFYING,
    SUCCESS,
    FAILED,
    CANCELLED
}

data class JarvisSystemState(
    val phase: JarvisPhase = JarvisPhase.IDLE,
    val actionId: String? = null,
    val request: String? = null,
    val detail: String? = null,
    val errorCode: String? = null,
    val changedAtMs: Long = System.currentTimeMillis()
)

/**
 * Rejects impossible lifecycle jumps instead of allowing unrelated booleans to
 * describe contradictory states. Terminal states may return only to IDLE or
 * begin a new request through UNDERSTANDING/LISTENING.
 */
class JarvisStateMachine(
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    private val mutable = MutableStateFlow(JarvisSystemState(changedAtMs = now()))
    val state: StateFlow<JarvisSystemState> = mutable.asStateFlow()

    @Synchronized
    fun begin(request: String, phase: JarvisPhase = JarvisPhase.UNDERSTANDING): JarvisSystemState {
        require(phase == JarvisPhase.UNDERSTANDING || phase == JarvisPhase.LISTENING)
        return publish(JarvisSystemState(phase, newId(), request.trim(), changedAtMs = now()))
    }

    @Synchronized
    fun transition(
        next: JarvisPhase,
        detail: String? = null,
        errorCode: String? = null,
        actionId: String? = mutable.value.actionId
    ): JarvisSystemState {
        val current = mutable.value
        require(next in allowed.getValue(current.phase)) {
            "Illegal JARVIS transition ${current.phase} -> $next"
        }
        if (current.actionId != null && actionId != null) {
            require(current.actionId == actionId) { "Stale action attempted a state transition" }
        }
        return publish(
            current.copy(
                phase = next,
                detail = detail,
                errorCode = errorCode,
                changedAtMs = now()
            )
        )
    }

    @Synchronized
    fun cancel(reason: String = "Cancelled by user"): JarvisSystemState {
        if (mutable.value.phase == JarvisPhase.IDLE) return mutable.value
        return if (JarvisPhase.CANCELLED in allowed.getValue(mutable.value.phase)) {
            transition(JarvisPhase.CANCELLED, reason, "USER_CANCELLED")
        } else mutable.value
    }

    @Synchronized
    fun reset(): JarvisSystemState {
        val current = mutable.value
        require(current.phase in terminal || current.phase == JarvisPhase.IDLE) {
            "Cannot reset an active action; cancel it first"
        }
        return publish(JarvisSystemState(changedAtMs = now()))
    }

    private fun publish(next: JarvisSystemState): JarvisSystemState {
        mutable.value = next
        return next
    }

    companion object {
        val terminal = setOf(JarvisPhase.SUCCESS, JarvisPhase.FAILED, JarvisPhase.CANCELLED)

        private val activeTargets = setOf(
            JarvisPhase.PLANNING,
            JarvisPhase.WAITING_FOR_CONFIRMATION,
            JarvisPhase.WAITING_FOR_PERMISSION,
            JarvisPhase.EXECUTING,
            JarvisPhase.FAILED,
            JarvisPhase.CANCELLED
        )

        private val allowed: Map<JarvisPhase, Set<JarvisPhase>> = mapOf(
            JarvisPhase.IDLE to setOf(JarvisPhase.LISTENING, JarvisPhase.UNDERSTANDING),
            JarvisPhase.LISTENING to setOf(JarvisPhase.UNDERSTANDING, JarvisPhase.FAILED, JarvisPhase.CANCELLED),
            JarvisPhase.UNDERSTANDING to activeTargets,
            JarvisPhase.PLANNING to setOf(
                JarvisPhase.WAITING_FOR_CONFIRMATION,
                JarvisPhase.WAITING_FOR_PERMISSION,
                JarvisPhase.EXECUTING,
                JarvisPhase.FAILED,
                JarvisPhase.CANCELLED
            ),
            JarvisPhase.WAITING_FOR_CONFIRMATION to setOf(
                JarvisPhase.WAITING_FOR_PERMISSION,
                JarvisPhase.EXECUTING,
                JarvisPhase.FAILED,
                JarvisPhase.CANCELLED
            ),
            JarvisPhase.WAITING_FOR_PERMISSION to setOf(
                JarvisPhase.EXECUTING,
                JarvisPhase.FAILED,
                JarvisPhase.CANCELLED
            ),
            JarvisPhase.EXECUTING to setOf(JarvisPhase.VERIFYING, JarvisPhase.FAILED, JarvisPhase.CANCELLED),
            JarvisPhase.VERIFYING to setOf(JarvisPhase.SUCCESS, JarvisPhase.FAILED, JarvisPhase.CANCELLED),
            JarvisPhase.SUCCESS to setOf(JarvisPhase.IDLE, JarvisPhase.LISTENING, JarvisPhase.UNDERSTANDING),
            JarvisPhase.FAILED to setOf(JarvisPhase.IDLE, JarvisPhase.LISTENING, JarvisPhase.UNDERSTANDING),
            JarvisPhase.CANCELLED to setOf(JarvisPhase.IDLE, JarvisPhase.LISTENING, JarvisPhase.UNDERSTANDING)
        )
    }
}
