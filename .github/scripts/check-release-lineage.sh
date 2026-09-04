#!/usr/bin/env bash
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Refuse to publish a release whose tag carries code that never went through main.
#
# The tag push is what releases (RELEASING.md), and it happens while the release
# commit is still on the `release` branch — the squash-merge into `main` comes
# afterwards and creates a *different* commit. So the tagged commit is never an
# ancestor of `main`, and "is this tag on main?" is the wrong question. The right
# one is: everything this tag adds on top of the reviewed history must be the
# version bump `make release` writes, and nothing else.
#
# WHAT THIS DOES AND DOES NOT GUARANTEE. It catches mistakes: a stray tracked
# edit swept in by `make release`'s `git commit -am`, a release cut from a base
# that never merged, a dependency added in a commit nobody reviewed. It is not a
# defence against someone who controls what the tag contains. release.yml, the
# `release-lineage` target and this script are all read from the tagged commit,
# so a tag can no-op or delete them and this check never runs. The control for
# that is tag and branch protection, not this file.
#
# Usage: check-release-lineage.sh [<head-ref>] [<main-ref>]
#
# Fixture tests: `make release-lineage-test`. This matches nothing in a healthy
# tree, so a green run on its own says as little about a working checker as
# about a broken one — change the rules here and change those tests with them.
#
# Written for bash 3.2 so it runs unchanged on a stock macOS shell.

set -o nounset -o pipefail -o errexit -o noglob

HEAD_REF="${1:-HEAD}"
MAIN_REF="${2:-origin/main}"

# What `make release` puts in the release commit. `mvn versions:set` writes
# pom.xml and the commit is `git commit --signoff -am`, which sweeps in every
# other modified tracked file too — that sweep is the thing this guard catches.
POM_PATH="pom.xml"

# The set of paths a release commit may touch, space separated. Every path in it
# needs a content rule below; a path allowed here with no content rule is a hole
# the size of that file. Widening this is a deliberate, reviewed change, never a
# wildcard. (noglob is on, so no entry is ever expanded as a pattern.)
ALLOWED_PATHS="${POM_PATH}"

# Allowing the path is not enough: pom.xml decides what gets compiled in, so an
# unreviewed commit that adds a dependency, a plugin or a repository would be
# built and signed by the release. Neither existing check in release.yml notices
# — the version is not a SNAPSHOT and the tag still matches it. So the pom's own
# diff must be one project version line replaced by another, and nothing else.
#
# The measured shape. v0.8.0, v0.8.1, v0.9.0, v0.11.0 and v0.12.0 each show
# exactly this pair in pom.xml and nothing else:
#
#   -    <version>0.10.1-SNAPSHOT</version>
#   +    <version>0.11.0</version>
#
# v0.10.0 is the case that does not fit, and it matters more than the five that
# do: its merge base is the tagged commit itself, so pom.xml is not in the
# changed set at all and this block never runs. It passes vacuously, not because
# the rule fits it. v0.12.0 is refused, which is intended — it also carried
# docs/docs/release-notes/*.md, a directory that has not existed on main since
# release notes became GitHub-only in #627.
#
# Four spaces is load-bearing, not incidental: the project's own <version> is the
# only one at that depth in pom.xml. The Spring Boot parent sits at 8,
# dependencies and plugins at 12 or deeper, and dependency versions pinned as
# properties are not <version> elements at all. A depth-blind "only <version>
# lines changed" rule would let a repointed dependency version through.
#
# This checks the shape of the change, not its value. The value is the next
# step's job: release.yml refuses a tag that disagrees with the pom version.
POM_VERSION_LINE='^[+-]    <version>[^<]*</version>$'

# Exit 1 for "this release is not allowed", exit 2 for "the question could not be
# answered". Distinct on purpose: not knowing is never the same as passing. Note
# that `make release-lineage` reports both as make's own exit 2, so through the
# Makefile the two cases are told apart by the message, not by the exit code.
refuse() { printf '%s\n' "$@" >&2; exit 1; }
undetermined() { printf '%s\n' "$@" >&2; exit 2; }

# The same preamble on every refusal: which reviewed commit this was measured
# against, and what the tag puts on top of it.
lineage_context() {
  printf '%s\n' \
    "Newest reviewed commit this tag shares with ${MAIN_REF}:" \
    "  $(git log -1 --format='%h %s' "${base_sha}")" \
    "" \
    "Commits the tag adds on top of it:" \
    "$(git log --format='  %h %s' "${base_sha}..${head_sha}")"
}

head_sha="$(git rev-parse --verify --quiet "${HEAD_REF}^{commit}")" || head_sha=""
if [ -z "${head_sha}" ]; then
  undetermined \
    "Cannot verify the release lineage: '${HEAD_REF}' does not resolve to a commit." \
    "Refusing to build rather than assuming the tag is clean."
fi

main_sha="$(git rev-parse --verify --quiet "${MAIN_REF}^{commit}")" || main_sha=""
if [ -z "${main_sha}" ]; then
  undetermined \
    "Cannot verify the release lineage: '${MAIN_REF}' does not resolve to a commit." \
    "The checkout needs 'fetch-depth: 0' for the reviewed history to be present." \
    "Refusing to build rather than assuming the tag is clean."
fi

# The newest commit this tag shares with main: the reviewed point it was cut
# from. Everything after it is what the release adds.
base_sha="$(git merge-base "${head_sha}" "${main_sha}")" || base_sha=""
if [ -z "${base_sha}" ]; then
  undetermined \
    "Cannot verify the release lineage: ${HEAD_REF} and ${MAIN_REF} share no common commit." \
    "A shallow clone or an unrelated history will do this; the checkout needs 'fetch-depth: 0'." \
    "Refusing to build rather than assuming the tag is clean."
fi

changed="$(git diff --name-only "${base_sha}" "${head_sha}")" || undetermined \
  "Cannot verify the release lineage: could not diff ${base_sha} against ${head_sha}." \
  "Refusing to build rather than assuming the tag is clean."

offending=""
pom_touched=0
while IFS= read -r path; do
  [ -n "${path}" ] || continue
  if [ "${path}" = "${POM_PATH}" ]; then
    pom_touched=1
  fi
  allowed=0
  for candidate in ${ALLOWED_PATHS}; do
    if [ "${path}" = "${candidate}" ]; then
      allowed=1
      break
    fi
  done
  if [ "${allowed}" -eq 0 ]; then
    offending="${offending}  ${path}
"
  fi
done <<EOF
${changed}
EOF

if [ -n "${offending}" ]; then
  refuse \
    "Refusing to release ${HEAD_REF}: it carries changes that never went through main." \
    "" \
    "$(lineage_context)" \
    "" \
    "A release commit may only change: ${ALLOWED_PATHS}" \
    "These paths also changed:" \
    "${offending}" \
    "Delete the tag, take those paths out of the release commit, and re-cut the" \
    "release (see RELEASING.md). Adding a path to ALLOWED_PATHS in" \
    ".github/scripts/check-release-lineage.sh is not enough on its own: a path" \
    "allowed with no content rule beside it can carry anything."
fi

if [ "${pom_touched}" -eq 1 ]; then
  pom_diff="$(git diff --unified=0 "${base_sha}" "${head_sha}" -- "${POM_PATH}")" || undetermined \
    "Cannot verify the release lineage: could not diff ${POM_PATH} between ${base_sha} and ${head_sha}." \
    "Refusing to build rather than assuming the tag is clean."

  # Assert what WAS found, never what was not. Several non-clean states produce
  # no +/- content lines at all — a pom.xml with a NUL byte in it diffs as
  # "Binary files ... differ", a chmod diffs as "old mode"/"new mode" — and
  # inferring "clean" from an empty grep passes every one of them. So count the
  # version lines and require exactly one replaced by exactly one.
  #
  # Content lines are recognised by position, not by pattern: everything before
  # the first @@ hunk header is preamble. Matching the ---/+++ headers by their
  # text would also swallow a real removed line whose content starts with "-- ",
  # which is reachable inside an XML comment.
  in_hunk=0
  pom_added=0
  pom_removed=0
  pom_other=""
  while IFS= read -r line; do
    case "${line}" in
      @@*)
        in_hunk=1
        continue
        ;;
    esac
    [ "${in_hunk}" -eq 1 ] || continue
    case "${line}" in
      -*)
        if [[ "${line}" =~ ${POM_VERSION_LINE} ]]; then
          pom_removed=$((pom_removed + 1))
        else
          pom_other="${pom_other}  ${line}
"
        fi
        ;;
      +*)
        if [[ "${line}" =~ ${POM_VERSION_LINE} ]]; then
          pom_added=$((pom_added + 1))
        else
          pom_other="${pom_other}  ${line}
"
        fi
        ;;
    esac
  done <<EOF
${pom_diff}
EOF

  if [ "${pom_removed}" -ne 1 ] || [ "${pom_added}" -ne 1 ] || [ -n "${pom_other}" ]; then
    detail="Refusing to release ${HEAD_REF}: its ${POM_PATH} change is not the version bump.

$(lineage_context)

A release commit must replace exactly one project <version> line in ${POM_PATH}
and change nothing else in it. This diff removed ${pom_removed} and added ${pom_added} such line(s)."
    if [ -n "${pom_other}" ]; then
      detail="${detail}

Other ${POM_PATH} lines changed:
${pom_other}"
    fi
    refuse "${detail}" \
      "A dependency, plugin or repository added here would be compiled into the" \
      "release and signed with it, without anyone having reviewed it." \
      "" \
      "A count of zero means the version line could not be seen at all, which is" \
      "what a ${POM_PATH} carrying a NUL byte (git diffs it as binary) or changed" \
      "only in its file mode looks like. Neither can be cleared, so neither passes." \
      "" \
      "Take the change through a pull request on main instead."
  fi
fi

printf '%s\n' \
  "Release lineage OK: ${HEAD_REF} adds no change on top of ${base_sha} outside one project <version> line in ${POM_PATH}." \
  "That is all this establishes, and it establishes it about mistakes, not about an author who controls the tag." \
  "It does not vouch for the content of ${base_sha} itself — review on ${MAIN_REF} does that — nor for the version's value, which the tag-versus-pom check does next."
