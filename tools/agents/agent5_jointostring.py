"""Agent 5 - joinToString receiver audit.

The Kotlin error we keep hitting prints the joinToString candidate list, which
happens when the receiver is NOT an Iterable (a Map or a Flow, for example) or
when the transform lambda does not yield a CharSequence. This agent finds the
receiver expression of every joinToString call and tries to classify it.
"""
import os, re

ROOT = "/data/AURIX-Android-AI/app/src/main/java"
PKG = os.path.join(ROOT, "com/jarvis/ai")

# declarations we can see: val NAME: TYPE = ... / val NAME = EXPR
MAP_HINTS = ("Map<", "mapOf", "MutableMap", "HashMap", "toMap(", "associate")
FLOW_HINTS = ("Flow<", "flow {", "asFlow", "channelFlow", "stateIn")

findings = []

for dirpath, _dirs, files in os.walk(PKG):
    for fname in files:
        if not fname.endswith(".kt"):
            continue
        path = os.path.join(dirpath, fname)
        rel = os.path.relpath(path, PKG)
        lines = open(path, encoding="utf-8").read().split("\n")
        for idx, line in enumerate(lines, start=1):
            if "joinToString" not in line or line.strip().startswith(("*", "//")):
                continue
            before = line.split("joinToString")[0]
            m = re.search(r"([A-Za-z_][A-Za-z0-9_.()\[\]]*)\.$", before.strip())
            recv = m.group(1) if m else "(chained)"
            root = recv.split(".")[0].split("(")[0]

            # look backwards for the declaration of the root receiver
            decl = None
            for back in range(idx - 1, max(0, idx - 120), -1):
                d = re.search(r"\b(?:val|var)\s+" + re.escape(root) + r"\b[^=]*=(.*)", lines[back - 1])
                if d:
                    decl = lines[back - 1].strip()
                    break
                p = re.search(r"\b" + re.escape(root) + r"\s*:\s*([A-Za-z<>?, .]+)", lines[back - 1])
                if p and ("val " in lines[back - 1] or "var " in lines[back - 1] or "(" in lines[back - 1]):
                    decl = lines[back - 1].strip()
                    break

            verdict = "ok"
            if decl:
                if any(h in decl for h in MAP_HINTS) and not recv.endswith(("entries", "keys", "values")):
                    verdict = "MAP RECEIVER - joinToString does not exist on Map"
                elif any(h in decl for h in FLOW_HINTS):
                    verdict = "FLOW RECEIVER - joinToString does not exist on Flow"
            if verdict != "ok":
                findings.append((rel, idx, recv, verdict, decl))

            # transform lambda that clearly does not return a string
            if re.search(r"joinToString\([^)]*\)\s*\{\s*$", line):
                pass

print("AGENT 5 (joinToString receivers): scanned repo")
if not findings:
    print("  OK - no Map/Flow receivers found")
for rel, idx, recv, verdict, decl in findings:
    print("  FAIL %s:%d receiver '%s' -> %s" % (rel, idx, recv, verdict))
    print("        decl: %s" % (decl or "?"))
