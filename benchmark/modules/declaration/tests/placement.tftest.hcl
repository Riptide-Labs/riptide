# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Derived placement, addresses and routes for the base fixture
# (tests/fixture), which declares the lab's real topology.

run "fixture" {
  module {
    source = "./tests/fixture"
  }
}

variables {
  raw = run.fixture.raw
}

run "valid_declaration_has_no_violations" {
  command = plan
  assert {
    condition     = length(output.violations) == 0
    error_message = "expected no violations, got: ${jsonencode(output.violations)}"
  }
}

run "sut_gets_whole_cores_from_its_node" {
  command = plan
  assert {
    condition     = jsonencode(output.services.sut.vcpu_pins) == jsonencode([1, 25, 3, 27, 5, 29, 7, 31])
    error_message = "sut pins: ${jsonencode(output.services.sut.vcpu_pins)}"
  }
  assert {
    condition     = output.services.sut.cores == 4 && output.services.sut.threads == 2
    error_message = "sut topology: ${output.services.sut.cores} cores x ${output.services.sut.threads} threads"
  }
}

run "emulator_threads_use_the_reserved_last_core" {
  command = plan
  assert {
    condition     = jsonencode(output.services.sut.emulator) == jsonencode([23, 47]) && jsonencode(output.services.clickhouse.emulator) == jsonencode([22, 46])
    error_message = "emulator: sut ${jsonencode(output.services.sut.emulator)}, clickhouse ${jsonencode(output.services.clickhouse.emulator)}"
  }
}

run "services_on_one_node_get_disjoint_cores_in_name_order" {
  command = plan
  # loadgen sorts before metrics, so it takes the first four cores.
  assert {
    condition     = jsonencode(output.services.loadgen.vcpu_pins) == jsonencode([0, 8, 1, 9, 2, 10, 3, 11])
    error_message = "loadgen pins: ${jsonencode(output.services.loadgen.vcpu_pins)}"
  }
  assert {
    condition     = jsonencode(output.services.metrics.vcpu_pins) == jsonencode([4, 12, 5, 13])
    error_message = "metrics pins: ${jsonencode(output.services.metrics.vcpu_pins)}"
  }
}

run "addresses_follow_sorted_service_names" {
  command = plan
  # clickhouse, loadgen, metrics, sut
  assert {
    condition = (output.services.clickhouse.addresses.store == "172.25.0.10"
      && output.services.metrics.addresses.store == "172.25.0.12"
    && output.services.sut.addresses.store == "172.25.0.13")
    error_message = "store addresses: ${jsonencode({ for s, v in output.services : s => lookup(v.addresses, "store", null) })}"
  }
  assert {
    condition     = output.services.clickhouse.addresses.mgmt == "192.168.11.200" && output.services.sut.addresses.mgmt == "192.168.11.203"
    error_message = "mgmt addresses: ${jsonencode({ for s, v in output.services : s => v.addresses.mgmt })}"
  }
}

run "sut_routes_exporters_through_the_loadgen" {
  command = plan
  assert {
    condition     = jsonencode(output.services.sut.routes) == jsonencode([{ network = "ingest", to = "172.26.0.0/16", via = "172.24.0.11" }])
    error_message = "sut routes: ${jsonencode(output.services.sut.routes)}"
  }
  assert {
    condition     = length(output.services.loadgen.routes) == 0
    error_message = "loadgen routes: ${jsonencode(output.services.loadgen.routes)}"
  }
}

run "proxmox_bridges_default_to_vmbr0" {
  command = plan
  assert {
    condition     = output.services.sut.bridges == { ingest = "vmbr0", store = "vmbr0", mgmt = "vmbr0" }
    error_message = "sut bridges: ${jsonencode(output.services.sut.bridges)}"
  }
}
