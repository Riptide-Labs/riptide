# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Run with -var-file=../site.example.tfvars -var-file=tests/java-opts.tfvars.
# A declared JAVA_OPTS that already carries the profiling flag keeps its value
# and does not get the flag twice.

mock_provider "libvirt" {
  alias    = "host"
  for_each = { kvm-1 = true }
}

mock_provider "proxmox" {
  alias    = "pve"
  for_each = { pve = true }

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

run "declared_flag_is_not_added_twice" {
  command = plan
  override_resource {
    target = random_password.clickhouse
    values = { result = "test-password" }
  }
  assert {
    condition     = strcontains(nonsensitive(local.riptide_env_file), "JAVA_OPTS=\"-Xmx4g --enable-native-access=ALL-UNNAMED\"\n")
    error_message = "JAVA_OPTS changed or duplicated the flag"
  }
}
