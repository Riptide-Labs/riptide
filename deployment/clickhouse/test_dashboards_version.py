#!/usr/bin/env python3
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

"""Fixture tests for dashboards-version.py. Run with:
    python3 -m unittest discover -s deployment/clickhouse

Each test builds a throwaway git repository holding two minimal dashboards,
runs the script as a subprocess against it, and asserts the exit code, the
message, and the JSON it wrote. The checker matches nothing in a healthy tree,
so these fixtures are the only thing that ever exercises its failure arms.
"""

import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).parent / "dashboards-version.py"
REL = Path("container-fs/grafana/provisioning/dashboards")


def dashboard(uid: str, links=None) -> dict:
    return {
        "uid": uid,
        "title": uid,
        "links": links if links is not None else [
            {"title": "Riptide dashboards", "type": "dashboards", "tags": ["riptide"]}],
        "panels": [],
    }


class Repo:
    """A git repository with a dashboards directory at the real relative path."""

    def __init__(self):
        self.root = Path(tempfile.mkdtemp())
        self.dir = self.root / REL
        self.dir.mkdir(parents=True)
        self.git("init", "-q")
        self.git("config", "user.email", "t@example.org")
        self.git("config", "user.name", "t")
        # Hermetic against a developer's global config: with tag.gpgsign on, a
        # plain `git tag` becomes a signed annotated tag and fails without a
        # message; CI signs nothing, so the fixtures must not either.
        self.git("config", "commit.gpgsign", "false")
        self.git("config", "tag.gpgsign", "false")

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.root), *args], check=True, capture_output=True, text=True)

    def write(self, name: str, data: dict):
        (self.dir / f"{name}.json").write_text(json.dumps(data, indent=2) + "\n")

    def read(self, name: str) -> dict:
        return json.loads((self.dir / f"{name}.json").read_text())

    def commit(self):
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "fixture")

    def run(self, *args):
        return subprocess.run([sys.executable, str(SCRIPT), "--dir", str(self.dir), *args],
                              capture_output=True, text=True)


def version_link(d: dict):
    return [l for l in d["links"] if l.get("title", "").startswith("Dashboards v")]


class SetWritesTheLinkIntoEveryDashboard(unittest.TestCase):

    def test_adds_the_link_where_absent_and_keeps_the_existing_links(self):
        repo = Repo()
        repo.write("a", dashboard("a"))
        repo.write("b", dashboard("b"))

        proc = repo.run("set", "1.0.0")

        self.assertEqual(proc.returncode, 0, proc.stderr)
        for name in ("a", "b"):
            d = repo.read(name)
            self.assertEqual(len(version_link(d)), 1, name)
            self.assertEqual(version_link(d)[0]["title"], "Dashboards v1.0.0")
            self.assertEqual(version_link(d)[0]["type"], "link")
            self.assertEqual(d["links"][0]["title"], "Riptide dashboards", "the navigation dropdown stays first")

    def test_rewrites_an_existing_link_in_place_and_is_idempotent(self):
        repo = Repo()
        repo.write("a", dashboard("a"))
        repo.run("set", "1.0.0")
        first = (repo.dir / "a.json").read_text()

        repo.run("set", "1.1.0")
        self.assertEqual(version_link(repo.read("a"))[0]["title"], "Dashboards v1.1.0")
        self.assertEqual(len(repo.read("a")["links"]), 2)

        repo.run("set", "1.0.0")
        self.assertEqual((repo.dir / "a.json").read_text(), first, "a round trip leaves the file byte-identical")

    def test_keeps_each_file_s_own_escaping_of_non_ascii_text(self):
        repo = Repo()
        raw = dashboard("raw"); raw["title"] = "Scope — raw"
        (repo.dir / "raw.json").write_text(json.dumps(raw, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        escaped = dashboard("escaped"); escaped["title"] = "Scope — escaped"
        repo.write("escaped", escaped)  # json.dumps default: —

        repo.run("set", "1.0.0")

        self.assertIn("Scope — raw", (repo.dir / "raw.json").read_text(encoding="utf-8"))
        self.assertIn("Scope \\u2014 escaped", (repo.dir / "escaped.json").read_text())
        for name in ("raw", "escaped"):
            before = (repo.dir / f"{name}.json").read_text(encoding="utf-8")
            repo.run("set", "1.0.0")
            self.assertEqual((repo.dir / f"{name}.json").read_text(encoding="utf-8"), before, name)

    def test_refuses_a_dashboard_that_already_carries_two_version_links(self):
        repo = Repo()
        repo.write("a", dashboard("a", links=[
            {"title": "Dashboards v1.0.0", "type": "link"},
            {"title": "Dashboards v0.9.0", "type": "link"}]))

        proc = repo.run("set", "1.1.0")

        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("a.json", proc.stderr)
        self.assertIn("more than one version link", proc.stderr)
        self.assertEqual(len(version_link(repo.read("a"))), 2, "nothing was rewritten")

    def test_refuses_a_malformed_version(self):
        repo = Repo()
        repo.write("a", dashboard("a"))

        proc = repo.run("set", "v1.0")

        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("MAJOR.MINOR.PATCH", proc.stderr)


class CheckDemandsOneWellFormedVersionEverywhere(unittest.TestCase):

    def test_passes_when_every_dashboard_agrees(self):
        repo = Repo()
        repo.write("a", dashboard("a"))
        repo.write("b", dashboard("b"))
        repo.run("set", "1.0.0")

        proc = repo.run("check")

        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertIn("1.0.0", proc.stdout)

    def test_names_a_dashboard_that_disagrees(self):
        repo = Repo()
        repo.write("a", dashboard("a"))
        repo.write("b", dashboard("b"))
        repo.run("set", "1.0.0")
        b = repo.read("b")
        version_link(b)[0]["title"] = "Dashboards v1.0.1"
        repo.write("b", b)

        proc = repo.run("check")

        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("b.json", proc.stderr)
        self.assertIn("1.0.1", proc.stderr)

    def test_names_a_dashboard_without_the_link(self):
        repo = Repo()
        repo.write("a", dashboard("a"))
        repo.write("b", dashboard("b"))
        repo.run("set", "1.0.0")
        repo.write("b", dashboard("b"))

        proc = repo.run("check")

        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("b.json", proc.stderr)
        self.assertIn("no version link", proc.stderr)

    def test_rejects_a_malformed_version_in_a_link(self):
        repo = Repo()
        repo.write("a", dashboard("a"))
        repo.run("set", "1.0.0")
        a = repo.read("a")
        version_link(a)[0]["title"] = "Dashboards v1.0"
        repo.write("a", a)

        proc = repo.run("check")

        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("a.json", proc.stderr)


class CheckAgainstABaseRefDemandsABumpWhenADashboardChanged(unittest.TestCase):

    def base(self) -> Repo:
        repo = Repo()
        repo.write("a", dashboard("a"))
        repo.write("b", dashboard("b"))
        repo.run("set", "1.0.0")
        repo.commit()
        return repo

    def test_passes_when_nothing_changed(self):
        repo = self.base()

        proc = repo.run("check", "--base-ref", "HEAD")

        self.assertEqual(proc.returncode, 0, proc.stderr)

    def test_fails_when_a_dashboard_changed_and_the_version_did_not(self):
        repo = self.base()
        b = repo.read("b")
        b["panels"].append({"id": 1, "title": "new"})
        repo.write("b", b)

        proc = repo.run("check", "--base-ref", "HEAD")

        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("b.json", proc.stderr)
        self.assertIn("1.0.0", proc.stderr)
        self.assertIn("bump", proc.stderr)

    def test_passes_when_a_dashboard_changed_and_the_version_moved(self):
        repo = self.base()
        b = repo.read("b")
        b["panels"].append({"id": 1, "title": "new"})
        repo.write("b", b)
        repo.run("set", "1.1.0")

        proc = repo.run("check", "--base-ref", "HEAD")

        self.assertEqual(proc.returncode, 0, proc.stderr)

    def test_a_new_dashboard_counts_as_a_change(self):
        repo = self.base()
        repo.write("c", dashboard("c"))
        repo.run("set", "1.0.0")

        proc = repo.run("check", "--base-ref", "HEAD")

        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("c.json", proc.stderr)

    def test_passes_when_the_base_had_no_version_yet(self):
        repo = Repo()
        repo.write("a", dashboard("a"))
        repo.commit()
        repo.run("set", "1.0.0")

        proc = repo.run("check", "--base-ref", "HEAD")

        self.assertEqual(proc.returncode, 0, proc.stderr)

    def test_a_removed_dashboard_counts_as_a_change(self):
        repo = self.base()
        (repo.dir / "b.json").unlink()

        proc = repo.run("check", "--base-ref", "HEAD")

        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("b.json", proc.stderr)

    def test_an_unresolvable_base_ref_fails_rather_than_passing(self):
        repo = self.base()

        proc = repo.run("check", "--base-ref", "no-such-ref")

        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("no-such-ref", proc.stderr)
        self.assertIn("does not resolve", proc.stderr)

    def test_a_version_lower_than_the_base_is_refused(self):
        repo = self.base()
        b = repo.read("b")
        b["panels"].append({"id": 1, "title": "new"})
        repo.write("b", b)
        repo.run("set", "0.9.0")

        proc = repo.run("check", "--base-ref", "HEAD")

        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("0.9.0", proc.stderr)
        self.assertIn("1.0.0", proc.stderr)


class SetOwnsTheWholeLink(unittest.TestCase):

    def test_replaces_a_stale_url_and_tooltip_without_adding_a_second_link(self):
        repo = Repo()
        repo.write("a", dashboard("a", links=[
            {"title": "Riptide dashboards", "type": "dashboards", "tags": ["riptide"]},
            {"title": "Dashboards v1.0.0", "type": "link", "url": "https://example.org/tree/main", "tooltip": "old"}]))

        proc = repo.run("set", "1.0.1")

        self.assertEqual(proc.returncode, 0, proc.stderr)
        links = version_link(repo.read("a"))
        self.assertEqual(len(links), 1)
        self.assertEqual(links[0]["title"], "Dashboards v1.0.1")
        self.assertTrue(links[0]["url"].startswith("https://riptide.space/docs/"), links[0]["url"])
        self.assertIn("grafana-dashboards", links[0]["url"])
        self.assertNotIn("tree/main", links[0]["url"])
        self.assertNotEqual(links[0]["tooltip"], "old")
        self.assertEqual(links[0]["icon"], "info")
        self.assertTrue(links[0]["targetBlank"])


class GetPrintsTheAgreedVersionAlone(unittest.TestCase):

    def test_prints_only_the_version(self):
        repo = Repo()
        repo.write("a", dashboard("a"))
        repo.write("b", dashboard("b"))
        repo.run("set", "1.2.3")

        proc = repo.run("get")

        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(proc.stdout, "1.2.3\n")

    def test_refuses_a_disagreeing_set(self):
        repo = Repo()
        repo.write("a", dashboard("a"))
        repo.write("b", dashboard("b"))
        repo.run("set", "1.0.0")
        b = repo.read("b")
        version_link(b)[0]["title"] = "Dashboards v1.0.1"
        repo.write("b", b)

        proc = repo.run("get")

        self.assertNotEqual(proc.returncode, 0)
        self.assertEqual(proc.stdout, "")
        self.assertIn("b.json", proc.stderr)


class BundleWritesOneDeterministicArchive(unittest.TestCase):

    PROVIDER = "apiVersion: 1\nproviders:\n  - name: riptide\n    type: file\n"

    def stamped(self) -> Repo:
        repo = Repo()
        repo.write("a", dashboard("a"))
        repo.write("b", dashboard("b"))
        (repo.dir / "dashboards.yml").write_text(self.PROVIDER)
        repo.run("set", "1.2.3")
        return repo

    def test_names_the_archive_after_the_set_version_and_holds_exactly_the_set(self):
        import tarfile
        repo = self.stamped()
        out = repo.root / "out"
        out.mkdir()

        proc = repo.run("bundle", str(out))

        self.assertEqual(proc.returncode, 0, proc.stderr)
        archive = out / "riptide-dashboards-1.2.3.tar.gz"
        self.assertTrue(archive.exists(), list(out.iterdir()))
        self.assertIn(str(archive), proc.stdout)
        with tarfile.open(archive) as tar:
            members = {m.name: m for m in tar.getmembers()}
            self.assertEqual(set(members), {"dashboards/a.json", "dashboards/b.json", "dashboards/dashboards.yml"})
            for m in members.values():
                self.assertEqual((m.uid, m.gid, m.uname, m.gname), (0, 0, "", ""), m.name)
                self.assertEqual(m.mtime, 0, m.name)
            self.assertEqual(tar.extractfile("dashboards/a.json").read(), (repo.dir / "a.json").read_bytes())
            self.assertEqual(tar.extractfile("dashboards/dashboards.yml").read(), self.PROVIDER.encode())

    def test_two_builds_are_byte_identical(self):
        import os, time
        repo = self.stamped()
        one, two = repo.root / "one", repo.root / "two"
        one.mkdir(); two.mkdir()

        self.assertEqual(repo.run("bundle", str(one)).returncode, 0)
        later = time.time() + 90
        for path in repo.dir.iterdir():
            os.utime(path, (later, later))
        self.assertEqual(repo.run("bundle", str(two)).returncode, 0)

        self.assertEqual((one / "riptide-dashboards-1.2.3.tar.gz").read_bytes(),
                         (two / "riptide-dashboards-1.2.3.tar.gz").read_bytes())

    def test_refuses_a_disagreeing_set_and_writes_nothing(self):
        repo = self.stamped()
        b = repo.read("b")
        version_link(b)[0]["title"] = "Dashboards v9.9.9"
        repo.write("b", b)
        out = repo.root / "out"
        out.mkdir()

        proc = repo.run("bundle", str(out))

        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("b.json", proc.stderr)
        self.assertEqual(list(out.iterdir()), [])

    def test_refuses_a_directory_without_the_provider_file(self):
        repo = self.stamped()
        (repo.dir / "dashboards.yml").unlink()
        out = repo.root / "out"
        out.mkdir()

        proc = repo.run("bundle", str(out))

        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("dashboards.yml", proc.stderr)
        self.assertEqual(list(out.iterdir()), [])


class HelmValuesPointTheChartAtTheTag(unittest.TestCase):
    """The values file for the grafana-community chart (issue for step 2 of #864's plan)."""

    PROVIDER = (
        "# comment kept in the source, discarded by the chart\n"
        "---\n"
        "apiVersion: 1\n"
        "\n"
        "providers:\n"
        "  - name: riptide\n"
        "    type: file\n"
        "    folder: 'Flow Analytics'\n"
        "    folderUid: riptide-flow-analytics\n"
        "    options:\n"
        "      path: /etc/grafana/provisioning/dashboards\n"
        "      foldersFromFilesStructure: false\n"
    )
    OUT = "riptide-dashboards-helm-values.yaml"

    def stamped(self, provider=None, tag="v9.9.9") -> Repo:
        """Dashboards a and b at set 1.2.3, committed and tagged: the file describes a tag."""
        repo = Repo()
        repo.write("a", dashboard("a"))
        repo.write("b", dashboard("b"))
        (repo.dir / "dashboards.yml").write_text(self.PROVIDER if provider is None else provider)
        repo.run("set", "1.2.3")
        repo.commit()
        repo.git("tag", tag)
        return repo

    def generate(self, repo, *args):
        out = repo.root / "out"
        out.mkdir(exist_ok=True)
        return out, repo.run("helm-values", *args, str(out))

    def test_refuses_to_guess_the_ref(self):
        repo = self.stamped()

        out, proc = self.generate(repo)

        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("--ref", proc.stderr)
        self.assertNotIn("invalid choice", proc.stderr, "refused for the wrong reason: the subcommand is missing")
        self.assertEqual(list(out.iterdir()), [])

    def test_lists_every_dashboard_at_the_tag_and_nothing_else(self):
        repo = self.stamped()

        out, proc = self.generate(repo, "--ref", "v9.9.9")

        self.assertEqual(proc.returncode, 0, proc.stderr)
        text = (out / self.OUT).read_text()
        base = "https://raw.githubusercontent.com/Riptide-Labs/riptide/v9.9.9/" + REL.as_posix()
        urls = re.findall(r'url: "([^"]+)"', text)
        self.assertEqual(sorted(urls), [f"{base}/a.json", f"{base}/b.json"])
        self.assertNotIn("dashboards.yml\"", text, "the provider file is not a dashboard to download")

    def test_every_download_keeps_tls_verification_on(self):
        repo = self.stamped()

        out, proc = self.generate(repo, "--ref", "v9.9.9")

        self.assertEqual(proc.returncode, 0, proc.stderr)
        text = (out / self.OUT).read_text()
        self.assertEqual(text.count('curlOptions: "-sSf"'), 2, "one override per dashboard, replacing the chart's -skf")
        self.assertNotIn("-k", text.replace("-sSf", ""))
        self.assertNotIn("--insecure", text)

    def test_embeds_the_shipped_provider_with_only_its_path_swapped(self):
        repo = self.stamped()

        out, proc = self.generate(repo, "--ref", "v9.9.9")

        self.assertEqual(proc.returncode, 0, proc.stderr)
        text = (out / self.OUT).read_text()
        expected = [("    " + l) if l else "" for l in self.PROVIDER.replace(
            "path: /etc/grafana/provisioning/dashboards", "path: /var/lib/grafana/dashboards/riptide").splitlines()
            if l != "---"]
        self.assertIn('  "riptide.yaml":\n' + "\n".join(expected) + "\n", text)
        self.assertNotIn("/etc/grafana/provisioning/dashboards", text)
        self.assertNotIn("\n---", text, "a document marker inside a mapping is invalid YAML")

    def test_names_the_tag_and_the_set_version_in_its_header(self):
        repo = self.stamped()

        out, proc = self.generate(repo, "--ref", "v9.9.9")

        self.assertEqual(proc.returncode, 0, proc.stderr)
        header = (out / self.OUT).read_text().split("\n\n")[0]
        self.assertIn("v9.9.9", header)
        self.assertIn("1.2.3", header)
        self.assertTrue(all(l.startswith("#") for l in header.splitlines()), header)

    def test_refuses_a_provider_without_the_path_line(self):
        repo = self.stamped(provider=self.PROVIDER.replace("/etc/grafana/provisioning/dashboards", "/srv/dashboards"))

        out, proc = self.generate(repo, "--ref", "v9.9.9")

        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("dashboards.yml", proc.stderr)
        self.assertEqual(list(out.iterdir()), [])

    def test_refuses_a_provider_with_the_path_line_twice(self):
        repo = self.stamped(provider=self.PROVIDER + "      path: /etc/grafana/provisioning/dashboards\n")

        out, proc = self.generate(repo, "--ref", "v9.9.9")

        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("dashboards.yml", proc.stderr)
        self.assertEqual(list(out.iterdir()), [])

    def test_refuses_a_disagreeing_set_and_writes_nothing(self):
        repo = self.stamped(tag="v1.0.0")
        b = repo.read("b")
        version_link(b)[0]["title"] = "Dashboards v9.9.9"
        repo.write("b", b)
        repo.commit()
        repo.git("tag", "v9.9.9")

        out, proc = self.generate(repo, "--ref", "v9.9.9")

        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("b.json", proc.stderr)
        self.assertEqual(list(out.iterdir()), [])

    def test_describes_the_dashboards_at_the_ref_not_the_working_tree(self):
        # Found by the kind run: a file generated on main for an older tag said
        # "set 1.0.2" while its URLs served set 1.0.0.
        repo = self.stamped()
        repo.write("c", dashboard("c"))
        repo.run("set", "2.0.0")

        out, proc = self.generate(repo, "--ref", "v9.9.9")

        self.assertEqual(proc.returncode, 0, proc.stderr)
        text = (out / self.OUT).read_text()
        self.assertEqual(sorted(re.findall(r'/([a-z]+)\.json"', text)), ["a", "b"], "c exists only in the working tree")
        header = text.split("\n\n")[0]
        self.assertIn("1.2.3", header)
        self.assertNotIn("2.0.0", header)

    def test_refuses_a_ref_that_does_not_resolve(self):
        repo = self.stamped()

        out, proc = self.generate(repo, "--ref", "v0.0.404")

        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("v0.0.404", proc.stderr)
        self.assertEqual(list(out.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
