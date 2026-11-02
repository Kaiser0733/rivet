#!/usr/bin/env python3
"""Device-fix round (kaiser, 2026-11-02): the refusal copy is plain words or it is not shipped.

There is no JDK here, so ContextRefusalCopyTest cannot run. What can be checked mechanically is the
thing the copy law forbids and the thing a careless edit re-introduces: a number, a piece of jargon,
or the "start a new conversation" dead end coming back into the refusal family.

Reads the three copy strings out of the Kotlin source (joining their string literals the way the
compiler does) and asserts the rules on each. Exits non-zero on any violation.
"""
import pathlib
import re
import sys

SRC = pathlib.Path("app/src/main/java/com/kaiser/rivet/chat/ChatViewModel.kt")
BANNED = ["token", "budget", "context window", "window", "capacity", "new conversation"]


def literals_between(text, start_marker, _end_marker=None):
    """The string literals from the declaration's first line until the run of quoted lines ends."""
    lines = text[text.index(start_marker):].split("\n")
    body, seen = [], False
    for line in lines:
        if '"' in line:
            body.append(line)
            seen = True
        elif seen:
            break
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', "\n".join(body))
    return "".join(p.encode().decode("unicode_escape") for p in parts)


def main():
    text = SRC.read_text()
    messages = {
        "contextUnavailableMessage": literals_between(
            text, "internal fun contextUnavailableMessage()", "internal fun contextTooSmallMessage()"),
        "contextTooSmallMessage": literals_between(
            text, "internal fun contextTooSmallMessage()", "internal sealed interface ProviderRuntimeResult"),
        "CONTEXT_LIMIT_ERROR": literals_between(
            text, "internal const val CONTEXT_LIMIT_ERROR", "\n    }\n"),
    }
    failures = []
    for name, message in messages.items():
        if any(ch.isdigit() for ch in message):
            failures.append(f"{name}: quotes a number: {message!r}")
        for word in BANNED:
            if word in message.lower():
                failures.append(f"{name}: says {word!r}: {message!r}")
        if not message.strip():
            failures.append(f"{name}: is empty")
    # A copy change also has to sweep the tests that wait for the copy: an await whose words no
    # longer exist does not fail on its assertion, it times out. That is what happened in CI round 4
    # (two ChatViewModelTest timeouts), so the retired sentences are checked for here.
    retired = ["context capacity", "compacted this chat as far as it could", "does not fit the",
               "Start a new conversation to keep going", "token context window"]
    for path in sorted(pathlib.Path("app/src/test").rglob("*.kt")):
        body = path.read_text(encoding="utf-8")
        for phrase in retired:
            if phrase in body:
                failures.append(f"{path}: still pins retired copy {phrase!r}")

    for failure in failures:
        print("FAIL", failure)
    if failures:
        return 1
    print(f"PASS: {len(messages)} refusal sentences carry no number, no jargon, no dead end")
    for name, message in messages.items():
        print(f"  {name}: {message}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
