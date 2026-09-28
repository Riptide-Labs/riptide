# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# The base declaration every test starts from. Host names and addresses are
# placeholders; the core layouts are two real machines read with
# bin/numa-topology: pve-1 has two sockets of 12 cores with SMT, node 0 holding
# the even CPUs and node 1 the odd ones, siblings n and n+24; kvm-1 has one node
# of 8 cores with SMT, siblings n and n+8.
#
# Test runs load it first (run "fixture") and derive variations from
# run.fixture.raw (site and experiment together) or run.fixture.site and
# run.fixture.experiment (apart), since a run block cannot read the file's own
# variables.

locals {
  value = {
    name     = "flow-capacity"
    ssh_keys = ["ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITestKeyOnly bench"]
    riptide  = { source = "release:0.16.2" }
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
      kvm-1 = {
        provider = "libvirt"
        uri      = "qemu+ssh://root@kvm-1.example.org/system"
        numa     = { 0 = ["0,8", "1,9", "2,10", "3,11", "4,12", "5,13", "6,14", "7,15"] }
        bridges  = { ingest = "br-vlan24", store = "br-vlan25", mgmt = "br0" }
      }
    }
    networks = {
      ingest = { vlan = 24 }
      store  = { vlan = 25 }
      mgmt   = { vlan = 11, cidr = "192.0.2.0/24", host_range = "192.0.2.200-229", dns = ["192.0.2.53"] }
    }
    protected_ranges = {
      "DN42"             = "172.20.0.0/14"
      "cluster pods"     = "10.244.0.0/16"
      "cluster services" = "10.96.0.0/12"
    }
    services = {
      sut        = { role = "riptide", host = "pve-1", numa_node = 1, vcpus = 8, memory_gb = 16, networks = ["ingest", "store", "mgmt"] }
      clickhouse = { role = "clickhouse", host = "pve-1", numa_node = 0, vcpus = 16, memory_gb = 64, disk_gb = 200, networks = ["store", "mgmt"] }
      loadgen    = { role = "nl6", host = "kvm-1", numa_node = 0, vcpus = 8, memory_gb = 8, networks = ["ingest", "mgmt"] }
      metrics    = { role = "victoriametrics", host = "kvm-1", numa_node = 0, vcpus = 4, memory_gb = 8, disk_gb = 50, networks = ["store", "mgmt"] }
    }
  }
}

output "raw" {
  value = local.value
}

output "site" {
  value = { for k, v in local.value : k => v if contains(["hosts", "networks", "protected_ranges"], k) }
}

output "experiment" {
  value = { for k, v in local.value : k => v if !contains(["hosts", "networks", "protected_ranges"], k) }
}
