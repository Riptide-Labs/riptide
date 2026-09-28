# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Renders the roles of a lab with an observability service
# (run.fixture.observed). Kept apart from render.tftest.hcl because two runs of
# one module in a file share one entry in the run map.

run "fixture" {
  module {
    source = "../declaration/tests/fixture"
  }
}

run "declaration" {
  module {
    source = "../declaration"
  }
  variables {
    raw = run.fixture.observed
  }
}

variables {
  experiment          = "flow-capacity"
  ssh_keys            = ["ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITestKeyOnly test"]
  clickhouse_password = "test-password"
  exporters_cidr      = "172.27.0.0/16"
  clickhouse_files    = { config_xml = "<clickhouse/>", users_xml = "<clickhouse/>" }
  images = {
    clickhouse      = "clickhouse/clickhouse-server:26.7@sha256:ch"
    victoriametrics = "victoriametrics/victoria-metrics:v1@sha256:vm"
    nl6             = "ghcr.io/labmonkeys-space/nl6:v0.32.0@sha256:nl6"
    prometheus      = "docker.io/prom/prometheus:v3.15.0@sha256:prom"
    pyroscope       = "docker.io/grafana/pyroscope:2.3.1@sha256:pyro"
    grafana         = "docker.io/grafana/grafana:13.2.2-distroless-slim@sha256:graf"
  }
}

run "node_exporter_listens_only_on_observe" {
  command = plan
  variables {
    service = run.declaration.services.loadgen
  }
  assert {
    condition     = strcontains(try(nonsensitive(output.files["/etc/systemd/system/prometheus-node-exporter.service.d/10-bench-listen.conf"]), ""), "ExecStart=\nExecStart=/usr/bin/prometheus-node-exporter --web.listen-address=172.26.0.11:9100 $ARGS\n")
    error_message = "node exporter drop-in: ${try(nonsensitive(output.files["/etc/systemd/system/prometheus-node-exporter.service.d/10-bench-listen.conf"]), "missing")}"
  }
  # Bound to one address, it fails at boot if it starts before the address exists.
  assert {
    condition     = startswith(try(nonsensitive(output.files["/etc/systemd/system/prometheus-node-exporter.service.d/10-bench-listen.conf"]), ""), "[Unit]\nAfter=network-online.target\nWants=network-online.target\n\n[Service]\n")
    error_message = "node exporter drop-in does not wait for the network: ${try(nonsensitive(output.files["/etc/systemd/system/prometheus-node-exporter.service.d/10-bench-listen.conf"]), "missing")}"
  }
}

run "nl6_pushes_profiles_to_pyroscope" {
  command = plan
  variables {
    service       = run.declaration.services.loadgen
    pyroscope_url = "http://172.26.0.13:4040"
  }
  assert {
    condition     = strcontains(output.units.nl6, " -profiling-pyroscope=http://172.26.0.13:4040")
    error_message = "nl6 unit: ${output.units.nl6}"
  }
}

run "clickhouse_exposes_prometheus_metrics" {
  command = plan
  variables {
    service = run.declaration.services.clickhouse
  }
  assert {
    condition     = strcontains(try(nonsensitive(output.files["/etc/bench/clickhouse/prometheus.xml"]), ""), "<port>9363</port>") && strcontains(output.units.clickhouse, "-v /etc/bench/clickhouse/prometheus.xml:/etc/clickhouse-server/config.d/prometheus.xml:ro")
    error_message = "clickhouse prometheus.xml missing or not mounted"
  }
}

run "observability_runs_three_pinned_containers" {
  command = plan
  variables {
    service = run.declaration.services.observe
  }
  assert {
    condition     = jsonencode(sort(keys(output.units))) == jsonencode(["grafana", "prometheus", "pyroscope"])
    error_message = "units: ${jsonencode(keys(output.units))}"
  }
  assert {
    condition     = strcontains(output.units.prometheus, "--storage.tsdb.retention.size=50GB") && strcontains(output.units.pyroscope, "-retention-period=720h")
    error_message = "retention: ${try(output.units.prometheus, "")} / ${try(output.units.pyroscope, "")}"
  }
  assert {
    condition     = strcontains(output.units.grafana, "docker.io/grafana/grafana:13.2.2-distroless-slim@sha256:graf") && strcontains(output.units.prometheus, "--network host")
    error_message = "grafana: ${try(output.units.grafana, "")}"
  }
}

run "prometheus_scrapes_every_10_seconds_with_lab_labels" {
  command = plan
  variables {
    service = run.declaration.services.observe
    prometheus_jobs = {
      node = [{ target = "172.26.0.10:9100", labels = { experiment = "flow-capacity", service = "clickhouse", role = "clickhouse", host = "pve-1" } }]
    }
    alert_rules = "groups: []\n"
  }
  assert {
    condition     = try(yamldecode(nonsensitive(output.files["/etc/bench/prometheus/prometheus.yml"])).global.scrape_interval, "") == "10s"
    error_message = "prometheus.yml: ${try(nonsensitive(output.files["/etc/bench/prometheus/prometheus.yml"]), "missing")}"
  }
  assert {
    condition     = try(jsonencode(yamldecode(nonsensitive(output.files["/etc/bench/prometheus/prometheus.yml"])).scrape_configs[0].static_configs[0]), "") == jsonencode({ labels = { experiment = "flow-capacity", host = "pve-1", role = "clickhouse", service = "clickhouse" }, targets = ["172.26.0.10:9100"] })
    error_message = "node job: ${try(nonsensitive(output.files["/etc/bench/prometheus/prometheus.yml"]), "missing")}"
  }
  assert {
    condition     = contains(keys(nonsensitive(output.files)), "/etc/bench/prometheus/riptide-alerts.yml") && try(yamldecode(nonsensitive(output.files["/etc/bench/prometheus/prometheus.yml"])).rule_files[0], "") == "/etc/prometheus/riptide-alerts.yml"
    error_message = "alert rules not wired"
  }
}

run "grafana_provisions_two_datasources_and_the_dashboards" {
  command = plan
  variables {
    service                = run.declaration.services.observe
    grafana_admin_password = "test-admin"
    grafana_dashboards     = { "riptide-health.json" = "{\"title\":\"h\"}" }
  }
  assert {
    condition     = try(jsonencode(sort([for d in yamldecode(nonsensitive(output.files["/etc/bench/grafana/provisioning/datasources/lab.yml"])).datasources : d.uid])), "") == jsonencode(["riptide-prometheus", "riptide-pyroscope"])
    error_message = "datasources: ${try(nonsensitive(output.files["/etc/bench/grafana/provisioning/datasources/lab.yml"]), "missing")}"
  }
  assert {
    condition     = contains(keys(nonsensitive(output.files)), "/etc/bench/grafana/dashboards/riptide-health.json") && try(nonsensitive(output.files["/etc/bench/grafana/admin-password"]), "") == "test-admin"
    error_message = "dashboards or password file missing"
  }
  assert {
    condition     = strcontains(try(output.units.grafana, ""), "GF_AUTH_ANONYMOUS_ENABLED=false")
    error_message = "grafana: ${try(output.units.grafana, "")}"
  }
  # The distroless image starts the grafana binary without /run.sh, so no
  # GF_*__FILE variable is read, and grafana.db on the kept disk keeps the
  # password it was created with. Every start resets it from the file.
  assert {
    condition     = strcontains(try(output.units.grafana, ""), "ExecStartPre=/bin/sh -c '/usr/bin/docker run --rm -i -v /var/lib/bench/grafana:/var/lib/grafana --entrypoint grafana docker.io/grafana/grafana:13.2.2-distroless-slim@sha256:graf cli admin reset-admin-password --password-from-stdin < /etc/bench/grafana/admin-password'") && !strcontains(try(output.units.grafana, ""), "__FILE")
    error_message = "grafana admin password reset: ${try(output.units.grafana, "")}"
  }
  assert {
    condition     = try(one([for f in yamldecode(trimprefix(nonsensitive(output.user_data), "#cloud-config\n")).write_files : f.permissions if f.path == "/etc/bench/grafana/admin-password"]), "") == "0600"
    error_message = "the Grafana admin password file must be readable by root only"
  }
  # Docker's default 10 s grace before SIGKILL can cut Pyroscope's flush short.
  assert {
    condition     = alltrue([for u in ["grafana", "prometheus", "pyroscope"] : strcontains(try(output.units[u], ""), "ExecStop=/usr/bin/docker stop -t 60 ${u}\n") && strcontains(try(output.units[u], ""), "TimeoutStopSec=90\n")])
    error_message = "stop grace: ${try(output.units.pyroscope, "")}"
  }
}

run "an_existing_data_disk_is_never_reformatted" {
  command = plan
  variables {
    service = run.declaration.services.observe
  }
  assert {
    condition     = try(yamldecode(trimprefix(nonsensitive(output.user_data), "#cloud-config\n")).fs_setup[0].overwrite, true) == false
    error_message = "fs_setup: ${jsonencode(try(yamldecode(trimprefix(nonsensitive(output.user_data), "#cloud-config\n")).fs_setup, null))}"
  }
}

run "large_files_travel_compressed" {
  command = plan
  variables {
    service            = run.declaration.services.observe
    grafana_dashboards = { "big.json" = join("", [for i in range(600) : "{\"panel\":${i}},"]) }
  }
  assert {
    condition     = one([for f in yamldecode(trimprefix(nonsensitive(output.user_data), "#cloud-config\n")).write_files : try(f.encoding, "none") if f.path == "/etc/bench/grafana/dashboards/big.json"]) == "gz+b64"
    error_message = "big dashboard not compressed"
  }
}
