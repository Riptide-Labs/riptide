# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# riptide names its exporters from the load generator's service discovery
# endpoint (nl6 v0.34.1 and later), read over mgmt. The riptide it installs
# must read riptide.discovery.name-labels (#957) and boot on an empty fleet
# (#959), so the source is a local build; the declaration refuses releases up
# to 0.17.0.

experiment = {
  name = "nl6-discovery"

  riptide = {
    source        = "deb:target/riptide_0.18.0_all.deb"
    nl6_discovery = true
  }

  services = {
    sut        = { role = "riptide", host = "pve-1", numa_node = 1, vcpus = 8, memory_gb = 16, networks = ["ingest", "store", "observe", "mgmt"] }
    clickhouse = { role = "clickhouse", host = "pve-1", numa_node = 0, vcpus = 16, memory_gb = 64, disk_gb = 200, networks = ["store", "observe", "mgmt"] }
    loadgen    = { role = "nl6", host = "kvm-1", numa_node = 0, vcpus = 8, memory_gb = 8, networks = ["ingest", "observe", "mgmt"] }
    metrics    = { role = "victoriametrics", host = "kvm-1", numa_node = 0, vcpus = 4, memory_gb = 8, disk_gb = 50, networks = ["store", "observe", "mgmt"] }
    observe    = { role = "observability", host = "kvm-1", numa_node = 0, vcpus = 2, memory_gb = 8, disk_gb = 100, networks = ["observe", "mgmt"] }
  }
}
