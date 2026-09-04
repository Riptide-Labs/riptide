#!/usr/bin/env bash
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Fixture tests for check-release-lineage.sh.
#
# The checker matches nothing in a healthy tree, so a green release run says as
# little about a working checker as about a broken one — and the checker's first
# real execution would otherwise be during the release it exists to protect.
# These build synthetic repositories and assert the refusals fire.
#
# The load-bearing case is "dependency version repointed": it is the one that
# fails if POM_VERSION_LINE is ever relaxed to a depth-blind "any <version> line
# changed", which reads like a harmless simplification and is not one.
#
# Written for bash 3.2 so it runs unchanged on a stock macOS shell.

set -o nounset -o pipefail -o errexit -o noglob

CHECK="$(cd "$(dirname "$0")" && pwd)/check-release-lineage.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

failures=0
passes=0
repo=""

g() {
  git -C "${repo}" \
    -c user.email=test@example.invalid \
    -c user.name=Test \
    -c commit.gpgsign=false \
    "$@"
}

commit_all() { g add -A && g commit -q --no-verify -m "$1"; }

write_pom() {
  # Structurally faithful to the real pom.xml in the one way that matters: the
  # project's own <version> at four spaces, the parent's at eight, dependency
  # versions at twelve, and a version pinned as a property.
  cat >"${repo}/pom.xml" <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.1</version>
    </parent>

    <groupId>org.riptide.flows</groupId>
    <artifactId>riptide-flows</artifactId>
    <version>0.12.1-SNAPSHOT</version>

    <properties>
        <caffeine.version>3.2.4</caffeine.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>com.github.ben-manes.caffeine</groupId>
            <artifactId>caffeine</artifactId>
            <version>${caffeine.version}</version>
        </dependency>
        <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-csv</artifactId>
            <version>1.11.2</version>
        </dependency>
    </dependencies>
</project>
POM
}

# Replace the project's own <version>, the way `mvn versions:set` does.
set_project_version() {
  awk -v v="$1" '
    !done && $0 ~ /^    <version>[^<]*<\/version>$/ {
      sub(/<version>[^<]*<\/version>/, "<version>" v "</version>")
      done = 1
    }
    { print }
  ' "${repo}/pom.xml" >"${repo}/pom.xml.new"
  mv "${repo}/pom.xml.new" "${repo}/pom.xml"
}

# Single-line substring replacement.
replace_in_pom() {
  awk -v from="$1" -v to="$2" '
    { i = index($0, from); if (i > 0) { $0 = substr($0, 1, i - 1) to substr($0, i + length(from)) } ; print }
  ' "${repo}/pom.xml" >"${repo}/pom.xml.new"
  mv "${repo}/pom.xml.new" "${repo}/pom.xml"
}

# Insert the block on stdin after the first line equal to $1. A multi-line value
# cannot go through awk's -v on BSD awk, so this stays in the shell.
insert_after_in_pom() {
  block="$(cat)"
  while IFS= read -r pom_line; do
    printf '%s\n' "${pom_line}"
    if [ "${pom_line}" = "$1" ]; then
      printf '%s\n' "${block}"
    fi
  done <"${repo}/pom.xml" >"${repo}/pom.xml.new"
  mv "${repo}/pom.xml.new" "${repo}/pom.xml"
}

# A repository with one reviewed commit on main, mirrored into origin/main the
# way actions/checkout populates it with fetch-depth: 0.
new_repo() {
  repo="${WORK}/$1"
  mkdir -p "${repo}/src/main/java"
  git init -q -b main "${repo}"
  write_pom
  printf 'class App {}\n' >"${repo}/src/main/java/App.java"
  commit_all "reviewed baseline"
  g update-ref refs/remotes/origin/main "$(g rev-parse HEAD)"
}

# assert <label> <expected exit> <expected substring> [<check args>...]
assert() {
  label="$1"
  want_rc="$2"
  want_sub="$3"
  shift 3
  set +e
  out="$(cd "${repo}" && "${CHECK}" "$@" 2>&1)"
  rc=$?
  set -e
  if [ "${rc}" -ne "${want_rc}" ]; then
    printf 'FAIL %s\n     expected exit %s, got %s\n%s\n\n' "${label}" "${want_rc}" "${rc}" "${out}"
    failures=$((failures + 1))
    return 0
  fi
  case "${out}" in
    *"${want_sub}"*) ;;
    *)
      printf 'FAIL %s\n     exit %s as expected, but the output never mentions "%s"\n%s\n\n' \
        "${label}" "${rc}" "${want_sub}" "${out}"
      failures=$((failures + 1))
      return 0
      ;;
  esac
  printf 'ok   %-46s exit %s\n' "${label}" "${rc}"
  passes=$((passes + 1))
}

# A release cut exactly as RELEASING.md prescribes.
cut_release() {
  g checkout -q -B release main
  set_project_version "$1"
  commit_all "release: Riptide version $1"
  g tag -a "v$1" -m "Release Riptide version $1"
  g checkout -q --force "refs/tags/v$1"
}

# ---------------------------------------------------------------- passes

new_repo clean
cut_release 1.0.0
assert "clean version bump" 0 "Release lineage OK" v1.0.0 origin/main

new_repo on-main
g tag -a v1.0.0 -m x
g checkout -q --force refs/tags/v1.0.0
assert "tag on a main commit, empty change set" 0 "Release lineage OK" v1.0.0 origin/main

# ---------------------------------------------------------------- refusals

new_repo swept-in
g checkout -q -B release main
set_project_version 1.0.0
printf '// left over from debugging\n' >>"${repo}/src/main/java/App.java"
commit_all "release: Riptide version 1.0.0"
g tag -a v1.0.0 -m x
g checkout -q --force refs/tags/v1.0.0
assert "unrelated file swept in by commit -am" 1 "src/main/java/App.java" v1.0.0 origin/main

new_repo unmerged-base
g checkout -q -B feature main
printf 'class Never {}\n' >"${repo}/src/main/java/Never.java"
commit_all "feat: never merged to main"
g checkout -q -B release
set_project_version 1.0.0
commit_all "release: Riptide version 1.0.0"
g tag -a v1.0.0 -m x
g checkout -q --force refs/tags/v1.0.0
assert "release cut from an unmerged commit" 1 "feat: never merged to main" v1.0.0 origin/main

new_repo dep-added
g checkout -q -B release main
insert_after_in_pom '    <dependencies>' <<'DEP'
        <dependency>
            <groupId>evil</groupId>
            <artifactId>evil-unreviewed</artifactId>
            <version>1.0.0</version>
        </dependency>
DEP
commit_all "chore: add a dependency, never reviewed"
set_project_version 1.0.0
commit_all "release: Riptide version 1.0.0"
g tag -a v1.0.0 -m x
g checkout -q --force refs/tags/v1.0.0
assert "dependency added to pom.xml" 1 "evil-unreviewed" v1.0.0 origin/main

# The mutation guard. A depth-blind "only <version> lines changed" rule accepts
# this, because a repointed dependency version IS a <version> line.
new_repo dep-repointed
g checkout -q -B release main
replace_in_pom '<version>1.11.2</version>' '<version>0.0.1-evil</version>'
commit_all "chore: repoint a dependency, never reviewed"
set_project_version 1.0.0
commit_all "release: Riptide version 1.0.0"
g tag -a v1.0.0 -m x
g checkout -q --force refs/tags/v1.0.0
assert "dependency version repointed" 1 "0.0.1-evil" v1.0.0 origin/main

new_repo property-repointed
g checkout -q -B release main
replace_in_pom '<caffeine.version>3.2.4</caffeine.version>' '<caffeine.version>0.0.1-evil</caffeine.version>'
commit_all "chore: repoint a version property, never reviewed"
set_project_version 1.0.0
commit_all "release: Riptide version 1.0.0"
g tag -a v1.0.0 -m x
g checkout -q --force refs/tags/v1.0.0
assert "version property repointed" 1 "caffeine.version" v1.0.0 origin/main

# Fails open on any rule that infers "clean" from finding no +/- lines: git
# diffs a pom.xml with a NUL byte as "Binary files ... differ".
new_repo pom-binary
g checkout -q -B release main
set_project_version 1.0.0
# Well inside git's binary-detection window, which only scans the first 8000
# bytes: a NUL past it is diffed as text and would be caught as a content line.
printf '\000' >>"${repo}/pom.xml"
commit_all "release: Riptide version 1.0.0"
g tag -a v1.0.0 -m x
g checkout -q --force refs/tags/v1.0.0
assert "pom.xml that git reads as binary" 1 "removed 0 and added 0" v1.0.0 origin/main

# The other shape with no +/- lines: a mode flip and nothing else.
new_repo pom-mode-only
g checkout -q -B release main
chmod +x "${repo}/pom.xml"
commit_all "release: Riptide version 1.0.0"
g tag -a v1.0.0 -m x
g checkout -q --force refs/tags/v1.0.0
assert "pom.xml changed only in file mode" 1 "removed 0 and added 0" v1.0.0 origin/main

# ------------------------------------------------------- cannot determine

new_repo no-main
cut_release 1.0.0
assert "origin/main does not resolve" 2 "does not resolve to a commit" v1.0.0 origin/nope

new_repo unrelated
g checkout -q --orphan orphan
g rm -rq --cached .
rm -f "${repo}/src/main/java/App.java"
write_pom
set_project_version 1.0.0
commit_all "release: Riptide version 1.0.0"
g tag -a v1.0.0 -m x
g checkout -q --force refs/tags/v1.0.0
assert "no common commit with main" 2 "share no common commit" v1.0.0 origin/main

# ----------------------------------------------------------------- report

printf '\n%s passed, %s failed\n' "${passes}" "${failures}"
[ "${failures}" -eq 0 ]
