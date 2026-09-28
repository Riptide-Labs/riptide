# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Two libvirt hosts and no Proxmox host. guybrush is a fixture host name.
experiment = {
  name    = "idle-proxmox"
  riptide = { source = "release:0.16.2" }
  hosts = {
    mad-monkey = {
      provider = "libvirt"
      uri      = "qemu+ssh://root@mad-monkey.labmonkeys.tech/system"
      numa     = { 0 = ["0,8", "1,9", "2,10", "3,11", "4,12", "5,13", "6,14", "7,15"] }
      bridges  = { ingest = "br-vlan24", store = "br-vlan25", mgmt = "br0" }
    }
    guybrush = {
      provider = "libvirt"
      uri      = "qemu+ssh://root@guybrush.invalid/system"
      numa     = { 0 = ["0,4", "1,5", "2,6", "3,7"] }
      bridges  = { ingest = "br-vlan24", store = "br-vlan25", mgmt = "br0" }
    }
  }
  networks = {
    ingest = { vlan = 24 }
    store  = { vlan = 25 }
    mgmt   = { host_range = "192.168.11.200-229" }
  }
  services = {
    sut        = { role = "riptide", host = "guybrush", numa_node = 0, vcpus = 4, memory_gb = 8, networks = ["ingest", "store", "mgmt"] }
    clickhouse = { role = "clickhouse", host = "mad-monkey", numa_node = 0, vcpus = 4, memory_gb = 8, networks = ["store", "mgmt"] }
    loadgen    = { role = "nl6", host = "mad-monkey", numa_node = 0, vcpus = 4, memory_gb = 4, networks = ["ingest", "mgmt"] }
    metrics    = { role = "victoriametrics", host = "mad-monkey", numa_node = 0, vcpus = 2, memory_gb = 4, networks = ["store", "mgmt"] }
  }
}
