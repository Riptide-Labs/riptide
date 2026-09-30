#!/usr/bin/env bash
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Brings up the shipped compose stack and asserts the properties #670 established
# by hand, which nothing else in CI covers:
#   - users.xml's from_env password merges over the entrypoint's generated
#     default-user.xml, and default keeps access_management
#   - ClickHouse's, Prometheus's and Pyroscope's published ports are bound to loopback only (#651)
#   - an unauthenticated request is refused and a credentialled one is served
#   - riptide provisions its schema through the env:// SecretRef indirection
#   - Grafana's provisioned ClickHouse and Prometheus datasources report healthy
#   - Grafana reads riptide.flows as the grafana user, which cannot write or change schema,
#     neither directly nor through the datasource
#   - Prometheus scrapes riptide as job="riptide" and loads the 9 alert rules (#908)
#   - riptide's profiles reach Pyroscope and its Grafana datasource is healthy (#921)
#   - every dashboard in the source tree sits in Flow Analytics under Riptide, none in General (#864)
# Then it stops the stack, keeping its volumes, and starts it again with
# compose.override.no-self-monitoring.yml (#927), asserting:
#   - no prometheus or pyroscope container exists and riptide does not profile
#   - Grafana holds only the ClickHouse datasource, the two a default run provisioned deleted
#   - with CLICKHOUSE_GRAFANA_PASSWORD unset, the datasource still reads as grafana
#   - riptide still provisions its schema, and every dashboard is still in Flow Analytics
#   - neither variant leaves a mountpoint file behind in the source tree
#
# It gates the compose wiring, not riptide's own code: the stack runs the
# published image, so a code change is covered by `make e2e`, not by this.
#
# Invoked via `make compose-smoke`.
set -euo pipefail
cd "$(dirname "$0")/../.."

COMPOSE_FILE="deployment/riptide/compose.yml"
DROP_FILE="deployment/riptide/compose.override.no-self-monitoring.yml"
# The files the current phase runs with, for the failure logs. The trap's `down` always uses the
# plain file: with the override, Compose neither stops nor removes the services and volumes of a
# disabled profile.
PHASE_FILES=(-f "$COMPOSE_FILE")
# Not the compose default: a real value proves the from_env indirection carried it
# rather than the entrypoint's fallback happening to match.
export CLICKHOUSE_PASSWORD="${CLICKHOUSE_PASSWORD:-smoke-$RANDOM-Xy9}"
# Distinct from CLICKHOUSE_PASSWORD, so a datasource or users.xml still wired to the admin password
# fails the grafana checks instead of passing on a shared value. The drop phase unsets it.
export CLICKHOUSE_GRAFANA_PASSWORD="${CLICKHOUSE_GRAFANA_PASSWORD:-smoke-gf-$RANDOM-Qz4}"

cleanup() {
    local status=$?
    if [ "$status" -ne 0 ]; then
        echo "=== smoke: FAILED, container logs follow ==="
        docker compose "${PHASE_FILES[@]}" ps || true
        docker compose "${PHASE_FILES[@]}" logs --no-color --tail 100 || true
    fi
    docker compose -f "$COMPOSE_FILE" down --volumes --remove-orphans >/dev/null 2>&1 || true
    exit "$status"
}
trap cleanup EXIT

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

# One raw SQL query through the provisioned ClickHouse datasource, so it runs as whatever user and
# password Grafana resolved for it; the health check only connects and cannot show either.
grafana_sql() {
    curl -s -u admin:admin -H 'Content-Type: application/json' "http://127.0.0.1:3000/api/ds/query" \
        -d "{\"queries\":[{\"refId\":\"A\",\"datasource\":{\"uid\":\"riptide-clickhouse\"},\"rawSql\":\"$1\",\"format\":1}],\"from\":\"now-5m\",\"to\":\"now\"}"
}

assert_datasource_reads_as_grafana() {
    local who
    who="$(grafana_sql "SELECT currentUser() AS u")"
    case "$who" in
        *'"values":[["grafana"]]'*) echo "  ok  the datasource reads as grafana" ;;
        *) fail "the datasource did not read as grafana: '$who'" ;;
    esac
}

tree_state() {
    git status --porcelain --untracked-files=all -- deployment/clickhouse
}
tree_before="$(tree_state)"

echo "=== smoke: bringing the stack up ==="
# --wait blocks on the healthchecks the compose files already declare, so the
# stack being up is itself the first assertion: riptide's readyz only answers
# once it has provisioned its schema against ClickHouse. The long-running
# services are named because --wait counts the grafana-folders one-shot's
# clean exit as a failure; that one runs in the foreground below so its exit
# code is the assertion.
docker compose -f "$COMPOSE_FILE" up --detach --wait --wait-timeout 300 clickhouse grafana prometheus pyroscope riptide
docker compose -f "$COMPOSE_FILE" run --rm --no-deps grafana-folders

echo "=== smoke: ClickHouse, Prometheus and Pyroscope ports are loopback only (#651) ==="
for service_port in clickhouse:8123 clickhouse:9000 prometheus:9090 pyroscope:4040; do
    service="${service_port%%:*}"
    port="${service_port##*:}"
    published="$(docker compose -f "$COMPOSE_FILE" port "$service" "$port")"
    case "$published" in
        127.0.0.1:*) echo "  ok  $service $port -> $published" ;;
        *) fail "$service $port is published on '$published', not loopback" ;;
    esac
done

echo "=== smoke: the default user is password-protected and still an admin ==="
anonymous="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:8123/?query=SELECT+1")"
[ "$anonymous" = "401" ] || fail "an unauthenticated query answered $anonymous, expected 401"
echo "  ok  unauthenticated -> 401"

authenticated="$(curl -s -u "default:${CLICKHOUSE_PASSWORD}" "http://127.0.0.1:8123/?query=SELECT+1")"
[ "$authenticated" = "1" ] || fail "an authenticated query answered '$authenticated', expected 1"
echo "  ok  authenticated -> 1"

# The half a bare password check cannot see: revert users.xml and the entrypoint's
# access_management: 0 wins silently, leaving default unable to manage grants.
grants="$(curl -s -u "default:${CLICKHOUSE_PASSWORD}" \
    --data-binary "SHOW GRANTS FOR default" "http://127.0.0.1:8123/")"
case "$grants" in
    *"GRANT ALL"*"WITH GRANT OPTION"*) echo "  ok  default holds GRANT ALL ... WITH GRANT OPTION" ;;
    *) fail "SHOW GRANTS FOR default returned '$grants'" ;;
esac

echo "=== smoke: riptide provisioned its schema through env:// ==="
tables="$(curl -s -u "default:${CLICKHOUSE_PASSWORD}" \
    --data-binary "SELECT count() FROM system.tables WHERE database = 'riptide'" \
    "http://127.0.0.1:8123/")"
[ "${tables:-0}" -gt 0 ] || fail "database 'riptide' holds $tables tables; the collector provisioned nothing"
echo "  ok  riptide database holds $tables tables"

echo "=== smoke: Grafana's provisioned datasource is healthy ==="
# By stable uid, so the check fails loudly if the provisioning file is renamed
# rather than silently passing against some other datasource.
health="$(curl -s -u admin:admin "http://127.0.0.1:3000/api/datasources/uid/riptide-clickhouse/health")"
case "$health" in
    *'"status":"OK"'*) echo "  ok  datasource riptide-clickhouse reports OK" ;;
    *) fail "datasource health returned '$health'" ;;
esac

echo "=== smoke: Grafana reads as a read-only user ==="
assert_datasource_reads_as_grafana
# The grant names the database literally (users.xml); a drift from CLICKHOUSE_DB fails here, which
# the health check, connecting only, would not notice.
counted="$(grafana_sql "SELECT count() AS c FROM riptide.flows")"
case "$counted" in
    *'"values":[['*) echo "  ok  the datasource reads riptide.flows" ;;
    *) fail "the datasource cannot read riptide.flows: '$counted'" ;;
esac
# Through Grafana too: Explore runs an editor's SQL as the datasource's user, which is the path the
# read-only user exists to close. The plugin reports only the numeric code, "code: 497, message:",
# not the name: 497 is ACCESS_DENIED (the grants), 164 is READONLY.
via_grafana="$(grafana_sql "CREATE TABLE riptide.smoke_ds (x UInt8) ENGINE = Memory")"
case "$via_grafana" in
    *'code: 497,'* | *'code: 164,'*) echo "  ok  DDL through the datasource refused" ;;
    *) fail "DDL through the datasource was not refused: '$via_grafana'" ;;
esac
# Direct, not through Grafana, so the refusal is ClickHouse's own. Matched on the error code: a
# wrong password (AUTHENTICATION_FAILED) or a typo (SYNTAX_ERROR) must not pass as a refusal. Either
# the grants (ACCESS_DENIED) or readonly (READONLY) may be what refuses; both are in users.xml.
as_grafana() {
    curl -s -u "grafana:${CLICKHOUSE_GRAFANA_PASSWORD}" --data-binary "$1" "http://127.0.0.1:8123/"
}
for statement in "INSERT INTO riptide.flows (tenant) VALUES ('smoke')" \
        "CREATE TABLE riptide.smoke (x UInt8) ENGINE = Memory"; do
    refused="$(as_grafana "$statement")"
    case "$refused" in
        *'(ACCESS_DENIED)'* | *'(READONLY)'*) echo "  ok  refused: ${statement%% (*}" ;;
        *) fail "grafana was not refused '$statement': '$refused'" ;;
    esac
done

echo "=== smoke: Prometheus scrapes riptide and loads its alert rules (#908) ==="
# The first scrape lands within one 15 s interval of the target appearing; poll
# rather than sleep so a healthy stack does not wait out a fixed guess.
target_up=""
for _ in $(seq 1 30); do
    targets="$(curl -s "http://127.0.0.1:9090/api/v1/targets?state=active")"
    case "$targets" in
        *'"job":"riptide"'*'"health":"up"'*) target_up=yes; break ;;
    esac
    sleep 2
done
[ -n "$target_up" ] || fail "Prometheus has no healthy riptide target: '$targets'"
echo "  ok  job riptide target is up"
# One line of JSON again, so grep -o into wc -l, and || true for the same reason
# as count_riptide_dashboards below.
alerts="$(curl -s "http://127.0.0.1:9090/api/v1/rules?type=alert" \
    | { grep -o '"type":"alerting"' || true; } | wc -l | tr -d ' ')"
[ "$alerts" = "9" ] || fail "Prometheus loaded $alerts alert rules, expected 9"
echo "  ok  9 alert rules loaded"
prometheus_health="$(curl -s -u admin:admin "http://127.0.0.1:3000/api/datasources/uid/riptide-prometheus/health")"
case "$prometheus_health" in
    *'"status":"OK"'*) echo "  ok  datasource riptide-prometheus reports OK" ;;
    *) fail "Prometheus datasource health returned '$prometheus_health'" ;;
esac

echo "=== smoke: riptide's profiles reach Pyroscope (#921) ==="
# Pyroscope has no healthcheck (its image is distroless), so wait on /ready from here: about a
# minute after start, once its ingester has joined the ring. Then the agent's first upload.
pyroscope_ready=""
for _ in $(seq 1 60); do
    [ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:4040/ready")" = "200" ] && { pyroscope_ready=yes; break; }
    sleep 3
done
[ -n "$pyroscope_ready" ] || fail "Pyroscope never reported ready on 127.0.0.1:4040"
echo "  ok  Pyroscope ready"
services=""
for _ in $(seq 1 40); do
    now_ms="$(($(date +%s) * 1000))"
    services="$(curl -s -X POST "http://127.0.0.1:4040/querier.v1.QuerierService/LabelValues" \
        -H 'Content-Type: application/json' \
        -d "{\"name\":\"service_name\",\"start\":$((now_ms - 3600000)),\"end\":${now_ms}}")"
    case "$services" in
        *'"riptide"'*) break ;;
    esac
    sleep 3
done
case "$services" in
    *'"riptide"'*) echo "  ok  Pyroscope lists service_name=riptide" ;;
    *) fail "riptide's profiles never reached Pyroscope: '$services'" ;;
esac
pyroscope_health="$(curl -s -u admin:admin "http://127.0.0.1:3000/api/datasources/uid/riptide-pyroscope/health")"
case "$pyroscope_health" in
    *'"status":"OK"'*) echo "  ok  datasource riptide-pyroscope reports OK" ;;
    *) fail "Pyroscope datasource health returned '$pyroscope_health'" ;;
esac

echo "=== smoke: the dashboards sit in Riptide / Flow Analytics, none in General (#864) ==="
# The provider creates the child folder; the grafana-folders one-shot nests it. Both are
# addressed by uid so a renamed folder fails here rather than passing by title. The
# provider re-reads its files every 30 s (updateIntervalSeconds in dashboards.yml), so
# waiting past one pass is what proves it keeps the folder where it was moved; checking
# right after the move would pass on a Grafana that reverts it.
sleep 35
child="$(curl -s -u admin:admin "http://127.0.0.1:3000/api/folders/riptide-flow-analytics")"
case "$child" in
    *'"parentUid":"riptide"'*) echo "  ok  folder riptide-flow-analytics has parent riptide" ;;
    *) fail "folder riptide-flow-analytics is not under riptide: '$child'" ;;
esac
# grep exits 1 when it matches nothing, and that is the passing case for General:
# without the `|| true` the pipeline's failure ends the script under `set -e` and
# `pipefail` before the assertion below can run, so a correct stack reports failure
# with no FAIL line at all. The API answers on one line, so counting occurrences
# needs `grep -o` piped into `wc -l`, not `grep -c`.
count_riptide_dashboards() {
    curl -s -u admin:admin "http://127.0.0.1:3000/api/search?type=dash-db&folderUIDs=$1" \
        | { grep -o '"uid":"riptide-' || true; } | wc -l | tr -d ' '
}

# Counted from the source tree, so adding a dashboard cannot leave this asserting the old set.
shipped="$(ls deployment/clickhouse/container-fs/grafana/provisioning/dashboards/riptide-*.json | wc -l | tr -d ' ')"
in_folder="$(count_riptide_dashboards riptide-flow-analytics)"
[ "$in_folder" = "$shipped" ] || fail "expected $shipped riptide dashboards in Flow Analytics, found $in_folder"
echo "  ok  $shipped dashboards in Flow Analytics"
in_general="$(count_riptide_dashboards general)"
[ "$in_general" = "0" ] || fail "$in_general riptide dashboards are still in General"
echo "  ok  none in General"

# The positive control for the drop phase's "does not profile" check: the agent logs this token
# itself, outside riptide's WARN root logger, so a drop phase without it is evidence only because
# this phase shows it.
# Read into a variable before grep: grep -q exits on its first match, `docker compose logs` then
# exits 255 on the closed pipe, and under pipefail a match reads as no match.
riptide_log="$(docker compose -f "$COMPOSE_FILE" logs --no-color riptide)"
grep -q 'Profiling started' <<<"$riptide_log" \
    || fail "riptide logged no 'Profiling started' with profiling on"
echo "  ok  riptide logged 'Profiling started'"

# A file mounted inside a directory bind makes Docker create the mountpoint on the host (#927).
# Compared with the tree as the script found it, so a developer's uncommitted work is not a failure.
assert_clean_tree() {
    local now
    now="$(tree_state)"
    [ "$now" = "$tree_before" ] || fail "the stack left files in the source tree: $(diff <(echo "$tree_before") <(echo "$now") || true)"
    echo "  ok  no mountpoint files in deployment/clickhouse"
}
assert_clean_tree

echo "=== smoke: default stack OK (compose stack, ClickHouse auth and grants, schema, Grafana datasources, grafana read user, Prometheus scrape and rules, Pyroscope profiles, dashboard folder) ==="

echo "=== smoke: restarting without self-monitoring on the same volumes (#927) ==="
# A plain down, as the compose guide says: with the override, Compose would leave the running
# Prometheus and Pyroscope alone. Without -v, so Grafana keeps the two datasources the default run
# provisioned and the delete file has something to delete.
docker compose -f "$COMPOSE_FILE" down
# The upgrade case: a stack that predates CLICKHOUSE_GRAFANA_PASSWORD sets only
# CLICKHOUSE_PASSWORD, and ClickHouse and Grafana must both fall back to it.
unset CLICKHOUSE_GRAFANA_PASSWORD
PHASE_FILES=(-f "$COMPOSE_FILE" -f "$DROP_FILE")
docker compose "${PHASE_FILES[@]}" up --detach --wait --wait-timeout 300 clickhouse grafana riptide
docker compose "${PHASE_FILES[@]}" run --rm --no-deps grafana-folders

echo "=== smoke: no self-monitoring services, and riptide does not profile ==="
# What the documented command starts: `up` above names its services, so it would never start
# Prometheus, which nothing depends on, whatever the override says. A disabled profile drops a
# service from this list.
active="$(docker compose "${PHASE_FILES[@]}" config --services)"
for service in prometheus pyroscope; do
    ! grep -qx "$service" <<<"$active" || fail "the override leaves $service enabled: $(tr '\n' ' ' <<<"$active")"
    echo "  ok  $service not enabled"
done
for service in prometheus pyroscope; do
    # The plain file, which still names the service; with the override it is in a disabled profile.
    running="$(docker compose -f "$COMPOSE_FILE" ps --all --quiet "$service")"
    [ -z "$running" ] || fail "a $service container exists without self-monitoring"
    echo "  ok  no $service container"
done
riptide_log="$(docker compose "${PHASE_FILES[@]}" logs --no-color riptide)"
if grep -q 'Profiling started' <<<"$riptide_log"; then
    fail "riptide logged 'Profiling started' without self-monitoring"
fi
echo "  ok  riptide logged no 'Profiling started'"

echo "=== smoke: riptide still provisioned its schema ==="
tables="$(curl -s -u "default:${CLICKHOUSE_PASSWORD}" \
    --data-binary "SELECT count() FROM system.tables WHERE database = 'riptide'" \
    "http://127.0.0.1:8123/")"
[ "${tables:-0}" -gt 0 ] || fail "database 'riptide' holds $tables tables without self-monitoring"
echo "  ok  riptide database holds $tables tables"

echo "=== smoke: Grafana holds only the ClickHouse datasource ==="
datasources="$(curl -s -u admin:admin "http://127.0.0.1:3000/api/datasources" \
    | { grep -o '"uid":"[^"]*"' || true; } | tr '\n' ' ')"
[ "$datasources" = '"uid":"riptide-clickhouse" ' ] \
    || fail "expected only riptide-clickhouse, Grafana lists: $datasources"
echo "  ok  only riptide-clickhouse"
health="$(curl -s -u admin:admin "http://127.0.0.1:3000/api/datasources/uid/riptide-clickhouse/health")"
case "$health" in
    *'"status":"OK"'*) echo "  ok  datasource riptide-clickhouse reports OK" ;;
    *) fail "datasource health returned '$health'" ;;
esac
assert_datasource_reads_as_grafana

echo "=== smoke: the dashboards are still in Riptide / Flow Analytics ==="
# The drop override restates Grafana's volume list; losing the dashboards mount there fails here.
# The provider has already read its files by the time Grafana reports healthy, and the folder
# survived the default phase's 35 s wait, so no second wait.
in_folder="$(count_riptide_dashboards riptide-flow-analytics)"
[ "$in_folder" = "$shipped" ] || fail "expected $shipped riptide dashboards in Flow Analytics without self-monitoring, found $in_folder"
echo "  ok  $shipped dashboards in Flow Analytics"
in_general="$(count_riptide_dashboards general)"
[ "$in_general" = "0" ] || fail "$in_general riptide dashboards are in General without self-monitoring"
echo "  ok  none in General"
assert_clean_tree

echo "=== smoke: OK (default stack, then without self-monitoring on its volumes) ==="
