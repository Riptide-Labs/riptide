#!/bin/sh
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Trains the image's AOT cache (#987). Runs inside the image build, as the final stage's own JVM
# and class path, so the cache matches what the image starts. Needs a ClickHouse the build reaches
# on the host network (make oci and the image workflows start one); without it the build fails.
#
# Both platforms of a multi-arch build train at the same time on one host network, so ports and
# the database are per architecture.
set -eu

arch="${TARGETARCH:?TARGETARCH unset}"
clickhouse="${AOT_TRAIN_CLICKHOUSE:?AOT_TRAIN_CLICKHOUSE unset}"
seconds="${AOT_TRAIN_SECONDS:-45}"
case "${arch}" in
  amd64) offset=1 ;;
  arm64) offset=2 ;;
  *) offset=3 ;;
esac
flows_port=$((offset * 10000 + 9999))
mgmt_port=$((offset * 10000 + 8080))
database="aot_train_${arch}"

ch() { wget -qO- --post-data "$1" "${clickhouse}/" ; }

ch "SELECT 1" >/dev/null || { echo "aot-train: no ClickHouse at ${clickhouse}; start one before building the image" >&2; exit 1; }
ch "DROP DATABASE IF EXISTS ${database}" >/dev/null

RIPTIDE_CLICKHOUSE_ENDPOINT="${clickhouse}" \
RIPTIDE_CLICKHOUSE_DATABASE="${database}" \
RIPTIDE_CLICKHOUSE_STARTUP_WAIT=10s \
RIPTIDE_RECEIVERS_FLOWS_TYPE=multi \
RIPTIDE_RECEIVERS_FLOWS_HOST=0.0.0.0 \
RIPTIDE_RECEIVERS_FLOWS_PORT="${flows_port}" \
RIPTIDE_MANAGEMENT_PORT="${mgmt_port}" \
RIPTIDE_MANAGEMENT_BIND_ADDRESS=127.0.0.1 \
  java -XX:AOTCacheOutput=/app/riptide.aot -jar /app/riptide.jar >/tmp/aot-train.log 2>&1 &
pid=$!

# Slow under QEMU, so a generous bound; a riptide that exits early ends the wait at once.
ready=""
for _ in $(seq 1 300); do
  kill -0 "${pid}" 2>/dev/null || break
  wget -qO- "http://127.0.0.1:${mgmt_port}/readyz" >/dev/null 2>&1 && { ready=yes; break; }
  sleep 1
done
[ -n "${ready}" ] || { cat /tmp/aot-train.log >&2; echo "aot-train: riptide never became ready" >&2; exit 1; }

java /train/bin/Send.java 127.0.0.1 "${flows_port}" /train/flows "${seconds}"
sleep 5

kill -TERM "${pid}"
wait "${pid}" || true
# Count what ClickHouse wrote, not what the table holds: the fixtures carry timestamps years old and
# the flows table has a 30-day TTL, so TTL merges delete training rows within seconds.
ch "SYSTEM FLUSH LOGS" >/dev/null
rows="$(ch "SELECT sum(rows) FROM system.part_log WHERE database = '${database}' AND table = 'flows' AND event_type = 'NewPart'")"
ch "DROP DATABASE IF EXISTS ${database}" >/dev/null
echo "aot-train: ${rows} flows persisted to ${database}"

[ "${rows}" -gt 0 ] || { cat /tmp/aot-train.log >&2; echo "aot-train: no flows persisted" >&2; exit 1; }
[ -s /app/riptide.aot ] || { cat /tmp/aot-train.log >&2; echo "aot-train: no AOT cache written" >&2; exit 1; }
ls -l /app/riptide.aot
rm -rf /tmp/aot-train.log /tmp/hsperfdata_root
