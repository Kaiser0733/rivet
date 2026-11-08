#!/usr/bin/env python3
"""Equivalence check for the incremental markdown block parser (perf audit P-1).

The render path keeps the blocks before the last one and re-parses only the last block as a
streaming answer grows. That is only sound if, for every text T and every extension T+Δ,

    full(T + Δ) == incremental(T, then T+Δ)

This transcribes `parseAssistantMarkdown` and `MarkdownBlocks.forText` from
app/src/main/java/com/kaiser/rivet/ui/chat/ChatMarkdown.kt line for line and checks that
identity over a corpus of documents (every block kind, blank runs, an open fence, CRLF) at
every prefix, plus random growths and random full replacements.

usage: python3 round3/markdown-incremental-check.py [documents]
"""
import random
import sys

WS = set(" \t\n\r\x0b\x0c")          # Kotlin Char.isWhitespace, ASCII half


def is_blank(line):
    return all(ch in WS for ch in line)


def trim_start(line):
    index = 0
    while index < len(line) and line[index] in WS:
        index += 1
    return line[index:]


def heading(line):
    level = 0
    while level < len(line) and line[level] == "#":
        level += 1
    if 1 <= level <= 3 and len(line) > level and line[level] in WS:
        return ("heading", level, trim_start(line[level:]))
    return None


def list_item(line):
    if len(line) >= 2 and line[0] in "-*+" and line[1] in WS:
        return (False, 1, line[2:])
    end = 0
    while end < len(line) and line[end].isdigit():
        end += 1
    if end == 0 or end > 9 or end + 1 >= len(line) or line[end] not in ".)" or line[end + 1] not in WS:
        return None
    number = int(line[:end])
    return (True, number, line[end + 2:])


def parse_blocks_detail(source):
    blocks, starts = _parse_blocks_inner(source)
    return blocks, starts


def _parse_blocks_inner(source):
    lines = source.split("\n")
    line_starts = []
    offset = 0
    for text in lines:
        line_starts.append(offset)
        offset += len(text) + 1
    blocks, starts, line = [], [], 0
    while line < len(lines):
        if is_blank(lines[line]):
            line += 1
            continue
        current = lines[line]
        trimmed = trim_start(current)
        if trimmed.startswith("```"):
            starts.append(line_starts[line])
            language = trimmed[3:].strip()
            line += 1
            code = []
            while line < len(lines) and not trim_start(lines[line]).startswith("```"):
                code.append(lines[line])
                line += 1
            if line < len(lines):
                line += 1
            blocks.append(("code", language, "\n".join(code)))
            continue
        head = heading(trimmed)
        if head is not None:
            starts.append(line_starts[line])
            blocks.append(head)
            line += 1
            continue
        if trimmed.startswith(">"):
            starts.append(line_starts[line])
            quote = []
            while line < len(lines):
                value = trim_start(lines[line])
                if not value.startswith(">"):
                    break
                value = value[1:]
                if value.startswith(" "):
                    value = value[1:]
                quote.append(value)
                line += 1
            blocks.append(("quote", "\n".join(quote)))
            continue
        first_item = list_item(trimmed)
        if first_item is not None:
            starts.append(line_starts[line])
            items, ordered, start = [], first_item[0], first_item[1]
            while line < len(lines):
                item = list_item(trim_start(lines[line]))
                if item is None or item[0] != ordered:
                    break
                items.append(item[2])
                line += 1
            blocks.append(("list", ordered, start, tuple(items)))
            continue
        starts.append(line_starts[line])
        paragraph = []
        while line < len(lines) and not is_blank(lines[line]):
            value = trim_start(lines[line])
            if paragraph and (value.startswith("```") or heading(value) is not None
                              or value.startswith(">") or list_item(value) is not None):
                break
            paragraph.append(lines[line])
            line += 1
        blocks.append(("paragraph", "\n".join(paragraph)))
    return blocks, starts


def parse_blocks(source):
    return parse_blocks_detail(source)


def full(source):
    return parse_blocks_detail(source)[0]


class Incremental:
    def __init__(self):
        self.source = ""
        self.blocks = []
        self.starts = []

    def for_text(self, text):
        if text == self.source:
            return self.blocks
        if (self.blocks and len(text) > len(self.source) and text.startswith(self.source)):
            keep = max(0, len(self.blocks) - 2)
            start = self.starts[keep]
            tail, tail_starts = parse_blocks(text[start:])
            if tail:
                self.blocks = self.blocks[:keep] + tail
                self.starts = self.starts[:keep] + [start + value for value in tail_starts]
                self.source = text
                return self.blocks
        self.blocks, self.starts = parse_blocks_detail(text)
        self.source = text
        return self.blocks


BASE = [
    "## A heading\n\nFirst paragraph.\nStill the same paragraph.\n\n> A quoted line",
    "- one\n- two\n\n3. three\n4. four",
    "```kotlin\nval answer = 42\nprintln(answer)\n```",
    "```\nfirst\nsecond",
    "text with **bold**, *italic* and `code`\n\n- item **b**\n\n## H3 ###\n> quote\n> more\n\nfinal",
    "",
    "a",
    "\n\n\n",
    "para\r\nwith crlf\r\n\r\n- x\r\n",
    "```\nopen fence\n\n# not a heading inside\n```\nafter\n\n## heading\n",
]

LINES = ["", "   ", "text", "more text here", "## heading", "### deeper", "#nope", "#### four",
         "- bullet", "* star", "+ plus", "1. ordered", "10) paren", "1234567890. long",
         "> quote", ">", "```", "```kotlin", "tail after fence", "\ttabbed", "trailing space  "]


def random_document(rng, max_lines=14):
    return "\n".join(rng.choice(LINES) for _ in range(rng.randint(0, max_lines)))


def main():
    rng = random.Random(20261102)
    checks = 0
    failures = 0
    documents = list(BASE)
    rounds = int(sys.argv[1]) if len(sys.argv) > 1 else 2000
    documents += [random_document(rng) for _ in range(rounds)]

    for document in documents:
        parser = Incremental()
        for end in range(len(document) + 1):
            prefix = document[:end]
            got = parser.for_text(prefix)
            want = full(prefix)
            checks += 1
            if got != want:
                failures += 1
                if failures <= 5:
                    print(f"MISMATCH prefix end={end} doc={document!r}\n  got  {got}\n  want {want}")

        for _ in range(4):
            parser = Incremental()
            end = rng.randint(0, len(document))
            prefix = document[:end]
            parser.for_text(prefix)
            grown = prefix + rng.choice(["\n", "\nmore", "\n\nnew paragraph", "\n- item", "\n```\nx",
                                         "\n> q", "## h", "\r\n", "\n\n"])
            checks += 1
            if parser.for_text(grown) != full(grown):
                failures += 1
                if failures <= 5:
                    print(f"MISMATCH growth prefix end={end} grown={grown!r}")

        # a replacement that is not an extension of the previous text
        parser = Incremental()
        parser.for_text(document)
        other = random_document(rng)
        checks += 1
        if parser.for_text(other) != full(other):
            failures += 1
            if failures <= 5:
                print(f"MISMATCH replacement {other!r}")

    print(f"{checks} comparisons over {len(documents)} documents (incl. random growths and replacements)")
    if failures:
        print(f"FAIL: {failures} mismatches")
        return 1
    print("PASS: the incremental parser equals the full parse everywhere it was checked")
    return 0


if __name__ == "__main__":
    sys.exit(main())
