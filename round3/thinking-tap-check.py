#!/usr/bin/env python3
"""Structural audit for kaiser's thinking-box tap (5f68178).

There is no Compose UI test harness here, so the gesture itself cannot be tested. What *can* be
checked is the shape the gesture depends on, and that is what this does — over the ThinkingBlock
function body only:

  * the expanded chain's Surface is a `clickable` with `indication = null` that collapses;
  * the header row keeps its own `clickable` with the original toggle, so Show/Hide is unchanged;
  * the body contains no `pointerInput` / `detectTapGestures` (a hand-rolled detector would be the
    one thing able to fire on a drag) and no `verticalScroll` (a nested scroller would compete with
    the chat list for the gesture);
  * every ThinkingBlock call site is the one declaration, so live and finished blocks share it.

It proves structure, not behaviour. The tap-versus-drag acceptance test is kaiser's device check.
"""
import pathlib
import re
import sys

FILE = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/java/com/kaiser/rivet/ui/chat/ChatScreen.kt"
START = "private fun ThinkingBlock("


def body(lines):
    start = next(i for i, line in enumerate(lines) if START in line)
    end = next(i for i in range(start + 1, len(lines)) if lines[i] == "}")
    return lines[start:end + 1], start


def main():
    lines = FILE.read_text(encoding="utf-8").split("\n")
    block, start = body(lines)
    text = "\n".join(block)
    failures = []

    def check(name, condition):
        print(("ok   " if condition else "FAIL ") + name)
        if not condition:
            failures.append(name)

    check("the chain's Surface is a clickable with no indication",
          re.search(r"\.clickable\(", text) is not None and "indication = null" in text)
    check("tapping the chain collapses the block",
          re.search(r"onClickLabel = \"Collapse thinking\",\s*\n\s*\) \{ expanded = false \}", text) is not None)
    check("the header keeps its own toggle, unchanged",
          re.search(r"\.clickable\(role = Role\.Button\) \{ expanded = !expanded \}", text) is not None)
    check("no hand-rolled tap detector in the block",
          "detectTapGestures" not in text and "pointerInput" not in text)
    check("no nested scroll in the chain to compete with the list",
          "verticalScroll" not in text and "rememberScrollState" not in text)
    check("one interaction source, remembered",
          text.count("remember { MutableInteractionSource() }") == 1)
    check("the chain still renders through the F6 preview path",
          "thinkingPreview(text, streaming, scan)" in text and "PreviewScan()" in text)

    declarations = sum(1 for line in lines if START in line)
    call_sites = sum(1 for line in lines if re.search(r"(?<!private fun )ThinkingBlock\(", line))
    check(f"one ThinkingBlock declaration and its call sites ({call_sites} of them)",
          declarations == 1 and call_sites == 3)

    print()
    if failures:
        print(f"{len(failures)} check(s) failed")
        return 1
    print("structure holds; the gesture itself is the device check")
    return 0


if __name__ == "__main__":
    sys.exit(main())
