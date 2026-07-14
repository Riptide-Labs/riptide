---
sidebar_position: 3
title: Operations
---

# Operations notes

## Image tags

| Tag | Meaning | Use for |
|---|---|---|
| `:<version>` | immutable release | production (pin this) |
| `:latest` | newest release | quickstarts |
| `:rc` | floating, rebuilt on **every merge to main** | tracking development — at your own risk |

## Restarts and data

:::warning

Riptide issues `CREATE OR REPLACE TABLE` for its `flows` table at startup — **flow data
does not survive a Riptide restart** in the current design. Plan retention accordingly
(e.g. export/aggregate downstream) until schema migration lands.

:::

## Config hot-reload

Node and routing configuration can reload from `/etc/riptide/config.yaml` without a
restart — adding a device or changing a subnet applies within one poll. Opt in with:

```properties
riptide.config.reload-interval=30s   # absent or 0 = disabled (the default)
```

Semantics:

- **Content-hash polling, not inotify** — the path is re-resolved and the content
  hashed every cycle, so bind mounts, Kubernetes ConfigMap symlink swaps, and
  mtime-insensitive writers are all picked up reliably.
- **Layering is preserved** — environment-variable overrides keep their precedence
  over the file, exactly as at boot. A file created after startup slots in beneath
  the environment as well.
- **Bad config never wins** — candidates run the same validation as startup; a failing
  reload keeps the running configuration, logs a warning naming the problem, and
  raises `config.reload.failures` plus a `config.reload.stale` gauge (alert on it).
- **A missing or empty file skips the cycle** — deletion is indistinguishable from an
  atomic replacement in progress, so the running config is kept. Removing the file
  layer for real requires a restart.
- On a successful reload the SNMP interface cache and the SOPS decrypted-file cache
  refresh; exporter-pushed interface names (option records) are kept — they describe
  devices, not configuration. Reloads trigger on **config-file changes only**: after
  rotating a SOPS secrets file, touch or edit `config.yaml` so the decrypted cache
  drops and the next poll picks up the new secret.

Limitations: profile-activated YAML documents and nested `spring.config.import` inside
the reloaded file are boot-only; `env://` secret references cannot rotate in-process
(the environment is immutable per process) — those need a restart.

## Upgrading

Compose: `docker compose pull && docker compose up -d`. Plain JAR: replace the jar,
restart. Configuration is backward-compatible within a minor line; breaking configuration
moves are logged loudly at startup (e.g. the pre-0.1.0 `riptide.snmp.config.definitions`
tree logs an explicit error pointing at `riptide.nodes`).

## Ports

| Port | Protocol | What |
|---|---|---|
| `9999/udp` | NetFlow/IPFIX | default flow ingest (container `EXPOSE`; receivers are configurable) |
| `8123` | HTTP | ClickHouse (stack-internal unless you expose it) |
