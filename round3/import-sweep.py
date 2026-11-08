#!/usr/bin/env python3
"""A2 CI fix: find cross-package references with no import (the shape CI caught in ContextBudget).

CI caught `config.manualContextWindowForModel()` at ContextBudget.kt:66 with no import of
`com.kaiser.rivet.provider.manualContextWindowForModel`. Nothing on this host can compile Kotlin,
so this script reproduces the *resolution rule* well enough to audit the branch for the same
shape:

  a top-level declaration that is importable (not `private`), declared in exactly ONE file,
  referenced by name in a file of a DIFFERENT package that neither imports it nor imports its
  package with a wildcard.

Names declared more than once are skipped: a reference to them may resolve to either and the
check cannot tell which. That is reported, not hidden.

usage: python3 round3/import-sweep.py [--verbose]
"""
import collections
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCE_ROOTS = [
    ROOT / "app/src/main/java",
    ROOT / "app/src/test/java",
    ROOT / "terminal-emulator/src/main/java",
]
BASE = "c0c49c8"

MODIFIERS = (
    "public", "internal", "private", "protected", "open", "abstract", "sealed", "data", "enum",
    "value", "annotation", "inline", "suspend", "operator", "infix", "const", "lateinit",
    "override", "actual", "expect", "external", "tailrec", "vararg", "inner", "fun",
)

DECL = re.compile(
    r"^(?P<mods>(?:(?:" + "|".join(MODIFIERS) + r")\s+)*)"
    r"(?P<kind>class|interface|object|typealias|val|var|fun)\s+"
    r"(?P<rest>.+)$"
)
# `await` is declared in this repo as an extension (Call.await, OkHttpClient.await) AND is a
# member of Deferred/Job/AgentApprovalGate. Every reference the sweep found is the member call;
# a caller of the project extension would need the import and CI would report it.
MEMBER_NAMES = {"await"}

RECEIVER = re.compile(r"^(?:<[^>]*>\s*)?(?P<receiver>[A-Za-z_][\w.<>, ?]*)\.")


def strip_noise(text):
    """Blank out comments and string/char literals, keeping line structure and offsets."""
    out = []
    i, n = 0, len(text)
    state = "code"
    while i < n:
        ch = text[i]
        if state == "code":
            if text.startswith("//", i):
                state = "line"
                out.append("  ")
                i += 2
                continue
            if text.startswith("/*", i):
                state = "block"
                out.append("  ")
                i += 2
                continue
            if text.startswith('"""', i):
                state = "raw"
                out.append("   ")
                i += 3
                continue
            if ch == '"':
                state = "string"
                out.append(" ")
                i += 1
                continue
            if ch == "'":
                state = "char"
                out.append(" ")
                i += 1
                continue
            out.append(ch)
            i += 1
        elif state == "line":
            if ch == "\n":
                state = "code"
                out.append("\n")
            else:
                out.append(" ")
            i += 1
        elif state == "block":
            if text.startswith("*/", i):
                state = "code"
                out.append("  ")
                i += 2
                continue
            out.append("\n" if ch == "\n" else " ")
            i += 1
        elif state == "string":
            if ch == "\\":
                out.append("  ")
                i += 2
                continue
            if ch == '"':
                state = "code"
                out.append(" ")
                i += 1
                continue
            out.append("\n" if ch == "\n" else " ")
            i += 1
        elif state == "raw":
            if text.startswith('"""', i):
                state = "code"
                out.append("   ")
                i += 3
                continue
            out.append("\n" if ch == "\n" else " ")
            i += 1
        elif state == "char":
            if ch == "\\":
                out.append("  ")
                i += 2
                continue
            if ch == "'":
                state = "code"
                out.append(" ")
                i += 1
                continue
            out.append(" ")
            i += 1
    return "".join(out)


def depth_map(code):
    """Brace depth at the start of each line, ignoring braces inside strings (already blanked)."""
    depths, depth = [], 0
    for line in code.split("\n"):
        depths.append(depth)
        depth += line.count("{") - line.count("}")
    return depths


def parse(path):
    raw = path.read_text(encoding="utf-8")
    code = strip_noise(raw)
    package = ""
    for line in code.split("\n"):
        m = re.match(r"\s*package\s+([\w.]+)", line)
        if m:
            package = m.group(1)
            break
    imports, wildcard = set(), set()
    for line in code.split("\n"):
        m = re.match(r"\s*import\s+([\w.]+)(\.\*)?\s*$", line)
        if not m:
            continue
        if m.group(2):
            wildcard.add(m.group(1))
        else:
            imports.add(m.group(1))
    return package, imports, wildcard, code


def declarations(code, want_private=False):
    """Top-level declarations: unindented lines at depth 0, with their kind."""
    depths = depth_map(code)
    found = []
    for number, line in enumerate(code.split("\n")):
        if depths[number] != 0:
            continue
        # Top-level declarations start in column 0; indented lines at depth 0 are
        # constructor parameters and wrapped signatures, not declarations.
        if line[:1] in (" ", "\t"):
            continue
        text = line.strip()
        if not text or text.startswith(")"):
            continue
        m = DECL.match(text)
        if not m:
            continue
        rest = m.group("rest")
        kind = m.group("kind")
        is_private = "private" in m.group("mods").split()
        if is_private and not want_private:
            continue
        if kind == "fun":
            rest = rest.split("(")[0]
            recv = RECEIVER.match(rest)
            name = recv.group("receiver").split(".")[-1] if False else None
            target = rest
            if recv:
                target = rest[recv.end():]
            name = re.match(r"([A-Za-z_][\w]*)", target)
        else:
            rest = rest.split("(")[0].split(":")[0].split("<")[0]
            name = re.match(r"([A-Za-z_][\w]*)", rest.strip())
        if not name:
            continue
        found.append((name.group(1), kind, is_private) if want_private else (name.group(1), kind))
    return found


def main():
    verbose = "--verbose" in sys.argv
    files = {}
    for root in SOURCE_ROOTS:
        if not root.is_dir():
            continue
        for path in sorted(root.rglob("*.kt")):
            files[path] = parse(path)

    declared = collections.defaultdict(dict)   # name -> package -> kind
    private_names = collections.defaultdict(set)
    for path, (package, _imports, _wild, code) in files.items():
        for name, kind in declarations(code):
            declared[name].setdefault(package, kind)
        for name, kind, is_private in declarations(code, want_private=True):
            if is_private:
                private_names[name].add(package)
    # Only the CI shape is reported: a function (extension or plain) or a type referenced by
    # name from another package. Top-level vals/consts are collected but not reported, because
    # a bare word match on them is mostly local-variable noise.
    REPORTABLE = {"fun", "class", "interface", "object"}

    changed = set()
    try:
        out = subprocess.run(["git", "diff", "--name-only", BASE, "--", "*.kt"],
                             cwd=ROOT, capture_output=True, text=True, check=True).stdout
        changed = {ROOT / line.strip() for line in out.splitlines() if line.strip()}
    except subprocess.CalledProcessError as error:
        print("git diff failed:", error, file=sys.stderr)
        return 2

    ambiguous = [name for name, where in declared.items() if len(where) > 1]
    unique = {name: (next(iter(where)), where[next(iter(where))])
              for name, where in declared.items() if len(where) == 1}

    findings, in_diff, out_of_diff = [], [], []
    filtered_qualified, filtered_member = [], []
    for path, (package, imports, wildcard, code) in files.items():
        used = set(re.findall(r"(?<![\w.])([A-Za-z_][\w]*)\s*[(<]", code))
        used |= set(re.findall(r"\.\s*([A-Za-z_][\w]*)\s*\(", code))
        used |= set(re.findall(r":\s*([A-Za-z_][\w]*)", code))
        for name in sorted(used & unique.keys()):
            decl_package, kind = unique[name]
            if kind not in REPORTABLE:
                continue
            if decl_package == package:
                continue
            if f"{decl_package}.{name}" in imports or decl_package in wildcard:
                continue
            line = code.split("\n")
            first = next((i + 1 for i, text in enumerate(line)
                          if re.search(r"\b" + re.escape(name) + r"\b", text)), 0)
            entry = (str(path.relative_to(ROOT)), first, f"{name} [{kind}]", decl_package,
                     "in diff" if path in changed else "not in diff")
            occurrences = [m.start() for m in re.finditer(r"\b" + re.escape(name) + r"\b", code)]
            qualified = all(
                code[:start].rstrip().endswith(decl_package + ".")
                for start in occurrences) and occurrences
            if qualified:
                filtered_qualified.append(entry)
                continue
            if name in MEMBER_NAMES:
                filtered_member.append(entry)
                continue
            findings.append(entry)
            (in_diff if path in changed else out_of_diff).append(entry)

    print(f"filtered: {len(filtered_qualified)} fully-qualified references, "
          f"{len(filtered_member)} references to a name that is also a library member ({', '.join(sorted(MEMBER_NAMES))})")
    print(f"scanned {len(files)} Kotlin files under {', '.join(str(r.relative_to(ROOT)) for r in SOURCE_ROOTS if r.is_dir())}")
    print(f"top-level importable names: {len(declared)} "
          f"({len(unique)} declared once, {len(ambiguous)} declared more than once and skipped)")
    print()
    for title, rows in (("references in files changed by c0c49c8..HEAD", in_diff),
                        ("references in files NOT changed by this branch", out_of_diff)):
        print(f"== {title}: {len(rows)} ==")
        for path, line, name, decl_package, _ in rows:
            print(f"  {path}:{line}  {name}  (declared in {decl_package})")
        print()
    if verbose:
        print(f"filtered as fully qualified ({len(filtered_qualified)}):")
        for path, line, name, decl_package, _ in filtered_qualified:
            print(f"  {path}:{line}  {decl_package}.{name.split(' [')[0]}")
        print()
        print(f"filtered as a library member name ({len(filtered_member)}):")
        for path, line, name, decl_package, _ in filtered_member:
            print(f"  {path}:{line}  {name}")
        print()
        print("names declared more than once (not checked):")
        for name in sorted(ambiguous):
            where = ", ".join(pkg for pkg, _, _kind in declared[name])
            print(f"  {name}: {where}")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
