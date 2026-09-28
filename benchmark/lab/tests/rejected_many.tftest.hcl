# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Run with -var-file=tests/rejected-many.tfvars. A rejected declaration stops the
# plan at terraform_data.checks, before any VM, disk or image is planned.
# modules/declaration/tests pins each rule and its message.

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

run "rejected_many_stops_at_the_checks" {
  command         = plan
  expect_failures = [terraform_data.checks]
}
