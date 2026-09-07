#!/usr/bin/env bash
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Refuse a change that alters how the classification tree is built without
# either updating the section that publishes what building it costs, or saying
# in the commit that the cost did not move.
#
# WHY THIS EXISTS. docs/docs/deploy/operations.md publishes a supported ruleset
# size, a wall time at four sizes and a work count at four sizes. All of it is
# operator-facing and the size is a support commitment. The benchmark that
# produced those figures runs in no workflow, so nothing re-checks them.
#
# The tests that do run watch the wrong thing for this purpose.
# BundledRulesetTreeIdentityTest pins twelve Tree.Info fields, but Tree.Info is a
# function of the tree that comes out, not of the work done building it — so a
# same-shape, different-cost change leaves it fully green. That is not a
# hypothesis: deleting `.parallel()` from Tree.of does exactly that (#768), and
# the deterministic work counter added by #768 is blind to it as well, on
# purpose, because the same candidates get scored either way.
#
# So the last observable able to notice `.parallel()` is wall time, and a
# wall-time budget in CI was rejected: the build is core-count dependent and
# runners vary. What is left is to make the person changing the build read a
# sentence saying a published number may have just gone stale. This is that
# sentence, delivered at the only moment it is cheap to act on.
#
# WHAT THIS DOES AND DOES NOT GUARANTEE. It establishes that somebody was told.
# It does not establish that the figures were re-measured, and it cannot: this
# is satisfied by editing the document, and editing a document is not measuring
# anything. It converts a silent staleness into a prompt, and a prompt can be
# answered badly. The mitigation is that the escape valve leaves a record in the
# commit, so a bad answer stays findable afterwards; there is no mitigation for
# a lazy edit to the document itself.
#
# It is also range-scoped rather than commit-scoped. One `${ESCAPE_TRAILER}`
# anywhere in the range clears every triggering commit in it, so a series that
# mixes a genuine no-op with a real cost change passes on the strength of the
# no-op. Tightening that to per-commit would refuse more honestly and refuse
# more often; the looser rule is the deliberate choice, recorded here so the
# next reader does not mistake it for an oversight.
#
# Usage: check-build-cost-docs.sh [<head-ref>] [<base-ref>]
#
# Fixture tests: `make build-cost-docs-test`. This matches nothing in a healthy
# tree, so a green run on its own says as little about a working checker as
# about a broken one.
#
# Written for bash 3.2 so it runs unchanged on a stock macOS shell.

set -o nounset -o pipefail -o errexit -o noglob

HEAD_REF="${1:-HEAD}"
BASE_REF="${2:-origin/main}"

# The package Tree.of's cost lives in. Every file under it participates in the
# build: Tree drives the recursion, Threshold scores and splits, PreprocessedRule
# is what gets walked, Bounds and Bound gate each verdict, and Classifier is what
# a leaf sorts. A cost change can come from any of them, so the trigger is the
# directory rather than the two files the issue happened to name.
#
# Two things that also feed the published figures are deliberately NOT here,
# because a test already reds on them AND already names this document, so adding
# them would report one staleness twice from two mechanisms.
#
#   classification-rules.csv — BundledRulesetTreeIdentityTest pins tree shape and
#     an answer digest over the whole ruleset, so it reds on any edit that changes
#     a rule, including a value-only one that holds the row and distinct-port
#     counts constant (measured: one dstPort changed to another unused value reds
#     it while the two counting tests stay green).
#   CsvImporter — a parse change alters what the rules become, which lands in that
#     same shape pin. Note it does NOT reliably move TreeBuildWorkCounterTest,
#     which builds only the first 400 rows: a parse change affecting rule shapes
#     absent from that slice leaves it green.
TRIGGER_PREFIX="src/main/java/org/riptide/classification/internal/decision/"

# The published figures. Section: "Supported ruleset size".
DOC_PATH="docs/docs/deploy/operations.md"

# The escape valve, chosen over a pull-request label because a label lives only
# in GitHub metadata and does not survive a squash-merge into the history. A
# trailer does, which is the whole point: every use of this valve stays greppable
# long after the pull request is closed.
ESCAPE_TRAILER="Cost-Unchanged:"

# A trailer with no reason after it is not a claim, so it does not clear the
# check. Requiring a non-blank remainder is what stops the valve degrading into
# a word people paste in.
ESCAPE_PATTERN="^${ESCAPE_TRAILER}[[:space:]]*[^[:space:]]"

# Exit 1 for "this change is not allowed", exit 2 for "the question could not be
# answered". Distinct on purpose: not knowing is never the same as passing.
refuse() { printf '%s\n' "$@" >&2; exit 1; }
undetermined() { printf '%s\n' "$@" >&2; exit 2; }

head_sha="$(git rev-parse --verify --quiet "${HEAD_REF}^{commit}")" || head_sha=""
if [ -z "${head_sha}" ]; then
  undetermined \
    "Cannot check the build-cost docs: '${HEAD_REF}' does not resolve to a commit." \
    "Refusing rather than assuming the published figures are current."
fi

base_sha_ref="$(git rev-parse --verify --quiet "${BASE_REF}^{commit}")" || base_sha_ref=""
if [ -z "${base_sha_ref}" ]; then
  undetermined \
    "Cannot check the build-cost docs: '${BASE_REF}' does not resolve to a commit." \
    "The checkout needs enough history for the base branch to be present;" \
    "actions/checkout defaults to a single commit, so set 'fetch-depth: 0'." \
    "Refusing rather than assuming the published figures are current."
fi

base_sha="$(git merge-base "${head_sha}" "${base_sha_ref}")" || base_sha=""
if [ -z "${base_sha}" ]; then
  undetermined \
    "Cannot check the build-cost docs: ${HEAD_REF} and ${BASE_REF} share no common commit." \
    "A shallow clone or an unrelated history will do this; the checkout needs 'fetch-depth: 0'." \
    "Refusing rather than assuming the published figures are current."
fi

# core.quotePath=false because the default emits a path with any byte outside
# ASCII double-quoted and C-escaped, and both the prefix match and the equality
# below would then miss it — passing a change they exist to catch. This still
# leaves a path containing a literal newline unmatched, which the line-oriented
# read cannot represent at all; no such path exists here and one would have to be
# added deliberately.
changed="$(git -c core.quotePath=false diff --name-only "${base_sha}" "${head_sha}")" || undetermined \
  "Cannot check the build-cost docs: could not diff ${base_sha} against ${head_sha}." \
  "Refusing rather than assuming the published figures are current."

triggered=""
doc_touched=0
while IFS= read -r path; do
  [ -n "${path}" ] || continue
  if [ "${path}" = "${DOC_PATH}" ]; then
    doc_touched=1
  fi
  case "${path}" in
    "${TRIGGER_PREFIX}"*)
      triggered="${triggered}  ${path}
"
      ;;
  esac
done <<EOF
${changed}
EOF

if [ -z "${triggered}" ]; then
  printf '%s\n' \
    "Build-cost docs OK: nothing under ${TRIGGER_PREFIX} changed between ${base_sha} and ${HEAD_REF}."
  exit 0
fi

if [ "${doc_touched}" -eq 1 ]; then
  printf '%s\n' \
    "Build-cost docs OK: ${TRIGGER_PREFIX} changed and ${DOC_PATH} changed with it." \
    "That the document was edited is all this establishes. It is not evidence that" \
    "anything was re-measured, and this check cannot tell the two apart."
  exit 0
fi

range_messages="$(git log --format=%B "${base_sha}..${head_sha}")" || undetermined \
  "Cannot check the build-cost docs: could not read commit messages for ${base_sha}..${head_sha}." \
  "Refusing rather than assuming the published figures are current."

if printf '%s\n' "${range_messages}" | grep -qE "${ESCAPE_PATTERN}"; then
  printf '%s\n' \
    "Build-cost docs OK: ${TRIGGER_PREFIX} changed, ${DOC_PATH} did not, and a" \
    "${ESCAPE_TRAILER} trailer claims the cost did not move:" \
    "$(printf '%s\n' "${range_messages}" | grep -E "${ESCAPE_PATTERN}" | sed 's/^/  /')" \
    "" \
    "This is a claim by the author, checked by nobody. It is recorded in the commit" \
    "so it can be audited later, which is the only guarantee on offer here."
  exit 0
fi

refuse \
  "Refusing: this change alters how the classification tree is built, but neither" \
  "updates the figures published for it nor says they are unaffected." \
  "" \
  "Changed under ${TRIGGER_PREFIX}:" \
  "${triggered}" \
  "${DOC_PATH} (section: Supported ruleset size) publishes a supported ruleset" \
  "size, a build time at four sizes and a work count at four sizes, all measured" \
  "from the code you just changed. Nothing else re-checks them: the benchmark runs" \
  "in no workflow, and the tree-shape pins cannot see a cost change at all." \
  "" \
  "Do one of these:" \
  "" \
  "  1. Re-measure and update the section." \
  "       make bench-jmh BENCH_TARGET=TreeBuildBenchmark" \
  "     Update the table, the work counts and the Provenance paragraph together." \
  "" \
  "  2. If the cost genuinely did not move, say so in the commit message:" \
  "       ${ESCAPE_TRAILER} <why this cannot change candidates scored or rules walked>" \
  "     A bare '${ESCAPE_TRAILER}' with no reason does not count." \
  "" \
  "Renames, comment edits and javadoc changes are case 2. So is a change whose" \
  "cost effect is already pinned by TreeBuildWorkCounterTest, but say that rather" \
  "than assuming the next reader will work it out."
