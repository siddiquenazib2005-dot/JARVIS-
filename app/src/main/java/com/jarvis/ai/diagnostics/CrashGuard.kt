package com.jarvis.ai.diagnostics

import android.content.Context
import android.content.SharedPreferences

/*
 * CrashGuard: on-device black box recorder.
 *
 * Why this exists: the user reports "AURIX keeps stopping" but has no PC, so
 * logcat is out of reach and every crash fix so far had to be a guess. This
 * installs a default uncaught-exception handler that persists the crash
 * (thread, exception type, message, and the top frames of the stack) BEFORE
 * the process dies. On the next launch the crash is available in-app:
 *  - the startup self-check records it,
 *  - the chat command "crash log" prints it verbatim.
 *
 * The previous handler is always chained, so the system still shows its own
 * dialog and the normal reporting path is untouched.
 */
object CrashGuard {

    private const val PREFS = "aurix_crash"
    private const val KEY_AT = "at_ms"
    private const val KEY_TYPE = "type"
    private const val KEY_MESSAGE = "message"
    private const val KEY_THREAD = "thread"
    private const val KEY_STACK = "stack"
    private const val KEY_COUNT = "count"
    private const val KEY_SEEN = "seen"

    /** How many stack frames are kept. Enough to name the guilty file:line. */
    private const val FRAMES = 18

    private var installed = false

    data class Crash(
        val atMs: Long,
        val type: String,
        val message: String,
        val thread: String,
        val stack: String,
        val count: Int,
        val seen: Boolean
    ) {
        fun summary(): String = type + (if (message.isBlank()) "" else ": " + message)
    }

    /**
     * Installs the recorder. Safe to call more than once; only the first call
     * does anything. Must be called as early as possible in the process.
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { persist(app, thread, error) }
            // Never swallow: let the platform (and MIUI's reporter) proceed.
            if (previous != null) previous.uncaughtException(thread, error)
            else throw RuntimeException(error)
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(context: Context, error: Throwable) {
        runCatching { persist(context.applicationContext, Thread.currentThread(), error) }
    }

    private fun persist(context: Context, thread: Thread, error: Throwable) {
        val root = rootCause(error)
        val store = prefs(context)
        store.edit()
            .putLong(KEY_AT, System.currentTimeMillis())
            .putString(KEY_TYPE, root::class.java.simpleName)
            .putString(KEY_MESSAGE, (root.message ?: "").take(400))
            .putString(KEY_THREAD, thread.name)
            .putString(KEY_STACK, renderStack(root))
            .putInt(KEY_COUNT, store.getInt(KEY_COUNT, 0) + 1)
            .putBoolean(KEY_SEEN, false)
            .commit() // commit, not apply: the process is about to die
    }

    private fun rootCause(error: Throwable): Throwable {
        var current = error
        var guard = 0
        while (guard < 8) {
            val cause = current.cause ?: break
            if (cause === current) break
            current = cause
            guard += 1
        }
        return current
    }

    private fun renderStack(error: Throwable): String {
        val frames: List<StackTraceElement> = error.stackTrace?.toList() ?: emptyList()
        if (frames.isEmpty()) return ""
        // Our own frames first (that is where the fix has to happen), then the
        // rest of the trace for context. Written with an explicit loop instead
        // of joinToString so no generic inference is involved: this file has to
        // compile even when everything else is on fire.
        val ours = ArrayList<StackTraceElement>()
        val rest = ArrayList<StackTraceElement>()
        for (frame in frames) {
            if (frame.className.startsWith("com.jarvis.ai")) ours.add(frame) else rest.add(frame)
        }
        val ordered = ArrayList<StackTraceElement>()
        ordered.addAll(ours)
        ordered.addAll(rest)
        val out = StringBuilder()
        var shown = 0
        for (frame in ordered) {
            if (shown >= FRAMES) break
            if (shown > 0) out.append("\n")
            val file: String = frame.fileName ?: "?"
            out.append(frame.className)
                .append(".")
                .append(frame.methodName)
                .append(" (")
                .append(file)
                .append(":")
                .append(frame.lineNumber)
                .append(")")
            shown += 1
        }
        return out.toString()
    }

    /** The last recorded crash, or null when the app has never crashed. */
    fun lastCrash(context: Context): Crash? {
        val store = prefs(context)
        val at = store.getLong(KEY_AT, 0L)
        if (at <= 0L) return null
        return Crash(
            atMs = at,
            type = store.getString(KEY_TYPE, "") ?: "",
            message = store.getString(KEY_MESSAGE, "") ?: "",
            thread = store.getString(KEY_THREAD, "") ?: "",
            stack = store.getString(KEY_STACK, "") ?: "",
            count = store.getInt(KEY_COUNT, 0),
            seen = store.getBoolean(KEY_SEEN, false)
        )
    }

    /** Marks the crash as already shown so the self-check stops nagging. */
    fun markSeen(context: Context) {
        prefs(context).edit().putBoolean(KEY_SEEN, true).apply()
    }

    /** Wipes the stored crash. */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** Full human-readable report for the chat command. */
    fun report(context: Context): String {
        val crash = lastCrash(context)
            ?: return "No crash on record, sir. Nothing has brought me down since install."
        markSeen(context)
        val ageMinutes = (System.currentTimeMillis() - crash.atMs) / 60000L
        val when_ = when {
            ageMinutes < 1L -> "just now"
            ageMinutes < 60L -> ageMinutes.toString() + " min ago"
            ageMinutes < 1440L -> (ageMinutes / 60L).toString() + " h ago"
            else -> (ageMinutes / 1440L).toString() + " d ago"
        }
        val lines = StringBuilder()
        lines.append("Last crash (").append(when_).append(", total ")
            .append(crash.count).append("):\n\n")
        lines.append("Type: ").append(crash.type).append("\n")
        if (crash.message.isNotBlank()) {
            lines.append("Message: ").append(crash.message).append("\n")
        }
        lines.append("Thread: ").append(crash.thread).append("\n\n")
        if (crash.stack.isNotBlank()) {
            lines.append("Stack:\n").append(crash.stack)
        }
        return lines.toString()
    }
}

/**
 * Reports an unshown crash from a previous run. This is the agent that turns a
 * dead "app keeps stopping" dialog into an actionable finding.
 */
object CrashAgent : DiagnosticAgent {

    override val name: String = "crash"

    override fun inspect(context: Context): List<Finding> {
        val crash = CrashGuard.lastCrash(context)
            ?: return listOf(
                Finding(name, Severity.OK, "No crash on record", "The app has not crashed since install.")
            )
        val firstLine = crash.stack.lineSequence().firstOrNull { it.contains("com.jarvis.ai") }
            ?: crash.stack.lineSequence().firstOrNull()
            ?: "no stack captured"
        val severity = if (crash.seen) Severity.WARN else Severity.FAIL
        return listOf(
            Finding(
                agent = name,
                severity = severity,
                title = "Crashed " + crash.count + "x, last: " + crash.summary(),
                detail = "Top frame: " + firstLine,
                fix = "Say 'crash log' for the full stack trace."
            )
        )
    }
}
