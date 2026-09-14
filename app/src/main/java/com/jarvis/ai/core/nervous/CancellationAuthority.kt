package com.jarvis.ai.core.nervous

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CancellationState(
    val generation: Long = 0,
    val cancelledAtMs: Long? = null,
    val reason: String? = null
)

/** Single authority used by TTS, recognition, planners, executors and verifiers. */
class CancellationAuthority(private val now: () -> Long = System::currentTimeMillis) {
    private val generation = AtomicLong(0)
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private val mutable = MutableStateFlow(CancellationState())
    val state: StateFlow<CancellationState> = mutable.asStateFlow()

    fun token(): Long = generation.get()
    fun isCancelled(token: Long): Boolean = token != generation.get()

    fun register(owner: String, cancel: () -> Unit) {
        require(owner.isNotBlank())
        callbacks[owner] = cancel
    }

    fun unregister(owner: String) { callbacks.remove(owner) }

    /** Invalidates all prior tokens before invoking callbacks, preventing late work. */
    fun cancelAll(reason: String = "Stopped by user"): CancellationState {
        val next = generation.incrementAndGet()
        mutable.value = CancellationState(next, now(), reason)
        callbacks.values.toList().forEach { runCatching { it() } }
        return mutable.value
    }

    fun clear(): CancellationState {
        val state = CancellationState(generation.get())
        mutable.value = state
        return state
    }
}
