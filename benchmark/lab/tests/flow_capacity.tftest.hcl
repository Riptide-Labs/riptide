# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Plans experiments/flow-capacity.tfvars against mocked providers and asserts
# what each VM module hands its provider. Run by benchmark/bin/bench check with
#   tofu test -filter=tests/flow_capacity.tftest.hcl -var-file=../experiments/flow-capacity.tfvars
# since provider for_each is evaluated from the var file before any run.

# Mocks carry the same for_each keys as the provider blocks they replace;
# a mock without for_each leaves every resource bound to a missing instance.
mock_provider "libvirt" {
  alias    = "host"
  for_each = { mad-monkey = true }
}

mock_provider "proxmox" {
  alias    = "pve"
  for_each = { pve = true }

  # The provider validates file ids even on mocked values.
  mock_resource "proxmox_download_file" {
    defaults = { id = "local:import/base.qcow2" }
  }
  mock_resource "proxmox_virtual_environment_file" {
    defaults = { id = "local:snippets/cloud-init.yaml" }
  }
}

variables {
  agent_ssh_keys     = ["ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITestKeyOnly test"]
  riptide_deb        = "/tmp/riptide_0.16.2_all.deb"
  riptide_deb_sha256 = "0000000000000000000000000000000000000000000000000000000000000000"
}

run "vms_go_to_their_backend" {
  command = plan
  assert {
    condition     = jsonencode(sort(keys(module.libvirt_vm))) == jsonencode(["loadgen", "metrics"]) && jsonencode(sort(keys(module.proxmox_vm))) == jsonencode(["clickhouse", "sut"])
    error_message = "libvirt: ${jsonencode(keys(module.libvirt_vm))}, proxmox: ${jsonencode(keys(module.proxmox_vm))}"
  }
}

run "sut_is_pinned_to_node_1_on_lechuck" {
  command = plan
  assert {
    condition     = module.proxmox_vm["sut"].vm.affinity == "1,25,3,27,5,29,7,31"
    error_message = "sut affinity: ${module.proxmox_vm["sut"].vm.affinity}"
  }
  assert {
    condition     = module.proxmox_vm["sut"].vm.numa.hostnodes == "1" && module.proxmox_vm["sut"].vm.numa.policy == "bind"
    error_message = "sut numa: ${jsonencode(module.proxmox_vm["sut"].vm.numa)}"
  }
}

run "proxmox_nics_carry_vlan_tags_and_macs" {
  command = plan
  assert {
    condition = jsonencode(module.proxmox_vm["sut"].vm.nics) == jsonencode([
      { bridge = "vmbr0", vlan_id = 24, mac = upper(output.services.sut.macs.ingest), queues = 8 },
      { bridge = "vmbr0", vlan_id = 25, mac = upper(output.services.sut.macs.store), queues = 8 },
      { bridge = "vmbr0", vlan_id = 11, mac = upper(output.services.sut.macs.mgmt), queues = 8 },
    ])
    error_message = "sut nics: ${jsonencode(module.proxmox_vm["sut"].vm.nics)}"
  }
}

run "proxmox_disks_land_on_the_declared_datastore" {
  command = plan
  assert {
    condition     = module.proxmox_vm["clickhouse"].vm.datastore == "scummbar" && module.proxmox_vm["clickhouse"].vm.disks == 2 && module.proxmox_vm["sut"].vm.disks == 1
    error_message = "clickhouse: ${jsonencode(module.proxmox_vm["clickhouse"].vm)}"
  }
}

run "proxmox_tags_are_sorted" {
  command = plan
  assert {
    condition     = jsonencode(module.proxmox_vm["sut"].vm.tags) == jsonencode(["exp-flow-capacity", "riptide-bench", "role-riptide"])
    error_message = "sut tags: ${jsonencode(module.proxmox_vm["sut"].vm.tags)}"
  }
}

run "libvirt_vcpus_pin_one_thread_each" {
  command = plan
  assert {
    condition = jsonencode(module.libvirt_vm["metrics"].domain.vcpu_pins) == jsonencode([
      { vcpu = 0, cpu_set = "4" }, { vcpu = 1, cpu_set = "12" }, { vcpu = 2, cpu_set = "5" }, { vcpu = 3, cpu_set = "13" },
    ])
    error_message = "metrics pins: ${jsonencode(module.libvirt_vm["metrics"].domain.vcpu_pins)}"
  }
  assert {
    condition     = module.libvirt_vm["metrics"].domain.topology.cores == 2 && module.libvirt_vm["metrics"].domain.topology.threads == 2
    error_message = "metrics topology: ${jsonencode(module.libvirt_vm["metrics"].domain.topology)}"
  }
}

run "libvirt_emulator_and_memory_stay_on_the_node" {
  command = plan
  assert {
    condition     = module.libvirt_vm["loadgen"].domain.emulator == "7,15"
    error_message = "loadgen emulator: ${module.libvirt_vm["loadgen"].domain.emulator}"
  }
  assert {
    condition     = module.libvirt_vm["loadgen"].domain.numa.mode == "strict" && module.libvirt_vm["loadgen"].domain.numa.nodeset == "0"
    error_message = "loadgen numa: ${jsonencode(module.libvirt_vm["loadgen"].domain.numa)}"
  }
}

run "libvirt_nics_use_declared_bridges" {
  command = plan
  assert {
    condition = jsonencode(module.libvirt_vm["loadgen"].domain.interfaces) == jsonencode([
      { bridge = "br-vlan24", mac = output.services.loadgen.macs.ingest, queues = 8 },
      { bridge = "br0", mac = output.services.loadgen.macs.mgmt, queues = 8 },
    ])
    error_message = "loadgen interfaces: ${jsonencode(module.libvirt_vm["loadgen"].domain.interfaces)}"
  }
}

run "libvirt_labels_in_title_and_metadata" {
  command = plan
  assert {
    condition     = module.libvirt_vm["metrics"].domain.title == "riptide-bench exp-flow-capacity role-victoriametrics"
    error_message = "metrics title: ${module.libvirt_vm["metrics"].domain.title}"
  }
  assert {
    condition     = module.libvirt_vm["metrics"].domain.metadata == "<bench:labels xmlns:bench=\"https://riptide-labs.github.io/benchmark/1\"><bench:label>riptide-bench</bench:label><bench:label>exp-flow-capacity</bench:label><bench:label>role-victoriametrics</bench:label></bench:labels>"
    error_message = "metrics metadata: ${module.libvirt_vm["metrics"].domain.metadata}"
  }
}

run "riptide_env_points_at_clickhouse_on_store" {
  command = plan
  override_resource {
    target = random_password.clickhouse
    values = { result = "test-password" }
  }
  assert {
    condition     = strcontains(nonsensitive(local.riptide_env_file), "RIPTIDE_CLICKHOUSE_ENDPOINT=\"http://172.25.0.10:8123\"\n")
    error_message = "env file lacks the ClickHouse store endpoint"
  }
  assert {
    condition     = strcontains(nonsensitive(local.riptide_env_file), "RIPTIDE_CLICKHOUSE_PASSWORD=\"test-password\"\n")
    error_message = "env file lacks the generated ClickHouse password"
  }
  assert {
    condition     = strcontains(nonsensitive(local.riptide_env_file), "JAVA_OPTS=\"-Xmx8g\"\n")
    error_message = "env file lacks the declared JAVA_OPTS"
  }
}

run "inventory_carries_the_capture_manifest_sut_fields" {
  command = plan
  override_resource {
    target = random_password.clickhouse
    values = { result = "test-password" }
  }
  assert {
    condition     = local.inventory.sut.version_identity.version == "0.16.2" && local.inventory.sut.jvm.heap == "-Xmx8g"
    error_message = "inventory sut: ${jsonencode(local.inventory.sut)}"
  }
  assert {
    condition     = local.inventory.sut.db_version == "clickhouse/clickhouse-server:26.7@sha256:f90a77560f72b10802106ee49e9870e41668cbc496e280c3911f6e3b216657f3"
    error_message = "db_version: ${local.inventory.sut.db_version}"
  }
}
