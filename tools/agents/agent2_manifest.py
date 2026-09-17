#!/usr/bin/env python3
"""Agent 2 - manifest vs code consistency (v2).

v1 false positives fixed:
 - camelCase foregroundServiceType -> SCREAMING_SNAKE mapping was wrong
   (specialUse must map to SPECIAL_USE, not SPECIALUSE)
 - Build.VERSION_CODES.S matched the permission regex
 - permission strings that are only metadata in ToolRegistry are not requests
"""
import os, re, sys, xml.etree.ElementTree as ET

APP = "/data/AURIX-Android-AI/app"
MANIFEST = f"{APP}/src/main/AndroidManifest.xml"
SRC = f"{APP}/src/main/java"
NS = "{http://schemas.android.com/apk/res/android}"

root = ET.parse(MANIFEST).getroot()
package = "com.jarvis.ai"

sources = {}
for dirpath, _, names in os.walk(SRC):
    for n in names:
        if n.endswith(".kt"):
            p = os.path.join(dirpath, n)
            sources[p] = open(p, encoding="utf-8").read()

problems, notes = [], []

components = [
    (tag, el.get(NS + "name"))
    for tag in ("service", "receiver", "activity", "provider")
    for el in root.iter(tag)
    if el.get(NS + "name")
]
blob = "\n".join(sources.values())
for tag, name in components:
    fq = package + name if name.startswith(".") else name
    simple = fq.rsplit(".", 1)[-1]
    if not re.search(r"\bclass\s+" + re.escape(simple) + r"\b", blob):
        problems.append(f"<{tag}> {name} has no matching class")

declared = {el.get(NS + "name") for el in root.iter("uses-permission")}

# Only count permissions the app actually CHECKS or REQUESTS at runtime.
RUNTIME_RE = re.compile(
    r"(?:checkSelfPermission\([^)]*?|requestPermissions?\([^)]*?|shouldShowRequestPermissionRationale\([^)]*?)"
    r"Manifest\.permission\.([A-Z_]+)",
    re.S,
)
checked = set()
for src in sources.values():
    checked |= set(RUNTIME_RE.findall(src))
    checked |= set(re.findall(r"Manifest\.permission\.([A-Z_]{4,})", src))

for perm in sorted(checked):
    full = "android.permission." + perm
    if full not in declared:
        problems.append(f"permission {perm} checked in code but NOT declared")

# Metadata-only permission strings (ToolRegistry) are informational.
for src_path, src in sources.items():
    for perm in re.findall(r'permission = "android\.permission\.([A-Z_]+)"', src):
        if "android.permission." + perm not in declared:
            notes.append(f"{os.path.basename(src_path)}: tool metadata cites {perm} (special-access, not a manifest grant)")

CAMEL = re.compile(r"(?<!^)(?=[A-Z])")
for el in root.iter("service"):
    fst = el.get(NS + "foregroundServiceType")
    if not fst:
        continue
    for part in fst.split("|"):
        need = "android.permission.FOREGROUND_SERVICE_" + CAMEL.sub("_", part.strip()).upper()
        if need not in declared:
            problems.append(f"service {el.get(NS + 'name')} type '{part}' needs {need}")

print(f"AGENT 2 (manifest v2): {len(components)} components, {len(declared)} permissions")
for p in problems:
    print("  FAIL", p)
for n in notes:
    print("  NOTE", n)
if not problems:
    print("  OK - manifest and code agree")
sys.exit(1 if problems else 0)
