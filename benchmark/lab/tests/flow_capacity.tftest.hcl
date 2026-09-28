# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Plans the committed experiments/flow-capacity.tfvars on the committed
# site.example.tfvars against mocked providers, and asserts what each VM module
# hands its provider. Run by benchmark/bin/bench check with
#   tofu test -filter=tests/flow_capacity.tftest.hcl \
#     -var-file=../site.example.tfvars -var-file=../experiments/flow-capacity.tfvars
# since provider for_each is evaluated from the var files before any run.

# Mocks carry the same for_each keys as the provider blocks they replace;
# a mock without for_each leaves every resource bound to a missing instance.
mock_provider "libvirt" {
  alias    = "host"
  for_each = { kvm-1 = true }
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
  base_image_path    = "/tmp/debian-13-genericcloud-amd64.qcow2"
}

run "vms_go_to_their_backend" {
  command = plan
  assert {
    condition     = jsonencode(sort(keys(module.libvirt_vm))) == jsonencode(["loadgen", "metrics", "observe"]) && jsonencode(sort(keys(module.proxmox_vm))) == jsonencode(["clickhouse", "sut"])
    error_message = "libvirt: ${jsonencode(keys(module.libvirt_vm))}, proxmox: ${jsonencode(keys(module.proxmox_vm))}"
  }
}

run "sut_is_pinned_to_node_1_on_pve-1" {
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
      { bridge = "vmbr0", vlan_id = 26, mac = upper(output.services.sut.macs.observe), queues = 8 },
      { bridge = "vmbr0", vlan_id = 11, mac = upper(output.services.sut.macs.mgmt), queues = 8 },
    ])
    error_message = "sut nics: ${jsonencode(module.proxmox_vm["sut"].vm.nics)}"
  }
}

run "proxmox_disks_land_on_the_declared_datastore" {
  command = plan
  assert {
    condition     = module.proxmox_vm["clickhouse"].vm.datastore == "local-zfs" && module.proxmox_vm["clickhouse"].vm.disks == 2 && module.proxmox_vm["sut"].vm.disks == 1
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
      { bridge = "br-vlan26", mac = output.services.loadgen.macs.observe, queues = 8 },
      { bridge = "br-mgmt", mac = output.services.loadgen.macs.mgmt, queues = 8 },
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
    condition     = strcontains(nonsensitive(local.riptide_env_file), "JAVA_OPTS=\"-Xmx8g --enable-native-access=ALL-UNNAMED\"\n")
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
    condition     = local.inventory.sut.version_identity.version == "0.16.2" && local.inventory.sut.jvm.heap == "-Xmx8g --enable-native-access=ALL-UNNAMED"
    error_message = "inventory sut: ${jsonencode(local.inventory.sut)}"
  }
  assert {
    condition     = local.inventory.sut.db_version == "clickhouse/clickhouse-server:26.7@sha256:f90a77560f72b10802106ee49e9870e41668cbc496e280c3911f6e3b216657f3"
    error_message = "db_version: ${local.inventory.sut.db_version}"
  }
}

# Proxmox defaults to i440fx when no machine is given.
run "every_vm_is_q35" {
  command = plan
  assert {
    condition     = alltrue([for m in module.proxmox_vm : m.vm.machine == "q35"]) && alltrue([for m in module.libvirt_vm : m.domain.machine == "q35"])
    error_message = "machines: ${jsonencode(merge({ for s, m in module.proxmox_vm : s => m.vm.machine }, { for s, m in module.libvirt_vm : s => m.domain.machine }))}"
  }
}

run "every_lab_image_comes_from_the_manifest_digest_pinned" {
  command = plan
  assert {
    condition     = jsonencode(sort(keys(local.images))) == jsonencode(["clickhouse", "grafana", "nl6", "prometheus", "pyroscope", "victoriametrics"])
    error_message = "images: ${jsonencode(keys(local.images))}"
  }
  assert {
    condition     = alltrue([for i in values(local.images) : can(regex("@sha256:[0-9a-f]{64}$", i))])
    error_message = "an image is not digest-pinned: ${jsonencode(local.images)}"
  }
  assert {
    condition     = local.images.grafana == "docker.io/grafana/grafana:13.2.2@sha256:ac461fb352abc50da10a51c7d02462e9c05488f11f53f14b3ad79a8145f638a0"
    error_message = "grafana: ${try(local.images.grafana, "missing")}"
  }
}

run "observability_data_disk_lives_outside_the_domain" {
  command = plan
  assert {
    condition     = length(libvirt_volume.observability_data) == 1 && libvirt_volume.observability_data["observe"].name == "bench-flow-capacity-observe-observability-data.qcow2"
    error_message = "observability data volumes: ${jsonencode(keys(libvirt_volume.observability_data))}"
  }
  assert {
    condition     = module.libvirt_vm["observe"].domain.disks == 3 && module.libvirt_vm["metrics"].domain.disks == 3 && module.libvirt_vm["observe"].domain.data_disk == "bench-flow-capacity-observe-observability-data.qcow2"
    error_message = "disks: observe ${module.libvirt_vm["observe"].domain.disks}, metrics ${module.libvirt_vm["metrics"].domain.disks}"
  }
}

run "riptide_profiles_and_serves_metrics_on_observe" {
  command = plan
  override_resource {
    target = random_password.clickhouse
    values = { result = "test-password" }
  }
  assert {
    condition     = strcontains(nonsensitive(local.riptide_env_file), "PYROSCOPE_SERVER_ADDRESS=\"http://172.26.0.13:4040\"\n") && strcontains(nonsensitive(local.riptide_env_file), "RIPTIDE_PROFILING_ENABLED=\"true\"\n") && strcontains(nonsensitive(local.riptide_env_file), "RIPTIDE_MANAGEMENT_BIND_ADDRESS=\"172.26.0.14\"\n")
    error_message = "riptide.env lacks the profiling or bind settings"
  }
  assert {
    condition     = strcontains(nonsensitive(local.riptide_env_file), "JAVA_OPTS=\"-Xmx8g --enable-native-access=ALL-UNNAMED\"\n")
    error_message = "JAVA_OPTS not appended once"
  }
}

# bench destroy powers the VM off. Without a clean stop, Pyroscope's last
# seconds of blocks reach the kept disk as empty files, and every later query
# that touches one fails. The destroy-time provisioner runs self.input.stop;
# that the provisioner exists is only shown by a real destroy and re-apply.
run "observability_stops_cleanly_before_destroy" {
  command = plan
  assert {
    condition     = terraform_data.observability_ready["observe"].input.host == "192.0.2.203"
    error_message = "observability_ready input: ${jsonencode(terraform_data.observability_ready["observe"].input)}"
  }
  assert {
    condition     = terraform_data.observability_ready["observe"].input.stop == "sudo systemctl stop grafana pyroscope prometheus && sync"
    error_message = "stop: ${jsonencode(terraform_data.observability_ready["observe"].input)}"
  }
}

run "prometheus_jobs_cover_every_vm_and_service" {
  command = plan
  assert {
    condition     = jsonencode(sort(keys(local.prometheus_jobs))) == jsonencode(["clickhouse", "node", "prometheus", "pyroscope", "riptide", "victoriametrics"]) && length(local.prometheus_jobs.node) == 5
    error_message = "jobs: ${jsonencode({ for k, v in local.prometheus_jobs : k => length(v) })}"
  }
  assert {
    condition     = local.prometheus_jobs.riptide[0].target == "172.26.0.14:8080" && local.prometheus_jobs.clickhouse[0].target == "172.26.0.10:9363" && local.prometheus_jobs.victoriametrics[0].target == "172.26.0.12:8428"
    error_message = "targets: ${jsonencode([local.prometheus_jobs.riptide, local.prometheus_jobs.clickhouse, local.prometheus_jobs.victoriametrics])}"
  }
  assert {
    condition     = jsonencode(local.prometheus_jobs.riptide[0].labels) == jsonencode({ experiment = "flow-capacity", host = "pve-1", role = "riptide", service = "sut" })
    error_message = "labels: ${jsonencode(local.prometheus_jobs.riptide[0].labels)}"
  }
}

run "observability_outputs_for_the_operator" {
  command = plan
  assert {
    condition     = length(local_file.grafana_admin) == 1 && local_file.grafana_admin[0].file_permission == "0600"
    error_message = "grafana-admin file missing or not 0600"
  }
  assert {
    condition     = local.inventory.observability.grafana == "http://192.0.2.203:3000" && local.inventory.observability.prometheus == "http://192.0.2.203:9090"
    error_message = "inventory observability: ${jsonencode(local.inventory.observability)}"
  }
}
