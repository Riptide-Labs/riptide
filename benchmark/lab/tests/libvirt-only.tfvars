# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Two libvirt hosts and no Proxmox host. kvm-2 is a fixture host name.
site = {
  hosts = {
    kvm-1 = {
      provider = "libvirt"
      uri      = "qemu+ssh://root@kvm-1.example.org/system"
      numa     = { 0 = ["0,8", "1,9", "2,10", "3,11", "4,12", "5,13", "6,14", "7,15"] }
      bridges  = { ingest = "br-vlan24", store = "br-vlan25", mgmt = "br0" }
    }
    kvm-2 = {
      provider = "libvirt"
      uri      = "qemu+ssh://root@kvm-2.invalid/system"
      numa     = { 0 = ["0,4", "1,5", "2,6", "3,7"] }
      bridges  = { ingest = "br-vlan24", store = "br-vlan25", mgmt = "br0" }
    }
  }
  networks = {
    ingest = { vlan = 24 }
    store  = { vlan = 25 }
    mgmt   = { vlan = 11, cidr = "192.0.2.0/24", host_range = "192.0.2.200-229", dns = ["192.0.2.53"] }
  }
}

experiment = {
  name    = "idle-proxmox"
  riptide = { source = "release:0.16.2" }
  services = {
    sut        = { role = "riptide", host = "kvm-2", numa_node = 0, vcpus = 4, memory_gb = 8, networks = ["ingest", "store", "mgmt"] }
    clickhouse = { role = "clickhouse", host = "kvm-1", numa_node = 0, vcpus = 4, memory_gb = 8, networks = ["store", "mgmt"] }
    loadgen    = { role = "nl6", host = "kvm-1", numa_node = 0, vcpus = 4, memory_gb = 4, networks = ["ingest", "mgmt"] }
    metrics    = { role = "victoriametrics", host = "kvm-1", numa_node = 0, vcpus = 2, memory_gb = 4, networks = ["store", "mgmt"] }
  }
}
