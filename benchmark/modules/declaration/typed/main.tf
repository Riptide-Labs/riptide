# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Converts the declaration to its object type: wrong types fail here, and
# omitted optional fields get their defaults. Unknown keys are dropped
# silently by the conversion, which is why the parent module checks them.

variable "experiment" {
  description = "The experiment declaration, typed and with defaults applied."
  type = object({
    name     = string
    ssh_keys = optional(list(string), [])
    riptide = object({
      source = string
      env    = optional(map(string), {})
    })
    hosts = map(object({
      provider  = string
      numa      = map(list(string))
      bridges   = optional(map(string), {})
      uri       = optional(string)
      pool      = optional(string, "default")
      node      = optional(string)
      address   = optional(string)
      datastore = optional(string)
    }))
    networks = object({
      ingest = object({
        vlan = number
        cidr = optional(string, "172.24.0.0/16")
      })
      store = object({
        vlan = number
        cidr = optional(string, "172.25.0.0/16")
      })
      exporters = optional(object({
        cidr = optional(string, "172.26.0.0/16")
      }), {})
      mgmt = object({
        host_range = string
        vlan       = optional(number, 11)
        cidr       = optional(string, "192.168.11.0/24")
        gateway    = optional(string)
        # Required, but checked as a violation in the parent module rather
        # than by the type: a type error would hide every other violation,
        # and would break destroy for declarations saved before dns existed.
        dns = optional(list(string))
      })
    })
    services = map(object({
      role      = string
      host      = string
      numa_node = number
      vcpus     = number
      memory_gb = number
      networks  = list(string)
      disk_gb   = optional(number)
      hugepages = optional(bool, false)
    }))
  })
}

output "experiment" {
  description = "The declaration, typed and with defaults applied."
  value       = var.experiment
}
