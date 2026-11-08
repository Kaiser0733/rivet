#!/usr/bin/env python3
"""A2 re-audit: independent transcription of Rivet's streaming preview scanner.

Written from app/src/main/java/com/kaiser/rivet/ui/chat/ChatText.kt at e1b833c, not
from round3/preview-scan-check.py. Two implementations:

  from_zero(text)      -- the pre-F6 loop (lastFinishedParagraphFirstLine), scanning from 0
  PreviewScan          -- the F6 holder (summary/paragraphStart/read) with advance(text)

The claim under audit: for a chain that only grows, advancing a PreviewScan over every
prefix equals scanning that prefix from zero. Brute-forced over an exhaustive small
alphabet and over random chains, plus random delta splittings.
"""
import itertools
import random
import re
import sys

PREVIEW_CHARS = 160

# Kotlin's Regex("\r?\n(?:[\t ]*\r?\n)+")
BLANK_LINE = re.compile(r"\r?\n(?:[\t ]*\r?\n)+")

# Kotlin Char.isWhitespace == Character.isWhitespace(c) || Character.isSpaceChar(c).
# Restricted to the characters this check uses; \x0b and \x0c are whitespace in Java too.
JAVA_WS = set(" \t\n\r\x0b\x0c")


def is_ws(c):
    return c in JAVA_WS


def trim(s):
    i, j = 0, len(s)
    while i < j and is_ws(s[i]):
        i += 1
    while j > i and is_ws(s[j - 1]):
        j -= 1
    return s[i:j]


def trim_start(s):
    i = 0
    while i < len(s) and is_ws(s[i]):
        i += 1
    return s[i:]


def blank_tail_start(text):
    index = len(text)
    while index > 0 and is_ws(text[index - 1]):
        index -= 1
    return index


def from_zero(text):
    """lastFinishedParagraphFirstLine(text).take(PREVIEW_CHARS)"""
    summary = ""
    start = 0
    while True:
        next_match = BLANK_LINE.search(text, start)
        paragraph_end = (next_match.start() + next_match.group().index("\n")) if next_match else len(text)
        newline = text.find("\n", start)
        if newline != -1 and newline <= paragraph_end:
            candidate = trim(text[start:newline])
            if candidate:
                summary = candidate
        if next_match is None:
            return summary[:PREVIEW_CHARS]
        start = next_match.end()  # next.range.last + 1


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
            next_match = BLANK_LINE.search(text, start)
            paragraph_end = (next_match.start() + next_match.group().index("\n")) if next_match else len(text)
            newline = text.find("\n", start)
            if newline != -1 and newline <= paragraph_end:
                candidate = trim(text[start:newline])
                if candidate:
                    self.summary = candidate
            if next_match is None:
                self.paragraph_start = min(start, blank_tail_start(text))
                self.read = len(text)
                return self.summary[:PREVIEW_CHARS]
            start = next_match.end()


def tail_of_last_line(text):
    end = -1
    for i in range(len(text) - 1, -1, -1):
        if not is_ws(text[i]):
            end = i
            break
    if end < 0:
        return ""
    start = text.rfind("\n", 0, end)  # lastIndexOf('\n', end - 1)
    start = 0 if start < 0 else start + 1
    return trim_start(trim(text[start:end + 1])[-PREVIEW_CHARS:])


def thinking_preview(text, streaming, scan=None):
    if not streaming:
        return first_line(text)
    value = (scan if scan is not None else PreviewScan()).advance(text)
    return value if value else tail_of_last_line(text)


def first_line(text):
    for line in text.split("\n"):
        line = trim(line)
        if line:
            return line[:PREVIEW_CHARS]
    return ""


def check_growth(text, deltas, failures):
    """Feed `text` through one scan in `deltas` slices; compare at every prefix."""
    scan = PreviewScan()
    end = 0
    for size in deltas:
        end = min(end + size, len(text))
        prefix = text[:end]
        got = thinking_preview(prefix, True, scan)
        want = thinking_preview(prefix, True)
        if got != want:
            failures.append((prefix, got, want, "incremental"))
            return
    # and the pure-scanner claim (no tail fallback)
    scan2 = PreviewScan()
    for size in deltas:
        pass
    scan3 = PreviewScan()
    for i in range(1, len(text) + 1):
        prefix = text[:i]
        got = scan3.advance(prefix)
        want = from_zero(prefix)
        if got != want:
            failures.append((prefix, got, want, "scan-from-zero"))
            return


def main():
    failures = []
    exhaustive_alphabet = "A \n\r"
    # exhaustive over every string up to length 8 and every one-char delta stream
    for length in range(0, 9):
        for tup in itertools.product(exhaustive_alphabet, repeat=length):
            check_growth("".join(tup), [1] * length, failures)
            if failures:
                break
        if failures:
            break
    print(f"exhaustive up to length 8 over {exhaustive_alphabet!r}: "
          f"{'FAIL' if failures else 'ok'}")

    # a wider alphabet incl. tabs, \r\n pairs and short words, random chains
    rng = random.Random(20261102)
    alphabet = ["A", "b", "long word", " ", "\t", "\n", "\n\n", "\r\n", "\r\n\r\n", "\r", "x\n"]
    checked = 0
    if not failures:
        for _ in range(4000):
            text = "".join(rng.choice(alphabet) for _ in range(rng.randrange(0, 14)))
            # one-char deltas: every prefix
            check_growth(text, [1] * len(text), failures)
            # random delta sizes
            deltas = []
            left = len(text)
            while left > 0:
                size = min(left, rng.choice([1, 1, 2, 3, 5, 9]))
                deltas.append(size)
                left -= size
            check_growth(text, deltas, failures)
            checked += 1
            if failures:
                break
    print(f"random chains over {len(alphabet)} tokens: {checked} checked, "
          f"{'FAIL' if failures else 'ok'}")

    # paragraphs that finish, blank runs that grow inside deltas, tail fallback
    handcrafted = [
        ["A\n\n", "A\n\n ", "A\n\n \n", "A\n\n \nB", "A\n\n \nB\n", "A\n\n \nB\nC"],
        ["First\nsecond\n\n", "First\nsecond\n\nThird"],
        ["A\r\n\r\n", "A\r\n\r\nB", "A\r\n\r\nB\r\n"],
        ["A\n\n\n", "A\n\n\nB\n"],
        ["\n\n", "\n\nA", "\n\nA\n", "\n\nA\nB"],
        ["A\n\r\n", "A\n\r\nB"],
        ["A ", "A \n", "A \n ", "A \n \n", "A \n \nB"],
    ]
    if not failures:
        for chain in handcrafted:
            scan = PreviewScan()
            for prefix in chain:
                got = thinking_preview(prefix, True, scan)
                want = thinking_preview(prefix, True)
                if got != want:
                    failures.append((prefix, got, want, "handcrafted"))
                    break
    print(f"handcrafted growth sequences: {'FAIL' if failures else 'ok'}")

    if failures:
        prefix, got, want, kind = failures[0]
        print("\nFIRST FAILURE (%s)" % kind)
        print("  prefix  = %r" % prefix)
        print("  scan    = %r" % got)
        print("  from 0  = %r" % want)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

# --- extra passes: wider exhaustive alphabet, skip-prefix deltas, idempotence -----------
def extra():
    failures = []
    # exhaustive, 5 symbols (adds a tab so the separator's [\t ] class is exercised), len<=7
    alphabet = "A \n\t\r"
    total = 0
    for length in range(0, 8):
        for tup in itertools.product(alphabet, repeat=length):
            text = "".join(tup)
            total += 1
            scan = PreviewScan()
            for i in range(1, len(text) + 1):
                prefix = text[:i]
                got = scan.advance(prefix)
                want = from_zero(prefix)
                if got != want:
                    failures.append(("exhaustive5", prefix, got, want))
                    break
            if failures:
                break
        if failures:
            break
    print(f"exhaustive 5-symbol len<=7: {total} chains, {'FAIL' if failures else 'ok'}")

    # deltas that skip whole prefixes (two deltas arrive before one composition reads them)
    rng = random.Random(7)
    if not failures:
        alphabet = ["w", "longer word", " ", "\n", "\n\n", "\r\n", "\t", "\r\n \n"]
        for _ in range(3000):
            full = "".join(rng.choice(alphabet) for _ in range(rng.randrange(0, 16)))
            cuts = sorted(rng.sample(range(len(full) + 1), min(len(full) + 1, rng.randrange(1, 5))))
            if not cuts or cuts[-1] != len(full):
                cuts = sorted(set(cuts + [len(full)]))
            scan = PreviewScan()
            for cut in cuts:
                got = scan.advance(full[:cut])
                want = from_zero(full[:cut])
                if got != want:
                    failures.append(("skip-deltas", full[:cut], got, want))
                    break
            if failures:
                break
        print(f"delta streams that skip prefixes: {'FAIL' if failures else 'ok'}")

    # the same text twice must not change the answer
    if not failures:
        for text in ["A\n\nB", "A\n\n B\nC", "", "\n\n", "A\r\n\r\nB"]:
            scan = PreviewScan()
            first = scan.advance(text)
            second = scan.advance(text)
            if first != second or first != from_zero(text):
                failures.append(("idempotence", text, second, from_zero(text)))
        print(f"repeated advance with the same text: {'FAIL' if failures else 'ok'}")

    if failures:
        kind, prefix, got, want = failures[0]
        print("\nFAILURE (%s)\n  prefix=%r\n  scan  =%r\n  from0 =%r" % (kind, prefix, got, want))
        return 1
    return 0
