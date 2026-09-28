# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# experiments/flow-capacity.tfvars with ClickHouse moved onto the SUT's NUMA node.
site = {
  hosts = {
    pve-1 = {
      provider  = "proxmox"
      node      = "pve-1"
      address   = "pve-1.example.org"
      datastore = "tank"
      numa = {
        0 = ["0,24", "2,26", "4,28", "6,30", "8,32", "10,34", "12,36", "14,38", "16,40", "18,42", "20,44", "22,46"]
        1 = ["1,25", "3,27", "5,29", "7,31", "9,33", "11,35", "13,37", "15,39", "17,41", "19,43", "21,45", "23,47"]
      }
    }
    kvm-1 = {
      provider = "libvirt"
      uri      = "qemu+ssh://root@kvm-1.example.org/system"
      pool     = "bench"
      numa     = { 0 = ["0,8", "1,9", "2,10", "3,11", "4,12", "5,13", "6,14", "7,15"] }
      # br0 carries VLAN 11 (mgmt). The ingest and store bridges are part of
      # the one-time lab setup in benchmark/README.md.
      bridges = { ingest = "br-vlan24", store = "br-vlan25", mgmt = "br0" }
    }
  }
  networks = {
    ingest = { vlan = 24 }
    store  = { vlan = 25 }
    mgmt   = { vlan = 11, cidr = "192.0.2.0/24", host_range = "192.0.2.200-229", dns = ["192.0.2.53"] }
  }
}

experiment = {
  name = "rejected"

  riptide = {
    source = "release:0.16.2"
    env = {
      JAVA_OPTS                    = "-Xmx8g"
      RIPTIDE_RECEIVERS_FLOWS_TYPE = "multi"
      RIPTIDE_RECEIVERS_FLOWS_HOST = "0.0.0.0"
      RIPTIDE_RECEIVERS_FLOWS_PORT = "9999"
    }
  }

  services = {
    sut        = { role = "riptide", host = "pve-1", numa_node = 1, vcpus = 8, memory_gb = 16, networks = ["ingest", "store", "mgmt"] }
    clickhouse = { role = "clickhouse", host = "pve-1", numa_node = 1, vcpus = 8, memory_gb = 64, disk_gb = 200, networks = ["store", "mgmt"] }
    loadgen    = { role = "nl6", host = "kvm-1", numa_node = 0, vcpus = 8, memory_gb = 8, networks = ["ingest", "mgmt"] }
    metrics    = { role = "victoriametrics", host = "kvm-1", numa_node = 0, vcpus = 4, memory_gb = 8, disk_gb = 50, networks = ["store", "mgmt"] }
  }
}
