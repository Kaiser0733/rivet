#!/usr/bin/env python3
"""CI fix CF-2: every `org.junit.Assert` member a test calls must be imported.

CI caught `assertEquals` in ProviderErrorClassificationTest at :89 and :91 with only
`assertTrue`, `assertFalse` and `org.junit.Test` imported. The earlier import sweep could not
see it: it indexed declarations made in this repo, and `assertEquals` comes from a jar.

This checks the test source set for that shape:

  a call to an `org.junit.Assert` static member, written unqualified, in a file that neither
  imports that member nor wildcard-imports `org.junit.Assert`.

Fully qualified calls (`org.junit.Assert.assertEquals(...)`) and `Assert.assertEquals(...)`
behind `import org.junit.Assert` need no member import and are reported separately as cleared.

usage: python3 round3/junit-import-sweep.py [--verbose]
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
TEST_ROOT = ROOT / "app/src/test"

# Every public static member of org.junit.Assert in junit 4.13.2.
ASSERT_MEMBERS = [
    "assertArrayEquals", "assertEquals", "assertNotEquals", "assertTrue", "assertFalse",
    "assertNull", "assertNotNull", "assertSame", "assertNotSame", "assertThat", "fail",
    "assertThrows", "assertDoesNotThrow", "assertAll", "assertTimeout",
    "assertTimeoutPreemptively", "assertIterableEquals", "assertLinesMatch",
]
ASSERT_CLASS = "org.junit.Assert"


def strip_noise(text):
    """Blank comments and string/char literals, keeping line structure."""
    out, i, n, state = [], 0, len(text), "code"
    while i < n:
        ch = text[i]
        if state == "code":
            if text.startswith("//", i):
                state = "line"; out.append("  "); i += 2; continue
            if text.startswith("/*", i):
                state = "block"; out.append("  "); i += 2; continue
            if text.startswith('"""', i):
                state = "raw"; out.append("   "); i += 3; continue
            if ch == '"':
                state = "string"; out.append(" "); i += 1; continue
            if ch == "'":
                state = "char"; out.append(" "); i += 1; continue
            out.append(ch); i += 1
        elif state == "line":
            state = "code" if ch == "\n" else state
            out.append("\n" if ch == "\n" else " "); i += 1
        elif state == "block":
            if text.startswith("*/", i):
                state = "code"; out.append("  "); i += 2; continue
            out.append("\n" if ch == "\n" else " "); i += 1
        elif state in ("string", "char"):
            if ch == "\\":
                out.append("  "); i += 2; continue
            closer = '"' if state == "string" else "'"
            if ch == closer:
                state = "code"; out.append(" "); i += 1; continue
            out.append("\n" if ch == "\n" else " "); i += 1
        elif state == "raw":
            if text.startswith('"""', i):
                state = "code"; out.append("   "); i += 3; continue
            out.append("\n" if ch == "\n" else " "); i += 1
    return "".join(out)


def parse(path):
    code = strip_noise(path.read_text(encoding="utf-8"))
    imports = set()
    wildcards = set()
    for line in code.split("\n"):
        m = re.match(r"\s*import\s+([\w.]+?)(\.\*)?\s*$", line)
        if m:
            (wildcards if m.group(2) else imports).add(m.group(1))
    return code, imports, wildcards


def main():
    verbose = "--verbose" in sys.argv
    files = sorted(TEST_ROOT.rglob("*.kt"))
    problems, cleared = [], []
    checked_members = 0

    for path in files:
        code, imports, wildcards = parse(path)
        lines = code.split("\n")
        wildcard_ok = ASSERT_CLASS in wildcards
        class_imported = ASSERT_CLASS in imports
        for member in ASSERT_MEMBERS:
            # unqualified call: not preceded by a dot, so `Assert.x(` and `pkg.x(` are excluded
            pattern = r"(?<![\w.])" + re.escape(member) + r"\s*(?:<[^>]*>)?\s*\("
            hits = [i + 1 for i, text in enumerate(lines) if re.search(pattern, text)]
            if not hits:
                continue
            checked_members += 1
            # An unqualified call resolves to any imported member of that name (a test that
            # imported MatcherAssert.assertThat would be just as valid), or to an object
            # wildcard such as `import org.junit.Assert.*`.
            member_imported = any(imp.endswith("." + member) for imp in imports)
            object_wildcard = wildcard_ok or any(w.endswith(".Assert") for w in wildcards)
            if member_imported or object_wildcard:
                if verbose:
                    print(f"  ok    {path.relative_to(ROOT)}:{hits[0]}  {member}"
                          f" ({'object wildcard' if object_wildcard else 'member import'})")
                continue
            problems.append((path.relative_to(ROOT), hits, member))
            continue
        # `Assert.member(` needs the class imported (or a wildcard on the class's package)
        if re.search(r"(?<![\w.])Assert\s*\.\s*(" + "|".join(ASSERT_MEMBERS) + r")\s*\(", code):
            if class_imported or "org.junit" in wildcards:
                cleared.append((path.relative_to(ROOT), "Assert.x( with the class imported"))
            else:
                problems.append((path.relative_to(ROOT), [0], "Assert.x( without importing org.junit.Assert"))

    print(f"scanned {len(files)} Kotlin files under {TEST_ROOT.relative_to(ROOT)}")
    print(f"(file, member) pairs resolved by an import or an object wildcard: {checked_members}"
          f"  (member list: the {len(ASSERT_MEMBERS)} statics junit 4.13.2 exposes)")
    if cleared:
        print(f"cleared without a member import: {len(cleared)}")
        for path, why in cleared:
            print(f"  {path}  ({why})")
    print()
    if problems:
        print(f"== MISSING IMPORT: {len(problems)} ==")
        for path, hits, member in problems:
            where = ", ".join(f":{line}" for line in hits)
            print(f"  {path}{where}  {member} is called but {ASSERT_CLASS}.{member} is not imported")
        return 1
    print("== no unqualified org.junit.Assert call is missing its import ==")
    return 0


if __name__ == "__main__":
    sys.exit(main())
