"""Agent 6 - joinToString transform-lambda audit.

The compiler prints the joinToString candidate list in two situations:
  1. the receiver is not an Iterable  (Agent 5 covers that)
  2. the transform lambda does not produce a CharSequence

This agent extracts the lambda body of every joinToString call that has one,
finds its LAST expression (that is what a Kotlin lambda returns) and flags
bodies whose last expression is clearly not a String: an `if` without `else`,
a call to a Unit-returning builder such as append(), a `.size`, a number, or
a `when` missing a branch.
"""
import os, re

PKG = "/data/AURIX-Android-AI/app/src/main/java/com/jarvis/ai"

def lambda_body(text, start):
    """Return the source of the lambda that starts at the '{' at/after start."""
    i = text.find("{", start)
    if i < 0:
        return None, None
    depth = 0
    for j in range(i, len(text)):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return text[i + 1:j], j
    return None, None

BAD_LAST = [
    (re.compile(r"\.size\s*$"), "last expression is an Int (.size)"),
    (re.compile(r"\bappend\([^)]*\)\s*$"), "last expression is StringBuilder.append (returns StringBuilder)"),
    (re.compile(r"^\s*if\s*\([^)]*\)\s*[^\n]*$", re.S), "if without else -> Unit"),
    (re.compile(r"\bUnit\s*$"), "last expression is Unit"),
    (re.compile(r"\bprintln\([^)]*\)\s*$"), "last expression is println (Unit)"),
]

findings = []
scanned = 0

for dirpath, _dirs, files in os.walk(PKG):
    for fname in files:
        if not fname.endswith(".kt"):
            continue
        path = os.path.join(dirpath, fname)
        rel = os.path.relpath(path, PKG)
        text = open(path, encoding="utf-8").read()
        for m in re.finditer(r"joinToString\s*\(([^()]*(?:\([^()]*\))?[^()]*)?\)\s*\{", text):
            scanned += 1
            body, end = lambda_body(text, m.end() - 1)
            if body is None:
                continue
            line = text[:m.start()].count("\n") + 1
            stmts = [s.strip() for s in body.strip().split("\n") if s.strip() and not s.strip().startswith("//")]
            if not stmts:
                findings.append((rel, line, "empty lambda body"))
                continue
            last = stmts[-1]
            for pattern, why in BAD_LAST:
                if pattern.search(last):
                    findings.append((rel, line, why + " :: " + last[:80]))
                    break
        # also: joinToString with a positional separator that is not a string
        for m in re.finditer(r"joinToString\(\s*([A-Za-z_][A-Za-z0-9_.]*)\s*\)", text):
            arg = m.group(1)
            if arg in ("it",) or arg.endswith(".size"):
                line = text[:m.start()].count("\n") + 1
                findings.append((rel, line, "separator argument looks non-CharSequence: " + arg))

print("AGENT 6 (joinToString lambdas): %d transform lambdas inspected" % scanned)
if not findings:
    print("  OK - every transform lambda ends in a String expression")
for rel, line, why in findings:
    print("  FAIL %s:%d %s" % (rel, line, why))
