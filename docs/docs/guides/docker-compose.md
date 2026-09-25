---
title: Docker Compose
description: Start riptide, ClickHouse and Grafana from the shipped compose stack, set its passwords, reach ClickHouse from another host, and pick an image variant.
---

# Run the Docker Compose stack

The stack starts riptide from the published image, ClickHouse pinned to the version the integration tests run against, and Grafana with the riptide dashboards provisioned.

## Prerequisites

- Docker with the Compose plugin.
- Ports free on the host: `9999/udp`, `3000/tcp`, and `8123/tcp` and `9000/tcp` on loopback.

## Steps

1. Get the stack files and set the two passwords in a `.env` file next to `compose.yml`. `CLICKHOUSE_PASSWORD` is required. Compose interpolates the values, so a literal `$` is written `$$`. The file is gitignored.

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
    Volume riptide_gf-data Created
    Volume riptide_clickhouse-data Created
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
   clickhouse   Up 30 seconds (healthy)
   grafana      Up 20 seconds (healthy)
   riptide      Up 20 seconds (healthy)
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

:::warning
Without `GF_SECURITY_ADMIN_PASSWORD`, Grafana's `admin` login is `admin`.
Grafana's port 3000 is published on every interface, so on a host with a routable address that login is reachable from the network.
:::

Without `CLICKHOUSE_PASSWORD`, every `docker compose` command in the directory stops before it starts anything, `ps` and `down` included:

```text
error while interpolating services.riptide.environment.CLICKHOUSE_PASSWORD: required variable CLICKHOUSE_PASSWORD is missing a value: set CLICKHOUSE_PASSWORD in a .env file next to compose.yml, see https://riptide.space/docs/guides/docker-compose
```

Write the `.env` file from step 1, or export the variable in the same shell.

## What the stack runs

| Service | Image | Published ports | Notes |
| --- | --- | --- | --- |
| **`riptide`** | `ghcr.io/riptide-labs/riptide:latest` | `9999/udp` | One `multi` [receiver](../reference/receivers.md) parses every protocol on that port. Starts only after ClickHouse reports healthy. Health is `/readyz` on the container's port 8080, which is not published. Logs at `WARN`. |
| **`clickhouse`** | `clickhouse/clickhouse-server:26.7`, pinned by digest | `127.0.0.1:8123`, `127.0.0.1:9000` | Database `riptide`, user `default`. 26.7 is the version the integration suite runs against, see [server versions](../reference/clickhouse.md#server-versions). |
| **`grafana`** | `grafana/grafana-oss:13.0.2`, pinned by digest | `3000` | ClickHouse datasource and nine dashboards provisioned; plugins `grafana-clickhouse-datasource` and `netsage-sankey-panel`. |

Dependabot moves the two digest pins.
The riptide image follows `:latest`, so `docker compose pull` moves the collector forward on its own.

| Variable | Read by | Default | When a change takes effect |
| --- | --- | --- | --- |
| **`CLICKHOUSE_PASSWORD`** | ClickHouse, riptide (as `env://CLICKHOUSE_PASSWORD`), Grafana's datasource | none, required | On `docker compose up -d`, all three follow. Anything else that connected with the old password needs the new one. |
| **`GF_SECURITY_ADMIN_PASSWORD`** | Grafana, only when it initialises its database | `admin` | First start only. To change it later, remove the `gf-data` volume, or change it in Grafana. |

Volumes `clickhouse-data` and `gf-data` hold the flows and Grafana's state.
`docker compose down` keeps them; `docker compose down -v` deletes them.

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

Grafana provisions the nine riptide dashboards from `deployment/clickhouse/container-fs/grafana/provisioning/dashboards/` into the folder **Flow Analytics** under **Riptide**; the `grafana-folders` one-shot service nests the folder after Grafana is healthy.
What each dashboard answers, how the same set installs into a Grafana you run yourself, and what an upgrade does to UI edits is on the [Grafana dashboards](grafana-dashboards.md) page.

The set carries its own version, shown as a `Dashboards vX.Y.Z` link in every dashboard's top bar, independent of the riptide version.
A contributor bumps it with `make dashboards-version DASHBOARDS_VERSION=x.y.z`; CI refuses a pull request that changes a dashboard without moving the number. The major part moves for a renamed uid or variable that an external link depends on, the minor part for a new panel, variable or dashboard, the patch part for text, query and layout fixes.

## Variants

| Command | Runs |
| --- | --- |
| `docker compose up -d` | `ghcr.io/riptide-labs/riptide:latest`, the last release |
| `docker compose -f compose.yml -f compose.override.rc.yml up -d` | `ghcr.io/riptide-labs/riptide:rc`, rebuilt on every merge to main; not for production |
| `docker compose -f compose.yml -f compose.override.dev.yml up -d` | `riptide:local`, built by `make oci` |

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
