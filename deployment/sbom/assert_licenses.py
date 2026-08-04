#!/usr/bin/env python3
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

"""Assert first-party license facts in the release SBOM (issue #406).

Syft cannot derive our own license for two of the three entries that describe
what we ship: a deb control file has no license field, and syft has no flag to
attach a license to the scanned directory. This script sets `licenseDeclared`
on exactly those two entries after generation. It runs between SBOM generation
and the HTML report render in the release workflow, so the machine-readable
file, the human-readable report, and the signed asset are the same corrected
document.

The license string is read from nfpm.yaml (`license:`) rather than hardcoded,
so this assertion cannot disagree with what nfpm writes into the RPM header.
The rpm entry is cross-checked, not rewritten: a mismatch there means the
sources of truth have drifted and the release must not ship.

Every selector must match exactly one package; zero or multiple matches exit
non-zero. The silent failure mode — a selector drifting after a syft upgrade
and NOASSERTION shipping again — is the bug class this script exists to
remove, so it fails the release instead.

licenseDeclared is the correct SPDX field here: we are the package author,
and nfpm.yaml/pom.xml/LICENSE are our declaration. licenseConcluded stays
reserved for third-party review (#405).

Spike outcome (2026-08-04, syft latest): the dpkg cataloger DOES read
usr/share/doc/riptide/copyright when scanning a .deb archive, but derives the
noisy expression `GPL-3.0-only AND GPL-3.0-or-later` from the DEP-5 text (the
GPL-3 full-text reference matches both ids). The assertion here normalizes it,
which is why it stays unconditional rather than trusting the cataloger.
"""

import argparse
import json
import re
import sys
from pathlib import Path


def read_license(nfpm_path: Path) -> str:
    """Extract the `license:` value from nfpm.yaml without a YAML dependency."""
    matches = re.findall(r"^license:\s*(\S+)\s*$", nfpm_path.read_text(), re.MULTILINE)
    if len(matches) != 1:
        sys.exit(f"error: expected exactly one `license:` line in {nfpm_path}, found {len(matches)}")
    return matches[0]


def purl_of(package: dict) -> str:
    for ref in package.get("externalRefs", []):
        if ref.get("referenceType") == "purl":
            return ref.get("referenceLocator", "")
    return ""


def select_one(packages: list, predicate, what: str) -> dict:
    hits = [p for p in packages if predicate(p)]
    if len(hits) != 1:
        sys.exit(f"error: selector for {what} matched {len(hits)} packages, expected exactly 1 "
                 f"({[p['SPDXID'] for p in hits]})")
    return hits[0]


def root_ids(doc: dict) -> set:
    """SPDXIDs the document describes — the shorthand field or DESCRIBES relationships."""
    ids = set(doc.get("documentDescribes") or [])
    for rel in doc.get("relationships", []):
        if rel.get("spdxElementId") == "SPDXRef-DOCUMENT" and rel.get("relationshipType") == "DESCRIBES":
            ids.add(rel.get("relatedSpdxElement"))
    return ids


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("sbom", type=Path, help="SPDX JSON file to assert, edited in place")
    parser.add_argument("--nfpm", type=Path, default=Path("nfpm.yaml"),
                        help="packaging descriptor carrying the license (default: nfpm.yaml)")
    args = parser.parse_args()

    license_id = read_license(args.nfpm)
    doc = json.loads(args.sbom.read_text())
    packages = doc.get("packages", [])

    described = root_ids(doc)
    root = select_one(packages, lambda p: p["SPDXID"] in described, "the document root")
    deb = select_one(packages, lambda p: purl_of(p).startswith("pkg:deb/"), "the deb package")
    rpm = select_one(packages, lambda p: purl_of(p).startswith("pkg:rpm/"), "the rpm package")

    if rpm.get("licenseDeclared") != license_id:
        sys.exit(f"error: rpm entry declares {rpm.get('licenseDeclared')!r} but {args.nfpm} says "
                 f"{license_id!r} — the sources of truth have drifted")

    for package in (root, deb):
        package["licenseDeclared"] = license_id

    args.sbom.write_text(json.dumps(doc, indent=1) + "\n")
    print(f"asserted licenseDeclared={license_id} on {root['SPDXID']} and {deb['SPDXID']}; "
          f"rpm entry already declares it")


if __name__ == "__main__":
    main()
