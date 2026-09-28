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

variable "node" {
  description = "Proxmox node name."
  type        = string
}

variable "datastore" {
  description = "Datastore holding the VM's disks."
  type        = string
}

variable "snippets_datastore" {
  description = "Datastore allowing snippets, for cloud-init documents."
  type        = string
  default     = "local"
}

variable "base_image_id" {
  description = "File id of the imported base image, for example local:import/<file>.qcow2."
  type        = string
}

variable "root_disk_gb" {
  description = "Size of the root disk."
  type        = number
  default     = 20
}
