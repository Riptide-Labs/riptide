# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Flow capacity: riptide alone on lechuck NUMA node 1, ClickHouse on node 0,
# the load generator and VictoriaMetrics on mad-monkey.
#
# Topology blocks come from benchmark/bin/numa-topology run on each host
# (2026-09-28). lechuck interleaves its nodes: node 0 holds the even CPUs,
# node 1 the odd ones, and each core's second thread is CPU n+24.

experiment = {
  name = "flow-capacity"

  riptide = {
    source = "release:0.16.2"
    env = {
      JAVA_OPTS                    = "-Xmx8g"
      RIPTIDE_RECEIVERS_FLOWS_TYPE = "multi"
      RIPTIDE_RECEIVERS_FLOWS_HOST = "0.0.0.0"
      RIPTIDE_RECEIVERS_FLOWS_PORT = "9999"
    }
  }

  hosts = {
    lechuck = {
      provider  = "proxmox"
      node      = "lechuck"
      address   = "lechuck.labmonkeys.tech"
      datastore = "scummbar"
      numa = {
        0 = ["0,24", "2,26", "4,28", "6,30", "8,32", "10,34", "12,36", "14,38", "16,40", "18,42", "20,44", "22,46"]
        1 = ["1,25", "3,27", "5,29", "7,31", "9,33", "11,35", "13,37", "15,39", "17,41", "19,43", "21,45", "23,47"]
      }
    }
    mad-monkey = {
      provider = "libvirt"
      uri      = "qemu+ssh://root@mad-monkey.labmonkeys.tech/system"
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
    mgmt   = { host_range = "192.168.11.200-229" }
  }

  services = {
    sut        = { role = "riptide", host = "lechuck", numa_node = 1, vcpus = 8, memory_gb = 16, networks = ["ingest", "store", "mgmt"] }
    clickhouse = { role = "clickhouse", host = "lechuck", numa_node = 0, vcpus = 16, memory_gb = 64, disk_gb = 200, networks = ["store", "mgmt"] }
    loadgen    = { role = "nl6", host = "mad-monkey", numa_node = 0, vcpus = 8, memory_gb = 8, networks = ["ingest", "mgmt"] }
    metrics    = { role = "victoriametrics", host = "mad-monkey", numa_node = 0, vcpus = 4, memory_gb = 8, disk_gb = 50, networks = ["store", "mgmt"] }
  }
}
