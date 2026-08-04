#!/usr/bin/env python3
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

"""Fixture tests for assert_licenses.py. Run with:
    python3 -m unittest discover -s deployment/sbom
"""

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).parent
SCRIPT = HERE / "assert_licenses.py"
FIXTURE = HERE / "fixtures" / "release.spdx.json"
REPO_NFPM = HERE.parent.parent / "nfpm.yaml"


def run(sbom: Path, nfpm: Path):
    return subprocess.run([sys.executable, str(SCRIPT), str(sbom), "--nfpm", str(nfpm)],
                          capture_output=True, text=True)


class AssertLicensesTest(unittest.TestCase):

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.sbom = self.tmp / "release.spdx.json"
        shutil.copy(FIXTURE, self.sbom)
        self.nfpm = self.tmp / "nfpm.yaml"
        self.nfpm.write_text("name: riptide\nlicense: GPL-3.0-or-later\n")

    def packages(self):
        return {p["SPDXID"]: p for p in json.loads(self.sbom.read_text())["packages"]}

    def test_success_asserts_root_and_deb_and_leaves_the_rest(self):
        result = run(self.sbom, self.nfpm)
        self.assertEqual(result.returncode, 0, result.stderr)
        pkgs = self.packages()
        self.assertEqual(pkgs["SPDXRef-DocumentRoot-Directory-."]["licenseDeclared"], "GPL-3.0-or-later")
        deb = next(p for i, p in pkgs.items() if i.startswith("SPDXRef-Package-deb-"))
        self.assertEqual(deb["licenseDeclared"], "GPL-3.0-or-later")
        rpm = next(p for i, p in pkgs.items() if i.startswith("SPDXRef-Package-rpm-"))
        self.assertEqual(rpm["licenseDeclared"], "GPL-3.0-or-later")
        third_party = next(p for i, p in pkgs.items() if "angus" in i)
        self.assertEqual(third_party["licenseDeclared"], "BSD-3-Clause")

    def test_repo_nfpm_matches_fixture_expectation(self):
        # The fixture test above uses a synthetic nfpm.yaml; this guards the real one.
        result = run(self.sbom, REPO_NFPM)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_selector_no_match_fails(self):
        doc = json.loads(self.sbom.read_text())
        doc["packages"] = [p for p in doc["packages"] if not p["SPDXID"].startswith("SPDXRef-Package-deb-")]
        self.sbom.write_text(json.dumps(doc))
        result = run(self.sbom, self.nfpm)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("the deb package", result.stderr)
        self.assertIn("matched 0", result.stderr)

    def test_selector_multiple_match_fails(self):
        doc = json.loads(self.sbom.read_text())
        deb = next(p for p in doc["packages"] if p["SPDXID"].startswith("SPDXRef-Package-deb-"))
        clone = json.loads(json.dumps(deb))
        clone["SPDXID"] += "-clone"
        doc["packages"].append(clone)
        self.sbom.write_text(json.dumps(doc))
        result = run(self.sbom, self.nfpm)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("matched 2", result.stderr)

    def test_missing_root_fails(self):
        doc = json.loads(self.sbom.read_text())
        doc["relationships"] = [r for r in doc["relationships"] if r["relationshipType"] != "DESCRIBES"]
        self.sbom.write_text(json.dumps(doc))
        result = run(self.sbom, self.nfpm)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("the document root", result.stderr)

    def test_rpm_drift_fails(self):
        self.nfpm.write_text("license: Apache-2.0\n")
        result = run(self.sbom, self.nfpm)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("drifted", result.stderr)

    def test_documentDescribes_shorthand_also_finds_root(self):
        doc = json.loads(self.sbom.read_text())
        doc["relationships"] = [r for r in doc["relationships"] if r["relationshipType"] != "DESCRIBES"]
        doc["documentDescribes"] = ["SPDXRef-DocumentRoot-Directory-."]
        self.sbom.write_text(json.dumps(doc))
        result = run(self.sbom, self.nfpm)
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
