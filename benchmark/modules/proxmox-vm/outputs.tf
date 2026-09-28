# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

output "name" {
  description = "VM name."
  value       = proxmox_virtual_environment_vm.this.name
}

output "vm_id" {
  description = "Proxmox VM id."
  value       = proxmox_virtual_environment_vm.this.vm_id
}

output "tags" {
  description = "Tags on the VM, sorted as Proxmox stores them."
  value       = local.tags
}

output "vm" {
  description = "The planned VM settings that carry placement and labels, read back from the resource for tests and review."
  value = {
    machine   = proxmox_virtual_environment_vm.this.machine
    tags      = proxmox_virtual_environment_vm.this.tags
    affinity  = proxmox_virtual_environment_vm.this.cpu[0].affinity
    cores     = proxmox_virtual_environment_vm.this.cpu[0].cores
    numa      = { hostnodes = proxmox_virtual_environment_vm.this.numa[0].hostnodes, policy = proxmox_virtual_environment_vm.this.numa[0].policy }
    nics      = [for n in proxmox_virtual_environment_vm.this.network_device : { bridge = n.bridge, vlan_id = n.vlan_id, mac = n.mac_address, queues = n.queues }]
    datastore = proxmox_virtual_environment_vm.this.disk[0].datastore_id
    disks     = length(proxmox_virtual_environment_vm.this.disk)
  }
}
