#!/usr/bin/env python3
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later
"""Fixture tests for the rendered-admonition gate.

The gate matches nothing in a healthy tree, so on its own a green run says
equally little about a working checker and a broken one. These are the positive
controls: each case asserts the checker flags a page it must flag, or spares one
it must not.

The malformed excerpts below are what the pinned Docusaurus (3.10.2) actually
produced for each spelling: rendered, then read back out of the
built HTML rather than assumed.
"""

import tempfile
import unittest
from pathlib import Path

from check_admonitions import check, main, stray_markers


def page(body: str) -> str:
    """A built page, shaped like Docusaurus output."""
    return f"<!doctype html><html><body><nav>nav</nav><main>{body}</main></body></html>"


class StrayMarkerTest(unittest.TestCase):

    def test_a_correct_admonition_leaves_no_marker(self):
        rendered = page(
            '<div class="theme-admonition admonition_xpFV alert alert--warning">'
            '<div class="admonitionHeading_Gvgb">Careful</div>'
            "<div><p>body</p></div></div>"
        )
        self.assertEqual(stray_markers(rendered), [])

    def test_the_v2_titled_form_is_flagged(self):
        # What ':::warning Some title' renders as: a plain paragraph.
        self.assertEqual(len(stray_markers(page("<p>:::warning Some title body :::</p>"))), 2)

    def test_every_malformed_spelling_is_flagged(self):
        # One list, because the rendered symptom is identical for all of them.
        # That is the whole reason this checks output instead of source.
        for spelling in [
            ":::warning Title",
            ":::warning [Title]",
            ':::warning {title="Title"}',
            ":::warning[Title] trailing words",
            "::: warning Title",
            "::::::warning Title",
            ":::note2 Title",
            ":::my-note Title",
        ]:
            with self.subTest(spelling=spelling):
                self.assertTrue(
                    stray_markers(page(f"<p>{spelling}</p>")),
                    f"{spelling!r} renders as body copy and must be flagged",
                )

    def test_a_mismatched_closing_fence_is_flagged(self):
        # ':::: opened, ::: closed' never closes; no regex over source spots it.
        self.assertTrue(stray_markers(page("<p>::::note[X] body ::: more</p>")))

    def test_markup_inside_code_is_spared(self):
        # Documenting the wrong spelling is legitimate, and belongs in <code>.
        self.assertEqual(stray_markers(page("<p>write <code>:::type[Title]</code></p>")), [])
        self.assertEqual(stray_markers(page("<pre><code>:::warning Bad</code></pre>")), [])

    def test_a_page_without_main_is_ignored(self):
        self.assertEqual(stray_markers("<html><body>:::warning stub</body></html>"), [])

    def test_two_colons_are_not_a_marker(self):
        self.assertEqual(stray_markers(page("<p>a :: b</p>")), [])


class CheckTest(unittest.TestCase):

    def build_dir(self, pages: dict[str, str]) -> Path:
        root = Path(tempfile.mkdtemp())
        for name, body in pages.items():
            target = root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(page(body))
        return root

    def test_a_clean_build_passes_and_counts_what_it_read(self):
        scanned, findings = check(self.build_dir({"a/index.html": "<p>fine</p>"}))
        self.assertEqual((scanned, findings), (1, []))

    def test_a_bad_page_is_reported_with_its_path(self):
        root = self.build_dir({"a/index.html": "<p>ok</p>", "b/index.html": "<p>:::tip X</p>"})
        scanned, findings = check(root)
        self.assertEqual(scanned, 2)
        self.assertEqual(len(findings), 1, "one finding per page, not per marker")
        self.assertEqual(findings[0][0].name, "index.html")

    def test_a_page_with_many_markers_reports_once_with_a_count(self):
        # A broken container spills every following ':::' into the text. Report
        # the page once: the first excerpt is the one that locates the mistake.
        root = self.build_dir({"b.html": "<p>:::tip A ::: :::note B ::: :::warning C :::</p>"})
        _, findings = check(root)
        self.assertEqual(len(findings), 1)
        self.assertGreater(findings[0][1], 1)

    def test_an_empty_build_fails_rather_than_passing_having_read_nothing(self):
        # The hole in the first version of this gate: it counted directories it
        # was configured with, not pages it read, so a moved build tree reported
        # success while checking nothing.
        self.assertEqual(main([str(self.build_dir({}))]), 1)

    def test_a_missing_build_directory_fails(self):
        self.assertEqual(main([str(Path(tempfile.mkdtemp()) / "absent")]), 1)

    def test_exit_codes(self):
        self.assertEqual(main([str(self.build_dir({"a.html": "<p>fine</p>"}))]), 0)
        self.assertEqual(main([str(self.build_dir({"a.html": "<p>:::warning X</p>"}))]), 1)


if __name__ == "__main__":
    unittest.main()
