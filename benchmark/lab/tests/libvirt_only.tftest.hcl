# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Run with -var-file=tests/libvirt-only.tfvars. Only libvirt is mocked: the
# Proxmox provider is the real one, with no PROXMOX_VE_* set (bin/bench check
# unsets them). If an experiment without a Proxmox host configured it anyway,
# this plan would fail for want of an endpoint.

mock_provider "libvirt" {
  alias    = "host"
  for_each = { kvm-1 = true, kvm-2 = true }
}

variables {
  agent_ssh_keys     = ["ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITestKeyOnly test"]
  riptide_deb        = "/tmp/riptide_0.16.2_all.deb"
  riptide_deb_sha256 = "0000000000000000000000000000000000000000000000000000000000000000"
  base_image_path    = "/tmp/debian-13-genericcloud-amd64.qcow2"
}

run "plans_without_proxmox" {
  command = plan
  assert {
    condition     = length(module.proxmox_vm) == 0 && length(proxmox_download_file.base) == 0
    error_message = "a libvirt-only experiment planned Proxmox resources"
  }
  assert {
    condition     = jsonencode(sort(keys(module.libvirt_vm))) == jsonencode(["clickhouse", "loadgen", "metrics", "sut"])
    error_message = "libvirt VMs: ${jsonencode(keys(module.libvirt_vm))}"
  }
}

run "each_libvirt_host_gets_its_own_base_image" {
  command = plan
  assert {
    condition     = jsonencode(sort(keys(libvirt_volume.base))) == jsonencode(["kvm-1", "kvm-2"])
    error_message = "base volumes: ${jsonencode(keys(libvirt_volume.base))}"
  }
}
