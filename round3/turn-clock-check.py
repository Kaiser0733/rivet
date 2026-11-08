#!/usr/bin/env python3
"""Structural audit for the turn clock (3051632) and its single-sourcing (31f0895).

The screen behaviour is kaiser's device check; what can be checked here is that the clock's
start has exactly one writer, lives in the turn rather than in a list row, and that the item
count and the item itself read the same value.

usage: python3 round3/turn-clock-check.py
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
VM = ROOT / "app/src/main/java/com/kaiser/rivet/chat/ChatViewModel.kt"
UI = ROOT / "app/src/main/java/com/kaiser/rivet/ui/chat/ChatScreen.kt"


def main():
    vm = VM.read_text(encoding="utf-8")
    ui = UI.read_text(encoding="utf-8")
    failures = []

    def check(name, condition, detail=""):
        print(("ok   " if condition else "FAIL ") + name + (f"  [{detail}]" if detail else ""))
        if not condition:
            failures.append(name)

    writers = re.findall(r"turnStartedAt\s*=\s*SystemClock\.elapsedRealtime\(\)", vm)
    check("the turn's start is written exactly once, from the monotonic clock", len(writers) == 1,
          f"{len(writers)} writers")

    check("no composable creates the clock's start any more",
          "rememberSaveable { SystemClock.elapsedRealtime() }" not in ui)

    check("the row takes the start as a parameter",
          re.search(r"private fun LiveStatusRow\(label: String, startedAt: Long\)", ui) is not None)

    call_sites = re.findall(r"LiveStatusRow\(statusLabel, ", ui)
    check("one call site, passing the turn's value", len(call_sites) == 1, f"{len(call_sites)}")

    check("the row emits only when the turn has a start",
          "val liveStatusStart = if (streaming) state.turnStartedAt else null" in ui)

    check("the item count and the item read the same value",
          ui.count("liveStatusStart != null") == 1 and ui.count("liveStatusStart?.let") == 1
          and "showLiveStatus" not in ui)

    check("the tick still recomputes from the passed start, not from its own clock",
          ui.count("(SystemClock.elapsedRealtime() - startedAt)") == 2)

    check("the F6 preview path is untouched",
          "thinkingPreview(text, streaming, scan)" in ui and "PreviewScan()" in ui)

    check("the tap toggle is intact beside it",
          ') { expanded = false },' in ui and ".clickable(role = Role.Button) { expanded = !expanded }" in ui)

    print()
    if failures:
        print(f"{len(failures)} check(s) failed")
        return 1
    print("structure holds; the screen behaviour is the device check")
    return 0


if __name__ == "__main__":
    sys.exit(main())
