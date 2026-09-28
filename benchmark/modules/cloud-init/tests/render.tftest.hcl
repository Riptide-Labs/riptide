# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Renders each role from the declaration module's output for the shared
# fixture, so the inputs are the derived values the lab passes, not
# hand-written stand-ins.

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
    raw = run.fixture.raw
  }
}

variables {
  experiment          = "flow-capacity"
  ssh_keys            = ["ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITestKeyOnly test"]
  clickhouse_password = "test-password"
  exporters_cidr      = "172.26.0.0/16"
  clickhouse_files    = { config_xml = "<clickhouse/>", users_xml = "<clickhouse/>" }
  images = {
    clickhouse      = "clickhouse/clickhouse-server:26.7@sha256:ch"
    victoriametrics = "victoriametrics/victoria-metrics:v1@sha256:vm"
    vmagent         = "victoriametrics/vmagent:v1@sha256:va"
    nl6             = "ghcr.io/labmonkeys-space/nl6:v0.32.0@sha256:nl6"
  }
}

run "sut_network_config_routes_exporters_via_loadgen" {
  command = plan
  variables {
    service      = run.declaration.services.sut
    expected_mac = run.declaration.services.sut.macs.store
  }
  assert {
    condition     = try(yamldecode(output.network_config).ethernets.ingest.routes[0].to, null) == "172.26.0.0/16" && try(yamldecode(output.network_config).ethernets.ingest.routes[0].via, null) == "172.24.0.11"
    error_message = "sut network-config: ${output.network_config}"
  }
  assert {
    condition     = yamldecode(output.network_config).ethernets.mgmt.routes[0].to == "default" && yamldecode(output.network_config).ethernets.mgmt.addresses[0] == "192.0.2.203/24"
    error_message = "sut mgmt: ${jsonencode(yamldecode(output.network_config).ethernets.mgmt)}"
  }
  assert {
    condition     = try(yamldecode(output.network_config).ethernets.store.match.macaddress, null) == var.expected_mac && !contains(keys(yamldecode(output.network_config).ethernets.store), "routes")
    error_message = "sut store: ${jsonencode(yamldecode(output.network_config).ethernets.store)}"
  }
}

run "every_role_moves_docker_pools_out_of_the_lab_range" {
  command = plan
  variables {
    service = run.declaration.services.sut
  }
  assert {
    condition     = jsondecode(nonsensitive(output.files["/etc/docker/daemon.json"]))["default-address-pools"][0].base == "172.28.0.0/14"
    error_message = "daemon.json: ${nonsensitive(output.files["/etc/docker/daemon.json"])}"
  }
}

run "sut_persists_receive_buffers_and_installs_java" {
  command = plan
  variables {
    service = run.declaration.services.sut
  }
  assert {
    condition     = strcontains(nonsensitive(output.files["/etc/sysctl.d/60-bench-riptide.conf"]), "net.core.rmem_max = 33554432")
    error_message = "sysctl: ${nonsensitive(output.files["/etc/sysctl.d/60-bench-riptide.conf"])}"
  }
  assert {
    condition     = contains(yamldecode(trimprefix(nonsensitive(output.user_data), "#cloud-config\n")).packages, "openjdk-25-jre-headless")
    error_message = "packages lack Java 25"
  }
}

run "clickhouse_runs_its_pinned_image_on_the_data_disk" {
  command = plan
  variables {
    service = run.declaration.services.clickhouse
  }
  assert {
    condition     = strcontains(output.units.clickhouse, "--network host") && strcontains(output.units.clickhouse, "clickhouse/clickhouse-server:26.7@sha256:ch")
    error_message = "clickhouse unit: ${output.units.clickhouse}"
  }
  assert {
    condition     = yamldecode(trimprefix(nonsensitive(output.user_data), "#cloud-config\n")).mounts[0][1] == "/var/lib/bench"
    error_message = "clickhouse data disk is not mounted at /var/lib/bench"
  }
  assert {
    condition     = strcontains(nonsensitive(output.files["/etc/bench/clickhouse.env"]), "CLICKHOUSE_PASSWORD=test-password\n")
    error_message = "clickhouse.env lacks the password"
  }
}

run "nl6_forwards_and_routes_its_exporters" {
  command = plan
  variables {
    service = run.declaration.services.loadgen
  }
  assert {
    condition     = strcontains(output.units.nl6, "--privileged --device /dev/net/tun") && strcontains(output.units.nl6, "ExecStartPost=/usr/local/sbin/bench-exporters-route")
    error_message = "nl6 unit: ${output.units.nl6}"
  }
  assert {
    condition     = strcontains(nonsensitive(output.files["/usr/local/sbin/bench-exporters-route"]), "ip route replace 172.26.0.0/16 via 10.254.0.2 dev veth-sim-host")
    error_message = "route script: ${nonsensitive(output.files["/usr/local/sbin/bench-exporters-route"])}"
  }
  assert {
    condition     = strcontains(nonsensitive(output.files["/etc/sysctl.d/60-bench-nl6.conf"]), "net.ipv4.ip_forward = 1")
    error_message = "nl6 does not forward"
  }
}

run "vmagent_scrapes_the_given_targets" {
  command = plan
  variables {
    service        = run.declaration.services.metrics
    scrape_targets = { node = ["192.0.2.200:9100"], riptide = ["192.0.2.203:8080"] }
  }
  assert {
    condition     = jsonencode(yamldecode(nonsensitive(output.files["/etc/bench/vmagent/scrape.yml"])).scrape_configs[1]) == jsonencode({ job_name = "riptide", static_configs = [{ targets = ["192.0.2.203:8080"] }] })
    error_message = "scrape.yml: ${nonsensitive(output.files["/etc/bench/vmagent/scrape.yml"])}"
  }
  assert {
    condition     = contains(keys(output.units), "victoriametrics") && contains(keys(output.units), "vmagent")
    error_message = "units: ${jsonencode(keys(output.units))}"
  }
}

run "sut_runs_no_container" {
  command = plan
  variables {
    service = run.declaration.services.sut
  }
  assert {
    condition     = length(output.units) == 0 && !contains(yamldecode(trimprefix(nonsensitive(output.user_data), "#cloud-config\n")).packages, "docker.io")
    error_message = "the SUT runs containers: ${jsonencode(keys(output.units))}"
  }
}
