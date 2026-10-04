#!/bin/sh
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Fails the image build unless this stage's JVM accepts the AOT cache train.sh wrote (#987). Runs
# right after training, before anything is pushed. The JVM skips a mismatched cache with only a
# warning, so this starts riptide with -XX:AOTMode=on, which makes a rejected cache fatal at VM init.
# No ClickHouse is reachable on port 1, so riptide then fails on purpose; reaching its first log
# line is what tells an accepted cache apart from a rejected one.
set -eu

log="$(java -XX:AOTCache=/app/riptide.aot -XX:AOTMode=on -Xlog:aot -jar /app/riptide.jar \
  --riptide.clickhouse.endpoint=http://127.0.0.1:1 --riptide.clickhouse.startup-wait=0s 2>&1 || true)"
rm -rf /tmp/hsperfdata_root

# Match on the captured text: a pipe into grep -q can report a present line as missing under pipefail.
case "${log}" in
  *"Using AOT-linked classes: true"*) ;;
  *) printf '%s\n' "${log}" | tail -n 20 >&2; echo "aot-check: the JVM did not use the AOT cache" >&2; exit 1 ;;
esac
case "${log}" in
  *"Starting RiptideApplication"*) ;;
  *) printf '%s\n' "${log}" | tail -n 20 >&2; echo "aot-check: the JVM stopped before riptide started" >&2; exit 1 ;;
esac
echo "aot-check: AOT cache accepted"
