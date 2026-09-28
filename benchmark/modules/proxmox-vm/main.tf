# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Differences from modules/libvirt-vm, both limits of Proxmox VE:
#  - cpu.affinity pins the whole QEMU process to the service's cores, not each
#    vCPU to one thread, so emulator threads run on those cores rather than on
#    the node's reserved core. The reserved core still stays free of VMs.
#  - Proxmox has no threads-per-core setting; the guest sees vcpus cores.

terraform {
  required_providers {
    proxmox = { source = "bpg/proxmox" }
  }
}

locals {
  name = "bench-${var.experiment}-${var.service.name}"
  # Proxmox stores tags sorted; an unsorted list shows a change on every plan.
  tags = sort(["riptide-bench", "exp-${var.experiment}", "role-${var.service.role}"])
}

resource "proxmox_virtual_environment_file" "user_data" {
  node_name    = var.node
  datastore_id = var.snippets_datastore
  content_type = "snippets"
  source_raw {
    file_name = "${local.name}-user-data.yaml"
    data      = var.cloud_init.user_data
  }
}

resource "proxmox_virtual_environment_file" "network_config" {
  node_name    = var.node
  datastore_id = var.snippets_datastore
  content_type = "snippets"
  source_raw {
    file_name = "${local.name}-network-config.yaml"
    data      = var.cloud_init.network_config
  }
}

resource "proxmox_virtual_environment_file" "meta_data" {
  node_name    = var.node
  datastore_id = var.snippets_datastore
  content_type = "snippets"
  source_raw {
    file_name = "${local.name}-meta-data.yaml"
    data      = var.cloud_init.meta_data
  }
}

resource "proxmox_virtual_environment_vm" "this" {
  name        = local.name
  node_name   = var.node
  description = "riptide benchmark ${var.experiment}, service ${var.service.name}"
  tags        = local.tags

  machine         = "q35"
  scsi_hardware   = "virtio-scsi-single"
  stop_on_destroy = true
  on_boot         = false
  started         = true

  operating_system {
    type = "l26"
  }

  cpu {
    type     = "host"
    sockets  = 1
    cores    = var.service.vcpus
    affinity = join(",", var.service.vcpu_pins)
    numa     = true
  }

  numa {
    device    = "numa0"
    cpus      = "0-${var.service.vcpus - 1}"
    memory    = var.service.memory_gb * 1024
    hostnodes = tostring(var.service.numa_node)
    policy    = "bind"
  }

  memory {
    dedicated = var.service.memory_gb * 1024
    hugepages = var.service.hugepages ? "2" : null
  }

  disk {
    datastore_id = var.datastore
    import_from  = var.base_image_id
    interface    = "virtio0"
    size         = var.root_disk_gb
    iothread     = true
    discard      = "on"
  }

  dynamic "disk" {
    for_each = var.service.disk_gb == null ? [] : [var.service.disk_gb]
    content {
      datastore_id = var.datastore
      interface    = "virtio1"
      size         = disk.value
      file_format  = "raw"
      iothread     = true
      discard      = "on"
    }
  }

  dynamic "network_device" {
    for_each = var.service.networks
    content {
      bridge      = var.service.bridges[network_device.value]
      vlan_id     = var.service.vlans[network_device.value]
      mac_address = upper(var.service.macs[network_device.value])
      model       = "virtio"
      queues      = var.service.vcpus
    }
  }

  initialization {
    datastore_id         = var.datastore
    user_data_file_id    = proxmox_virtual_environment_file.user_data.id
    network_data_file_id = proxmox_virtual_environment_file.network_config.id
    meta_data_file_id    = proxmox_virtual_environment_file.meta_data.id
  }

  serial_device {}
}
