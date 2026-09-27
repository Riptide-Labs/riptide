---
title: Monitor riptide with Prometheus
description: Scrape riptide's /metrics, load its alert rules, send the alerts to an Alertmanager and add the two health dashboards, on your own Prometheus or with the compose stack.
---

# Monitor riptide with Prometheus

Scrape riptide's **`/metrics`** as `job="riptide"`, load **`riptide-alerts.yml`**, and riptide pages you on flow loss and warns you before a stage saturates.
What each alert means and what to do about it is in [Respond to riptide alerts](../operations/monitoring.md).

The compose stack does all of this already: its Prometheus scrapes the riptide service and loads the rules.
For a riptide running on the host beside it, start the stack with **`PROMETHEUS_TARGETS=./container-fs/prometheus/targets-host`**, as the [compose guide](docker-compose.md) shows.
The steps below are for your own Prometheus.

## Prerequisites

- Prometheus 3.x. Everything on this page was verified on Prometheus 3.15.0; 2.x is (unverified).
- Network access from Prometheus to riptide's management port, **`8080`** by default (**`riptide.management.port`**).
- `jq` for the verify step.

## Steps

1. Add a scrape job named `riptide` to **`prometheus.yml`**.
   The rules and dashboards select `job="riptide"`, so keep that name.
   Give each collector a **`collector`** label: the dashboards show it instead of the scrape address.

   ```yaml
   scrape_configs:
     - job_name: riptide
       static_configs:
         - targets:
             - riptide-01.example.net:8080
           labels:
             collector: collector-1
   ```

2. Download **`riptide-alerts.yml`** for the riptide version you run into the directory of **`prometheus.yml`**, and load it:

   Set **`V`** to the riptide version you run, without the leading `v`:

   ```bash
   V=<version>
   curl -fsSLO "https://raw.githubusercontent.com/Riptide-Labs/riptide/v$V/deployment/clickhouse/container-fs/prometheus/riptide-alerts.yml"
   ```

   ```yaml
   rule_files:
     - riptide-alerts.yml
   ```

   The file holds 9 alerts and 5 recording rules. The recording rules lift the listener or parser name out of riptide's metric names into a `component` label; the alerts and both dashboards read them.

3. Send the alerts to your Alertmanager. No Alertmanager ships with riptide.

   ```yaml
   alerting:
     alertmanagers:
       - static_configs:
           - targets:
               - alertmanager.example.net:9093
   ```

   Alerts carry `severity: critical` (page) or `severity: warning`, and a `runbook_url` annotation; route on the label.

4. Check the configuration:

   ```bash
   promtool check config prometheus.yml
   ```

   Expected output:

   ```text
   Checking prometheus.yml
     SUCCESS: 1 rule files found
    SUCCESS: prometheus.yml is valid prometheus config file syntax

   Checking riptide-alerts.yml
     SUCCESS: 14 rules found
   ```

5. Reload Prometheus: restart it, send it `SIGHUP`, or `curl -X POST http://<prometheus>:9090/-/reload` when it runs with `--web.enable-lifecycle`.

6. Add the **Riptide - Health** and **Riptide - Stage detail** dashboards with the rest of the set, as [Grafana dashboards](grafana-dashboards.md) describes, and point their **Prometheus** variable at this Prometheus.

## Verify

After one scrape interval:

```bash
PROM=http://127.0.0.1:9090
curl -s "$PROM/api/v1/targets?state=active" | jq -r '.data.activeTargets[] | select(.labels.job == "riptide") | "\(.labels.job) \(.labels.collector) \(.health)"'
curl -s "$PROM/api/v1/rules?type=alert" | jq '[.data.groups[] | select(.name == "riptide-alerts") | .rules[]] | length'
```

Expected output:

```text
riptide collector-1 up
9
```

## Open questions

- Step 2 downloads the rules from a release tag. No release contains the file yet, so the URL is (unverified) until the first release that ships it.
- Prometheus 2.x is (unverified): the rules use no 3.x-only feature, but nothing on this page ran against 2.x.
