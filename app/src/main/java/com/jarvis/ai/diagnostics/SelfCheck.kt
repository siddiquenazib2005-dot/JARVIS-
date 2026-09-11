package com.jarvis.ai.diagnostics

import android.content.Context
import com.jarvis.ai.accessibility.JarvisAccessibilityService
import com.jarvis.ai.core.JarvisRuntime
import com.jarvis.ai.onboarding.PermissionCatalog
import com.jarvis.ai.orchestrator.TaskPlanner
import com.jarvis.ai.orchestrator.ToolExecutor
import com.jarvis.ai.orchestrator.ToolRegistry
import com.jarvis.ai.provider.Capability
import com.jarvis.ai.provider.ProviderRegistry
import com.jarvis.ai.tools.EmailSender
import com.jarvis.ai.tools.PhotoVision

/*
 * On-device self-check: the runtime descendants of the four build-time agents.
 *
 * The build-time agents read Kotlin source, which does not exist inside an
 * APK. So each one was translated into the question it was really asking,
 * expressed against live objects instead of text:
 *
 *   agent 1 (symbols)   -> registry integrity: is every advertised tool
 *                          actually dispatchable?
 *   agent 2 (manifest)  -> permission reality: is every permission the code
 *                          depends on actually GRANTED, not merely declared?
 *   agent 3 (hygiene)   -> runtime fault log: what has been failing quietly?
 *   agent 4 (wiring)    -> capability probe: is each feature reachable end to
 *                          end, or only present?
 *
 * Nothing here performs a side effect. A diagnostic that sends a test SMS or
 * burns an API call would be worse than the bug it hunts.
 */

enum class Severity { OK, WARN, FAIL }

/**
 * One observation.
 *
 * @param fix what the OWNER can do about it. A finding without a remedy is
 *        just an alarm, and users learn to ignore alarms.
 */
data class Finding(
    val agent: String,
    val severity: Severity,
    val title: String,
    val detail: String,
    val fix: String? = null
)

/** One runtime inspector. Must be cheap, offline, and side-effect free. */
interface DiagnosticAgent {
    val name: String
    fun inspect(context: Context): List<Finding>
}

/**
 * Rolling record of things that failed while the app was being used.
 *
 * This is the piece that makes the checks "real time" rather than a snapshot:
 * the command router and the tool executor report their failures here as they
 * happen, so a later self-check can describe what actually broke instead of
 * only what is configured. Capped, in memory only, never persisted: a
 * diagnostic buffer that outlives the process becomes a privacy problem.
 */
object DiagnosticsLog {

    data class Entry(val atMs: Long, val source: String, val message: String)

    private const val CAP = 60
    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun record(source: String, message: String) {
        entries.addLast(Entry(System.currentTimeMillis(), source, message.take(240)))
        while (entries.size > CAP) entries.removeFirst()
    }

    @Synchronized
    fun recent(limit: Int = 5): List<Entry> = entries.toList().takeLast(limit).reversed()

    @Synchronized
    fun countSince(sinceMs: Long): Int = entries.count { it.atMs >= sinceMs }

    @Synchronized
    fun clear() = entries.clear()
}

/**
 * Agent 1 - registry integrity.
 *
 * The brain is offered a tool list built from [ToolRegistry]; the executor
 * dispatches from its own `when`. If those two drift, the model confidently
 * calls a tool that cannot run, and the user sees "Unknown tool". This is the
 * runtime equivalent of the unresolved-reference class of build error.
 */
object RegistryAgent : DiagnosticAgent {
    override val name = "registry"

    override fun inspect(context: Context): List<Finding> {
        val advertised = ToolRegistry.getAllToolNames().toSet()
        val dispatchable: Set<String> = TaskPlanner.SUPPORTED_TOOLS
        val findings = mutableListOf<Finding>()

        val undispatchable = (advertised - dispatchable).filterNot { it.startsWith("memory") }
        if (undispatchable.isNotEmpty()) {
            findings += Finding(
                name, Severity.FAIL, "Tools advertised but not dispatchable",
                undispatchable.joinToString(separator = ", "), "Report this - it is an internal wiring bug."
            )
        }
        val orphaned = dispatchable - advertised
        if (orphaned.isNotEmpty()) {
            findings += Finding(
                name, Severity.WARN, "Tools the brain is never told about",
                orphaned.joinToString(separator = ", "), "They work by voice but the model will not choose them."
            )
        }
        if (findings.isEmpty()) {
            findings += Finding(
                name, Severity.OK, "Tool registry consistent",
                "${advertised.size} tools advertised, all dispatchable."
            )
        }
        return findings
    }
}

/**
 * Agent 2 - permission reality.
 *
 * A declared permission proves nothing: the SOS location bug was a DECLARED
 * gap, this catches the GRANTED gap. Optional permissions are warnings, never
 * failures, because a deliberately denied permission is a valid choice.
 */
object PermissionAgent : DiagnosticAgent {
    override val name = "permissions"

    override fun inspect(context: Context): List<Finding> {
        val findings = mutableListOf<Finding>()
        val missing = PermissionCatalog.items().filterNot { it.isGranted(context) }

        missing.filter { it.required }.forEach {
            findings += Finding(
                name, Severity.FAIL, "Missing: ${it.title}",
                it.unlocks + " is unavailable.", "Grant it in the setup wizard."
            )
        }
        missing.filterNot { it.required }.forEach {
            findings += Finding(
                name, Severity.WARN, "Not granted: ${it.title}",
                it.unlocks + " stays off.", "Optional - grant only if you want that feature."
            )
        }
        if (findings.isEmpty()) {
            findings += Finding(name, Severity.OK, "All permissions granted", "Nothing is gated.")
        }
        return findings
    }
}

/**
 * Agent 4 - capability probe (runs before the health agent because a cold log
 * is meaningless until we know what is even reachable).
 *
 * Checks configuration, never behaviour: "can this feature run if asked",
 * not "does it work", which would require actually sending something.
 */
object CapabilityAgent : DiagnosticAgent {
    override val name = "capabilities"

    override fun inspect(context: Context): List<Finding> {
        val runtime = JarvisRuntime.get(context)
        val findings = mutableListOf<Finding>()

        val chatReady = runCatching { runtime.backendOnline() }.getOrDefault(false)
        findings += if (chatReady) {
            Finding(name, Severity.OK, "Chat brain online", "At least one provider has a usable key.")
        } else {
            Finding(
                name, Severity.FAIL, "No usable AI provider",
                "Every chat provider is keyless or cooling down.",
                "Add an API key in settings. Offline device commands still work."
            )
        }

        val keyed = ProviderRegistry.enabledFor(Capability.CHAT).count {
            runCatching { runtime.keys.slots(it.providerId).isNotEmpty() }.getOrDefault(false)
        }
        findings += Finding(
            name, if (keyed > 1) Severity.OK else Severity.WARN,
            "Provider redundancy", "$keyed chat provider(s) hold keys.",
            if (keyed > 1) null else "Add a second provider so one outage does not silence AURIX."
        )

        findings += if (EmailSender(context).isConfigured()) {
            Finding(name, Severity.OK, "Email auto-send ready", "SMTP credentials present.")
        } else {
            Finding(
                name, Severity.WARN, "Email falls back to compose window",
                "No SMTP credentials stored.", EmailSender.SETUP_HINT
            )
        }

        findings += if (PhotoVision(context).hasPermission()) {
            Finding(name, Severity.OK, "Photo vision ready", "Gallery access granted.")
        } else {
            Finding(
                name, Severity.WARN, "Cannot read your photos",
                "Photo permission not granted.", "Grant photos access to ask about your last picture."
            )
        }

        val micOk = runCatching { runtime.hasRecordAudioPermission() }.getOrDefault(false)
        findings += if (micOk) {
            Finding(name, Severity.OK, "Voice and wake word available", "Microphone granted.")
        } else {
            Finding(
                name, Severity.WARN, "Voice input unavailable",
                "Microphone not granted.", "Grant the mic permission for voice and wake word."
            )
        }

        val a11y = runCatching {
            JarvisAccessibilityService.isAccessibilityServiceEnabled(context)
        }.getOrDefault(false)
        findings += if (a11y) {
            Finding(name, Severity.OK, "Screen automation armed", "Accessibility service is on.")
        } else {
            Finding(
                name, Severity.WARN, "Screen automation off",
                "Accessibility service disabled.",
                "Enable it to let AURIX tap and type inside other apps."
            )
        }
        return findings
    }
}

/**
 * Agent 3 - runtime fault log.
 *
 * Reads what actually failed during use, plus per-provider health counters.
 * This is the only agent whose output changes minute to minute, and it is the
 * one that answers the question the owner really asks: "why did that not
 * work just now?"
 */
object HealthAgent : DiagnosticAgent {
    override val name = "health"

    private const val WINDOW_MS = 10 * 60 * 1000L

    /*
     * Recent faults as one line. Written as an explicit loop: the same text
     * used to be built with a joinToString lambda, and a generic-inference
     * failure there took the whole build down. Diagnostics code must be the
     * most boring code in the app.
     */
    private fun describeRecent(limit: Int): String {
        val out = StringBuilder()
        for (entry in DiagnosticsLog.recent(limit)) {
            if (out.isNotEmpty()) out.append(" | ")
            out.append(entry.source).append(": ").append(entry.message)
        }
        return if (out.isEmpty()) "no details captured" else out.toString()
    }

    override fun inspect(context: Context): List<Finding> {
        val runtime = JarvisRuntime.get(context)
        val findings = mutableListOf<Finding>()

        val recentFailures = DiagnosticsLog.countSince(System.currentTimeMillis() - WINDOW_MS)
        findings += when {
            recentFailures == 0 -> Finding(name, Severity.OK, "No recent faults", "Clean for 10 minutes.")
            recentFailures < 3 -> Finding(
                name, Severity.WARN, "$recentFailures fault(s) in 10 minutes",
                describeRecent(2)
            )
            else -> Finding(
                name, Severity.FAIL, "$recentFailures faults in 10 minutes",
                describeRecent(3),
                "Something is repeatedly breaking - share this list."
            )
        }

        val now = System.currentTimeMillis()
        ProviderRegistry.enabledFor(Capability.CHAT).forEach { cfg ->
            val m = runCatching { runtime.health.metrics(cfg.providerId) }.getOrNull() ?: return@forEach
            if (m.consecutiveFailures == 0 && m.cooldownUntilMs <= now) return@forEach
            val cooling = ((m.cooldownUntilMs - now) / 1000L).coerceAtLeast(0L)
            findings += Finding(
                name, if (cooling > 0) Severity.WARN else Severity.OK,
                "Provider ${cfg.providerId} degraded",
                "state=${m.state.name}, failures=${m.consecutiveFailures}" +
                    if (cooling > 0) ", cooling ${cooling}s" else "",
                if (m.state.name == "AUTH_FAILED") "That key looks invalid - replace it." else null
            )
        }
        return findings
    }
}

/** Full report plus a chat-ready rendering. */
data class DiagnosticReport(val findings: List<Finding>, val generatedAtMs: Long) {

    val failures: List<Finding> get() = findings.filter { it.severity == Severity.FAIL }
    val warnings: List<Finding> get() = findings.filter { it.severity == Severity.WARN }
    val healthy: Boolean get() = failures.isEmpty()

    /**
     * Chat rendering. Problems first and OK lines collapsed to a count: a
     * wall of green ticks trains the owner to skip the one red line.
     */
    fun toChatMessage(): String {
        val sb = StringBuilder()
        sb.append(if (healthy) "Self-check passed, sir." else "Self-check found problems, sir.")
        sb.append("\n")
        failures.forEach { f ->
            sb.append("\nFAIL  ${f.title}\n      ${f.detail}")
            f.fix?.let { sb.append("\n      Fix: $it") }
        }
        warnings.forEach { f ->
            sb.append("\nWARN  ${f.title}\n      ${f.detail}")
            f.fix?.let { sb.append("\n      Fix: $it") }
        }
        val okCount = findings.count { it.severity == Severity.OK }
        if (okCount > 0) sb.append("\n\n$okCount check(s) passed.")
        return sb.toString()
    }
}

/** Runs every agent. One bad agent must never take the report down. */
object SelfCheck {

    val agents: List<DiagnosticAgent> =
        listOf(CrashAgent, RegistryAgent, PermissionAgent, CapabilityAgent, HealthAgent)

    fun run(context: Context): DiagnosticReport {
        val findings = agents.flatMap { agent ->
            runCatching { agent.inspect(context) }.getOrElse {
                listOf(
                    Finding(
                        agent.name, Severity.WARN, "Agent '${agent.name}' could not run",
                        it.message ?: it::class.java.simpleName
                    )
                )
            }
        }
        return DiagnosticReport(findings, System.currentTimeMillis())
    }

    /**
     * Startup pass. Only FAIL-level findings are logged: a boot that prints
     * ten warnings every time is noise, and noise is how the SOS location bug
     * survived this long.
     */
    fun runAtStartup(context: Context) {
        runCatching {
            run(context).failures.forEach {
                DiagnosticsLog.record("startup", "${it.title}: ${it.detail}")
            }
        }
    }
}
