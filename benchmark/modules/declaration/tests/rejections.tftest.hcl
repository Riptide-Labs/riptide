# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# One run per rejection rule. Each changes the base fixture in one place and
# asserts that exactly one violation results and that it is the expected
# message, so a run cannot pass on a different rule than the one it names.

run "fixture" {
  module {
    source = "./tests/fixture"
  }
}

run "unknown_service_key_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { services = merge(run.fixture.raw.services, { sut = merge(run.fixture.raw.services.sut, { hugepage = true }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "service sut: unknown key \"hugepage\"")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "unknown_host_key_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { hosts = merge(run.fixture.raw.hosts, { lechuck = merge(run.fixture.raw.hosts.lechuck, { password = "secret" }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "host lechuck: unknown key \"password\"")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "unknown_network_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { networks = merge(run.fixture.raw.networks, { spare = { vlan = 27 } }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "networks: unknown network \"spare\"")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "unknown_network_key_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { networks = merge(run.fixture.raw.networks, { mgmt = merge(run.fixture.raw.networks.mgmt, { gw = "192.168.11.1" }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "network mgmt: unknown key \"gw\"")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "unknown_top_level_key_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { owner = "ronny" })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "experiment: unknown key \"owner\"")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "unknown_riptide_key_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { riptide = merge(run.fixture.raw.riptide, { version = "0.16.2" }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "riptide: unknown key \"version\"")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "name_outside_label_charset_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { name = "Flow_Capacity" })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "experiment name \"Flow_Capacity\" may contain only lowercase letters, digits and \"-\"")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "malformed_riptide_source_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { riptide = { source = "latest" } })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "riptide source \"latest\" must be release:X.Y.Z or deb:<path>")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "empty_ssh_keys_are_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { ssh_keys = [] })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "no SSH key: ssh_keys is empty and the SSH agent holds none; apply reaches every VM over SSH as user bench")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "unknown_role_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { services = merge(run.fixture.raw.services, { metrics = merge(run.fixture.raw.services.metrics, { role = "kafka" }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "service metrics: role \"kafka\" is not one of riptide, clickhouse, nl6, victoriametrics")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "missing_clickhouse_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { services = { for k, v in run.fixture.raw.services : k => v if k != "clickhouse" } })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "exactly 1 clickhouse service required, found 0")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "second_nl6_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { services = merge(run.fixture.raw.services, { loadgen2 = { role = "nl6", host = "lechuck", numa_node = 0, vcpus = 2, memory_gb = 2, networks = ["ingest", "mgmt"] } }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "at most 1 nl6 service allowed, found 2")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "unknown_provider_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { hosts = merge(run.fixture.raw.hosts, { lechuck = merge(run.fixture.raw.hosts.lechuck, { provider = "vmware", bridges = { ingest = "vmbr0", store = "vmbr0", mgmt = "vmbr0" } }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "host lechuck: provider \"vmware\" is not libvirt or proxmox")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "libvirt_host_without_uri_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { hosts = merge(run.fixture.raw.hosts, { mad-monkey = { for k, v in run.fixture.raw.hosts.mad-monkey : k => v if k != "uri" } }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "host mad-monkey: a libvirt host needs uri")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "proxmox_host_without_node_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { hosts = merge(run.fixture.raw.hosts, { lechuck = { for k, v in run.fixture.raw.hosts.lechuck : k => v if k != "node" } }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "host lechuck: a Proxmox host needs node")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "proxmox_host_without_datastore_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { hosts = merge(run.fixture.raw.hosts, { lechuck = { for k, v in run.fixture.raw.hosts.lechuck : k => v if k != "datastore" } }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "host lechuck: a Proxmox host needs datastore for VM disks")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "non_numeric_cpu_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { hosts = merge(run.fixture.raw.hosts, { mad-monkey = merge(run.fixture.raw.hosts.mad-monkey, { numa = { 0 = ["0,8", "1,9", "2,10", "3,11", "4,12", "5,13", "6,14", "7,x"] } }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "host mad-monkey: numa lists a CPU that is not a number")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "cpu_in_two_cores_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { hosts = merge(run.fixture.raw.hosts, { lechuck = merge(run.fixture.raw.hosts.lechuck, { numa = { 0 = run.fixture.raw.hosts.lechuck.numa["0"], 1 = concat(["1,24"], slice(run.fixture.raw.hosts.lechuck.numa["1"], 1, 12)) } }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "host lechuck: numa lists a CPU in more than one core")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "mixed_thread_counts_are_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { hosts = merge(run.fixture.raw.hosts, { mad-monkey = merge(run.fixture.raw.hosts.mad-monkey, { numa = { 0 = ["0,8", "1,9", "2,10", "3,11", "4,12", "5,13", "6,14", "7"] } }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "host mad-monkey: cores list different thread counts")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "single_core_node_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { hosts = merge(run.fixture.raw.hosts, { lechuck = merge(run.fixture.raw.hosts.lechuck, { numa = merge(run.fixture.raw.hosts.lechuck.numa, { 2 = ["48,49"] }) }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "host lechuck: NUMA node 2 declares 1 core, needs at least 2 (one is reserved)")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "undeclared_host_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { services = merge(run.fixture.raw.services, { metrics = merge(run.fixture.raw.services.metrics, { host = "guybrush" }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "service metrics: host \"guybrush\" is not declared")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "undeclared_numa_node_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { services = merge(run.fixture.raw.services, { sut = merge(run.fixture.raw.services.sut, { numa_node = 2 }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "service sut: host lechuck declares no NUMA node 2")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "non_positive_vcpus_are_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { services = merge(run.fixture.raw.services, { metrics = merge(run.fixture.raw.services.metrics, { vcpus = 0 }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "service metrics: vcpus must be positive")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "non_positive_memory_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { services = merge(run.fixture.raw.services, { metrics = merge(run.fixture.raw.services.metrics, { memory_gb = 0 }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "service metrics: memory_gb must be positive")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "vcpus_splitting_a_core_are_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { services = merge(run.fixture.raw.services, { metrics = merge(run.fixture.raw.services.metrics, { vcpus = 5 }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "service metrics: vcpus 5 is not a multiple of 2 threads per core on host mad-monkey")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "overcommitted_node_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { services = merge(run.fixture.raw.services, { clickhouse = merge(run.fixture.raw.services.clickhouse, { vcpus = 24 }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "host lechuck NUMA node 0: services clickhouse need 12 cores, 11 available (one of 12 is reserved)")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "sut_sharing_its_numa_node_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { services = merge(run.fixture.raw.services, { clickhouse = merge(run.fixture.raw.services.clickhouse, { numa_node = 1, vcpus = 8 }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "service sut is the system under test and shares host lechuck NUMA node 1 with service clickhouse")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "missing_bridge_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { hosts = merge(run.fixture.raw.hosts, { mad-monkey = merge(run.fixture.raw.hosts.mad-monkey, { bridges = { ingest = "br-vlan24", mgmt = "br0" } }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "service metrics: host mad-monkey has no bridge for network store")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "missing_role_network_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { services = merge(run.fixture.raw.services, { loadgen = merge(run.fixture.raw.services.loadgen, { networks = ["mgmt"] }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "service loadgen: role nl6 needs network ingest")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "unknown_service_network_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { services = merge(run.fixture.raw.services, { sut = merge(run.fixture.raw.services.sut, { networks = ["ingest", "store", "mgmt", "exporters"] }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "service sut: network \"exporters\" is not one of ingest, store, mgmt")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "invalid_cidr_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { networks = merge(run.fixture.raw.networks, { store = merge(run.fixture.raw.networks.store, { cidr = "172.25.0.0/40" }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "network store: \"172.25.0.0/40\" is not an IPv4 CIDR")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "overlapping_networks_are_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { networks = merge(run.fixture.raw.networks, { store = merge(run.fixture.raw.networks.store, { cidr = "172.24.128.0/17" }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "networks ingest (172.24.0.0/16) and store (172.24.128.0/17) overlap")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "network_in_dn42_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { networks = merge(run.fixture.raw.networks, { store = merge(run.fixture.raw.networks.store, { cidr = "172.22.0.0/16" }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "network store (172.22.0.0/16) overlaps protected range DN42 172.20.0.0/14")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "network_in_k0s_services_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { networks = merge(run.fixture.raw.networks, { exporters = { cidr = "10.100.0.0/16" } }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "network exporters (10.100.0.0/16) overlaps protected range k0s services 10.96.0.0/12")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "small_mgmt_range_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { networks = merge(run.fixture.raw.networks, { mgmt = merge(run.fixture.raw.networks.mgmt, { host_range = "192.168.11.200-202" }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "mgmt host_range 192.168.11.200-202 holds 3 addresses for 4 services: clickhouse, loadgen, metrics, sut")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "mgmt_range_outside_cidr_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { networks = merge(run.fixture.raw.networks, { mgmt = merge(run.fixture.raw.networks.mgmt, { host_range = "192.168.12.200-229" }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "mgmt host_range 192.168.12.200-229 is not inside 192.168.11.0/24")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "malformed_mgmt_range_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { networks = merge(run.fixture.raw.networks, { mgmt = merge(run.fixture.raw.networks.mgmt, { host_range = "192.168.11.200" }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "mgmt host_range \"192.168.11.200\" is not a range like 192.168.11.200-229")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "invalid_env_name_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { riptide = { source = "release:0.16.2", env = { "JAVA OPTS" = "-Xmx1g" } } })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "riptide env: \"JAVA OPTS\" is not an environment variable name")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "lab_network_too_small_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { networks = merge(run.fixture.raw.networks, { ingest = { vlan = 24, cidr = "172.24.0.0/29" } }) })
  }
  # loadgen (index 1) and sut (index 3) join ingest; a /29 has no host 11 or 13.
  assert {
    condition     = length(output.violations) == 2 && contains(output.violations, "network ingest (172.24.0.0/29) has no host number 11 for service loadgen") && contains(output.violations, "network ingest (172.24.0.0/29) has no host number 13 for service sut")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "empty_dns_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { networks = merge(run.fixture.raw.networks, { mgmt = merge(run.fixture.raw.networks.mgmt, { dns = [] }) }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "network mgmt: declare dns, the resolvers the VMs install packages through")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

# A declaration saved before dns existed must still convert, so its lab can be
# destroyed; the missing value is a violation, not a type error.
run "missing_dns_is_rejected" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { networks = merge(run.fixture.raw.networks, { mgmt = { host_range = "192.168.11.200-229" } }) })
  }
  assert {
    condition     = length(output.violations) == 1 && contains(output.violations, "network mgmt: declare dns, the resolvers the VMs install packages through")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}

run "misspelt_dns_names_the_key" {
  command = plan
  variables {
    raw = merge(run.fixture.raw, { networks = merge(run.fixture.raw.networks, { mgmt = { host_range = "192.168.11.200-229", dnss = ["192.168.10.16"] } }) })
  }
  assert {
    condition     = length(output.violations) == 2 && contains(output.violations, "network mgmt: unknown key \"dnss\"") && contains(output.violations, "network mgmt: declare dns, the resolvers the VMs install packages through")
    error_message = "violations: ${jsonencode(output.violations)}"
  }
}
