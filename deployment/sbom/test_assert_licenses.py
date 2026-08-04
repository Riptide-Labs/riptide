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


ALLOWLIST = HERE / "concluded-licenses.json"


def run(sbom: Path, nfpm: Path, concluded: Path = ALLOWLIST):
    return subprocess.run([sys.executable, str(SCRIPT), str(sbom), "--nfpm", str(nfpm),
                           "--concluded", str(concluded)],
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
        self.assertEqual(third_party["licenseConcluded"], "NOASSERTION")

    def test_success_concludes_allowlisted_packages_with_evidence(self):
        result = run(self.sbom, self.nfpm)
        self.assertEqual(result.returncode, 0, result.stderr)
        # Only the filename-derived purls are allowlisted; the pom-cataloger entry with
        # the real annotations coordinates already carries a POM-resolved LicenseRef.
        pkgs = self.packages()
        concluded = [p for p in pkgs.values()
                     if p["name"] in ("annotations", "RoaringBitmap")
                     and p["licenseDeclared"] == "NOASSERTION"]
        self.assertEqual(len(concluded), 2)
        for pkg in concluded:
            self.assertEqual(pkg["licenseConcluded"], "Apache-2.0")
            self.assertIn("Maven Central", pkg["licenseComments"])
        pom_derived = pkgs["SPDXRef-Package-java-archive-annotations-aed61a17fe9b8687"]
        self.assertEqual(pom_derived["licenseConcluded"], "NOASSERTION")
        self.assertEqual(pom_derived["licenseDeclared"],
                         "LicenseRef-The-Apache-Software-License--Version-2.0")

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

    def test_stale_allowlist_entry_fails(self):
        # A dependency bump changes the filename-derived purl; the entry must not silently carry over.
        doc = json.loads(self.sbom.read_text())
        rb = next(p for p in doc["packages"] if p["name"] == "RoaringBitmap")
        for ref in rb["externalRefs"]:
            if ref["referenceType"] == "purl":
                ref["referenceLocator"] = "pkg:maven/RoaringBitmap/RoaringBitmap@1.0.7"
        self.sbom.write_text(json.dumps(doc))
        result = run(self.sbom, self.nfpm)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("pkg:maven/RoaringBitmap/RoaringBitmap@1.0.6", result.stderr)
        self.assertIn("matched 0", result.stderr)
        self.assertIn("re-review", result.stderr)

    def test_allowlist_purl_matching_multiple_entries_concludes_all(self):
        # Real SBOMs carry the same jar once per path syft finds it at (plain jar,
        # packaged copy); every copy must get the conclusion.
        doc = json.loads(self.sbom.read_text())
        rb = next(p for p in doc["packages"] if p["name"] == "RoaringBitmap")
        clone = json.loads(json.dumps(rb))
        clone["SPDXID"] += "-clone"
        doc["packages"].append(clone)
        self.sbom.write_text(json.dumps(doc))
        result = run(self.sbom, self.nfpm)
        self.assertEqual(result.returncode, 0, result.stderr)
        pkgs = self.packages()
        self.assertEqual(pkgs[rb["SPDXID"]]["licenseConcluded"], "Apache-2.0")
        self.assertEqual(pkgs[clone["SPDXID"]]["licenseConcluded"], "Apache-2.0")

    def test_package_gaining_a_derived_license_fails(self):
        # If a syft upgrade starts identifying the package, the conclusion needs a re-review.
        doc = json.loads(self.sbom.read_text())
        rb = next(p for p in doc["packages"] if p["name"] == "RoaringBitmap")
        rb["licenseDeclared"] = "Apache-2.0"
        self.sbom.write_text(json.dumps(doc))
        result = run(self.sbom, self.nfpm)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("licenseDeclared='Apache-2.0'", result.stderr)
        self.assertIn("re-review", result.stderr)

    def test_allowlist_entry_missing_evidence_fails(self):
        bad = self.tmp / "concluded.json"
        bad.write_text(json.dumps([{"purl": "pkg:maven/RoaringBitmap/RoaringBitmap@1.0.6",
                                    "licenseConcluded": "Apache-2.0", "reviewed": "2026-08-04"}]))
        result = run(self.sbom, self.nfpm, concluded=bad)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("evidence", result.stderr)

    def test_documentDescribes_shorthand_also_finds_root(self):
        doc = json.loads(self.sbom.read_text())
        doc["relationships"] = [r for r in doc["relationships"] if r["relationshipType"] != "DESCRIBES"]
        doc["documentDescribes"] = ["SPDXRef-DocumentRoot-Directory-."]
        self.sbom.write_text(json.dumps(doc))
        result = run(self.sbom, self.nfpm)
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
