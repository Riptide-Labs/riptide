# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Flow capacity: riptide alone on NUMA node 1 of a two-node Proxmox host,
# ClickHouse on node 0, the load generator and VictoriaMetrics on a libvirt
# host. The host keys pve-1 and kvm-1 name hosts in the site file
# (benchmark/site.tfvars, see site.example.tfvars); the site file says where
# they are and what they hold.

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

  services = {
    sut        = { role = "riptide", host = "pve-1", numa_node = 1, vcpus = 8, memory_gb = 16, networks = ["ingest", "store", "mgmt"] }
    clickhouse = { role = "clickhouse", host = "pve-1", numa_node = 0, vcpus = 16, memory_gb = 64, disk_gb = 200, networks = ["store", "mgmt"] }
    loadgen    = { role = "nl6", host = "kvm-1", numa_node = 0, vcpus = 8, memory_gb = 8, networks = ["ingest", "mgmt"] }
    metrics    = { role = "victoriametrics", host = "kvm-1", numa_node = 0, vcpus = 4, memory_gb = 8, disk_gb = 50, networks = ["store", "mgmt"] }
  }
}
