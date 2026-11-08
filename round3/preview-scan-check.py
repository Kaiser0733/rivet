"""Executable transcription of ChatText.kt's preview scan (no JDK on this host).

It is a line-by-line port of the Kotlin added in the F6 commit: same loop, same regex,
same indexOf/BLANK_LINE semantics (java.util.regex Matcher.find(start) == re.search(s, start)).
It proves the algorithm, not the Kotlin syntax. Run: python3 preview-scan-check.py
"""
import random
import re

PREVIEW_CHARS = 160
BLANK_LINE = re.compile(r"\r?\n(?:[\t ]*\r?\n)+")


class PreviewScan:
    def __init__(self):
        self.summary = ""
        self.paragraph_start = 0
        self.read = 0

    def advance(self, text):
        if len(text) < self.read:
            self.summary = ""
            self.paragraph_start = 0
            self.read = 0
        if len(text) == self.read:
            return self.summary[:PREVIEW_CHARS]
        start = self.paragraph_start
        while True:
            m = BLANK_LINE.search(text, start)
            if m is None:
                paragraph_end = len(text)
            else:
                paragraph_end = m.start() + m.group(0).find("\n")
            newline = text.find("\n", start)
            if newline != -1 and newline <= paragraph_end:
                candidate = text[start:newline].strip()
                if candidate:
                    self.summary = candidate
            if m is None:
                self.paragraph_start = min(start, blank_tail_start(text))
                self.read = len(text)
                return self.summary[:PREVIEW_CHARS]
            start = m.end()  # range.last + 1


def blank_tail_start(text):
    index = len(text)
    while index > 0 and text[index - 1].isspace():
        index -= 1
    return index


def first_line(text):
    for line in text.split("\n"):
        line = line.strip()
        if line:
            return line[:PREVIEW_CHARS]
    return ""


def tail_of_last_line(text):
    end = len(text) - 1
    while end >= 0 and text[end].isspace():
        end -= 1
    if end < 0:
        return ""
    start = text.rfind("\n", 0, end)
    start = 0 if start < 0 else start + 1
    return text[start:end + 1].strip()[-PREVIEW_CHARS:].lstrip()


def thinking_preview(text, streaming, scan=None):
    if scan is None:
        scan = PreviewScan()
    if not streaming:
        return first_line(text)
    out = scan.advance(text)
    return out if out else tail_of_last_line(text)


# ---- the pins, unchanged (ThinkingPreviewTest.kt at 859c601) -------------------------
pins = []
pins.append(("aFinishedTurnPreviewsItsFirstLine", "Start with the parser",
             thinking_preview("Start with the parser\nThen the renderer", False)))
pins.append(("aFinishedTurnPreviewsItsFirstLine/blank", "",
             thinking_preview("\n\n   ", False)))
pins.append(("aStreamingChainOnlyAdvances..1", "First thought",
             thinking_preview("First thought\nstill first\n\nSecond thought is still goi", True)))
pins.append(("aStreamingChainOnlyAdvances..2", "Second",
             thinking_preview("First\n\nSecond\n\nThird has no line end yet", True)))
pins.append(("anUnfinishedLine..1", "One long paragraph with no line end",
             thinking_preview("One long paragraph with no line end", True)))
pins.append(("anUnfinishedLine..2", "older line",
             thinking_preview("older line\nfresh thought", True)))
pins.append(("anUnfinishedLine..3", "", thinking_preview("", True)))
pins.append(("anUnfinishedLine..4", "", thinking_preview("   \n\t", True)))
pins.append(("theTailPreviewStaysOneShortLine/len", "160",
             str(len(thinking_preview("y" * 500, True)))))
finished = "Check the parser first\nsecond line\n\n"
open_par = "Now a much longer thought that never ends " + "word " * 80
pins.append(("aFinishedParagraphKeeps..1", "Check the parser first",
             thinking_preview(finished + open_par, True)))
pins.append(("aFinishedParagraphKeeps..2", "Check the parser first",
             thinking_preview(finished.rstrip(), True)))
pins.append(("thePreviewFollowsTheLastFinishedParagraph/1", "Second",
             thinking_preview("First\none\n\nSecond\ntwo\n\nThird", True)))
pins.append(("thePreviewFollowsTheLastFinishedParagraph/2", "First",
             thinking_preview("First\none\n\nSecond", True)))
pins.append(("thePreviewStaysOneShortLine", "x" * 160,
             thinking_preview("x" * 400 + "\n\n" + "y" * 400, True)))

failures = 0
for name, expected, actual in pins:
    ok = expected == actual
    failures += 0 if ok else 1
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f" expected={expected!r} actual={actual!r}"))

# ---- resumingAScanAtEachDeltaMatchesScanningFromZero --------------------------------
chain = ("First thought\nsecond line\n\nSecond thought\n\nThird one is still"
         " being written and has no line end yet" + "\n\n" + "fourth\n\n")
scan = PreviewScan()
for end in range(1, len(chain) + 1):
    prefix = chain[:end]
    one = thinking_preview(prefix, True)
    resumed = thinking_preview(prefix, True, scan)
    if one != resumed:
        failures += 1
        print(f"FAIL resume mismatch at prefix {end}: one={one!r} resumed={resumed!r}")
print(f"PASS resumingAScanAtEachDeltaMatchesScanningFromZero ({len(chain)} prefixes)")

# ---- aScanHandedAShorterChainStartsOver --------------------------------------------
scan = PreviewScan()
thinking_preview("First\nsecond\n\nThird\nfourth\n\nFifth", True, scan)
for nxt in ("Re", "Restarted", "Restarted\nand growing"):
    one = thinking_preview(nxt, True)
    resumed = thinking_preview(nxt, True, scan)
    if one != resumed:
        failures += 1
        print(f"FAIL shorter-chain mismatch for {nxt!r}: one={one!r} resumed={resumed!r}")
print("PASS aScanHandedAShorterChainStartsOver")

# ---- randomised equivalence: every prefix, many shapes -----------------------------
# The alphabet is deliberately hostile to the resume point: unfinished blank-line runs,
# \r\n runs, tab-and-space indentation, and paragraphs that open with blanks.
alphabet = ["a", "bb", "word", " ", "  ", "   ", "\n", "\n\n", "\n\n\n", "\t\n", " \n",
            "\r\n", "\r\n\r\n", "\n \n", "\n\t\n", "\r\n \r\n", "\n   \n"]
chains = 0
for seed in (20261102, 7, 99, 1234, 424242):
    random.seed(seed)
    for trial in range(400):
        parts = [random.choice(alphabet) for _ in range(random.randint(1, 40))]
        text = "".join(parts)
        scan = PreviewScan()
        chains += 1
        for end in range(0, len(text) + 1):
            prefix = text[:end]
            one = thinking_preview(prefix, True)
            resumed = thinking_preview(prefix, True, scan)
            if one != resumed:
                failures += 1
                print(f"FAIL seed {seed} trial {trial} prefix {end} one={one!r} "
                      f"resumed={resumed!r} text={text!r}")
                break
print(f"PASS randomised delta-by-delta equivalence ({chains} chains, every 1-char prefix)")

# ---- the scan really does less work: count characters read per delta ---------------
def scan_cost(text, step):
    """Characters the loop touches per delta, fresh-scan vs resumed scan."""
    fresh = resumed = 0
    scan = PreviewScan()
    for end in range(step, len(text) + 1, step):
        prefix = text[:end]
        fresh += len(prefix)                      # from-zero scan reads everything
        before = scan.paragraph_start
        thinking_preview(prefix, True, scan)
        resumed += len(prefix) - before           # resumed scan starts at the open paragraph
    return fresh, resumed

body = "".join(f"Paragraph {i} opens here and then keeps going with a few more words.\n"
               f"its second line\n\n" for i in range(40))
body += "and the final paragraph keeps streaming without a line end " + "word " * 200
fresh, resumed = scan_cost(body, 4)
print(f"cost, paragraph-shaped chain {len(body)} chars, 4-char deltas: fresh reads "
      f"{fresh} chars, resumed reads {resumed} ({fresh / max(resumed, 1):.1f}x less)")

one_par = "only one paragraph, no blank line anywhere, streaming on and on " + "word " * 300
fresh, resumed = scan_cost(one_par, 4)
print(f"cost, one-paragraph chain {len(one_par)} chars, 4-char deltas: fresh reads "
      f"{fresh} chars, resumed reads {resumed} ({fresh / max(resumed, 1):.1f}x less) "
      "- this is the honest worst case")

print("VERDICT", "FAIL" if failures else "PASS", f"({failures} failures)")
raise SystemExit(1 if failures else 0)
