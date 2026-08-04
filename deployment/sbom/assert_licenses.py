#!/usr/bin/env python3
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

"""Assert license facts in the release SBOM (issues #406 and #405).

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
and nfpm.yaml/pom.xml/LICENSE are our declaration.

A second pass (issue #405) sets `licenseConcluded` on third-party packages
syft cannot identify: jars shipping no Maven metadata get a self-referential
filename-derived purl, so no POM lookup can ever rescue them. The conclusions
come from concluded-licenses.json, a reviewed allowlist keyed on the exact
purl; each entry carries its review evidence, which is mirrored into the
package's licenseComments so the shipped document explains itself. One purl
can match several packages (syft catalogues the same jar once per path); all
matches get the conclusion, zero matches fail. The exact-purl match embeds
the version, so a dependency bump (or a syft fix that starts deriving real
coordinates) makes the entry match nothing and fails the release: a bump
forces a re-review instead of silently carrying a stale conclusion.
licenseConcluded, never licenseDeclared, keeps third-party review
distinguishable from first-party declaration.

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


ALLOWLIST_FIELDS = ("purl", "licenseConcluded", "evidence", "reviewed")


def load_allowlist(path: Path) -> list:
    entries = json.loads(path.read_text())
    for entry in entries:
        missing = [f for f in ALLOWLIST_FIELDS if not entry.get(f)]
        if missing:
            sys.exit(f"error: allowlist entry {entry.get('purl', '<no purl>')!r} in {path} "
                     f"is missing {missing} — a conclusion without evidence is not reviewable")
    return entries


def conclude(packages: list, entry: dict, allowlist_path: Path) -> list:
    # The same jar is catalogued once per path syft finds it at (plain jar, packaged
    # copy), so one purl legitimately matches several entries; all get the conclusion.
    hits = [p for p in packages if purl_of(p) == entry["purl"]]
    if not hits:
        sys.exit(f"error: allowlist entry {entry['purl']} matched 0 packages — a dependency bump "
                 f"or a syft change invalidated it; re-review {allowlist_path} and update or "
                 f"remove the entry")
    for package in hits:
        for field in ("licenseDeclared", "licenseConcluded"):
            current = package.get(field, "NOASSERTION")
            if current != "NOASSERTION":
                sys.exit(f"error: {entry['purl']} now carries {field}={current!r} — syft can "
                         f"identify it, so the allowlist entry in {allowlist_path} is redundant "
                         f"or wrong; re-review and update or remove it")
        package["licenseConcluded"] = entry["licenseConcluded"]
        package["licenseComments"] = f"Concluded by the Riptide release review ({entry['reviewed']}): {entry['evidence']}"
    return hits


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
    parser.add_argument("--concluded", type=Path, default=Path(__file__).parent / "concluded-licenses.json",
                        help="reviewed third-party license allowlist (default: beside this script)")
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

    concluded = [p for entry in load_allowlist(args.concluded)
                 for p in conclude(packages, entry, args.concluded)]

    args.sbom.write_text(json.dumps(doc, indent=1) + "\n")
    print(f"asserted licenseDeclared={license_id} on {root['SPDXID']} and {deb['SPDXID']}; "
          f"rpm entry already declares it")
    for package in concluded:
        print(f"concluded {package['licenseConcluded']} on {package['SPDXID']} per allowlist")


if __name__ == "__main__":
    main()
