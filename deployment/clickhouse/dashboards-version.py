#!/usr/bin/env python3
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

"""The version of the provisioned Grafana dashboard set.

The nine dashboards ship together and link to each other by uid and variable
name, so they carry one version, independent of the riptide version in
pom.xml. It lives in exactly one place: a link titled "Dashboards vX.Y.Z" in
every dashboard's top link bar, where a user reads it without opening
settings.

    dashboards-version.py set 1.2.0             rewrite (or add) the link in every dashboard
    dashboards-version.py check                 every dashboard carries the same well-formed version
    dashboards-version.py check --base-ref REF  ... and it moved if any dashboard differs from REF

Grafana's own top-level "version" integer is its save-revision counter and is
left alone.
"""

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

DEFAULT_DIR = Path(__file__).parent / "container-fs/grafana/provisioning/dashboards"
TITLE = re.compile(r"^Dashboards v(.+)$")
SEMVER = re.compile(r"^\d+\.\d+\.\d+$")
URL = "https://github.com/Riptide-Labs/riptide/tree/main/deployment/clickhouse/container-fs/grafana/provisioning/dashboards"
TOOLTIP = "Version of the provisioned dashboard set. Independent of the riptide version."


def fail(message: str) -> int:
    print(f"error: {message}", file=sys.stderr)
    return 1


def dump(path: Path, data: dict, original: str) -> None:
    # Same shape the checked-in files use, so a version bump is a one-line diff.
    # The files do not agree on how they store non-ASCII text (some escape an
    # em dash as —, a UI export keeps it raw), so each keeps its own way:
    # a file with no non-ASCII character stays escaped, any other stays raw.
    path.write_text(json.dumps(data, indent=2, ensure_ascii=original.isascii()) + "\n")


def version_of(data: dict):
    """(version, error) for one dashboard; version is None when absent."""
    found = [m.group(1) for link in data.get("links", []) if (m := TITLE.match(str(link.get("title", ""))))]
    if not found:
        return None, "no version link"
    if len(found) > 1:
        return None, "more than one version link"
    if not SEMVER.match(found[0]):
        return None, f"malformed version {found[0]!r}"
    return found[0], None


def parse(text: str) -> dict:
    return json.loads(text)


def set_version(directory: Path, version: str) -> int:
    if not SEMVER.match(version):
        return fail(f"{version!r} is not MAJOR.MINOR.PATCH")
    files = sorted(directory.glob("*.json"))
    if not files:
        return fail(f"no dashboards under {directory}")
    for path in files:
        original = path.read_text()
        data = parse(original)
        links = data.setdefault("links", [])
        existing = [link for link in links if TITLE.match(str(link.get("title", "")))]
        if len(existing) > 1:
            return fail(f"{path.name}: more than one version link; remove the stale one by hand")
        if existing:
            existing[0]["title"] = f"Dashboards v{version}"
        else:
            links.append({"title": f"Dashboards v{version}", "type": "link", "url": URL,
                          "tooltip": TOOLTIP, "icon": "info", "targetBlank": True})
        dump(path, data, original)
    print(f"dashboards version {version} ({len(files)} dashboards)")
    return 0


def git(directory: Path, *args) -> subprocess.CompletedProcess:
    return subprocess.run(["git", "-C", str(directory), *args], capture_output=True, text=True)


def base_dashboards(directory: Path, ref: str) -> dict:
    """{name: text} of every dashboard under `directory` at `ref`.

    A ref that does not resolve is an error, never "no dashboards": treating it
    as the latter would turn the CI gate into a silent no-op on an unfetched or
    mistyped base.
    """
    resolved = git(directory, "rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}")
    if resolved.returncode != 0:
        raise SystemExit(fail(f"base ref {ref!r} does not resolve to a commit"))
    root = Path(git(directory, "rev-parse", "--show-toplevel").stdout.strip())
    rel = directory.resolve().relative_to(root).as_posix()
    listing = git(root, "ls-tree", "--name-only", ref, "--", rel + "/")
    if listing.returncode != 0:
        raise SystemExit(fail(f"git ls-tree {ref} failed: {listing.stderr.strip()}"))
    out = {}
    for line in listing.stdout.splitlines():
        if line.endswith(".json"):
            shown = git(root, "show", f"{ref}:{line}")
            if shown.returncode != 0:
                raise SystemExit(fail(f"git show {ref}:{line} failed: {shown.stderr.strip()}"))
            out[Path(line).name] = shown.stdout
    return out


def check(directory: Path, base_ref) -> int:
    files = sorted(directory.glob("*.json"))
    if not files:
        return fail(f"no dashboards under {directory}")
    versions = {}
    for path in files:
        version, error = version_of(parse(path.read_text()))
        if error:
            return fail(f"{path.name}: {error}")
        versions[path.name] = version
    distinct = sorted(set(versions.values()))
    if len(distinct) > 1:
        detail = ", ".join(f"{name} has {v}" for name, v in versions.items())
        return fail(f"dashboards disagree on their version: {detail}")
    current = distinct[0]
    print(f"dashboards version {current} ({len(files)} dashboards)")
    if base_ref is None:
        return 0

    base = base_dashboards(directory, base_ref)
    current_text = {path.name: path.read_text() for path in files}
    # Added, removed and modified all count: a removed dashboard is exactly the
    # kind of set change the version exists to mark.
    changed = sorted(name for name in set(base) | set(current_text) if base.get(name) != current_text.get(name))
    base_versions = {v for text in base.values() if (v := version_of(parse(text))[0])}
    if not base_versions:
        return 0  # the base had no version yet: this change introduces it
    base_version = max(base_versions, key=lambda v: tuple(map(int, v.split("."))))
    as_tuple = lambda v: tuple(map(int, v.split(".")))
    if as_tuple(current) < as_tuple(base_version):
        return fail(f"dashboards version {current} is lower than {base_version} at {base_ref}")
    if changed and current == base_version:
        return fail(f"{', '.join(changed)} changed since {base_ref} but the dashboards version is still {current}; "
                    f"bump it with: make dashboards-version DASHBOARDS_VERSION=<new>")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dir", type=Path, default=DEFAULT_DIR, help="the dashboards directory")
    sub = parser.add_subparsers(dest="command", required=True)
    s = sub.add_parser("set", help="write this version into every dashboard")
    s.add_argument("version")
    c = sub.add_parser("check", help="every dashboard carries the same well-formed version")
    c.add_argument("--base-ref", help="also require a bump when any dashboard differs from this git ref")
    args = parser.parse_args()
    if args.command == "set":
        return set_version(args.dir, args.version)
    return check(args.dir, args.base_ref)


if __name__ == "__main__":
    sys.exit(main())
