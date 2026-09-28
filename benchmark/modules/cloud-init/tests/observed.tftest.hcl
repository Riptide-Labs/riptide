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
