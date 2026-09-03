package com.jarvis.ai.orchestrator

/**
 * Cooperative cancellation signal shared with the agent loop. A thread-safe,
 * monitor-backed flag a coordinator can set to halt automation mid-run and
 * that a worker can poll to check whether cancellation was requested.
 */
class CancellationToken {
    internal var _cancelled = false
    private val lock = Any()

    var isCancelled: Boolean
        get() = synchronized(lock) { _cancelled }
        set(value) = synchronized(lock) { _cancelled = value }

    fun cancel() {
        synchronized(lock) { _cancelled = true }
    }

    fun reset() {
        synchronized(lock) { _cancelled = false }
    }

    fun checkCancellation() {
        if (isCancelled) {
            throw InterruptedException("Operation cancelled by user")
        }
    }
}