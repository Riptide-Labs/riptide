#!/usr/bin/env python3
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later
"""Fail when a built page shows admonition markup as body copy.

A Docusaurus admonition that is not spelled exactly right is not a directive at
all. It renders as literal ``:::`` text on the published page, and nothing
reports it: Docusaurus's ``unusedDirectives`` warning visits directive nodes and
this never becomes one, ``onBrokenLinks``/``onBrokenAnchors`` say nothing about
admonition syntax, and in a diff the right and wrong spellings look nearly
identical. That is how a warning about default passwords shipped as ordinary
paragraph text (#680, #721).

This checks the **rendered** output rather than the source, which is what the
repo's own rule asks for and is also the only approach that scales. Measured
against the pinned Docusaurus (3.10.2), every one of these renders as literal
``:::`` and a source-level regex has to enumerate them all:

    :::warning Title                 the classic v2 form
    :::warning [Title]               a space before the label
    :::warning {title="Title"}       a space before the attributes
    :::warning[Title] trailing       anything after the label
    ::: warning Title                a space after the colons
    ::::::warning Title              six or more colons
    :::note2 Title                   a digit in the type
    :::my-note Title                 a hyphen in the type
    > :::tip Title                   inside a blockquote

The symptom is identical in every case, so checking the symptom needs no such
list and cannot drift out of step with the parser. It also catches a failure no
reasonable regex could: a container opened with four colons and closed with
three never closes, and swallows the rest of the page.

Code spans and code blocks are excluded. Prose that *documents* the wrong
spelling belongs in ``<code>``, which is where a reader would expect it and
where this checker ignores it.
"""

from __future__ import annotations

import argparse
import html
import re
import sys
from pathlib import Path

# The rendered marker. Three or more colons, since a mismatched fence leaves the
# opening run intact in the text.
MARKER = re.compile(r":{3,}")

_MAIN = re.compile(r"<main\b.*?</main>", re.S | re.I)
_STRIPPED = re.compile(r"<(code|pre|script|style)\b.*?</\1>", re.S | re.I)
_TAG = re.compile(r"<[^>]+>")


def stray_markers(page_html: str) -> list[str]:
    """Excerpts around each ``:::`` left in a page's rendered prose."""
    main = _MAIN.search(page_html)
    if main is None:
        # No <main>: a redirect stub or an asset shell, nothing authored here.
        return []
    body = _STRIPPED.sub(" ", main.group(0))
    text = html.unescape(_TAG.sub(" ", body))
    return [
        " ".join(text[max(0, m.start() - 60):m.end() + 60].split())
        for m in MARKER.finditer(text)
    ]


def check(build_dir: Path) -> tuple[int, list[tuple[Path, int, str]]]:
    """Scan every built page.

    Returns (pages scanned, findings), one finding per *page* rather than per
    marker. A broken container spills every following ``:::`` on the page into
    the text. Per-marker reporting buries the first one, which is the only one
    that locates the mistake, under a screen of overlapping excerpts.
    """
    scanned = 0
    findings: list[tuple[Path, int, str]] = []
    for page in sorted(build_dir.rglob("*.html")):
        scanned += 1
        excerpts = stray_markers(page.read_text(errors="ignore"))
        if excerpts:
            findings.append((page, len(excerpts), excerpts[0]))
    return scanned, findings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("build_dir", type=Path, help="the Docusaurus build output")
    parser.add_argument(
        "--min-pages",
        type=int,
        default=1,
        help="fail if fewer pages than this were scanned, so an empty or moved "
        "build directory cannot pass by checking nothing",
    )
    args = parser.parse_args(argv)

    if not args.build_dir.is_dir():
        print(f"error: {args.build_dir} is not a directory. Was the site built?", file=sys.stderr)
        return 1

    scanned, findings = check(args.build_dir)

    # A gate that scanned nothing must not report success. This is the failure
    # the directory-counting version of this check could not tell from a clean
    # run: rename the docs tree and it passed, having read no pages at all.
    if scanned < args.min_pages:
        print(
            f"error: scanned {scanned} page(s) under {args.build_dir}, expected at "
            f"least {args.min_pages}. The build output moved or is empty, so this "
            f"gate checked nothing.",
            file=sys.stderr,
        )
        return 1

    if findings:
        print(
            "error: admonition markup rendered as body copy. The page shows ':::' as\n"
            "       text, which means the directive was not parsed. Write\n"
            "       ':::type[Title]' with no space before the bracket and nothing after it,\n"
            "       and close the container with as many colons as opened it.\n",
            file=sys.stderr,
        )
        for page, count, excerpt in findings:
            plural = "" if count == 1 else "s"
            print(f"  {page}  ({count} marker{plural})\n      …{excerpt}…", file=sys.stderr)
        return 1

    print(f"admonition syntax: {scanned} rendered page(s) checked, no stray ':::'")
    return 0


if __name__ == "__main__":
    sys.exit(main())
