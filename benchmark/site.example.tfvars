# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Copy to benchmark/site.tfvars (not in git) and describe your lab. Every
# experiment places its services on the host keys defined here (pve-1, kvm-1),
# so keep the keys and change what they point at.
#
# Print a host's numa block on the host itself:
#   ssh root@<host> sh -s < benchmark/bin/numa-topology

site = {
  hosts = {
    # A Proxmox node with two NUMA nodes of 12 cores and SMT, nodes interleaved.
    pve-1 = {
      provider  = "proxmox"
      node      = "pve-1"             # Proxmox node name
      address   = "pve-1.example.org" # SSH and API address; defaults to node
      datastore = "local-zfs"         # VM disks; snippets and images go to local
      numa = {
        0 = ["0,24", "2,26", "4,28", "6,30", "8,32", "10,34", "12,36", "14,38", "16,40", "18,42", "20,44", "22,46"]
        1 = ["1,25", "3,27", "5,29", "7,31", "9,33", "11,35", "13,37", "15,39", "17,41", "19,43", "21,45", "23,47"]
      }
      # bridges default to vmbr0 for every network, tagged with its VLAN.
    }
    # A libvirt host with one NUMA node of 8 cores and SMT.
    kvm-1 = {
      provider = "libvirt"
      uri      = "qemu+ssh://root@kvm-1.example.org/system"
      pool     = "default"
      numa     = { 0 = ["0,8", "1,9", "2,10", "3,11", "4,12", "5,13", "6,14", "7,15"] }
      bridges  = { ingest = "br-vlan24", store = "br-vlan25", mgmt = "br-mgmt" }
    }
  }

  networks = {
    ingest = { vlan = 24 } # cidr defaults to 172.24.0.0/16
    store  = { vlan = 25 } # cidr defaults to 172.25.0.0/16
    mgmt = {
      vlan       = 11
      cidr       = "192.0.2.0/24"
      host_range = "192.0.2.200-229" # one address per service, unused
      dns        = ["192.0.2.53"]    # resolvers reachable from mgmt
      # gateway defaults to the first host of cidr
    }
  }

  # Ranges no lab network may overlap, besides the Docker ranges the tool
  # protects itself: other networks, clusters and overlays at the site.
  protected_ranges = {
    "cluster pods"     = "10.244.0.0/16"
    "cluster services" = "10.96.0.0/12"
  }
}
