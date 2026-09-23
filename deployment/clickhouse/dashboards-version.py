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
    dashboards-version.py get                   print that version and nothing else
    dashboards-version.py bundle DIR            write DIR/riptide-dashboards-<version>.tar.gz
    dashboards-version.py helm-values --ref TAG DIR
                                                write DIR/riptide-dashboards-helm-values.yaml,
                                                values for the Grafana Helm chart pinned to TAG

The link's url and tooltip are owned by this script too: `set` rewrites the
whole link, so changing URL below reaches every dashboard on the next bump.

The bundle is the release asset (issue #864): every dashboard plus the
provider file dashboards.yml under one dashboards/ prefix, so an operator
extracts it straight into Grafana's provisioning directory. It is written with
tarfile and pinned metadata so two builds of the same tree are byte-identical
on one machine; the same is expected across macOS and Linux but no CI compares
them.

Grafana's own top-level "version" integer is its save-revision counter and is
left alone.
"""

import argparse
import gzip
import io
import json
import re
import subprocess
import sys
import tarfile
from pathlib import Path

DEFAULT_DIR = Path(__file__).parent / "container-fs/grafana/provisioning/dashboards"
TITLE = re.compile(r"^Dashboards v(.+)$")
SEMVER = re.compile(r"^\d+\.\d+\.\d+$")
URL = "https://riptide.space/docs/guides/grafana-dashboards"
TOOLTIP = "Version of the provisioned dashboard set, independent of the riptide version. Opens the install and upgrade guide."
PROVIDER = "dashboards.yml"
BUNDLE_PREFIX = "dashboards"
REPO_SLUG = "Riptide-Labs/riptide"
HELM_VALUES = "riptide-dashboards-helm-values.yaml"
# The shipped provider points at Grafana's own provisioning directory; the chart
# downloads into a directory named after the provider instead.
COMPOSE_PATH_LINE = "path: /etc/grafana/provisioning/dashboards"
CHART_PATH_LINE = "path: /var/lib/grafana/dashboards/riptide"
# Replaces the chart's defaultCurlOptions "-skf", whose -k turns off TLS
# certificate verification. -S makes a failed download say why in the pod log.
CHART_CURL_OPTIONS = "-sSf"


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
        link = {"title": f"Dashboards v{version}", "type": "link", "url": URL,
                "tooltip": TOOLTIP, "icon": "info", "targetBlank": True}
        if existing:
            # Whole link, in place: url and tooltip follow this script, not the file.
            existing[0].clear()
            existing[0].update(link)
        else:
            links.append(link)
        dump(path, data, original)
    print(f"dashboards version {version} ({len(files)} dashboards)")
    return 0


def agreed_version(directory: Path):
    """(version, files, error): the one version every dashboard carries, or why not."""
    files = sorted(directory.glob("*.json"))
    if not files:
        return None, files, f"no dashboards under {directory}"
    versions = {}
    for path in files:
        version, error = version_of(parse(path.read_text()))
        if error:
            return None, files, f"{path.name}: {error}"
        versions[path.name] = version
    distinct = sorted(set(versions.values()))
    if len(distinct) > 1:
        detail = ", ".join(f"{name} has {v}" for name, v in versions.items())
        return None, files, f"dashboards disagree on their version: {detail}"
    return distinct[0], files, None


def get(directory: Path) -> int:
    version, _, error = agreed_version(directory)
    if error:
        return fail(error)
    print(version)
    return 0


def bundle(directory: Path, out_dir: Path) -> int:
    version, files, error = agreed_version(directory)
    if error:
        return fail(error)
    provider = directory / PROVIDER
    if not provider.is_file():
        return fail(f"{provider} missing: the bundle must provision itself when extracted")
    archive = out_dir / f"riptide-dashboards-{version}.tar.gz"
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w", format=tarfile.PAX_FORMAT) as tar:
        for path in sorted([*files, provider]):
            info = tarfile.TarInfo(f"{BUNDLE_PREFIX}/{path.name}")
            info.size = path.stat().st_size
            info.mode = 0o644
            info.mtime = 0
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            with path.open("rb") as content:
                tar.addfile(info, content)
    with archive.open("wb") as out, gzip.GzipFile(filename="", mode="wb", fileobj=out, mtime=0) as gz:
        gz.write(buffer.getvalue())
    print(f"{archive} ({len(files)} dashboards + {PROVIDER}, set version {version})")
    return 0


def helm_values(directory: Path, ref: str, out_dir: Path) -> int:
    """Values for the grafana-community Grafana chart: one provider, one download per dashboard.

    The provider is dashboards.yml itself, re-indented, with only its path line
    swapped, so it is never written a second time. The URLs point at the files
    at `ref`; release tags here are immutable, so the content behind them is too.

    Everything is read at `ref`, never from the working tree: the file must
    describe what its URLs serve. In the release job the two are the same
    checkout; anywhere else they are not, and a file generated on main for an
    older tag once named a set version its URLs did not serve.
    """
    at_ref = base_dashboards(directory, ref)  # refuses a ref that does not resolve
    if not at_ref:
        return fail(f"no dashboards under {directory.name} at {ref}")
    versions = {}
    for name, text in sorted(at_ref.items()):
        version, error = version_of(parse(text))
        if error:
            return fail(f"{name} at {ref}: {error}")
        versions[name] = version
    if len(set(versions.values())) > 1:
        detail = ", ".join(f"{name} has {v}" for name, v in versions.items())
        return fail(f"dashboards at {ref} disagree on their version: {detail}")
    version = next(iter(versions.values()))

    root = Path(git(directory, "rev-parse", "--show-toplevel").stdout.strip())
    rel = directory.resolve().relative_to(root.resolve()).as_posix()
    shown = git(root, "show", f"{ref}:{rel}/{PROVIDER}")
    if shown.returncode != 0:
        return fail(f"{PROVIDER} missing at {ref}: the chart needs the provider it describes")
    source = shown.stdout.splitlines()
    found = sum(1 for line in source if line.strip() == COMPOSE_PATH_LINE)
    if found != 1:
        return fail(f"{PROVIDER} at {ref}: expected exactly one '{COMPOSE_PATH_LINE}' line, found {found}; "
                    f"cannot swap in the chart's directory")
    base = f"https://raw.githubusercontent.com/{REPO_SLUG}/{ref}/{rel}"

    lines = [
        "# Riptide Grafana dashboards for the grafana-community Grafana Helm chart.",
        f"# Riptide {ref}, dashboard set {version}. Generated by dashboards-version.py; do not edit.",
        "# Add it after your own values: helm upgrade <release> <chart> -f your-values.yaml -f <this file>",
        f"# Guide: {URL}",
        "",
        "dashboardProviders:",
        '  "riptide.yaml":',
    ]
    for line in source:
        if line == "---":
            continue  # a document marker inside a mapping is invalid YAML
        if line.strip() == COMPOSE_PATH_LINE:
            line = line.replace(COMPOSE_PATH_LINE, CHART_PATH_LINE)
        lines.append(f"    {line}" if line else "")
    lines += ["dashboards:", "  riptide:"]
    for name in sorted(at_ref):
        lines += [f'    "{Path(name).stem}":',
                  f'      url: "{base}/{name}"',
                  f'      curlOptions: "{CHART_CURL_OPTIONS}"']
    out = out_dir / HELM_VALUES
    out.write_text("\n".join(lines) + "\n")
    print(f"{out} ({len(at_ref)} dashboards at {ref}, set version {version})")
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
        raise SystemExit(fail(f"ref {ref!r} does not resolve to a commit; fetch it or check its name"))
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
    current, files, error = agreed_version(directory)
    if error:
        return fail(error)
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
    sub.add_parser("get", help="print the version every dashboard carries, and nothing else")
    b = sub.add_parser("bundle", help="write riptide-dashboards-<version>.tar.gz into a directory")
    b.add_argument("out_dir", type=Path)
    h = sub.add_parser("helm-values", help=f"write {HELM_VALUES} for the Grafana Helm chart into a directory")
    h.add_argument("--ref", required=True, help="the release tag the download URLs are pinned to, e.g. v0.15.2")
    h.add_argument("out_dir", type=Path)
    args = parser.parse_args()
    if args.command == "helm-values":
        return helm_values(args.dir, args.ref, args.out_dir)
    if args.command == "set":
        return set_version(args.dir, args.version)
    if args.command == "get":
        return get(args.dir)
    if args.command == "bundle":
        return bundle(args.dir, args.out_dir)
    return check(args.dir, args.base_ref)


if __name__ == "__main__":
    sys.exit(main())
