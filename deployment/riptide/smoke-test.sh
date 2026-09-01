#!/usr/bin/env bash
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Brings up the shipped compose stack and asserts the properties #670 established
# by hand, which nothing else in CI covers:
#   - users.xml's from_env password merges over the entrypoint's generated
#     default-user.xml, and default keeps access_management
#   - ClickHouse's published ports are bound to loopback only (#651)
#   - an unauthenticated request is refused and a credentialled one is served
#   - riptide provisions its schema through the env:// SecretRef indirection
#   - Grafana's provisioned datasource reports healthy
#
# It gates the compose wiring, not riptide's own code: the stack runs the
# published image, so a code change is covered by `make e2e`, not by this.
#
# Invoked via `make compose-smoke`.
set -euo pipefail
cd "$(dirname "$0")/../.."

COMPOSE_FILE="deployment/riptide/compose.yml"
# Not the compose default: a real value proves the from_env indirection carried it
# rather than the entrypoint's fallback happening to match.
export CLICKHOUSE_PASSWORD="${CLICKHOUSE_PASSWORD:-smoke-$RANDOM-Xy9}"

cleanup() {
    local status=$?
    if [ "$status" -ne 0 ]; then
        echo "=== smoke: FAILED, container logs follow ==="
        docker compose -f "$COMPOSE_FILE" ps || true
        docker compose -f "$COMPOSE_FILE" logs --no-color --tail 100 || true
    fi
    docker compose -f "$COMPOSE_FILE" down --volumes --remove-orphans >/dev/null 2>&1 || true
    exit "$status"
}
trap cleanup EXIT

fail() {
    echo "FAIL: $1" >&2
    exit 1
}

echo "=== smoke: bringing the stack up ==="
# --wait blocks on the healthchecks the compose files already declare, so the
# stack being up is itself the first assertion: riptide's readyz only answers
# once it has provisioned its schema against ClickHouse.
docker compose -f "$COMPOSE_FILE" up --detach --wait --wait-timeout 300

echo "=== smoke: ClickHouse ports are loopback only (#651) ==="
for port in 8123 9000; do
    published="$(docker compose -f "$COMPOSE_FILE" port clickhouse "$port")"
    case "$published" in
        127.0.0.1:*) echo "  ok  $port -> $published" ;;
        *) fail "clickhouse $port is published on '$published', not loopback" ;;
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

echo "=== smoke: OK (compose stack, ClickHouse auth and grants, schema, Grafana datasource) ==="
