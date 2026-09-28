# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# The base declaration every test starts from, on the lab's real topology read
# from the hosts on 2026-09-28: lechuck has two sockets of 12 cores with SMT,
# node 0 holding the even CPUs and node 1 the odd ones, siblings n and n+24;
# mad-monkey has one node of 8 cores with SMT, siblings n and n+8.
#
# Test runs load it first (run "fixture") and derive variations from
# run.fixture.raw, since a run block cannot read the file's own variables.

locals {
  value = {
    name     = "flow-capacity"
    ssh_keys = ["ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITestKeyOnly bench"]
    riptide  = { source = "release:0.16.2" }
    hosts = {
      lechuck = {
        provider  = "proxmox"
        node      = "lechuck"
        datastore = "scummbar"
        numa = {
          0 = ["0,24", "2,26", "4,28", "6,30", "8,32", "10,34", "12,36", "14,38", "16,40", "18,42", "20,44", "22,46"]
          1 = ["1,25", "3,27", "5,29", "7,31", "9,33", "11,35", "13,37", "15,39", "17,41", "19,43", "21,45", "23,47"]
        }
      }
      mad-monkey = {
        provider = "libvirt"
        uri      = "qemu+ssh://root@mad-monkey.labmonkeys.tech/system"
        numa     = { 0 = ["0,8", "1,9", "2,10", "3,11", "4,12", "5,13", "6,14", "7,15"] }
        bridges  = { ingest = "br-vlan24", store = "br-vlan25", mgmt = "br0" }
      }
    }
    networks = {
      ingest = { vlan = 24 }
      store  = { vlan = 25 }
      mgmt   = { host_range = "192.168.11.200-229", dns = ["192.168.10.16", "192.168.10.53"] }
    }
    services = {
      sut        = { role = "riptide", host = "lechuck", numa_node = 1, vcpus = 8, memory_gb = 16, networks = ["ingest", "store", "mgmt"] }
      clickhouse = { role = "clickhouse", host = "lechuck", numa_node = 0, vcpus = 16, memory_gb = 64, disk_gb = 200, networks = ["store", "mgmt"] }
      loadgen    = { role = "nl6", host = "mad-monkey", numa_node = 0, vcpus = 8, memory_gb = 8, networks = ["ingest", "mgmt"] }
      metrics    = { role = "victoriametrics", host = "mad-monkey", numa_node = 0, vcpus = 4, memory_gb = 8, disk_gb = 50, networks = ["store", "mgmt"] }
    }
  }
}

output "raw" {
  value = local.value
}
