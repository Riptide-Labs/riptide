#!/usr/bin/env python3
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

"""Fixture tests for dashboards-import.py. Run with:
    python3 -m unittest discover -s deployment/clickhouse

Each test starts an in-process fake Grafana that answers the way Grafana 13.2.2
answered an Editor service-account token when measured on 2026-09-24 (issue
#872): an absent folder reads as 403, an existing folder re-created is 412, a
file-provisioned dashboard is refused with 400, an existing uid without
overwrite is 412. The script runs as a subprocess against it, and the tests
assert both what it printed and every request it sent. A fake can drift from
the real thing, so the same behaviour is also run against a real Grafana
before a change ships; see the guide section this script belongs to.
"""

import io
import json
import os
import subprocess
import sys
import tarfile
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

SCRIPT = Path(__file__).parent / "dashboards-import.py"
TOKEN = "glsa_editor"
VIEWER = "glsa_viewer"
FOLDER = "riptide-flow-analytics"


def dashboard(uid, version="1.0.2"):
    return {"uid": uid, "title": f"Riptide - {uid}", "panels": [],
            "links": [{"title": f"Dashboards v{version}", "type": "link", "url": "https://example.org"}]}


class FakeGrafana:
    """The measured answers, plus a log of every request."""

    def __init__(self):
        self.folders = {}      # uid -> {"title", "parentUid"}
        self.dashboards = {}   # uid -> {"dashboard", "folderUid", "version", "provisioned"}
        self.library = {}      # folder uid -> number of library elements in it
        self.restricted = set()  # dashboard uids this token may neither read nor write
        self.drop = set()        # dashboard uids whose import drops the connection
        self.requests = []     # (method, path, body)
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def _reply(self, status, body):
                raw = json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def _handle(self, method):
                length = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(length)) if length else None
                fake.requests.append((method, self.path, body))
                if self.path.startswith("/moved/"):
                    # An http-to-https style redirect in front of Grafana.
                    self.send_response(301)
                    self.send_header("Location", self.path.removeprefix("/moved"))
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                if method == "POST" and self.path == "/api/dashboards/db" and body["dashboard"]["uid"] in fake.drop:
                    self.close_connection = True
                    return
                token = (self.headers.get("Authorization") or "").removeprefix("Bearer ")
                if token not in (TOKEN, VIEWER):
                    return self._reply(401, {"message": "Invalid API key"})
                status, answer = fake.answer(method, urlparse(self.path), body, token == VIEWER)
                self._reply(status, answer)

            def do_GET(self):
                self._handle("GET")

            def do_POST(self):
                self._handle("POST")

            def do_DELETE(self):
                self._handle("DELETE")

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()

    def writes(self):
        return [r for r in self.requests if r[0] != "GET"]

    def answer(self, method, url, body, viewer):
        path = url.path
        denied = lambda perm: (403, {"title": "Access denied", "message":
                                     f"You'll need additional permissions to perform this action. Permissions needed: {perm}"})
        if method == "GET" and path == "/api/search":
            q = parse_qs(url.query)
            if "folderUIDs" in q:
                wanted = q["folderUIDs"][0]
                return 200, [{"uid": u} for u, d in self.dashboards.items() if d["folderUid"] == wanted]
            return 200, []
        if path.startswith("/api/folders"):
            parts = path.split("/")[3:]
            if method == "GET" and len(parts) == 1:
                f = self.folders.get(parts[0])
                return (200, {"uid": parts[0], **f}) if f else denied("folders:read")
            if method == "GET" and len(parts) == 2 and parts[1] == "counts":
                # Measured on 13.2.2: every kind a folder delete would take with it.
                uid = parts[0]
                return 200, {"alertrules": 0, "librarypanels": 0,
                             "dashboards": sum(1 for d in self.dashboards.values() if d["folderUid"] == uid),
                             "folders": sum(1 for f in self.folders.values() if f.get("parentUid") == uid),
                             "library_elements": self.library.get(uid, 0)}
            if method == "POST" and not parts:
                if viewer:
                    return denied("folders:create")
                if body["uid"] in self.folders:
                    return 412, {"message": "the folder has been changed by someone else"}
                self.folders[body["uid"]] = {"title": body["title"], "parentUid": body.get("parentUid")}
                return 200, {"uid": body["uid"], **self.folders[body["uid"]]}
            if method == "POST" and len(parts) == 2 and parts[1] == "move":
                self.folders[parts[0]]["parentUid"] = body["parentUid"]
                return 200, {"uid": parts[0], **self.folders[parts[0]]}
            if method == "DELETE":
                return denied("folders:delete")
        if method == "GET" and path.startswith("/api/dashboards/uid/"):
            uid = path.rsplit("/", 1)[1]
            if uid in self.restricted:
                return denied("dashboards:read")
            d = self.dashboards.get(uid)
            if not d:
                return 404, {"message": "Dashboard not found"}
            folder = self.folders.get(d["folderUid"], {})
            # Grafana returns the revision inside the dashboard JSON, as measured on 13.2.2.
            return 200, {"dashboard": {**d["dashboard"], "version": d["version"]}, "meta": {"folderUid": d["folderUid"],
                         "folderTitle": folder.get("title", "General"), "provisioned": d["provisioned"]}}
        if method == "POST" and path == "/api/dashboards/db":
            if viewer:
                return denied("any of dashboards:create, dashboards:write")
            uid = body["dashboard"]["uid"]
            if uid in self.restricted:
                return denied("dashboards:write")
            old = self.dashboards.get(uid)
            if old and old["provisioned"]:
                return 400, {"message": "Cannot save provisioned dashboard"}
            if old and not body.get("overwrite"):
                return 412, {"message": "A dashboard with the same uid already exists", "status": "name-exists"}
            # Measured on 13.2.2: re-posting an identical dashboard keeps its version.
            same = old and old["dashboard"] == body["dashboard"] and old["folderUid"] == body.get("folderUid", "")
            version = (old["version"] if same else old["version"] + 1) if old else 1
            self.dashboards[uid] = {"dashboard": body["dashboard"], "folderUid": body.get("folderUid", ""),
                                    "version": version, "provisioned": False}
            return 200, {"uid": uid, "version": version, "status": "success", "folderUid": body.get("folderUid")}
        return 404, {"message": "Not found"}


class ImportTest(unittest.TestCase):

    def setUp(self):
        self.grafana = FakeGrafana()
        self.addCleanup(self.grafana.close)
        self.tmp = Path(tempfile.mkdtemp())

    def source(self, uids=("a", "b", "c"), version="1.0.2", versions=None):
        d = self.tmp / f"set-{version}"
        d.mkdir(exist_ok=True)
        for uid in uids:
            v = (versions or {}).get(uid, version)
            (d / f"{uid}.json").write_text(json.dumps(dashboard(uid, v), indent=2) + "\n")
        (d / "dashboards.yml").write_text("apiVersion: 1\n")
        return d

    def tarball(self, directory):
        path = self.tmp / "riptide-dashboards.tar.gz"
        with tarfile.open(path, "w:gz") as tar:
            for f in sorted(directory.iterdir()):
                tar.add(f, arcname=f"dashboards/{f.name}")
        return path

    def run_import(self, source, *args, token=TOKEN, grafana=None):
        env = {k: v for k, v in os.environ.items() if k != "GRAFANA_TOKEN"}
        if token is not None:
            env["GRAFANA_TOKEN"] = token
        return subprocess.run([sys.executable, str(SCRIPT), "--grafana", grafana or self.grafana.url, *args, str(source)],
                              capture_output=True, text=True, env=env, timeout=60)

    def imports(self):
        return [b for m, p, b in self.grafana.requests if m == "POST" and p == "/api/dashboards/db"]


class AnEmptyInstance(ImportTest):

    def test_gets_both_folders_nested_and_every_dashboard_in_the_inner_one(self):
        proc = self.run_import(self.source())

        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertEqual(self.grafana.folders["riptide"]["title"], "Riptide")
        self.assertEqual(self.grafana.folders[FOLDER], {"title": "Flow Analytics", "parentUid": "riptide"})
        self.assertEqual(sorted(self.grafana.dashboards), ["a", "b", "c"])
        for body in self.imports():
            self.assertIsNone(body["dashboard"]["id"])
            self.assertEqual(body["folderUid"], FOLDER)
            self.assertIs(body["overwrite"], True)
        for uid in "abc":
            self.assertIn(f"{uid}: created", proc.stdout)


class FoldersAlreadyThere(ImportTest):

    def test_a_412_means_present_and_a_top_level_flow_analytics_is_moved_under_riptide(self):
        self.grafana.folders["riptide"] = {"title": "Riptide", "parentUid": None}
        self.grafana.folders[FOLDER] = {"title": "Flow Analytics", "parentUid": None}

        proc = self.run_import(self.source())

        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertEqual(self.grafana.folders[FOLDER]["parentUid"], "riptide")
        self.assertIn(("POST", f"/api/folders/{FOLDER}/move", {"parentUid": "riptide"}), self.grafana.requests)
        self.assertEqual(len(self.imports()), 3)

    def test_a_nested_flow_analytics_is_left_alone(self):
        self.grafana.folders["riptide"] = {"title": "Riptide", "parentUid": None}
        self.grafana.folders[FOLDER] = {"title": "Flow Analytics", "parentUid": "riptide"}

        proc = self.run_import(self.source())

        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertFalse([r for r in self.grafana.requests if r[1].endswith("/move")])


class ARerun(ImportTest):

    def test_reports_the_set_version_each_dashboard_had(self):
        self.run_import(self.source(version="1.0.0"))

        proc = self.run_import(self.source(version="1.0.2"))

        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        for uid in "abc":
            self.assertIn(f"{uid}: updated 1.0.0 -> 1.0.2", proc.stdout)
            self.assertEqual(self.grafana.dashboards[uid]["version"], 2)


class AnIdenticalRerun(ImportTest):

    def test_reports_unchanged_rather_than_updated(self):
        self.run_import(self.source())

        proc = self.run_import(self.source())

        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        for uid in "abc":
            self.assertIn(f"{uid}: unchanged (1.0.2)", proc.stdout)
        self.assertNotIn("updated", proc.stdout)

    def test_a_dry_run_says_re_import_not_update_to_the_same_version(self):
        self.run_import(self.source())

        proc = self.run_import(self.source(), "--dry-run")

        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertIn("a: would re-import 1.0.2", proc.stdout)
        self.assertNotIn("1.0.2 -> 1.0.2", proc.stdout)


class AHandImportedCopy(ImportTest):

    def test_is_moved_in_and_the_folder_it_left_empty_is_named_not_deleted(self):
        self.grafana.folders["efsybbe4ozv28c"] = {"title": "Riptide Flow Analytics", "parentUid": None}
        self.grafana.dashboards["a"] = {"dashboard": dashboard("a", "1.0.0"), "folderUid": "efsybbe4ozv28c",
                                        "version": 3, "provisioned": False}

        proc = self.run_import(self.source())

        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertEqual(self.grafana.dashboards["a"]["folderUid"], FOLDER)
        self.assertIn("Riptide Flow Analytics", proc.stdout)
        self.assertIn("efsybbe4ozv28c", proc.stdout)
        self.assertIn("empty", proc.stdout)
        self.assertFalse([r for r in self.grafana.requests if r[0] == "DELETE"], "the script deletes nothing")

    def test_a_folder_holding_only_a_library_panel_is_not_called_empty(self):
        # Found on a real Grafana: search showed 0 dashboards in the folder, but
        # deleting it would have taken the library panel with it.
        self.grafana.folders["efsybbe4ozv28c"] = {"title": "Riptide Flow Analytics", "parentUid": None}
        self.grafana.dashboards["a"] = {"dashboard": dashboard("a", "1.0.0"), "folderUid": "efsybbe4ozv28c",
                                        "version": 3, "provisioned": False}
        self.grafana.library["efsybbe4ozv28c"] = 1

        proc = self.run_import(self.source())

        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertNotIn("is now empty", proc.stdout)
        self.assertIn("library", proc.stdout, "say what is left, so nobody deletes it thinking it is empty")

    def test_a_folder_that_still_holds_something_is_not_called_empty(self):
        self.grafana.folders["mixed"] = {"title": "Mixed", "parentUid": None}
        self.grafana.dashboards["a"] = {"dashboard": dashboard("a"), "folderUid": "mixed", "version": 1, "provisioned": False}
        self.grafana.dashboards["someone-elses"] = {"dashboard": dashboard("someone-elses"), "folderUid": "mixed",
                                                    "version": 1, "provisioned": False}

        proc = self.run_import(self.source())

        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertNotIn("empty", proc.stdout)


class AProvisionedDashboard(ImportTest):

    def test_is_refused_with_grafanas_reason_while_the_others_are_imported(self):
        self.grafana.dashboards["b"] = {"dashboard": dashboard("b"), "folderUid": "prov", "version": 1, "provisioned": True}

        proc = self.run_import(self.source())

        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("b: refused: Cannot save provisioned dashboard", proc.stdout)
        self.assertEqual(self.grafana.dashboards["a"]["folderUid"], FOLDER)
        self.assertEqual(self.grafana.dashboards["c"]["folderUid"], FOLDER)


class TheToken(ImportTest):

    def test_a_viewer_stops_at_the_first_write_naming_the_permission(self):
        proc = self.run_import(self.source(), token=VIEWER)

        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("folders:create", proc.stdout + proc.stderr)
        self.assertEqual(len(self.grafana.writes()), 1, "nothing after the first refused write")

    def test_a_wrong_token_stops_before_any_write(self):
        proc = self.run_import(self.source(), token="glsa_wrong")

        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("401", proc.stdout + proc.stderr)
        self.assertEqual(self.grafana.writes(), [])

    def test_no_token_stops_before_any_request(self):
        proc = self.run_import(self.source(), token=None)

        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("GRAFANA_TOKEN", proc.stderr)
        self.assertEqual(self.grafana.requests, [])


class ARedirect(ImportTest):

    def test_is_refused_and_the_token_goes_nowhere_else(self):
        # From review: urllib turns a redirected POST into a GET, which answered
        # 200 and printed "created" for folders that were never made, and it
        # re-sends the bearer header to wherever the redirect points.
        proc = self.run_import(self.source(), grafana=self.grafana.url + "/moved")

        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("redirect", proc.stdout + proc.stderr)
        self.assertNotIn("created", proc.stdout)
        self.assertTrue(all(p.startswith("/moved/") for _, p, _ in self.grafana.requests),
                        f"followed the redirect: {self.grafana.requests}")


class ADashboardTheTokenMayNotTouch(ImportTest):

    def test_is_refused_on_its_own_and_the_others_still_import(self):
        self.grafana.restricted.add("b")

        proc = self.run_import(self.source())

        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("b: refused:", proc.stdout)
        self.assertIn("dashboards:write", proc.stdout)
        self.assertEqual(sorted(self.grafana.dashboards), ["a", "c"])


class AFailureMidRun(ImportTest):

    def test_a_dropped_connection_ends_with_a_clean_error_not_a_traceback(self):
        self.grafana.drop.add("b")

        proc = self.run_import(self.source())

        self.assertNotEqual(proc.returncode, 0)
        self.assertNotIn("Traceback", proc.stderr)
        self.assertIn("error:", proc.stderr)

    def test_a_malformed_dashboard_file_is_named_before_any_request(self):
        source = self.source()
        (source / "b.json").write_text("{ not json")

        proc = self.run_import(source)

        self.assertNotEqual(proc.returncode, 0)
        self.assertNotIn("Traceback", proc.stderr)
        self.assertIn("b.json", proc.stderr)
        self.assertEqual(self.grafana.requests, [])


class ADryRun(ImportTest):

    def test_reports_but_writes_nothing(self):
        self.grafana.dashboards["a"] = {"dashboard": dashboard("a", "1.0.0"), "folderUid": "", "version": 1,
                                        "provisioned": False}

        proc = self.run_import(self.source(), "--dry-run")

        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertEqual(self.grafana.writes(), [])
        self.assertIn("a: would update 1.0.0 -> 1.0.2", proc.stdout)
        self.assertIn("b: would create", proc.stdout)


class TheSource(ImportTest):

    def test_a_tarball_and_its_directory_import_the_same_dashboards(self):
        directory = self.source()
        tarball = self.tarball(directory)

        proc = self.run_import(tarball)

        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        from_tar = sorted(json.dumps(b["dashboard"], sort_keys=True) for b in self.imports())
        self.grafana.requests.clear()
        self.run_import(directory)
        from_dir = sorted(json.dumps(b["dashboard"], sort_keys=True) for b in self.imports())
        self.assertEqual(from_tar, from_dir)
        self.assertEqual(len(from_tar), 3)

    def test_a_set_that_disagrees_on_its_version_is_refused_before_any_request(self):
        proc = self.run_import(self.source(versions={"b": "9.9.9"}))

        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("b.json", proc.stderr)
        self.assertEqual(self.grafana.requests, [])


if __name__ == "__main__":
    unittest.main()
