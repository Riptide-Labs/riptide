# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

terraform {
  required_providers {
    libvirt = { source = "dmacvicar/libvirt" }
  }
}

locals {
  name     = "bench-${var.experiment}-${var.service.name}"
  gib      = 1073741824
  labels   = ["riptide-bench", "exp-${var.experiment}", "role-${var.service.role}"]
  metadata = "<bench:labels xmlns:bench=\"https://riptide-labs.github.io/benchmark/1\">${join("", [for l in local.labels : "<bench:label>${l}</bench:label>"])}</bench:labels>"
}

resource "libvirt_cloudinit_disk" "this" {
  name           = "${local.name}-cloudinit"
  user_data      = var.cloud_init.user_data
  meta_data      = var.cloud_init.meta_data
  network_config = var.cloud_init.network_config
}

resource "libvirt_volume" "cloudinit" {
  name = "${local.name}-cloudinit.iso"
  pool = var.pool
  create = {
    content = { url = libvirt_cloudinit_disk.this.path }
  }
}

resource "libvirt_volume" "root" {
  name     = "${local.name}-root.qcow2"
  pool     = var.pool
  capacity = var.root_disk_gb * local.gib
  target   = { format = { type = "qcow2" } }
  backing_store = {
    path   = var.base_volume_path
    format = { type = "qcow2" }
  }
}

resource "libvirt_volume" "data" {
  count    = var.service.disk_gb == null ? 0 : 1
  name     = "${local.name}-data.qcow2"
  pool     = var.pool
  capacity = var.service.disk_gb * local.gib
  target   = { format = { type = "qcow2" } }
}

resource "libvirt_domain" "this" {
  name        = local.name
  title       = join(" ", local.labels)
  description = "riptide benchmark ${var.experiment}, service ${var.service.name}"
  type        = "kvm"
  running     = true

  memory      = var.service.memory_gb
  memory_unit = "GiB"
  vcpu        = var.service.vcpus

  metadata = { xml = local.metadata }

  os = {
    type         = "hvm"
    type_arch    = "x86_64"
    type_machine = "q35"
  }

  # The guest sees the host's SMT layout, so its scheduler knows which vCPUs
  # share a physical core.
  cpu = {
    mode     = "host-passthrough"
    topology = { sockets = 1, cores = var.service.cores, threads = var.service.threads }
  }

  cpu_tune = {
    vcpu_pin     = [for i, cpu in var.service.vcpu_pins : { vcpu = i, cpu_set = tostring(cpu) }]
    emulator_pin = { cpu_set = join(",", var.service.emulator) }
  }

  numa_tune = {
    memory = { mode = "strict", nodeset = tostring(var.service.numa_node) }
  }

  memory_backing = var.service.hugepages ? {
    memory_huge_pages = { hugepages = [{ size = 2048, unit = "KiB" }] }
  } : null

  devices = {
    disks = concat(
      [{
        source = { volume = { pool = libvirt_volume.root.pool, volume = libvirt_volume.root.name } }
        target = { bus = "virtio", dev = "vda" }
        driver = { type = "qcow2" }
      }],
      [for v in libvirt_volume.data : {
        source = { volume = { pool = v.pool, volume = v.name } }
        target = { bus = "virtio", dev = "vdb" }
        driver = { type = "qcow2" }
      }],
      [{
        device = "cdrom"
        source = { volume = { pool = libvirt_volume.cloudinit.pool, volume = libvirt_volume.cloudinit.name } }
        target = { bus = "sata", dev = "sda" }
      }],
    )

    interfaces = [
      for n in var.service.networks : {
        mac    = { address = var.service.macs[n] }
        model  = { type = "virtio" }
        source = { bridge = { bridge = var.service.bridges[n] } }
        driver = { name = "vhost", queues = var.service.vcpus }
      }
    ]

    # A Debian genericcloud guest without a video device boot-looped in GRUB
    # on mad-monkey (2026-09-26); VNC on loopback plus virtio video boots.
    graphics = [{ vnc = { auto_port = true, listen = "127.0.0.1" } }]
    videos   = [{ model = { type = "virtio", primary = "yes" } }]
    serials  = [{ target = { port = 0 } }]
    consoles = [{ target = { type = "serial", port = 0 } }]
  }
}
