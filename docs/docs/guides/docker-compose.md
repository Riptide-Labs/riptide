---
title: Docker Compose
description: Start riptide, ClickHouse, Grafana and Prometheus from the shipped compose stack, set its passwords, reach ClickHouse from another host, and pick an image variant.
---

# Run the Docker Compose stack

The stack starts riptide from the published image, ClickHouse pinned to the version the integration tests run against, Grafana with the riptide dashboards provisioned, Prometheus scraping riptide's own metrics and evaluating its alert rules, and Pyroscope receiving riptide's continuous profiles.
Prometheus and Pyroscope serve riptide's self-monitoring only; [one command](#run-without-self-monitoring) starts the stack without them.

## Prerequisites

- Docker with the Compose plugin, version 2.24.4 or later.
- Ports free on the host: `9999/udp`, `3000/tcp`, and `8123/tcp`, `9000/tcp`, `9090/tcp` and `4040/tcp` on loopback.

## Steps

1. Get the stack files and set the two passwords in a `.env` file next to `compose.yml`. Compose interpolates the values, so a literal `$` is written `$$`. The file is gitignored.

   ```bash
   git clone https://github.com/Riptide-Labs/riptide.git
   cd riptide/deployment/riptide
   cat > .env <<'EOF'
   CLICKHOUSE_PASSWORD=change-me-before-anyone-else-can-reach-this-host
   GF_SECURITY_ADMIN_PASSWORD=change-me-too
   EOF
   ```

2. Start it:

   ```bash
   docker compose up -d
   ```

   Expected output, after the image pull:

   ```text
    Network riptide_default Created
    Volume riptide_clickhouse-data Created
    Volume riptide_gf-data Created
    Volume riptide_prometheus-data Created
    Volume riptide_pyroscope-data Created
    Container riptide-prometheus-1 Started
    Container riptide-pyroscope-1 Started
    Container riptide-clickhouse-1 Healthy
    Container riptide-riptide-1 Started
    Container riptide-grafana-1 Healthy
    Container riptide-grafana-folders-1 Started
   ```

3. Verify:

   ```bash
   docker compose ps --format 'table {{.Service}}\t{{.Status}}'
   ```

   Expected output, once Grafana's health check has passed:

   ```text
   SERVICE      STATUS
   clickhouse   Up 42 seconds (healthy)
   grafana      Up 35 seconds (healthy)
   prometheus   Up 42 seconds (healthy)
   pyroscope    Up 42 seconds
   riptide      Up 35 seconds (healthy)
   ```

4. Point a NetFlow v5, NetFlow v9, IPFIX or sFlow exporter at UDP port `9999` of the host, then count rows:

   ```bash
   docker compose exec clickhouse sh -c 'clickhouse-client --password "$CLICKHOUSE_PASSWORD" -q "SELECT count() FROM riptide.flows"'
   ```

   The password comes from the container's environment, so nothing needs exporting on the host. Expected output, before the first flow arrives:

   ```text
   0
   ```

   Grafana is at `http://localhost:3000`, user `admin`. Its Explore view runs ad-hoc queries against the provisioned ClickHouse datasource.
   Prometheus is at `http://127.0.0.1:9090`; its **Alerts** page lists riptide's alert rules.
   Pyroscope is at `http://127.0.0.1:4040`; riptide's profiles arrive there about a minute and a half after start, and **Riptide - Profiling** reads them.

:::warning
Without a `.env` file the stack starts with ClickHouse's `default` user at password `riptide` and Grafana's `admin` at `admin`.
Grafana's port 3000 is published on every interface, so on a host with a routable address that login is reachable from the network.
The ClickHouse `default` user holds `access_management`, so change its password before publishing ports 8123 or 9000 beyond loopback.
:::

## What the stack runs

Always:

| Service | Image | Published ports | Notes |
| --- | --- | --- | --- |
| **`riptide`** | `ghcr.io/riptide-labs/riptide:latest` | `9999/udp` | One `multi` [receiver](../reference/receivers.md) parses every protocol on that port. Starts only after ClickHouse reports healthy. Health is `/readyz` on the container's port 8080, which is not published. Logs at `WARN`. |
| **`clickhouse`** | `clickhouse/clickhouse-server:26.7`, pinned by digest | `127.0.0.1:8123`, `127.0.0.1:9000` | Database `riptide`, user `default`. 26.7 is the version the integration suite runs against, see [server versions](../reference/clickhouse.md#server-versions). |
| **`grafana`** | `grafana/grafana-oss:13.0.2`, pinned by digest | `3000` | ClickHouse datasource, the Prometheus and Pyroscope datasources with self-monitoring, and the riptide dashboards provisioned; plugins `grafana-clickhouse-datasource` and `netsage-sankey-panel`. |

Self-monitoring, from **`deployment/clickhouse/compose.self-monitoring.yml`**, unless [dropped](#run-without-self-monitoring):

| Service | Image | Published ports | Notes |
| --- | --- | --- | --- |
| **`prometheus`** | `prom/prometheus:v3.15.0`, pinned by digest | `127.0.0.1:9090` | Scrapes riptide's `/metrics` every 15 s as `job="riptide"` and evaluates riptide's alert rules. Targets come from `container-fs/prometheus/targets/`; no Alertmanager ships. |
| **`pyroscope`** | `grafana/pyroscope:2.3.1`, pinned by digest | `127.0.0.1:4040` | Receives riptide's profiles; the compose riptide runs with [continuous profiling](../operations/profiling.md) on and the label `collector=riptide`. No health check: the image has no shell, and it reports ready about a minute after start. |

Dependabot moves the four digest pins.
The riptide image follows `:latest`, so `docker compose pull` moves the collector forward on its own.

| Variable | Read by | Default | When a change takes effect |
| --- | --- | --- | --- |
| **`CLICKHOUSE_PASSWORD`** | ClickHouse, riptide (as `env://CLICKHOUSE_PASSWORD`), Grafana's datasource | `riptide` | On `docker compose up -d`, all three follow. Anything else that connected with the old password needs the new one. |
| **`GF_SECURITY_ADMIN_PASSWORD`** | Grafana, only when it initialises its database | `admin` | First start only. To change it later, remove the `gf-data` volume, or change it in Grafana. |
| **`PROMETHEUS_TARGETS`** | Prometheus, the directory of scrape targets | `./container-fs/prometheus/targets`, the `riptide` service | On `docker compose up -d`. Set `./container-fs/prometheus/targets-host` for a riptide running on the host instead. |

Volumes `clickhouse-data`, `gf-data`, `prometheus-data` and `pyroscope-data` hold the flows, Grafana's state, riptide's own metrics and its profiles.
`docker compose down` keeps them; `docker compose down -v` deletes them.
Run both without the override file: with it, Compose leaves Prometheus and Pyroscope running and keeps their volumes.

## Configure riptide further

Riptide reads its settings from the `environment` block of `compose.yml`, in the `RIPTIDE_*` form described on the [Plain JAR](plain-jar.md#environment-variables) page, or from a config file you mount.
Agent ranges and, without [dynamic discovery](../reference/discovery.md), enrichment entries live in the [inventory file](../reference/agent-configuration.md), which has to be mounted into the container and named by `RIPTIDE_INVENTORY_FILE`.

Put local changes in **`compose.override.yml`**.
Compose loads it automatically and it is gitignored.

## Reach ClickHouse from another host

ClickHouse listens on loopback only because riptide and Grafana reach it over the compose network.
To publish it, set `CLICKHOUSE_PASSWORD` first, then replace the ports list in `compose.override.yml`.
Compose merges `ports` by appending, so the override has to replace the list:

```yaml
services:
  clickhouse:
    ports: !override
      - "8123:8123/tcp"
      - "9000:9000/tcp"
```

Do not restrict the `default` user by source address in `users.xml` instead.
Riptide and Grafana connect from a compose bridge address that varies by network, and a loopback or fixed-CIDR rule breaks them.

## Dashboards

Grafana provisions the riptide dashboards from `deployment/clickhouse/container-fs/grafana/provisioning/dashboards/` into the folder **Flow Analytics** under **Riptide**; the `grafana-folders` one-shot service nests the folder after Grafana is healthy.
What each dashboard answers, how the same set installs into a Grafana you run yourself, and what an upgrade does to UI edits is on the [Grafana dashboards](grafana-dashboards.md) page.

The set carries its own version, shown as a `Dashboards vX.Y.Z` link in every dashboard's top bar, independent of the riptide version.
A contributor bumps it with `make dashboards-version DASHBOARDS_VERSION=x.y.z`; CI refuses a pull request that changes a dashboard without moving the number. The major part moves for a renamed uid or variable that an external link depends on, the minor part for a new panel, variable or dashboard, the patch part for text, query and layout fixes.

## Variants

| Command | Runs |
| --- | --- |
| `docker compose up -d` | `ghcr.io/riptide-labs/riptide:latest`, the last release |
| `docker compose -f compose.yml -f compose.override.rc.yml up -d` | `ghcr.io/riptide-labs/riptide:rc`, rebuilt on every merge to main; not for production |
| `docker compose -f compose.yml -f compose.override.dev.yml up -d` | `riptide:local`, built by `make oci` |
| `docker compose -f compose.yml -f compose.override.no-self-monitoring.yml up -d` | The last release, without Prometheus and Pyroscope, see [below](#run-without-self-monitoring) |

Combine an image variant with the self-monitoring override by naming all three files, the override last:

```bash
docker compose -f compose.yml -f compose.override.dev.yml -f compose.override.no-self-monitoring.yml up -d
```

## Run without self-monitoring

1. Stop a running stack first, without the override file. Compose leaves a running Prometheus and Pyroscope alone when the override names them, so `up` with it would not stop them. The volumes stay.

   ```bash
   docker compose down
   ```

2. Start the stack with **`compose.override.no-self-monitoring.yml`**:

   ```bash
   docker compose -f compose.yml -f compose.override.no-self-monitoring.yml up -d
   ```

   Expected output, on the volumes step 1 kept:

   ```text
    Network riptide_default Created
    Container riptide-clickhouse-1 Healthy
    Container riptide-riptide-1 Started
    Container riptide-grafana-1 Healthy
    Container riptide-grafana-folders-1 Started
   ```

3. Verify:

   ```bash
   docker compose -f compose.yml -f compose.override.no-self-monitoring.yml ps --format 'table {{.Service}}\t{{.Status}}'
   ```

   Expected output, once Grafana's health check has passed:

   ```text
   SERVICE      STATUS
   clickhouse   Up 47 seconds (healthy)
   grafana      Up 42 seconds (healthy)
   riptide      Up 42 seconds (healthy)
   ```

Riptide runs with continuous profiling off.
Grafana holds only the ClickHouse datasource: the override deletes the Prometheus and Pyroscope datasources that an earlier run provisioned.
It deletes by name, on every start, so a datasource you add yourself named `Prometheus` or `Pyroscope` in the main organisation goes too.
Give your own a different name.
**Riptide - Health**, **Riptide - Pipeline Diagnostics** and **Riptide - Profiling** stay in Flow Analytics and show no data.
The `prometheus-data` and `pyroscope-data` volumes stay until a `docker compose down -v` without the override file.

To switch self-monitoring back on, stop the stack with the same override and start it without:

```bash
docker compose -f compose.yml -f compose.override.no-self-monitoring.yml down
docker compose up -d
```

## Upgrade from a stack that included ch-ui

The stack used to publish a ClickHouse web UI on `5521`; it was removed in [#671](https://github.com/Riptide-Labs/riptide/issues/671) and Grafana's Explore view replaces it.
Compose does not remove a service you deleted from the file: it warns about an orphan and leaves the old container running. Clear it once:

```bash
docker compose up -d --remove-orphans
```

Expected output, on a stack that still had it:

```text
 Container riptide-ch-ui-1 Removed
```

## Open questions

- The `--remove-orphans` output line was not captured from a stack that still ran ch-ui; it follows Compose's usual form `(unverified)`.
