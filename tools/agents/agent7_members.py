#!/usr/bin/env python3
"""AGENT 7 - qualified member audit.

Catches the exact bug class that broke v1.19.2:
    ToolExecutor.SUPPORTED_TOOLS   <- symbol existed in the FILE,
                                      but inside a DIFFERENT class.

Agent 1 indexes declarations file-wide, so it said "resolves".
Agent 7 is stricter: for every `Type.member` reference where `Type` is one of
our own top-level classes/objects, it checks the member is declared inside
THAT type's own body (including its companion object).
"""
import os, re, sys

ROOT = "/data/AURIX-Android-AI/app/src/main/java/com/jarvis/ai"

DECL = re.compile(r"^(?P<indent>[ \t]*)(?:@\w+\s+)*"
                  r"(?:public |internal |private |abstract |open |sealed |data |enum )*"
                  r"(?P<kind>class|object|interface)\s+(?P<name>[A-Z]\w*)")
MEMBER = re.compile(r"^[ \t]*(?:@\w+\s+)*"
                    r"(?:public |internal |private |protected |override |open |abstract |const |lateinit |suspend |inline |operator |external )*"
                    r"(?:val|var|fun)\s+(?:<[^>]*>\s*)?(?P<name>\w+)")
ENUM_ENTRY = re.compile(r"^[ \t]*(?P<name>[A-Z][A-Z0-9_]*)\s*[,;(]")
NESTED = re.compile(r"^[ \t]*(?:@\w+\s+)*"
                    r"(?:public |internal |private |abstract |open |sealed |data |enum )*"
                    r"(?:class|object|interface)\s+(?P<name>[A-Z]\w*)")


def kt_files():
    for base, _, files in os.walk(ROOT):
        for f in files:
            if f.endswith(".kt"):
                yield os.path.join(base, f)


def brace_span(lines, start):
    """Return (end_index_exclusive) of the block opened on/after line `start`."""
    depth = 0
    opened = False
    for i in range(start, len(lines)):
        code = strip_noise(lines[i])
        for ch in code:
            if ch == "{":
                depth += 1
                opened = True
            elif ch == "}":
                depth -= 1
                if opened and depth == 0:
                    return i + 1
        if opened and depth == 0:
            return i + 1
    return len(lines)


def strip_noise(line):
    line = re.sub(r'"(?:\\.|[^"\\])*"', '""', line)
    line = re.sub(r"//.*$", "", line)
    return line


def build_index():
    """type name -> set of member names declared inside its own body."""
    members = {}
    owners = {}
    for path in kt_files():
        lines = open(path, encoding="utf-8").read().split("\n")
        for i, line in enumerate(lines):
            m = DECL.match(strip_noise(line))
            if not m or m.group("indent"):
                continue  # only top-level declarations
            name = m.group("name")
            end = brace_span(lines, i)
            body = lines[i + 1:end]
            found = set()
            for b in body:
                b = strip_noise(b)
                mm = MEMBER.match(b)
                if mm:
                    found.add(mm.group("name"))
                ee = ENUM_ENTRY.match(b)
                if ee:
                    found.add(ee.group("name"))
                nn = NESTED.match(b)
                if nn:
                    found.add(nn.group("name"))
            members.setdefault(name, set()).update(found)
            owners[name] = os.path.relpath(path, ROOT)
    return members, owners


USE = re.compile(r"\b(?P<type>[A-Z]\w*)\.(?P<member>[A-Za-z_]\w*)")
SKIP_TYPES = {"Log", "Build", "Color", "Math", "String", "Int", "Long", "Uri",
              "Intent", "Context", "Settings", "Toast", "View", "R", "Modifier",
              "Arrangement", "Alignment", "FontWeight", "TextAlign", "Dispatchers",
              "System", "Locale", "TimeUnit", "Base64", "JSONObject", "JSONArray"}


def main():
    members, owners = build_index()
    findings = []
    for path in kt_files():
        rel = os.path.relpath(path, ROOT)
        text = open(path, encoding="utf-8").read()
        for n, raw in enumerate(text.split("\n"), 1):
            line = strip_noise(raw)
            if line.strip().startswith(("import ", "package ", "*", "/*")):
                continue
            for m in USE.finditer(line):
                t, mem = m.group("type"), m.group("member")
                if t in SKIP_TYPES or t not in members:
                    continue
                if mem[0].isupper() and mem.isupper() is False and mem in members:
                    continue  # Type.NestedType chain
                if mem in members[t]:
                    continue
                findings.append((rel, n, t, mem, raw.strip()[:90]))

    print("AGENT 7 (qualified members): %d of our types indexed" % len(members))
    if not findings:
        print("  OK - every Type.member reference exists inside that type")
        return 0
    for rel, n, t, mem, src in findings:
        print("  FAIL %s:%d  %s.%s not declared in %s (%s)"
              % (rel, n, t, mem, t, owners.get(t, "?")))
        print("       %s" % src)
    print("  %d suspicious reference(s)" % len(findings))
    return 1


if __name__ == "__main__":
    sys.exit(main())
