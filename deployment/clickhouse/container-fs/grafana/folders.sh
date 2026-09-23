#!/bin/sh
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Nests the provisioned "Flow Analytics" folder under "Riptide". Grafana's file
# provider creates the folder it is told (uid riptide-flow-analytics, see
# provisioning/dashboards/dashboards.yml) but cannot create a parent, and it keeps
# addressing the folder by uid after a move. So one move, once per Grafana
# database, is the whole job; every later run finds nothing to do. Runs as the
# grafana-folders one-shot service in compose.yml, with only sh and curl.
set -eu

GRAFANA="${GRAFANA_URL:-http://grafana:3000}"
AUTH="admin:${GF_SECURITY_ADMIN_PASSWORD:-admin}"
CHILD=riptide-flow-analytics
PARENT=riptide

get() { curl -sf -u "$AUTH" "$GRAFANA/api/folders/$1"; }
post() { curl -sf -u "$AUTH" -H 'Content-Type: application/json' -X POST "$GRAFANA/api/folders$1" -d "$2" >/dev/null; }
status() { curl -s -o /dev/null -w '%{http_code}' -u "$AUTH" "$GRAFANA/api/folders/$1"; }

# The provider creates the child a moment after Grafana reports healthy. A
# rejected login is not "not yet": Grafana keeps the admin password it was
# initialised with, so a changed GF_SECURITY_ADMIN_PASSWORD fails here at once.
i=0
until get "$CHILD" >/dev/null 2>&1; do
    case "$(status "$CHILD")" in
        401|403) echo "Grafana refused the admin login; GF_SECURITY_ADMIN_PASSWORD does not match the password Grafana was initialised with" >&2; exit 1 ;;
    esac
    i=$((i + 1))
    [ "$i" -le 60 ] || { echo "folder $CHILD never appeared; is the dashboards provider mounted?" >&2; exit 1; }
    sleep 2
done

get "$PARENT" >/dev/null 2>&1 || post "" "{\"uid\":\"$PARENT\",\"title\":\"Riptide\"}"

if get "$CHILD" | grep -q "\"parentUid\":\"$PARENT\""; then
    echo "Flow Analytics already under Riptide"
else
    post "/$CHILD/move" "{\"parentUid\":\"$PARENT\"}"
    echo "moved Flow Analytics under Riptide"
fi
