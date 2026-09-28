# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Inputs are shared with modules/proxmox-vm except for the backend-specific
# location of the base image and disks.

variable "experiment" {
  description = "Experiment name."
  type        = string
}

variable "service" {
  description = "One entry of the declaration module's `services` output."
  type = object({
    name      = string
    role      = string
    numa_node = number
    vcpus     = number
    memory_gb = number
    disk_gb   = optional(number)
    hugepages = bool
    threads   = number
    cores     = number
    vcpu_pins = list(number)
    emulator  = list(number)
    networks  = list(string)
    bridges   = map(string)
    vlans     = map(number)
    macs      = map(string)
  })
}

variable "cloud_init" {
  description = "Rendered cloud-init documents."
  type        = object({ user_data = string, meta_data = string, network_config = string })
  sensitive   = true
}

variable "pool" {
  description = "libvirt storage pool for the VM's disks."
  type        = string
}

variable "base_volume_path" {
  description = "Path of the base image volume the root disk is layered on."
  type        = string
}

variable "root_disk_gb" {
  description = "Size of the root disk."
  type        = number
  default     = 20
}

variable "data_volume" {
  description = "An existing volume to attach as the data disk instead of creating one; the lab root owns it so it survives bench destroy (the observability service). Null otherwise."
  type        = object({ pool = string, name = string })
  default     = null
}
