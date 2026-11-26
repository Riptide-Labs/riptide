#!/usr/bin/env bash
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Refuse to publish a CodeQL scan that ran without the project's dependencies.
#
# codeql.yml scans with `build-mode: none`. CodeQL asks Maven for the dependency
# graph and then downloads every jar itself. When a download fails the scan
# still exits 0. Every type from a missing jar is then unknown, so a variable
# passed to a library call reads as never read and a class named in an
# annotation reads as unused. On 2026-11-25 Maven Central was rate limiting the
# runner with HTTP 429, CodeQL could not download 49 jars, and that one scan of
# main opened 27 alerts against code with nothing wrong in it. A second scan of
# the same commit closed all of them.
#
# So codeql.yml holds the results back, runs this on them, and uploads only if
# it passes.
#
# WHAT THIS DOES AND DOES NOT ESTABLISH. It reads the diagnostics CodeQL wrote
# into its own SARIF and refuses the ones in DEGRADED_IDS. A pass means CodeQL
# reported none of them. It does not mean the scan was complete. CodeQL's wider
# verdict, `java/diagnostic/database-quality`, is not refused here, because how
# a healthy scan of this repository scores against its thresholds has not been
# measured. A cause of missing types that CodeQL reports under an id not listed
# below still passes.
#
# Usage: check-codeql-degraded-scan.sh <java.sarif>
#
# Fixture tests: `make codeql-degraded-scan-test`. This matches nothing in a
# healthy scan, so a green run on its own says as little about a working checker
# as about a broken one. Change the ids here and change those tests with them.
#
# Written for bash 3.2 so it runs unchanged on a stock macOS shell.

set -o nounset -o pipefail -o errexit -o noglob

SARIF="${1:-}"

# The diagnostics that mean "a dependency could not be transferred", space
# separated. The ids and what each one means are taken from CodeQL's own
# expectations for a scan with an unavailable dependency
# (java/ql/integration-tests/java/buildless-maven-tolerate-unavailable-dependency/diagnostics.expected
# in github/codeql). The first two are the ones the 2026-11-25 scan reported.
#
#   jar-fetch-failed                         one per jar that could not be downloaded
#   suggested-classpath-fetches-failed       once per scan, if any jar could not be
#   depgraph-maven-plugin-transfer-exception Maven could not resolve the graph itself,
#                                            so CodeQL guessed jars from package names
DEGRADED_IDS="java/extractor/buildless/jar-fetch-failed java/extractor/buildless/suggested-classpath-fetches-failed java/autobuilder/buildless/depgraph-maven-plugin-transfer-exception"

# Present in every `build-mode: none` scan, degraded or not. It is required
# because "none of DEGRADED_IDS was found" is also what a SARIF with no
# diagnostics in it looks like, and the codeql-action only includes them while a
# feature flag on GitHub's side says so. Without this, the day that flag turns
# off is the day this check passes everything.
CONTROL_ID="java/autobuilder/buildless/mode-active"

# How many messages of one diagnostic to print. A rate-limited scan reports one
# per jar, and the first few name the cause as well as all of them do.
MAX_MESSAGES=5

# Exit 1 for "this scan is degraded", exit 2 for "the question could not be
# answered". They are distinct on purpose. Not knowing is never the same as
# passing. Note that `make codeql-degraded-scan` reports both as make's own
# exit 2, so through the Makefile the two cases are told apart by the message,
# not by the exit code.
refuse() { printf '%s\n' "$@" >&2; exit 1; }
undetermined() { printf '%s\n' "$@" >&2; exit 2; }

NOT_UPLOADING="Refusing to upload rather than assuming the scan is sound."

if [ -z "${SARIF}" ]; then
  undetermined "usage: check-codeql-degraded-scan.sh <java.sarif>"
fi

command -v jq >/dev/null || undetermined \
  "Cannot check the CodeQL scan: jq is not installed." \
  "${NOT_UPLOADING}"

if [ ! -f "${SARIF}" ]; then
  undetermined \
    "Cannot check the CodeQL scan: no SARIF file at '${SARIF}'." \
    "${NOT_UPLOADING}"
fi

# Where CodeQL puts its diagnostics. The codeql-action's own checks read them
# from runs[].invocations[].toolExecutionNotifications[].descriptor.id.
NOTIFICATIONS='.runs[].invocations[]?.toolExecutionNotifications[]?'

ids="$(jq -r "${NOTIFICATIONS} | .descriptor.id" "${SARIF}")" || undetermined \
  "Cannot check the CodeQL scan: '${SARIF}' is not SARIF this script can read." \
  "${NOT_UPLOADING}"

count_of() {
  # grep exits 1 on a count of zero, which is an answer here and not a failure.
  grep -cxF -- "$1" <<EOF || true
${ids}
EOF
}

if [ "$(count_of "${CONTROL_ID}")" -eq 0 ]; then
  undetermined \
    "Cannot check the CodeQL scan: '${SARIF}' does not carry the diagnostic" \
    "'${CONTROL_ID}', which every build-mode none scan reports." \
    "" \
    "Either CodeQL no longer writes its diagnostics into the SARIF, or codeql.yml" \
    "no longer scans with build-mode none, or CodeQL renamed the diagnostic. In all" \
    "three cases the ids in .github/scripts/check-codeql-degraded-scan.sh need" \
    "another look before this check can tell a degraded scan from a sound one." \
    "${NOT_UPLOADING}"
fi

found=""
for id in ${DEGRADED_IDS}; do
  count="$(count_of "${id}")"
  [ "${count}" -gt 0 ] || continue
  messages="$(jq -r --arg id "${id}" --argjson max "${MAX_MESSAGES}" \
    "[${NOTIFICATIONS} | select(.descriptor.id == \$id) | .message.text] | .[:\$max][] | \"    \" + ." \
    "${SARIF}")"
  found="${found}  ${id} (${count})
${messages}
"
done

if [ -n "${found}" ]; then
  refuse \
    "Refusing to upload this CodeQL scan: it ran without all of the project's dependencies." \
    "" \
    "CodeQL reported, with at most ${MAX_MESSAGES} messages shown for each:" \
    "${found}" \
    "Types from a jar that is missing are unknown to the analysis, and it then" \
    "reports uses it cannot see as unused variables, parameters and classes." \
    "Uploading that would open alerts against code with nothing wrong in it." \
    "" \
    "Nothing was uploaded and no alert changed. If the cause is an outage or a rate" \
    "limit at the repository, re-run the job. If the same jar fails on every run," \
    "the scan cannot resolve it at all and that needs fixing in pom.xml or here."
fi

printf '%s\n' \
  "CodeQL scan OK to upload: it reports none of the dependency transfer failures this check knows." \
  "That is all this establishes. It does not say every type was resolved." \
  "" \
  "Diagnostics in ${SARIF}, by count:" \
  "$(sort <<EOF | uniq -c
${ids}
EOF
)"
