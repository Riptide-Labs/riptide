# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Run with -var-file=tests/proxmox-only.tfvars. Only Proxmox is mocked: the
# libvirt provider is the real one. If an experiment without a libvirt host
# configured it anyway, it would try qemu:///system and this plan would fail.

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

run "plans_without_libvirt" {
  command = plan
  assert {
    condition     = length(module.libvirt_vm) == 0 && length(libvirt_volume.base) == 0
    error_message = "a Proxmox-only experiment planned libvirt resources"
  }
  assert {
    condition     = jsonencode(sort(keys(module.proxmox_vm))) == jsonencode(["clickhouse", "loadgen", "metrics", "observe", "sut"])
    error_message = "Proxmox VMs: ${jsonencode(keys(module.proxmox_vm))}"
  }
}

run "proxmox_observability_disk_is_allocated_outside_the_vm" {
  command = plan
  assert {
    condition     = length(terraform_data.proxmox_observability_data) == 1 && terraform_data.proxmox_observability_data["observe"].input.volume == "vm-999999-bench-idle-libvirt-observe-data"
    error_message = "proxmox observability data: ${jsonencode(keys(terraform_data.proxmox_observability_data))}"
  }
  assert {
    condition     = module.proxmox_vm["observe"].vm.disks == 2 && module.proxmox_vm["observe"].vm.data_path == "vm-999999-bench-idle-libvirt-observe-data"
    error_message = "observe vm: ${jsonencode(module.proxmox_vm["observe"].vm)}"
  }
}
