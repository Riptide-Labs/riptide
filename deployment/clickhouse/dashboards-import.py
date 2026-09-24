#!/usr/bin/env python3
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

"""Import the riptide Grafana dashboards into a Grafana over its HTTP API.

For a Grafana that takes no provisioning files. Needs python3 and nothing else.

    GRAFANA_TOKEN=<service-account token, Editor role> \\
      python3 riptide-dashboards-import.py --grafana https://grafana.example.org \\
        riptide-dashboards-1.0.2.tar.gz

SOURCE is a release tarball or a directory of dashboard JSON, such as
/usr/share/riptide/grafana/dashboards from the packages. The script creates the
folder "Riptide" with "Flow Analytics" inside it (or moves an existing "Flow
Analytics" there) and imports every dashboard into it with overwrite, keeping
each uid. It prints one line per dashboard and exits non-zero if any was not
imported. It deletes nothing. Grafana keeps every replaced dashboard in its
version history, so an overwritten UI edit can be restored from the Versions
tab. --dry-run prints the same report and writes nothing.

The token is read from GRAFANA_TOKEN and never from the command line, so it
stays out of process listings and shell history. For an https URL, TLS
certificates are verified. Redirects are never followed: a redirected POST
would silently become a GET, and the token would travel to wherever the
redirect points, so the script stops and names the URL to use instead.

Grafana answers as measured on 13.2.2 with an Editor token (issue #872): an
absent folder reads as 403, not 404, so folders are created first and a 412
means "already there".

Guide: https://riptide.space/docs/guides/grafana-dashboards
"""

import argparse
import http.client
import json
import os
import re
import sys
import tarfile
import urllib.error
import urllib.request
from pathlib import Path

PARENT_UID, PARENT_TITLE = "riptide", "Riptide"
FOLDER_UID, FOLDER_TITLE = "riptide-flow-analytics", "Flow Analytics"
# The set's version lives in one link per dashboard. The same pattern is TITLE
# in dashboards-version.py; this script ships alone, so it carries its own copy.
SET_VERSION = re.compile(r"^Dashboards v(\d+\.\d+\.\d+)$")


class Stop(Exception):
    """A condition every later request would hit too: stop the run."""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise Stop(f"Grafana answered {code} with a redirect to {newurl}. Redirects are not followed, "
                   f"so the token goes only to the URL you give: pass the final URL to --grafana")


class Grafana:
    def __init__(self, url, token):
        self.url = url.rstrip("/")
        self.token = token
        self.opener = urllib.request.build_opener(NoRedirect)

    def call(self, method, path, body=None):
        request = urllib.request.Request(self.url + path, method=method,
                                         data=json.dumps(body).encode() if body is not None else None)
        request.add_header("Authorization", f"Bearer {self.token}")
        request.add_header("Content-Type", "application/json")
        request.add_header("Accept", "application/json")
        try:
            with self.opener.open(request, timeout=30) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as error:
            status, raw = error.code, error.read()
        except urllib.error.URLError as error:
            raise Stop(f"cannot reach {self.url}: {error.reason}")
        except (OSError, http.client.HTTPException) as error:
            # A timeout after connecting, or a dropped connection: where the run
            # stopped is the last line printed above.
            raise Stop(f"lost the connection during {method} {path}: {error or type(error).__name__}")
        try:
            answer = json.loads(raw) if raw else {}
        except ValueError:
            answer = {"message": raw.decode(errors="replace")[:200]}
        if status == 401:
            raise Stop(f"Grafana rejected the token (401 {message(answer)})")
        return status, answer

    def write(self, method, path, body):
        status, answer = self.call(method, path, body)
        if status == 403:
            raise Stop(f"the token may not do this (403): {message(answer)}. "
                       f"It needs the Editor role or folders:create, folders:write, dashboards:create, dashboards:write")
        return status, answer


def message(answer):
    return answer.get("message", "") if isinstance(answer, dict) else str(answer)


def set_version(dashboard):
    for link in dashboard.get("links", []):
        found = SET_VERSION.match(str(link.get("title", "")))
        if found:
            return found.group(1)
    return None


def read_source(source):
    """{file name: dashboard} from a tarball or a directory; every dashboard carries one set version."""
    path = Path(source)
    if path.is_dir():
        files = {p.name: p.read_text() for p in sorted(path.glob("*.json"))}
    elif path.is_file() and tarfile.is_tarfile(path):
        with tarfile.open(path) as tar:
            files = {Path(m.name).name: tar.extractfile(m).read().decode()
                     for m in sorted(tar.getmembers(), key=lambda m: m.name)
                     if m.isfile() and m.name.endswith(".json")}
    else:
        raise Stop(f"{source} is neither a directory nor a tarball")
    if not files:
        raise Stop(f"no dashboard JSON in {source}")
    dashboards, versions = {}, {}
    for name, text in files.items():
        try:
            dashboard = json.loads(text)
        except ValueError as error:
            raise Stop(f"{name} is not valid JSON: {error}")
        if not dashboard.get("uid"):
            raise Stop(f"{name} has no uid")
        versions[name] = set_version(dashboard)
        dashboards[name] = dashboard
    if len(set(versions.values())) != 1 or None in versions.values():
        raise Stop("the dashboards do not carry one set version: "
                   + ", ".join(f"{name} has {v or 'none'}" for name, v in versions.items()))
    return dashboards, next(iter(versions.values()))


def ensure_folders(grafana, dry_run):
    if dry_run:
        for uid, title in ((PARENT_UID, PARENT_TITLE), (FOLDER_UID, FOLDER_TITLE)):
            status, folder = grafana.call("GET", f"/api/folders/{uid}")
            if status != 200:
                # 403 and 404 both mean absent here: an Editor reads an absent folder as 403.
                print(f"folder {title}: would create")
            elif uid == FOLDER_UID and folder.get("parentUid") != PARENT_UID:
                print(f"folder {title}: would move under {PARENT_TITLE}")
            else:
                print(f"folder {title}: present")
        return

    status, answer = grafana.write("POST", "/api/folders", {"uid": PARENT_UID, "title": PARENT_TITLE})
    if status not in (200, 412):
        raise Stop(f"cannot create folder {PARENT_TITLE} ({status}): {message(answer)}")
    print(f"folder {PARENT_TITLE}: {'created' if status == 200 else 'present'}")

    status, answer = grafana.write("POST", "/api/folders",
                                   {"uid": FOLDER_UID, "title": FOLDER_TITLE, "parentUid": PARENT_UID})
    if status == 200:
        print(f"folder {FOLDER_TITLE}: created")
        return
    if status != 412:
        raise Stop(f"cannot create folder {FOLDER_TITLE} ({status}): {message(answer)}")
    status, folder = grafana.call("GET", f"/api/folders/{FOLDER_UID}")
    if status != 200:
        raise Stop(f"folder {FOLDER_TITLE} exists but cannot be read ({status}): {message(folder)}")
    if folder.get("parentUid") == PARENT_UID:
        print(f"folder {FOLDER_TITLE}: present")
        return
    status, answer = grafana.write("POST", f"/api/folders/{FOLDER_UID}/move", {"parentUid": PARENT_UID})
    if status != 200:
        raise Stop(f"cannot move folder {FOLDER_TITLE} under {PARENT_TITLE} ({status}): {message(answer)}")
    print(f"folder {FOLDER_TITLE}: moved under {PARENT_TITLE}")


def import_all(grafana, dashboards, version, dry_run):
    """Returns (refused, {folder uid: title} left by a moved dashboard)."""
    refused, left = 0, {}
    for name in sorted(dashboards):
        dashboard = dict(dashboards[name], id=None)
        uid = dashboard["uid"]
        status, current = grafana.call("GET", f"/api/dashboards/uid/{uid}")
        # 404, or 403 as for folders: either way it is not there for this token.
        existing = status == 200
        was = set_version(current.get("dashboard", {})) if existing else None
        revision = current.get("dashboard", {}).get("version") if existing else None
        folder = current.get("meta", {}).get("folderUid") or "" if existing else ""
        moving = existing and folder not in ("", FOLDER_UID)
        where = f" (from folder '{current['meta'].get('folderTitle', folder)}')" if moving else ""

        if dry_run:
            if not existing:
                action = "create"
            elif was == version:
                action = f"re-import {version}"
            else:
                action = f"update {was or 'unversioned'} -> {version}"
            print(f"{uid}: would {action}{where}")
            continue
        # A 403 here is about this one dashboard (a copy in a folder the token
        # cannot touch), not the token: it is a refusal, and the others go on.
        # A token that may write nothing has already stopped at the folders.
        status, answer = grafana.call("POST", "/api/dashboards/db",
                                      {"dashboard": dashboard, "folderUid": FOLDER_UID, "overwrite": True})
        if status != 200:
            refused += 1
            print(f"{uid}: refused: {message(answer)}")
            continue
        if not existing:
            print(f"{uid}: created{where}")
        elif answer.get("version") == revision:
            # Grafana keeps the revision when the body is identical: nothing was replaced.
            print(f"{uid}: unchanged ({version}){where}")
        else:
            print(f"{uid}: updated {was or 'unversioned'} -> {version}{where}")
        if moving:
            left[folder] = current["meta"].get("folderTitle", folder)
    return refused, left


def report_left(grafana, left):
    """What a moved dashboard left behind, from Grafana's own counts.

    Not from search: a folder's dashboards are only part of what deleting it
    removes, and on 13.2.2 search showed a folder as holding nothing while it
    still held a library panel. The counts cover every kind a delete takes.
    """
    for uid, title in sorted(left.items()):
        status, counts = grafana.call("GET", f"/api/folders/{uid}/counts")
        if status != 200 or not isinstance(counts, dict):
            print(f"folder '{title}' ({uid}): riptide dashboards moved out; Grafana did not report what is left ({status})")
            continue
        remaining = {kind: n for kind, n in sorted(counts.items()) if isinstance(n, int) and n}
        if remaining:
            print(f"folder '{title}' ({uid}) still holds "
                  + ", ".join(f"{kind}: {n}" for kind, n in remaining.items()))
        else:
            print(f"folder '{title}' ({uid}) is now empty: no dashboards, folders, alert rules or library elements. "
                  f"This script deletes nothing.")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--grafana", required=True, help="Grafana base URL, e.g. https://grafana.example.org")
    parser.add_argument("--dry-run", action="store_true", help="report what would change, write nothing")
    parser.add_argument("source", help="release tarball or directory of dashboard JSON")
    args = parser.parse_args()

    token = os.environ.get("GRAFANA_TOKEN", "").strip()
    if not token:
        print("error: set GRAFANA_TOKEN to a Grafana service-account token (Editor role)", file=sys.stderr)
        return 1
    try:
        dashboards, version = read_source(args.source)
        grafana = Grafana(args.grafana, token)
        grafana.call("GET", "/api/search?limit=1")  # a wrong token stops here, before any write
        print(f"riptide dashboard set {version}, {len(dashboards)} dashboards -> {grafana.url}"
              + (" (dry run, nothing is written)" if args.dry_run else ""))
        ensure_folders(grafana, args.dry_run)
        refused, left = import_all(grafana, dashboards, version, args.dry_run)
        if not args.dry_run:
            report_left(grafana, left)
    except Stop as stop:
        print(f"error: {stop}", file=sys.stderr)
        return 1
    if refused:
        print(f"{refused} of {len(dashboards)} dashboards were not imported", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
