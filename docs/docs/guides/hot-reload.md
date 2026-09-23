---
title: Enable configuration hot reload
sidebar_position: 7
description: Set riptide.config.reload-interval so credential sets, polling profiles and routing reload from config.yaml without a restart, and verify the reload from the log and the metrics.
---

# Enable configuration hot reload

With a reload interval set, credential sets, polling profiles and routing reload from **`/etc/riptide/config.yaml`** without a restart, and the [inventory file](../reference/agent-configuration.md) reloads on its own content changes.
Adding a device or carving a range out applies within one poll.

## Prerequisites

- The configuration riptide should watch is a file, imported through `spring.config.import` (the packages import `/etc/riptide/config.yaml`; the plain jar takes `--spring.config.import=file:<path>`).
- Environment-variable overrides keep their precedence over the file, at boot and on every reload.

## Steps

1. Set the interval in the file riptide watches, or on the command line.

   ```yaml
   riptide:
     config:
       reload-interval: 30s
   ```

   | Name | Type | Default | Description |
   | --- | --- | --- | --- |
   | **`riptide.config.reload-interval`** | duration | unset | Poll interval for the main configuration file and the inventory file. Absent or `0` disables reloading. |

2. Start the collector and confirm the watcher is on.

   ```bash
   java -jar riptide.jar --spring.config.import=file:/etc/riptide/config.yaml --riptide.config.reload-interval=5s
   ```

   Expected output:

   ```text
   INFO  org.riptide.config.ConfigFileReloader    : Config hot-reload enabled: watching /etc/riptide/config.yaml every PT5S
   ```

3. Edit the file and wait one interval.

   Expected output:

   ```text
   INFO  org.riptide.config.ConfigFileReloader    : Config reloaded from /etc/riptide/config.yaml: 1 credential set(s), 0 polling profile(s) serving
   ```

## Verify

```bash
curl -s http://localhost:8080/metrics | grep -E '^config_reload'
```

Expected output:

```text
config_reload_dead 0.0
config_reload_stale 0.0
config_reload_failures 0.0
config_reload_partial 0.0
config_reload_successes 2.0
```

`config_reload_successes` counts the boot commit and every committed edit since.
A failed edit raises `config_reload_failures` and `config_reload_stale`, and the running configuration is kept.
The gauges exist only while reloading is enabled, so alert on their absence if hot reload is mandatory in your deployment.

## What does and does not reload

| Change | Applied by |
| --- | --- |
| Credential sets, polling profiles, routing tables in `config.yaml` | the next poll that sees a changed content hash |
| Inventory file (agent ranges, exporter entries) | the next poll that sees a changed content hash |
| SNMP interface cache, SOPS decrypted-file cache | refreshed on a successful `config.yaml` reload; after rotating a SOPS secrets file, touch or edit `config.yaml` so the cache drops |
| Exporter-pushed interface names (option records) | kept across reloads; they describe devices, not configuration |
| Classification rules | their own schedule, **`riptide.classification.reload-interval`**; see [Write a classification rule](classification-rules.md) |
| Profile-activated YAML documents, nested `spring.config.import` inside the reloaded file | boot only |
| `env://` secret references | restart; the environment is immutable per process |
| Removing the file layer for real | restart |

## Related

- [How configuration reloads work](../architecture/reloading.md): content-hash polling, why bad config never wins, what a skipped cycle does to the gauges.
- [Metrics reference](../reference/metrics.md#configuration-and-inventory-reload): every `config.reload.*` and `inventory.reload.*` series.
- [Troubleshooting](../operations/troubleshooting.md): a stale gauge that will not clear, a dead schedule.

## Open questions

- The log lines above were captured from the 0.15.0 jar with `--spring.config.import=file:<path>`; the deb and rpm import `/etc/riptide/config.yaml` through the bundled `spring.config.import`, and that path was not exercised on a systemd host.
