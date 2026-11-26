#!/usr/bin/env bash
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Fixture tests for check-codeql-degraded-scan.sh.
#
# The checker matches nothing in a healthy scan, so a green CodeQL run says as
# little about a working checker as about a broken one. These build SARIF files
# and assert that each refusal fires, and that a file the checker cannot read
# is never a pass.
#
# The fixtures are synthetic. Their shape is the one the codeql-action's own
# checks read, and the ids and messages are the ones the 2026-11-25 scan of main
# printed, but no file here was written by CodeQL. Whether a real scan carries
# what these assume is only shown by the `analyze` job passing or refusing.
#
# The load-bearing case is "no diagnostics at all". A SARIF without them must
# NOT pass. Dropping the CONTROL_ID check reads like a simplification and makes
# the checker pass everything the day CodeQL stops writing diagnostics.
#
# Written for bash 3.2 so it runs unchanged on a stock macOS shell.

set -o nounset -o pipefail -o errexit -o noglob

CHECK="$(cd "$(dirname "$0")" && pwd)/check-codeql-degraded-scan.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

SARIF="${WORK}/java.sarif"

MODE_ACTIVE="java/autobuilder/buildless/mode-active"
JAR_FETCH_FAILED="java/extractor/buildless/jar-fetch-failed"
FETCHES_FAILED="java/extractor/buildless/suggested-classpath-fetches-failed"
TRANSFER_EXCEPTION="java/autobuilder/buildless/depgraph-maven-plugin-transfer-exception"
DATABASE_QUALITY="java/diagnostic/database-quality"

failures=0
passes=0

# What every build-mode none scan of this repository reports, sound or not.
HEALTHY="${MODE_ACTIVE}
java/autobuilder/buildless/using-build-tool-advice
java/autobuilder/buildless/depgraph-provided-by-maven
java/autobuilder/buildless/jdk-system-default
java/autobuilder/buildless/complete"

# Writes a SARIF whose one invocation carries one notification per line of $1.
# Each message names its own position, so a test can tell which were printed.
write_sarif() {
  jq -R -s '{
    version: "2.1.0",
    runs: [{
      tool: {driver: {name: "CodeQL"}},
      invocations: [{
        executionSuccessful: true,
        toolExecutionNotifications: (
          split("\n") | map(select(length > 0)) | to_entries | map({
            descriptor: {id: .value},
            level: "note",
            message: {text: "message \(.key + 1) of \(.value)"}
          })
        )
      }],
      results: []
    }]
  }' >"${SARIF}" <<EOF
$1
EOF
}

# $2 repeated $1 times, one per line.
times() {
  local n="$1" out=""
  while [ "${n}" -gt 0 ]; do
    out="${out}$2
"
    n=$((n - 1))
  done
  printf '%s' "${out}"
}

# Runs the checker and asserts the exit code. Exit 1 is a refusal, 0 is a pass,
# 2 is undetermined.
expect() {
  local label="$1" want="$2" got=0
  shift 2
  "${CHECK}" "$@" >"${WORK}/out" 2>&1 || got=$?
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
# name the diagnostic is a refusal nobody can act on.
expect_mentions() {
  local label="$1" needle="$2"
  if grep -qF -- "${needle}" "${WORK}/out"; then
    passes=$((passes + 1))
    printf 'ok   %s\n' "${label}"
  else
    failures=$((failures + 1))
    printf 'FAIL %s: output does not mention %s\n' "${label}" "${needle}"
    sed 's/^/     /' "${WORK}/out"
  fi
}

expect_silent_on() {
  local label="$1" needle="$2"
  if grep -qF -- "${needle}" "${WORK}/out"; then
    failures=$((failures + 1))
    printf 'FAIL %s: output mentions %s\n' "${label}" "${needle}"
    sed 's/^/     /' "${WORK}/out"
  else
    passes=$((passes + 1))
    printf 'ok   %s\n' "${label}"
  fi
}

# ---------------------------------------------------------------- passes

write_sarif "${HEALTHY}"
expect "a scan with every dependency passes" 0 "${SARIF}"
expect_mentions "the pass does not claim the scan was complete" "does not say every type was resolved"
expect_mentions "the pass lists what the scan reported" "${MODE_ACTIVE}"

# Pins a stated limit, not a wish. The header of the checker says CodeQL's own
# quality verdict is not refused, because nobody has measured how a healthy scan
# of this repository scores. Refusing it is a decision to take with that number
# in hand, and this fails when someone takes it without changing the header.
write_sarif "${HEALTHY}
${DATABASE_QUALITY}"
expect "a low quality verdict alone is not refused" 0 "${SARIF}"

# ---------------------------------------------------------------- refusals

# The 2026-11-25 scan of main, with one diagnostic per jar and the summary. The five
# healthy diagnostics come first, so the jar messages are numbered from 6.
write_sarif "${HEALTHY}
$(times 49 "${JAR_FETCH_FAILED}")
${FETCHES_FAILED}
${DATABASE_QUALITY}"
expect "the 2026-11-25 scan is refused" 1 "${SARIF}"
expect_mentions "the refusal counts the jars" "${JAR_FETCH_FAILED} (49)"
expect_mentions "the refusal names the summary diagnostic" "${FETCHES_FAILED} (1)"
expect_mentions "the refusal prints the first messages" "message 6 of ${JAR_FETCH_FAILED}"
expect_mentions "the refusal prints up to the limit" "message 10 of ${JAR_FETCH_FAILED}"
expect_silent_on "the refusal stops at the limit" "message 11 of ${JAR_FETCH_FAILED}"
expect_mentions "the refusal says what to do" "re-run the job"

# Each id on its own, so no one of them is carried by another.
write_sarif "${HEALTHY}
${JAR_FETCH_FAILED}"
expect "one jar that could not be downloaded is refused" 1 "${SARIF}"
expect_mentions "the refusal names the diagnostic" "${JAR_FETCH_FAILED} (1)"

write_sarif "${HEALTHY}
${FETCHES_FAILED}"
expect "the fetch summary alone is refused" 1 "${SARIF}"
expect_mentions "the refusal names the diagnostic" "${FETCHES_FAILED} (1)"

write_sarif "${HEALTHY}
${TRANSFER_EXCEPTION}"
expect "a dependency graph Maven could not resolve is refused" 1 "${SARIF}"
expect_mentions "the refusal names the diagnostic" "${TRANSFER_EXCEPTION} (1)"

# ------------------------------------------------------- cannot determine

expect "no argument" 2
expect_mentions "the usage is printed" "usage:"

expect "a SARIF file that does not exist" 2 "${WORK}/missing.sarif"
expect_mentions "the missing file is named" "no SARIF file at"

printf 'not json\n' >"${SARIF}"
expect "a file that is not JSON" 2 "${SARIF}"
expect_mentions "the unreadable file is named" "is not SARIF this script can read"

printf '{}\n' >"${SARIF}"
expect "JSON that is not SARIF" 2 "${SARIF}"
expect_mentions "the unreadable file is named" "is not SARIF this script can read"

# What the SARIF looks like once the codeql-action stops including diagnostics.
write_sarif ""
expect "no diagnostics at all" 2 "${SARIF}"
expect_mentions "the missing control is named" "${MODE_ACTIVE}"

# A scan in another build mode, or after CodeQL renamed the control.
write_sarif "java/autobuilder/buildless/complete"
expect "diagnostics without the control" 2 "${SARIF}"
expect_mentions "the missing control is named" "${MODE_ACTIVE}"

# ----------------------------------------------------------------- report

printf '\n%s passed, %s failed\n' "${passes}" "${failures}"
[ "${failures}" -eq 0 ]
