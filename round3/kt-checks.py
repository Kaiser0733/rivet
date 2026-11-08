"""Structural checks for changed Kotlin files on a host with no JDK.

Not a compiler. It checks the two failure modes reading alone tends to miss:
unbalanced delimiters (including inside string templates, where a naive counter
desyncs) and a declaration that is referenced but never defined in its package.

usage: python3 round3/kt-checks.py balance <file.kt>...
       python3 round3/kt-checks.py symbols <dir> <Name>...
"""
import os
import re
import sys

NORMAL, STRING, RAW, CHAR, LINE_COMMENT, BLOCK_COMMENT = range(6)
OPEN, CLOSE = "({[", ")}]"


def walk(text):
    """Yields (index, char, context) for characters that are code, not literals/comments."""
    stack = [NORMAL]          # NORMAL or ("template", brace_depth)
    i = 0
    while i < len(text):
        ctx = stack[-1]
        ch = text[i]
        if ctx == NORMAL:
            if ch == "/" and i + 1 < len(text) and text[i + 1] == "/":
                stack.append(LINE_COMMENT)
                i += 2
                continue
            if ch == "/" and i + 1 < len(text) and text[i + 1] == "*":
                stack.append(BLOCK_COMMENT)
                i += 2
                continue
            if text.startswith('"""', i):
                stack.append(RAW)
                i += 3
                continue
            if ch == '"':
                stack.append(STRING)
                i += 1
                continue
            if ch == "'":
                stack.append(CHAR)
                i += 1
                continue
            if ch == "}" and len(stack) > 1 and stack[-1] == NORMAL and _is_template(stack):
                stack.pop()          # template expression closed
                i += 1
                continue
            yield i, ch, "normal"
            i += 1
            continue
        if ctx == LINE_COMMENT:
            if ch == "\n":
                stack.pop()
            i += 1
            continue
        if ctx == BLOCK_COMMENT:
            if ch == "*" and i + 1 < len(text) and text[i + 1] == "/":
                stack.pop()
                i += 2
                continue
            i += 1
            continue
        if ctx == CHAR:
            if ch == "\\":
                i += 2
                continue
            if ch == "'":
                stack.pop()
            i += 1
            continue
        if ctx == RAW:
            if text.startswith('"""', i):
                stack.pop()
                i += 3
                continue
            i += 1
            continue
        if ctx == STRING:
            if ch == "\\":
                i += 2
                continue
            if text.startswith("${", i):
                stack.append(["template", 0])
                i += 2
                continue
            if ch == '"':
                stack.pop()
                i += 1
                continue
            i += 1
            continue
        # inside a template expression we are back to code, but braces belong to the template
        if ch == "{":
            stack[-1][1] += 1
            yield i, ch, "template"
            i += 1
            continue
        if ch == "}":
            if stack[-1][1] == 0:
                stack.pop()
                i += 1
                continue
            stack[-1][1] -= 1
            yield i, ch, "template"
            i += 1
            continue
        if ch in "([":
            yield i, ch, "template"
        i += 1


def _is_template(stack):
    return False  # templates are popped in their own branch; kept for clarity


def balance(paths):
    problems = 0
    for path in paths:
        text = open(path, encoding="utf-8").read()
        pairs = {")": "(", "]": "[", "}": "{"}
        stack = []
        for index, ch, ctx in walk(text):
            if ctx == "template":
                continue
            if ch in OPEN:
                stack.append((ch, index))
            elif ch in CLOSE:
                if not stack or stack[-1][0] != pairs[ch]:
                    line = text.count("\n", 0, index) + 1
                    print(f"FAIL {path}:{line}: unmatched {ch!r}")
                    problems += 1
                    break
                stack.pop()
        else:
            if stack:
                ch, index = stack[-1]
                line = text.count("\n", 0, index) + 1
                print(f"FAIL {path}:{line}: unclosed {ch!r}")
                problems += 1
            else:
                print(f"PASS {path}: delimiters balanced")
    return problems


def symbols(directory, names):
    sources = {}
    for root, _, files in os.walk(directory):
        for name in files:
            if name.endswith(".kt"):
                full = os.path.join(root, name)
                sources[full] = open(full, encoding="utf-8").read()
    problems = 0
    for wanted in names:
        # Modifiers (private, internal, suspend, inline, ...) sit between the line start and
        # the declaration keyword, so match the keyword+name pair rather than the line shape.
        decl = re.compile(
            r"\b(?:class|object|interface|fun|val|var)\s+" + re.escape(wanted) + r"\b")
        hits = [p for p, t in sources.items() if decl.search(t)]
        if hits:
            print(f"PASS {wanted}: declared in " + ", ".join(sorted(os.path.basename(h) for h in hits)))
        else:
            print(f"FAIL {wanted}: no declaration found under {directory}")
            problems += 1
    return problems


if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "balance":
        raise SystemExit(1 if balance(sys.argv[2:]) else 0)
    if mode == "symbols":
        raise SystemExit(1 if symbols(sys.argv[2], sys.argv[3:]) else 0)
    raise SystemExit(2)
