---
sidebar_position: 4.5
title: Grafana dashboards
description: Install the riptide dashboard set into Grafana from the compose stack, the deb or rpm package or the release tarball, keep it in the Riptide / Flow Analytics folder, and upgrade it in place.
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
- For the tarball and package paths: shell access to the Grafana host and an admin login for the one-time folder move.

## Install with the compose stack

Nothing to install. The stack bind-mounts the set from the checkout and a one-shot service nests the folder after Grafana is healthy.

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

## Upgrade an installed set

Replace the files; Grafana does the rest within one 30-second provisioning interval and needs no restart.

| Install path | Upgrade step |
| --- | --- |
| Compose stack | Update the checkout, then `docker compose up -d`. |
| Release tarball | Extract the new archive over the old files. If you changed `path` in `dashboards.yml`, add `--exclude dashboards/dashboards.yml` to the `tar` command so the shipped provider does not replace yours. |
| deb or rpm | Install the new package; it replaces the directory. |

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
