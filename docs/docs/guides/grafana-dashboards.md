---
sidebar_position: 4.5
title: Grafana dashboards
description: Install the riptide dashboard set into Grafana from the compose stack, the deb or rpm package, the release tarball or the Grafana Helm chart, keep it in the Riptide / Flow Analytics folder, and upgrade it in place.
---

# Install and upgrade the Grafana dashboards

The set is nine dashboards and one provisioning file. Grafana's file provider loads it into the folder **Flow Analytics** (uid `riptide-flow-analytics`), which sits under **Riptide**. The set carries its own version, shown as a **`Dashboards vX.Y.Z`** link in every dashboard's top bar; that link opens this page.

| Dashboard | Answers |
| --- | --- |
| **Riptide - Top 10** | Top talkers by AS, host, application, service, protocol, exporter and interface, with a source-AS table carrying a 95th percentile. |
| **Riptide - Traffic Paths (Sankey)** | Where traffic enters and leaves: AS peering, geo origination and termination, ultimate exit, filterable by exporter and direction. |
| **Riptide - Flow Forensics** | One slice of flows by tenant, zone, exporter, application, HTTP host and URI, protocol, address and port, down to the raw records. |
| **Riptide - Collection Health** | Is every exporter delivering: reporting and silent verdicts, activity timeline, collection lag, exporter inventory. |
| **Riptide - Interface Traffic Analysis** | Throughput and usage per exporter interface, by application, conversation, host and DSCP, in versus out. |
| **Riptide - Capacity & Routing** | Headroom against SNMP-reported link speed, next-hop distribution, prefix volume, one-directional conversations. |
| **Riptide - Behavioural Anomalies** | Scanning, sweeps, repeated attempts on service ports, SYN-only ratio, fan-in targets, packet-size outliers, with thresholds as variables. |
| **Riptide - Traffic Composition** | Country maps, VLAN and DSCP mix, flow duration, IPv4 versus IPv6, prefix lengths, core services. |
| **Riptide - Data Trust** | Sampling configuration per exporter, clock corrections, tenant and zone labelling, exporter identity. |

Every dashboard has a **Datasource** and a **Database** variable, so the same JSON works against any ClickHouse datasource and any riptide database name.

## Prerequisites

- Grafana 11 or newer with nested folders. Everything on this page was verified on Grafana 13.0.2, the version the compose stack pins.
- Plugins **`grafana-clickhouse-datasource`** and **`netsage-sankey-panel`** installed.
- A ClickHouse datasource pointing at the riptide database. The compose stack provisions one; elsewhere, add it under *Connections* first.
- For the tarball and package paths: shell access to the Grafana host.
- For the Helm path: `helm` and `kubectl` access to the release's namespace.
- For the tarball, package and Helm paths: an admin login for the one-time folder move.
- For the API path: `python3` and a Grafana service-account token with the Editor role.

## Install with the compose stack

Nothing to install. The stack bind-mounts the set from the checkout and a one-shot service nests the folder after Grafana is healthy.
The commands need the stack's `.env` file with `CLICKHOUSE_PASSWORD`; see [Run the Docker Compose stack](docker-compose.md#steps).

```bash
cd deployment/riptide
docker compose up -d
docker compose logs --no-color grafana-folders
```

Expected output:

```text
grafana-folders-1  | moved Flow Analytics under Riptide
```

On every later `docker compose up -d` the same service prints `Flow Analytics already under Riptide` and exits.

## Install from the release tarball

Use this for a Grafana you run yourself, whose provisioning directory you can write to.

1. Download the set for the riptide release you run from its [release page](https://github.com/Riptide-Labs/riptide/releases). The asset is named after the set version, not the riptide version: **`riptide-dashboards-1.0.2.tar.gz`** for set 1.0.2. A `.sigstore.json` bundle sits next to it like every other asset.

2. Extract it into Grafana's provisioning directory. The archive holds one `dashboards/` directory, so it lands as `/etc/grafana/provisioning/dashboards/`.

   ```bash
   tar -tzf riptide-dashboards-1.0.2.tar.gz
   sudo tar -xzf riptide-dashboards-1.0.2.tar.gz -C /etc/grafana/provisioning/
   ```

   Expected output:

   ```text
   dashboards/dashboards.yml
   dashboards/riptide-behavioural-anomalies.json
   dashboards/riptide-capacity-routing.json
   dashboards/riptide-collection-health.json
   dashboards/riptide-data-trust.json
   dashboards/riptide-flow-forensics.json
   dashboards/riptide-interface-traffic-analysis.json
   dashboards/riptide-top-10.json
   dashboards/riptide-traffic-composition.json
   dashboards/riptide-traffic-paths.json
   ```

   The provider in **`dashboards.yml`** loads every JSON file under its `path`. If that directory already holds other dashboards, extract somewhere else and change `path` in `dashboards.yml` to that directory.

3. Restart Grafana. Providers are read at startup only; the files under them are re-read every 30 seconds.

4. Move the folder under **Riptide** once. The file provider creates `Flow Analytics` but cannot create a parent. After the move it keeps the folder where it is, because it addresses the folder by uid.

   ```bash
   GRAFANA=http://localhost:3000
   AUTH=admin:admin
   curl -s -u "$AUTH" -H 'Content-Type: application/json' -X POST "$GRAFANA/api/folders" \
     -d '{"uid":"riptide","title":"Riptide"}' -o /dev/null -w '%{http_code}\n'
   curl -s -u "$AUTH" -H 'Content-Type: application/json' -X POST "$GRAFANA/api/folders/riptide-flow-analytics/move" \
     -d '{"parentUid":"riptide"}' -o /dev/null -w '%{http_code}\n'
   ```

   Expected output:

   ```text
   200
   200
   ```

   The first call answers `412` when a folder `Riptide` already exists; the move still applies.
   The same two steps in the UI: *Dashboards* > *New* > *New folder* named `Riptide`, then open `Flow Analytics` > *Folder actions* > *Move* and pick `Riptide`.

5. Verify.

   ```bash
   curl -s -u "$AUTH" "$GRAFANA/api/folders/riptide-flow-analytics" | grep -o '"parentUid":"[^"]*"'
   curl -s -u "$AUTH" "$GRAFANA/api/search?type=dash-db&folderUIDs=riptide-flow-analytics" | grep -o '"uid":"riptide-' | wc -l
   ```

   Expected output:

   ```text
   "parentUid":"riptide"
   9
   ```

## Install from the deb or rpm package

The package puts the same files under **`/usr/share/riptide/grafana/dashboards/`** and replaces them on every package upgrade.

1. Check the files are there.

   ```bash
   ls /usr/share/riptide/grafana/dashboards/
   ```

   Expected output:

   ```text
   dashboards.yml
   riptide-behavioural-anomalies.json
   riptide-capacity-routing.json
   riptide-collection-health.json
   riptide-data-trust.json
   riptide-flow-forensics.json
   riptide-interface-traffic-analysis.json
   riptide-top-10.json
   riptide-traffic-composition.json
   riptide-traffic-paths.json
   ```

2. Point a provider at that directory on the Grafana host. The shipped `dashboards.yml` names Grafana's own provisioning directory as `path`, so write a provider of your own instead of copying it.

   ```bash
   sudo tee /etc/grafana/provisioning/dashboards/riptide.yml >/dev/null <<'EOF'
   apiVersion: 1
   providers:
     - name: riptide
       type: file
       folder: 'Flow Analytics'
       folderUid: riptide-flow-analytics
       updateIntervalSeconds: 30
       allowUiUpdates: true
       options:
         path: /usr/share/riptide/grafana/dashboards
         foldersFromFilesStructure: false
   EOF
   ```

   Grafana on another host: copy the directory over, or extract the release tarball there as described above.

3. Restart Grafana, then move the folder under **Riptide** and verify as in steps 4 and 5 of the tarball section.

## Install with the Grafana Helm chart

Use this for a Grafana deployed with the [grafana-community Helm chart](https://github.com/grafana-community/helm-charts).
Every release after v0.15.1 carries **`riptide-dashboards-helm-values.yaml`**, a values file that makes the chart download the nine dashboards of that release.
Everything below was verified with chart 13.2.5.

The Grafana pod needs outbound HTTPS to `raw.githubusercontent.com`.
An init container downloads each dashboard from there when the pod starts.

The commands assume a release called `grafana` in namespace `monitoring`.
For a release whose name does not contain `grafana`, the chart names the deployment, service and secret `<release>-grafana`.

1. Add the file to the `helm upgrade` you already run, after your own values file.
   Keep your own chart version; 13.2.5 is the one verified here.

   ```bash
   RELEASE=grafana
   NAMESPACE=monitoring
   helm upgrade "$RELEASE" oci://ghcr.io/grafana-community/helm-charts/grafana --version 13.2.5 \
     -n "$NAMESPACE" -f your-values.yaml \
     -f https://github.com/Riptide-Labs/riptide/releases/download/v%%VERSION%%/riptide-dashboards-helm-values.yaml
   kubectl -n "$NAMESPACE" rollout status deploy/grafana
   ```

   Expected output:

   ```text
   Pulled: ghcr.io/grafana-community/helm-charts/grafana:13.2.5
   Digest: sha256:0fcb82fdbf9409a24fb0f9b4c20875a2d0fa668d4f3ad71f1717845ba9bb99ca
   Release "grafana" has been upgraded. Happy Helming!
   NAME: grafana
   LAST DEPLOYED: Thu Sep 24 01:03:46 2026
   NAMESPACE: monitoring
   STATUS: deployed
   ...
   Waiting for deployment "grafana" rollout to finish: 1 old replicas are pending termination...
   deployment "grafana" successfully rolled out
   ```

   Pass the file together with your own values, never alone: a `helm upgrade` without your values file resets the release to the chart's defaults.
   Helm merges the two files, so your own dashboards and providers stay.
   The file sets its own curl options for each download, so the chart's default `-skf`, whose `-k` skips certificate checks, does not apply to them.

2. Move the folder under **Riptide** once.
   The move survives later upgrades, including the ones that replace the pod.

   ```bash
   kubectl -n "$NAMESPACE" port-forward svc/grafana 3000:80 >/dev/null &
   GRAFANA=http://localhost:3000
   AUTH="admin:$(kubectl -n "$NAMESPACE" get secret grafana -o jsonpath='{.data.admin-password}' | base64 -d)"
   curl -s -u "$AUTH" -H 'Content-Type: application/json' -X POST "$GRAFANA/api/folders" \
     -d '{"uid":"riptide","title":"Riptide"}' -o /dev/null -w '%{http_code}\n'
   curl -s -u "$AUTH" -H 'Content-Type: application/json' -X POST "$GRAFANA/api/folders/riptide-flow-analytics/move" \
     -d '{"parentUid":"riptide"}' -o /dev/null -w '%{http_code}\n'
   ```

   Expected output:

   ```text
   200
   200
   ```

   The first call answers `412` when a folder `Riptide` already exists; the move still applies.

3. Verify.

   ```bash
   curl -s -u "$AUTH" "$GRAFANA/api/folders/riptide-flow-analytics" | grep -o '"parentUid":"[^"]*"'
   curl -s -u "$AUTH" "$GRAFANA/api/search?type=dash-db&folderUIDs=riptide-flow-analytics" | grep -o '"uid":"riptide-' | wc -l
   curl -s -u "$AUTH" "$GRAFANA/api/dashboards/uid/riptide-top10" | grep -o '"title":"Dashboards v[^"]*"'
   ```

   Expected output, for the release that carries set 1.0.2:

   ```text
   "parentUid":"riptide"
   9
   "title":"Dashboards v1.0.2"
   ```

Riptide dashboards imported by hand before are adopted into **Flow Analytics** on the first pass, under their uids and URLs.
A folder that held only those dashboards is left empty; delete it in the UI.

### Check the file's signature before applying it

Download the file and its signature bundle, verify them, then pass the local file to `helm upgrade` instead of the URL.

```bash
V=%%VERSION%%
curl -fsSLO "https://github.com/Riptide-Labs/riptide/releases/download/v$V/riptide-dashboards-helm-values.yaml"
curl -fsSLO "https://github.com/Riptide-Labs/riptide/releases/download/v$V/riptide-dashboards-helm-values.yaml.sigstore.json"
cosign verify-blob riptide-dashboards-helm-values.yaml \
  --bundle riptide-dashboards-helm-values.yaml.sigstore.json \
  --certificate-identity-regexp '^https://github.com/Riptide-Labs/riptide/\.github/workflows/release\.yml@refs/tags/v.*$' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

Expected output:

```text
Verified OK
```

The file pins every download to the release tag, and release tags cannot be moved or recreated, so a verified file also fixes the dashboards it downloads.

## Install into a Grafana over its API

Use this for a Grafana that reads no provisioning files: a hosted instance, or one whose deployment you cannot change.
Every release after v0.15.2 carries **`riptide-dashboards-import.py`**, which imports the set over Grafana's HTTP API.
It creates **Riptide** with **Flow Analytics** inside it, imports every dashboard there under its own uid, and deletes nothing.
Everything below was verified on Grafana 13.2.2.

1. Create a service account with the **Editor** role and a token for it: *Administration* > *Users and access* > *Service accounts* > *Add service account*, then *Add service account token*.
   An Editor may create and move folders and import dashboards; a Viewer is refused.

2. Download the script and its signature bundle, and verify them.
   Download the dashboard tarball from the same release as described in [Install from the release tarball](#install-from-the-release-tarball).

   ```bash
   V=%%VERSION%%
   curl -fsSLO "https://github.com/Riptide-Labs/riptide/releases/download/v$V/riptide-dashboards-import.py"
   curl -fsSLO "https://github.com/Riptide-Labs/riptide/releases/download/v$V/riptide-dashboards-import.py.sigstore.json"
   cosign verify-blob riptide-dashboards-import.py \
     --bundle riptide-dashboards-import.py.sigstore.json \
     --certificate-identity-regexp '^https://github.com/Riptide-Labs/riptide/\.github/workflows/release\.yml@refs/tags/v.*$' \
     --certificate-oidc-issuer https://token.actions.githubusercontent.com
   ```

   Expected output:

   ```text
   Verified OK
   ```

3. Preview what would change.
   The token is read from **`GRAFANA_TOKEN`** and never from the command line; `read -rs` keeps it out of your shell history.

   ```bash
   GRAFANA=https://grafana.example.org
   read -rs GRAFANA_TOKEN && export GRAFANA_TOKEN
   python3 riptide-dashboards-import.py --grafana "$GRAFANA" --dry-run riptide-dashboards-1.0.2.tar.gz
   ```

   Expected output, on a Grafana that holds none of the dashboards yet:

   ```text
   riptide dashboard set 1.0.2, 9 dashboards -> https://grafana.example.org (dry run, nothing is written)
   folder Riptide: would create
   folder Flow Analytics: would create
   riptide-behavioural-anomalies: would create
   riptide-capacity-routing: would create
   riptide-collection-health: would create
   riptide-data-trust: would create
   riptide-flow-forensics: would create
   riptide-interface-traffic-analysis: would create
   riptide-top10: would create
   riptide-traffic-composition: would create
   riptide-traffic-paths: would create
   ```

4. Import.

   ```bash
   python3 riptide-dashboards-import.py --grafana "$GRAFANA" riptide-dashboards-1.0.2.tar.gz
   ```

   Expected output:

   ```text
   riptide dashboard set 1.0.2, 9 dashboards -> https://grafana.example.org
   folder Riptide: created
   folder Flow Analytics: created
   riptide-behavioural-anomalies: created
   riptide-capacity-routing: created
   riptide-collection-health: created
   riptide-data-trust: created
   riptide-flow-forensics: created
   riptide-interface-traffic-analysis: created
   riptide-top10: created
   riptide-traffic-composition: created
   riptide-traffic-paths: created
   ```

5. Verify.

   ```bash
   curl -s -H "Authorization: Bearer $GRAFANA_TOKEN" "$GRAFANA/api/folders/riptide-flow-analytics" | grep -o '"parentUid":"[^"]*"'
   curl -s -H "Authorization: Bearer $GRAFANA_TOKEN" "$GRAFANA/api/folders/riptide-flow-analytics/counts" \
     | python3 -c 'import sys, json; print(json.load(sys.stdin)["dashboards"])'
   ```

   Expected output:

   ```text
   "parentUid":"riptide"
   9
   ```

The script prints one line per dashboard and exits non-zero if any was not imported.

| Line | Meaning |
| --- | --- |
| **`created`** | The dashboard was not on this instance. |
| **`updated 1.0.0 -> 1.0.2`** | Replaced. The previous body stays in the dashboard's *Settings* > *Versions*, where a replaced UI edit can be restored. |
| **`unchanged (1.0.2)`** | Identical to what the instance already had; Grafana saved nothing. |
| **`(from folder 'Riptide Flow Analytics')`** | The dashboard was in another folder, for example after a hand import, and was moved into **Flow Analytics**. |
| **`refused: Cannot save provisioned dashboard`** | This instance also loads the dashboard from files. Use one install path per Grafana. |
| **`folder '…' (…) is now empty: …`** | A folder the dashboards were moved out of holds nothing else: no dashboards, folders, alert rules or library elements. The script does not delete it; an admin can. |
| **`folder '…' (…) still holds …`** | That folder holds something else. Leave it. |

| Symptom | Cause | Fix |
| --- | --- | --- |
| `error: the token may not do this (403): … Permissions needed: folders:create …` | The token's service account has the Viewer role. | Use a token of a service account with the Editor role. |
| `error: Grafana rejected the token (401 Invalid API key)` | The token is wrong, revoked or expired. | Create a new token. |
| `error: set GRAFANA_TOKEN to a Grafana service-account token (Editor role)` | `GRAFANA_TOKEN` is not exported in this shell. | Run the `read -rs GRAFANA_TOKEN && export GRAFANA_TOKEN` line again. |

To upgrade, run step 4 with the newer release's tarball.
The output of an upgrade from dashboards imported by hand into a folder of their own:

```text
riptide dashboard set 1.0.2, 9 dashboards -> https://grafana.example.org
folder Riptide: created
folder Flow Analytics: created
riptide-behavioural-anomalies: updated 1.0.0 -> 1.0.2 (from folder 'Riptide Flow Analytics')
...
riptide-traffic-paths: updated 1.0.0 -> 1.0.2 (from folder 'Riptide Flow Analytics')
folder 'Riptide Flow Analytics' (efsybbe4ozv28c) is now empty: no dashboards, folders, alert rules or library elements. This script deletes nothing.
```

## Upgrade an installed set

Replace the files; Grafana does the rest within one 30-second provisioning interval and needs no restart.

| Install path | Upgrade step |
| --- | --- |
| Compose stack | Update the checkout, then `docker compose up -d`. |
| Release tarball | Extract the new archive over the old files. If you changed `path` in `dashboards.yml`, add `--exclude dashboards/dashboards.yml` to the `tar` command so the shipped provider does not replace yours. |
| deb or rpm | Install the new package; it replaces the directory. |
| Helm chart | Run the `helm upgrade` from the Helm section with the newer release's URL. The pod restarts and downloads the new files. |
| Grafana API | Run the import script again with the newer release's tarball. |

What happens to what is already in Grafana, as observed on 13.0.2:

| Already in Grafana | After the files change |
| --- | --- |
| A riptide dashboard from an older set | Replaced under the same uid and URL; the dashboard's version counter moves by one. |
| A riptide dashboard edited in the UI | The edit is overwritten. Use *Save as* before upgrading to keep it. |
| A copy made with *Save as* | Untouched; it has its own uid. |
| A riptide dashboard imported by hand, in General or in any folder | Adopted on the first provisioning pass and moved into `Flow Analytics`. Grafana logs only `starting to provision dashboards` and `finished to provision dashboards`. |
| The `Flow Analytics` folder under `Riptide` | Stays there. |

Verify the version that Grafana now serves:

```bash
curl -s -u "$AUTH" "$GRAFANA/api/dashboards/uid/riptide-top10" | grep -o '"title":"Dashboards v[^"]*"'
```

Expected output:

```text
"title":"Dashboards v1.0.2"
```

The API reports `"provisioned":false` for these dashboards on Grafana 13.0.2 even though the file provider owns them, so do not use that field to tell a provisioned dashboard from a hand-imported one.

## Related

- [Run the Docker Compose stack](docker-compose.md) for the stack the compose path refers to.
- [Install the DEB or RPM package](linux-packages.md) for what else the package installs.
- [Riptide releases](https://github.com/Riptide-Labs/riptide/releases) for the tarball and its signature bundle.
