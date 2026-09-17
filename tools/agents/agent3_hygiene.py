#!/usr/bin/env python3
"""Agent 3 - Kotlin file hygiene (v2).

v1 false positives fixed:
 - file-header KDoc followed by a blank line is normal, not orphaned
 - a local `val state` inside a function is fine; only a CLASS PROPERTY named
   `state` inside an apply{} block caused the v1.10 build failure
 - "suspicious ending" fired on files ending in a top-level val; dropped in
   favour of the brace/paren balance check, which is the real truncation signal
"""
import os, re, sys

ROOT = "/data/AURIX-Android-AI/app/src/main/java"

def strip_noise(src):
    src = re.sub(r'""".*?"""', '""', src, flags=re.S)
    src = re.sub(r"\\.", "", src)
    src = re.sub(r'"[^"\n]*"', '""', src)
    src = re.sub(r"'[^'\n]*'", "''", src)
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)

problems, warnings, count = [], [], 0

for dirpath, _, names in os.walk(ROOT):
    for n in sorted(names):
        if not n.endswith(".kt"):
            continue
        count += 1
        path = os.path.join(dirpath, n)
        raw = open(path, encoding="utf-8").read()
        code = strip_noise(raw)
        rel = path.split("com/jarvis/ai/")[-1]

        if code.count("{") != code.count("}"):
            problems.append(f"{rel} braces {code.count('{')}/{code.count('}')} - truncated write?")
        if code.count("(") != code.count(")"):
            problems.append(f"{rel} parens {code.count('(')}/{code.count(')')} - truncated write?")

        lines = raw.splitlines()
        in_apply = 0
        for i, line in enumerate(lines, 1):
            if any(ord(c) > 127 for c in line):
                warnings.append(f"{rel}:{i}")
            # the v1.10 crash pattern: assigning a property named `state`
            # inside apply{} on a Drawable, which shadows getState()
            if re.search(r"\.apply\s*\{", line):
                in_apply = i
            if in_apply and i - in_apply < 30 and re.match(r"\s*(val|var)\s+state\b", line):
                problems.append(f"{rel}:{i} `state` declared inside apply{{}} - shadowing hazard")

        # orphaned KDoc = doc comment whose next non-blank line is another doc
        # comment (i.e. it documents nothing), excluding the file header.
        opener = None
        for i, line in enumerate(lines):
            stripped = line.strip()
            if stripped.startswith("/**"):
                opener = "kdoc"
            elif stripped.startswith("/*"):
                opener = "block"
            if stripped != "*/" or i < 3 or opener != "kdoc":
                continue
            nxt = next((l.strip() for l in lines[i + 1:] if l.strip()), "")
            if nxt.startswith("/**"):
                problems.append(f"{rel}:{i + 1} KDoc documents nothing (next item is another KDoc)")

print(f"AGENT 3 (hygiene v2): scanned {count} files")
for p in problems:
    print("  FAIL", p)
if warnings:
    print(f"  INFO {len(warnings)} non-ASCII lines (pre-existing, harmless in UTF-8 sources)")
if not problems:
    print("  OK - no structural damage")
sys.exit(1 if problems else 0)
