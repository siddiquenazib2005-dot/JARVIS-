#!/usr/bin/env python3
"""Agent 4 - wiring checker.

The project's recurring bug is not bad syntax, it is code that exists but is
never reached. This agent checks that each feature's entry point is actually
called from somewhere, and that newly referenced members really exist.
"""
import os, re, sys

ROOT = "/data/AURIX-Android-AI/app/src/main/java"
PKG = os.path.join(ROOT, "com/jarvis/ai")

sources = {}
for dirpath, _, names in os.walk(ROOT):
    for n in names:
        if n.endswith(".kt"):
            p = os.path.join(dirpath, n)
            sources[p] = open(p, encoding="utf-8").read()
blob = "\n".join(sources.values())

def defined(pattern):
    return re.search(pattern, blob) is not None

def used(name, exclude_substr=None):
    """True when `name` appears outside the file that defines it."""
    hits = 0
    for path, src in sources.items():
        if exclude_substr and exclude_substr in path:
            continue
        hits += len(re.findall(r"\b" + re.escape(name) + r"\b", src))
    return hits > 0

checks = []

# --- feature entry points must be CALLED, not merely defined -----------------
checks.append(("EmailSender reachable from chat",
               used("emailSender", "tools/EmailSender.kt")))
checks.append(("emailRequest seam called",
               used("emailRequest", "tools/QuickCommandRouter.kt")))
checks.append(("PhotoVision reachable from chat",
               used("photoVision", "tools/PhotoVision.kt")))
checks.append(("lastPhotoQuestion seam called",
               used("lastPhotoQuestion", "tools/QuickCommandRouter.kt")))
checks.append(("WakeWordService started somewhere",
               used("WakeWordService", "service/WakeWordService.kt")))
checks.append(("send_email dispatched in executor",
               '"send_email" -> executeSendEmail' in blob))
checks.append(("send_email registered for the brain",
               '"send_email" to ToolDefinition' in blob))
checks.append(("PersistentVectorStore wired as durable store",
               "durableStore" in blob and "PersistentVectorStore(" in blob))
checks.append(("LocalEmbeddingProvider actually used",
               used("FallbackEmbeddingProvider", "memory/vector/OnDeviceMemory.kt")))
checks.append(("ToolSchema exposed to the router",
               used("ToolSchema", "orchestrator/ToolSchema.kt")))
checks.append(("routeTools called by the orchestrator",
               used("routeTools", "provider/ProviderRouter.kt")))
checks.append(("soft-fail fallthrough wired",
               used("isSoftFail", "tools/QuickCommandRouter.kt")))

# --- runtime self-check must itself be wired ---------------------------------
checks.append(("SelfCheck reachable from chat",
               used("SelfCheck", "diagnostics/SelfCheck.kt")))
checks.append(("SelfCheck runs at startup",
               "SelfCheck.runAtStartup" in blob))
checks.append(("DiagnosticsLog fed by command router",
               "DiagnosticsLog.record" in open(
                   os.path.join(PKG, "tools/QuickCommandRouter.kt"), encoding="utf-8").read()))
checks.append(("DiagnosticsLog fed by tool executor",
               "DiagnosticsLog.record" in open(
                   os.path.join(PKG, "orchestrator/ToolExecutor.kt"), encoding="utf-8").read()))
checks.append(("SUPPORTED_TOOLS matches executor branches", True))

# --- members referenced by the new code must exist ---------------------------
members = [
    ("EmailSender.SETUP_HINT", r"const val SETUP_HINT"),
    ("EmailSender.Outcome.Sent", r"class Sent\("),
    ("PhotoVision.Outcome.Ready", r"class Ready\("),
    ("WakeWordService.start", r"fun start\(context: Context\)"),
    ("WakeWordService.stop", r"fun stop\(context: Context\)"),
    ("EmailRequest data class", r"data class EmailRequest"),
    ("masterOrchestrator.analyzeImage", r"suspend fun analyzeImage\("),
    ("finishSideChannelTurn helper", r"suspend fun finishSideChannelTurn\("),
    ("ToolExecutor.SUPPORTED_TOOLS", r"val SUPPORTED_TOOLS"),
    ("DiagnosticReport.toChatMessage", r"fun toChatMessage\("),
    ("five runtime agents registered", r"listOf\(CrashAgent, RegistryAgent, PermissionAgent, CapabilityAgent, HealthAgent\)"),
    ("CrashGuard installed in MainActivity", r"CrashGuard\.install\("),
    ("CrashGuard.report reachable from chat", r"CrashGuard\.report\(app\)"),
]

# the registry agent is only trustworthy if the two lists really do agree
exec_src = open(os.path.join(PKG, "orchestrator/ToolExecutor.kt"), encoding="utf-8").read()
branches = set(re.findall(r'^\s+"([a-z_]+)" -> execute', exec_src, re.M))
declared_set = set(re.findall(r'^\s+"([a-z_]+)",?$', exec_src.split("SUPPORTED_TOOLS")[-1], re.M))
checks = [c for c in checks if c[0] != "SUPPORTED_TOOLS matches executor branches"]
checks.append((f"SUPPORTED_TOOLS == executor branches ({len(branches)} tools)",
               branches == declared_set))
for label, pattern in members:
    checks.append((f"{label} defined", defined(pattern)))

fails = [label for label, ok in checks if not ok]
print(f"AGENT 4 (wiring): {len(checks)} checks")
for label, ok in checks:
    print(f"  {'OK  ' if ok else 'FAIL'} {label}")
sys.exit(1 if fails else 0)
