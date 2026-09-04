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

# Exit 1 for "this release is not allowed", exit 2 for "the question could not be
# answered". Distinct on purpose: not knowing is never the same as passing.
refuse() { printf '%s\n' "$@" >&2; exit 1; }
undetermined() { printf '%s\n' "$@" >&2; exit 2; }

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
    "Newest reviewed commit this tag shares with ${MAIN_REF}:" \
    "  $(git log -1 --format='%h %s' "${base_sha}")" \
    "" \
    "Commits the tag adds on top of it:" \
    "$(git log --format='  %h %s' "${base_sha}..${head_sha}")" \
    "" \
    "A release commit may only change: ${ALLOWED_PATHS}" \
    "These paths also changed:" \
    "${offending}" \
    "Delete the tag, take those paths out of the release commit, and re-cut the" \
    "release (see RELEASING.md). If a path genuinely belongs in a release commit," \
    "add it to ALLOWED_PATHS in .github/scripts/check-release-lineage.sh through a" \
    "reviewed pull request."
fi

echo "Release lineage OK: ${HEAD_REF} adds nothing outside [${ALLOWED_PATHS}] on top of ${base_sha}, which is on ${MAIN_REF}."
