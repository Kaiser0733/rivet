#!/usr/bin/env python3
"""Structural audit for the follow-scroll fix (b06145d).

The behaviour is a device check; what can be checked here is the shape:
the jump and the settle agree on the content's end, growth is followed from the layout
rather than a character count, the old bucket constant is gone, and the settle cannot
scroll while the reader is not following or while a gesture is in progress.

usage: python3 round3/scroll-follow-check.py
"""
import pathlib
import re
import sys

UI = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/java/com/kaiser/rivet/ui/chat/ChatScreen.kt"


def main():
    ui = UI.read_text(encoding="utf-8")
    failures = []

    def check(name, condition, detail=""):
        print(("ok   " if condition else "FAIL ") + name + (f"  [{detail}]" if detail else ""))
        if not condition:
            failures.append(name)

    check("no character bucketing left anywhere",
          "STREAMED_SCROLL_STEP" not in ui and "/ STREAMED" not in ui)

    check("the jump bottom-aligns to the content's end, padding included",
          re.search(r"val offset = item\.size \+ layout\.afterContentPadding - viewport", ui) is not None)

    check("the settle reads the same end",
          re.search(r"last\.offset \+ last\.size \+ layout\.afterContentPadding - layout\.viewportEndOffset", ui) is not None)

    check("the settle only acts while following", "if (!followBottom || gap <= 0" in ui)

    check("the settle never competes with a gesture",
          "listState.isScrollInProgress) return@collect" in ui)

    check("the settle scrolls by the measured gap", "listState.scrollBy(gap.toFloat())" in ui)

    check("scrollBy is imported", "import androidx.compose.foundation.gestures.scrollBy" in ui)

    check("growth is no longer an effect key",
          ui.count("streamingText.length /") == 0 and ui.count("streamingReasoning.length /") == 0)

    check("following is still switched on in exactly one place",
          ui.count("followBottom = true") == 1 and ui.count("followBottom = near") == 1)

    print()
    if failures:
        print(f"{len(failures)} check(s) failed")
        return 1
    print("structure holds; the scroll behaviour is the device check")
    return 0


if __name__ == "__main__":
    sys.exit(main())
