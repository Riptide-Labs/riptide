# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# pve-1 alone: the SUT on node 1, everything else on node 0.
site = {
  hosts = {
    pve-1 = {
      provider  = "proxmox"
      node      = "pve-1"
      datastore = "tank"
      numa = {
        0 = ["0,24", "2,26", "4,28", "6,30", "8,32", "10,34", "12,36", "14,38", "16,40", "18,42", "20,44", "22,46"]
        1 = ["1,25", "3,27", "5,29", "7,31", "9,33", "11,35", "13,37", "15,39", "17,41", "19,43", "21,45", "23,47"]
      }
    }
  }
  networks = {
    ingest = { vlan = 24 }
    store  = { vlan = 25 }
    mgmt   = { vlan = 11, cidr = "192.0.2.0/24", host_range = "192.0.2.200-229", dns = ["192.0.2.53"] }
  }
}

experiment = {
  name    = "idle-libvirt"
  riptide = { source = "release:0.16.2" }
  services = {
    sut        = { role = "riptide", host = "pve-1", numa_node = 1, vcpus = 8, memory_gb = 16, networks = ["ingest", "store", "mgmt"] }
    clickhouse = { role = "clickhouse", host = "pve-1", numa_node = 0, vcpus = 16, memory_gb = 64, disk_gb = 200, networks = ["store", "mgmt"] }
    loadgen    = { role = "nl6", host = "pve-1", numa_node = 0, vcpus = 4, memory_gb = 8, networks = ["ingest", "mgmt"] }
    metrics    = { role = "victoriametrics", host = "pve-1", numa_node = 0, vcpus = 2, memory_gb = 8, networks = ["store", "mgmt"] }
  }
}
