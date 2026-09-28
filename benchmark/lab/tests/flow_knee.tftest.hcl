# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Plans the committed experiments/flow-knee.tfvars on the committed
# site.example.tfvars against mocked providers, and asserts what each VM module
# hands its provider. Run by benchmark/bin/bench check with
#   tofu test -filter=tests/flow_knee.tftest.hcl \
#     -var-file=../site.example.tfvars -var-file=../experiments/flow-knee.tfvars
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
  riptide_deb        = "/tmp/riptide_0.17.0_all.deb"
  riptide_deb_sha256 = "0000000000000000000000000000000000000000000000000000000000000000"
  base_image_path    = "/tmp/debian-13-genericcloud-amd64.qcow2"
}

run "vms_go_to_their_backend" {
  command = plan
  assert {
    condition     = jsonencode(sort(keys(module.libvirt_vm))) == jsonencode(["clickhouse", "observe"]) && jsonencode(sort(keys(module.proxmox_vm))) == jsonencode(["loadgen", "sut"])
    error_message = "libvirt: ${jsonencode(keys(module.libvirt_vm))}, proxmox: ${jsonencode(keys(module.proxmox_vm))}"
  }
}

run "sut_has_two_cores_of_node_1_to_itself" {
  command = plan
  assert {
    condition     = module.proxmox_vm["sut"].vm.affinity == "1,25,3,27"
    error_message = "sut affinity: ${module.proxmox_vm["sut"].vm.affinity}"
  }
  assert {
    condition     = module.proxmox_vm["loadgen"].vm.affinity == "0,24,2,26,4,28,6,30"
    error_message = "loadgen affinity: ${module.proxmox_vm["loadgen"].vm.affinity}"
  }
}

run "clickhouse_and_observe_share_kvm_1_without_overlap" {
  command = plan
  assert {
    condition = jsonencode(module.libvirt_vm["clickhouse"].domain.vcpu_pins) == jsonencode([
      { vcpu = 0, cpu_set = "0" }, { vcpu = 1, cpu_set = "8" }, { vcpu = 2, cpu_set = "1" }, { vcpu = 3, cpu_set = "9" },
      { vcpu = 4, cpu_set = "2" }, { vcpu = 5, cpu_set = "10" }, { vcpu = 6, cpu_set = "3" }, { vcpu = 7, cpu_set = "11" },
    ])
    error_message = "clickhouse pins: ${jsonencode(module.libvirt_vm["clickhouse"].domain.vcpu_pins)}"
  }
  assert {
    condition = jsonencode(module.libvirt_vm["observe"].domain.vcpu_pins) == jsonencode([
      { vcpu = 0, cpu_set = "4" }, { vcpu = 1, cpu_set = "12" }, { vcpu = 2, cpu_set = "5" }, { vcpu = 3, cpu_set = "13" },
    ])
    error_message = "observe pins: ${jsonencode(module.libvirt_vm["observe"].domain.vcpu_pins)}"
  }
}

run "riptide_writes_to_the_knee_database_with_a_5g_heap" {
  command = plan
  override_resource {
    target = random_password.clickhouse
    values = { result = "test-password" }
  }
  assert {
    condition     = strcontains(nonsensitive(local.riptide_env_file), "RIPTIDE_CLICKHOUSE_DATABASE=\"riptide_knee\"\n")
    error_message = "env file lacks the knee database"
  }
  # Profiling is on with an observability service, so the flag is appended.
  assert {
    condition     = strcontains(nonsensitive(local.riptide_env_file), "JAVA_OPTS=\"-Xmx5g --enable-native-access=ALL-UNNAMED\"\n")
    error_message = "env file lacks the declared heap"
  }
}

run "no_victoriametrics" {
  command = plan
  assert {
    condition     = !contains(keys(local.prometheus_jobs), "victoriametrics")
    error_message = "jobs: ${jsonencode(keys(local.prometheus_jobs))}"
  }
}
