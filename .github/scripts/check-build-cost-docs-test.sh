#!/usr/bin/env bash
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Fixture tests for check-build-cost-docs.sh.
#
# The checker matches nothing in a healthy tree, so a green CI run says as little
# about a working checker as about a broken one. These build synthetic
# repositories and assert that the refusal fires, that each of the two ways past
# it works, and that neither fires when it should not.
#
# The load-bearing case is "bare trailer": a `Cost-Unchanged:` with no reason
# after it must NOT clear the check. Relaxing ESCAPE_PATTERN to a plain substring
# match reads like a simplification and turns the valve into a word people paste.
#
# Written for bash 3.2 so it runs unchanged on a stock macOS shell.

set -o nounset -o pipefail -o errexit -o noglob

CHECK="$(cd "$(dirname "$0")" && pwd)/check-build-cost-docs.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

TRIGGER_DIR="src/main/java/org/riptide/classification/internal/decision"
DOC_PATH="docs/docs/deploy/operations.md"

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

# A repository shaped like this one in the only ways the checker looks at: the
# trigger directory, the published document, and a main branch to diff against.
new_repo() {
  repo="${WORK}/$1"
  mkdir -p "${repo}/${TRIGGER_DIR}" "${repo}/$(dirname "${DOC_PATH}")" "${repo}/src/main/java/org/riptide/other"
  g init -q -b main
  printf 'class Tree {}\n' >"${repo}/${TRIGGER_DIR}/Tree.java"
  printf 'class Threshold {}\n' >"${repo}/${TRIGGER_DIR}/Threshold.java"
  printf 'class Other {}\n' >"${repo}/src/main/java/org/riptide/other/Other.java"
  printf '# Operations\n\n## Supported ruleset size\n\n929 ms\n' >"${repo}/${DOC_PATH}"
  commit_all "baseline"
  g branch -q base-point
}

# Runs the checker against main's tip with base-point as the base, and asserts
# the exit code. Exit 1 is a refusal, 0 is a pass, 2 is undetermined.
expect() {
  local label="$1" want="$2" got=0
  ( cd "${repo}" && "${CHECK}" HEAD base-point ) >"${WORK}/out" 2>&1 || got=$?
  if [ "${got}" -eq "${want}" ]; then
    passes=$((passes + 1))
    printf 'ok   %s\n' "${label}"
  else
    failures=$((failures + 1))
    printf 'FAIL %s: expected exit %s, got %s\n' "${label}" "${want}" "${got}"
    sed 's/^/     /' "${WORK}/out"
  fi
}

# Asserts the output mentions something a reader needs. A refusal that does not
# name the document is a refusal nobody can act on.
expect_mentions() {
  local label="$1" needle="$2"
  if grep -qF "${needle}" "${WORK}/out"; then
    passes=$((passes + 1))
    printf 'ok   %s\n' "${label}"
  else
    failures=$((failures + 1))
    printf 'FAIL %s: output does not mention %s\n' "${label}" "${needle}"
    sed 's/^/     /' "${WORK}/out"
  fi
}

# 1. The case the checker exists for. This is the `.parallel()` shape: the build
#    changes, nothing else does, and every existing test stays green.
new_repo build-changed-alone
printf 'class Tree { /* no parallel */ }\n' >"${repo}/${TRIGGER_DIR}/Tree.java"
commit_all "drop parallel"
expect "a build change alone is refused" 1
expect_mentions "the refusal names the document" "${DOC_PATH}"
expect_mentions "the refusal names the re-measure command" "make bench-jmh"

# 2. First way past: re-measure and update the section.
new_repo build-and-docs
printf 'class Tree { /* no parallel */ }\n' >"${repo}/${TRIGGER_DIR}/Tree.java"
printf '# Operations\n\n## Supported ruleset size\n\n1400 ms\n' >"${repo}/${DOC_PATH}"
commit_all "drop parallel and re-measure"
expect "a build change with a docs change passes" 0
expect_mentions "the pass does not claim anything was measured" "not evidence"

# 3. Second way past: the trailer.
new_repo build-with-trailer
printf 'class Tree { /* renamed local */ }\n' >"${repo}/${TRIGGER_DIR}/Tree.java"
g add -A
g commit -q --no-verify -m "rename a local

Cost-Unchanged: identifier rename only, scores the same candidates"
expect "a build change with a reasoned trailer passes" 0
expect_mentions "the pass records the claim" "checked by nobody"

# 4. The load-bearing case: a trailer with no reason is not a claim.
new_repo build-with-bare-trailer
printf 'class Tree { /* something */ }\n' >"${repo}/${TRIGGER_DIR}/Tree.java"
g add -A
g commit -q --no-verify -m "change the build

Cost-Unchanged:"
expect "a bare trailer does not clear the check" 1

# 5. Every file in the package triggers it, not only the two the issue named.
new_repo threshold-changed
printf 'class Threshold { /* costlier verdict */ }\n' >"${repo}/${TRIGGER_DIR}/Threshold.java"
commit_all "make each verdict do more"
expect "a change to any file in the package is refused" 1

# 6. It must not fire on unrelated work, or it will be routed around by habit.
new_repo unrelated-change
printf 'class Other { /* unrelated */ }\n' >"${repo}/src/main/java/org/riptide/other/Other.java"
commit_all "unrelated change"
expect "an unrelated change passes" 0

# 7. A docs-only edit is not the trigger either.
new_repo docs-only
printf '# Operations\n\n## Supported ruleset size\n\n929 ms, clarified\n' >"${repo}/${DOC_PATH}"
commit_all "clarify the docs"
expect "a docs-only change passes" 0

# 8. A path git would quote must still match. With the default core.quotePath,
#    a non-ASCII path is emitted double-quoted and C-escaped, so both the prefix
#    match and the DOC_PATH equality miss it and the change passes unseen.
new_repo non-ascii-path
printf 'class Umlaut {}\n' >"${repo}/${TRIGGER_DIR}/Uber.java"
mv "${repo}/${TRIGGER_DIR}/Uber.java" "${repo}/${TRIGGER_DIR}/Über.java"
commit_all "add a class with a non-ascii filename"
expect "a non-ascii path in the trigger directory is still seen" 1

# 9. Not knowing is not passing.
new_repo undetermined-base
printf 'class Tree { /* x */ }\n' >"${repo}/${TRIGGER_DIR}/Tree.java"
commit_all "change the build"
got=0
( cd "${repo}" && "${CHECK}" HEAD refs/heads/nonexistent ) >"${WORK}/out" 2>&1 || got=$?
if [ "${got}" -eq 2 ]; then
  passes=$((passes + 1))
  printf 'ok   %s\n' "an unresolvable base is undetermined, not a pass"
else
  failures=$((failures + 1))
  printf 'FAIL %s: expected exit 2, got %s\n' "an unresolvable base is undetermined, not a pass" "${got}"
  sed 's/^/     /' "${WORK}/out"
fi

printf '\n%s passed, %s failed\n' "${passes}" "${failures}"
[ "${failures}" -eq 0 ]
