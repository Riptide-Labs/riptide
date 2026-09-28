# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# One provider instance per host in use. A backend no host declares gets no
# instance at all, so it is never configured or contacted, and its credentials
# may be unset. Both maps are read from the raw declaration because provider
# for_each is evaluated before any module runs; the var file must therefore be
# passed to `tofu init` too (benchmark/bin/bench does).
#
# A provider instance must outlive its resources by one apply. Destroy with the
# declaration saved at apply (benchmark/bin/bench destroy does), not with an
# edited copy that dropped a host.

locals {
  libvirt_hosts = { for h, v in var.experiment.hosts : h => v if try(v.provider, "") == "libvirt" }
  proxmox_hosts = { for h, v in var.experiment.hosts : h => v if try(v.provider, "") == "proxmox" }
}

provider "libvirt" {
  alias    = "host"
  for_each = local.libvirt_hosts
  uri      = try(each.value.uri, null)
}

# Endpoint and credentials come from PROXMOX_VE_ENDPOINT, PROXMOX_VE_USERNAME
# (root@pam, needed for cpu.affinity and hugepages) and PROXMOX_VE_PASSWORD.
# Snippet upload goes over SSH as root with the agent's keys.
provider "proxmox" {
  alias    = "pve"
  for_each = length(local.proxmox_hosts) > 0 ? { pve = true } : {}

  ssh {
    agent    = true
    username = "root"

    dynamic "node" {
      for_each = { for h, v in local.proxmox_hosts : v.node => v.address if try(v.address, null) != null }
      content {
        name    = node.key
        address = node.value
      }
    }
  }
}
