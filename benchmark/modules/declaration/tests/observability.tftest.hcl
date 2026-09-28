# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# The observe network and the observability role, on run.fixture.observed.
# Each rejection changes that lab in one place and asserts the exact message.

run "fixture" {
  module {
    source = "./tests/fixture"
  }
}

run "observed_lab_is_valid" {
  command = plan
  variables {
    raw = run.fixture.observed
  }
  assert {
    condition     = length(output.violations) == 0
    error_message = "violations: ${jsonencode(output.violations)}"
  }
  assert {
    condition     = output.observability == "observe" && output.observe_enabled
    error_message = "observability: ${jsonencode(output.observability)}"
  }
}

run "observe_addresses_follow_sorted_service_names" {
  command = plan
  variables {
    raw = run.fixture.observed
  }
  assert {
    condition     = output.services.observe.addresses.observe == "172.26.0.13" && output.services.sut.addresses.observe == "172.26.0.14" && output.services.clickhouse.addresses.observe == "172.26.0.10"
    error_message = "observe addresses: ${jsonencode({ for s, v in output.services : s => lookup(v.addresses, "observe", null) })}"
  }
  assert {
    condition     = output.services.sut.vlans.observe == 26 && output.services.sut.bridges.observe == "vmbr0" && output.services.loadgen.bridges.observe == "br-vlan26"
    error_message = "observe vlans/bridges: ${jsonencode([output.services.sut.vlans, output.services.sut.bridges, output.services.loadgen.bridges])}"
  }
  assert {
    condition     = can(regex("^52:54:00:", output.services.observe.macs.observe))
    error_message = "observe mac: ${jsonencode(output.services.observe.macs)}"
  }
}

run "without_observe_nothing_joins_it" {
  command = plan
  variables {
    raw = run.fixture.raw
  }
  assert {
    condition     = length(output.violations) == 0 && !output.observe_enabled && output.observability == null && !contains(keys(output.services.sut.addresses), "observe")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "second_observability_service_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.observed, { services = merge(run.fixture.observed.services, { observe2 = { role = "observability", host = "pve-1", numa_node = 0, vcpus = 2, memory_gb = 4, disk_gb = 10, networks = ["observe", "mgmt"] } }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "at most 1 observability service allowed, found 2")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "observability_without_disk_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.observed, { services = merge(run.fixture.observed.services, { observe = { for k, v in run.fixture.observed.services.observe : k => v if k != "disk_gb" } }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "service observe: role observability needs disk_gb for the data that survives bench destroy")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "service_off_observe_is_rejected_when_observability_exists" {
  command = plan
  variables {
    raw = merge(run.fixture.observed, { services = merge(run.fixture.observed.services, { metrics = merge(run.fixture.observed.services.metrics, { networks = ["store", "mgmt"] }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "service metrics: network observe is required while the lab has an observability service")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "observe_without_site_network_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.observed, { networks = { for k, v in run.fixture.observed.networks : k => v if k != "observe" } })
  }
  assert {
    condition     = contains(output.violations, "network observe: the site declares no observe network, but services join it: clickhouse, loadgen, metrics, observe, sut")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "observe_without_vlan_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.observed, { networks = merge(run.fixture.observed.networks, { observe = {} }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "network observe: declare vlan, the observability network's VLAN ID")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "observability_on_the_sut_node_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.observed, { services = merge(run.fixture.observed.services, { observe = merge(run.fixture.observed.services.observe, { host = "pve-1", numa_node = 1 }) }) })
  }
  assert {
    condition     = contains(output.violations, "service sut is the system under test and shares host pve-1 NUMA node 1 with service observe")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "observe_overlapping_exporters_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.observed, { networks = merge(run.fixture.observed.networks, { observe = { vlan = 26, cidr = "172.27.0.0/16" } }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "networks exporters (172.27.0.0/16) and observe (172.27.0.0/16) overlap")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "observability_without_observe_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.observed, { services = merge(run.fixture.observed.services, { observe = merge(run.fixture.observed.services.observe, { networks = ["mgmt"] }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "service observe: role observability needs network observe")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}
