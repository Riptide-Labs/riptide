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
# Usage: check-release-lineage.sh [<head-ref>] [<main-ref>]
#
# Written for bash 3.2 so it runs unchanged on a stock macOS shell.

set -o nounset -o pipefail -o errexit

HEAD_REF="${1:-HEAD}"
MAIN_REF="${2:-origin/main}"

# What `make release` puts in the release commit. `mvn versions:set` writes
# pom.xml and the commit is `git commit --signoff -am`, which sweeps in every
# other modified tracked file too — that sweep is the thing this guard catches.
# Widening this list widens what can be published without review, so it is a
# deliberate, reviewed change, never a wildcard.
ALLOWED_PATHS="pom.xml"

# Allowing the path is not enough: pom.xml decides what gets compiled in, so an
# unreviewed commit that adds a dependency, a plugin or a repository would be
# built and signed by the release. Neither existing check catches that — the
# version is not a SNAPSHOT and the tag still matches it. So the pom's own diff
# must be the version bump and nothing else.
#
# The measured shape, identical for v0.8.0, v0.8.1, v0.9.0, v0.11.0 and v0.12.0:
#
#   -    <version>0.10.1-SNAPSHOT</version>
#   +    <version>0.11.0</version>
#
# Four spaces is what makes this precise without parsing XML: the project's own
# <version> is the only one at that depth in pom.xml. The Spring Boot parent sits
# at 8, dependencies and plugins at 12 or deeper, and the version *properties*
# are not <version> elements at all — so repointing a dependency does not match,
# which a depth-blind rule would have let through.
#
# This checks the shape of the change, not the value. The value is the next
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
while IFS= read -r path; do
  [ -n "${path}" ] || continue
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
    "release (see RELEASING.md). If a path genuinely belongs in a release commit," \
    "add it to ALLOWED_PATHS in .github/scripts/check-release-lineage.sh through a" \
    "reviewed pull request."
fi

if printf '%s\n' "${changed}" | grep -qxF 'pom.xml'; then
  pom_diff="$(git diff --unified=0 "${base_sha}" "${head_sha}" -- pom.xml)" || undetermined \
    "Cannot verify the release lineage: could not diff pom.xml between ${base_sha} and ${head_sha}." \
    "Refusing to build rather than assuming the tag is clean."

  # Content lines only: drop the ---/+++ file headers, keep every other +/- line,
  # and see what is left once the project version bump is accounted for.
  pom_other_lines="$(printf '%s\n' "${pom_diff}" \
    | grep -E '^[+-]' \
    | grep -vE '^(--- |\+\+\+ )' \
    | grep -vE "${POM_VERSION_LINE}")" || pom_other_lines=""

  if [ -n "${pom_other_lines}" ]; then
    refuse \
      "Refusing to release ${HEAD_REF}: its pom.xml change is more than the version bump." \
      "" \
      "$(lineage_context)" \
      "" \
      "A release commit may only rewrite the project <version> line in pom.xml." \
      "These pom.xml lines also changed:" \
      "$(printf '%s\n' "${pom_other_lines}" | sed 's/^/  /')" \
      "" \
      "A dependency, plugin or repository added here would be compiled into the" \
      "release and signed with it, without anyone having reviewed it. Take the" \
      "change through a pull request on main instead."
  fi
fi

printf '%s\n' \
  "Release lineage OK: ${HEAD_REF} adds no change on top of ${base_sha} outside the project <version> line in pom.xml." \
  "That is all this establishes. It does not vouch for the content of ${base_sha} itself — review on ${MAIN_REF} does that — nor for the version's value, which the tag-versus-pom check does next."
