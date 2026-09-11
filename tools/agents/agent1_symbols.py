#!/usr/bin/env python3
"""Agent 1 - fully-qualified symbol resolver (v2).

v1 produced false positives: it only indexed class/object/interface, so
@Composable functions, `fun interface`, typealiases and top-level vals looked
missing. It also scanned inside string literals. Fixed here.
"""
import os, re, sys

ROOT = "/data/AURIX-Android-AI/app/src/main/java"

DECL_RE = re.compile(
    r"^\s*(?:public |internal |private |protected |abstract |open |sealed |data |enum |annotation |value |fun )*"
    r"(?:class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)"
)
TYPEALIAS_RE = re.compile(r"^\s*(?:public |internal )?typealias\s+([A-Za-z_][A-Za-z0-9_]*)")
TOPLEVEL_RE = re.compile(
    r"^(?:public |internal |private )?(?:suspend |inline )*(?:fun|val|const val)\s+([A-Za-z_][A-Za-z0-9_]*)"
)
PKG_RE = re.compile(r"^package\s+([\w.]+)")
FQ_RE = re.compile(r"\b(com\.jarvis\.ai(?:\.[a-z][A-Za-z0-9_]*)*)\.([A-Za-z][A-Za-z0-9_]*)\b")

def strip_literals(src):
    src = re.sub(r'""".*?"""', '""', src, flags=re.S)
    src = re.sub(r'"[^"\n]*"', '""', src)
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)

files = []
for dirpath, _, names in os.walk(ROOT):
    for n in names:
        if n.endswith(".kt"):
            files.append(os.path.join(dirpath, n))

decl = {}
for path in files:
    src = open(path, encoding="utf-8").read()
    pkg_m = PKG_RE.search(src)
    pkg = pkg_m.group(1) if pkg_m else ""
    for line in src.splitlines():
        for rx in (DECL_RE, TYPEALIAS_RE, TOPLEVEL_RE):
            m = rx.match(line)
            if m:
                decl.setdefault(m.group(1), set()).add(pkg)
                break

problems = []
for path in files:
    clean = strip_literals(open(path, encoding="utf-8").read())
    for line_no, line in enumerate(clean.splitlines(), 1):
        for pkg, name in FQ_RE.findall(line):
            if not name[0].isupper():
                continue
            if name not in decl:
                problems.append(f"{path}:{line_no} unknown {pkg}.{name}")
            elif pkg not in decl[name]:
                problems.append(
                    f"{path}:{line_no} {name} imported from {pkg} but declared in {sorted(decl[name])}"
                )

print(f"AGENT 1 (symbols v2): {len(files)} files, {len(decl)} declarations indexed")
for p in problems:
    print("  FAIL", p)
if not problems:
    print("  OK - every com.jarvis.ai.* reference resolves")
sys.exit(1 if problems else 0)
