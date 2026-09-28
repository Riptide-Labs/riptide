# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Flow knee: riptide with 4 vCPUs and 8 GiB alone on NUMA node 1 of a Proxmox
# host, the nl6 load generator on node 0 of the same host, ClickHouse and the
# observability VM on a libvirt host. The ladder in benchmark/ladder grows a
# CRS-X fleet (1:1:2 NetFlow v5, v9, IPFIX) until riptide loses or lags.
# Host keys pve-1 and kvm-1 name hosts in the site file.

experiment = {
  name = "flow-knee"

  riptide = {
    source = "release:0.17.0"
    env = {
      JAVA_OPTS                    = "-Xmx5g"
      RIPTIDE_CLICKHOUSE_DATABASE  = "riptide_knee"
      RIPTIDE_RECEIVERS_FLOWS_TYPE = "multi"
      RIPTIDE_RECEIVERS_FLOWS_HOST = "0.0.0.0"
      RIPTIDE_RECEIVERS_FLOWS_PORT = "9999"
    }
  }

  services = {
    sut        = { role = "riptide", host = "pve-1", numa_node = 1, vcpus = 4, memory_gb = 8, networks = ["ingest", "store", "observe", "mgmt"] }
    loadgen    = { role = "nl6", host = "pve-1", numa_node = 0, vcpus = 8, memory_gb = 16, networks = ["ingest", "observe", "mgmt"] }
    clickhouse = { role = "clickhouse", host = "kvm-1", numa_node = 0, vcpus = 8, memory_gb = 16, disk_gb = 200, networks = ["store", "observe", "mgmt"] }
    observe    = { role = "observability", host = "kvm-1", numa_node = 0, vcpus = 4, memory_gb = 8, disk_gb = 100, networks = ["observe", "mgmt"] }
  }
}
