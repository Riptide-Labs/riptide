# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# experiments/flow-capacity.tfvars with the observability VM on the SUT's
# NUMA node. Pins the gate in outputs.tf: nothing indexes local.services while
# the declaration is rejected, observability or not.

experiment = {
  name = "rejected-observed"

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
    sut        = { role = "riptide", host = "pve-1", numa_node = 1, vcpus = 8, memory_gb = 16, networks = ["ingest", "store", "observe", "mgmt"] }
    clickhouse = { role = "clickhouse", host = "pve-1", numa_node = 0, vcpus = 16, memory_gb = 64, disk_gb = 200, networks = ["store", "observe", "mgmt"] }
    loadgen    = { role = "nl6", host = "kvm-1", numa_node = 0, vcpus = 8, memory_gb = 8, networks = ["ingest", "observe", "mgmt"] }
    metrics    = { role = "victoriametrics", host = "kvm-1", numa_node = 0, vcpus = 4, memory_gb = 8, disk_gb = 50, networks = ["store", "observe", "mgmt"] }
    observe    = { role = "observability", host = "pve-1", numa_node = 1, vcpus = 2, memory_gb = 8, disk_gb = 100, networks = ["observe", "mgmt"] }
  }
}
