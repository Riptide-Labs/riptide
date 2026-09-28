# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

output "id" {
  description = "Domain UUID; changes when the domain is re-created."
  value       = libvirt_domain.this.uuid
}

output "name" {
  description = "Domain name."
  value       = libvirt_domain.this.name
}

output "labels" {
  description = "Labels carried in the domain title and metadata."
  value       = local.labels
}

output "domain" {
  description = "The planned domain settings that carry placement and labels, read back from the resource for tests and review."
  value = {
    title      = libvirt_domain.this.title
    metadata   = libvirt_domain.this.metadata.xml
    topology   = libvirt_domain.this.cpu.topology
    vcpu_pins  = [for p in libvirt_domain.this.cpu_tune.vcpu_pin : { vcpu = p.vcpu, cpu_set = p.cpu_set }]
    emulator   = libvirt_domain.this.cpu_tune.emulator_pin.cpu_set
    numa       = libvirt_domain.this.numa_tune.memory
    interfaces = [for i in libvirt_domain.this.devices.interfaces : { bridge = i.source.bridge.bridge, mac = i.mac.address, queues = i.driver.queues }]
    disks      = length(libvirt_domain.this.devices.disks)
  }
}
