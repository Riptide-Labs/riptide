# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Everything derived from the declaration lives here: the checks, core
# placement, addresses and routes. The module creates no resources; the lab
# root turns `violations` into one precondition and reads the rest.

module "typed" {
  source     = "./typed"
  experiment = local.combined
}

locals {
  x = module.typed.experiment

  roles              = ["riptide", "clickhouse", "nl6", "victoriametrics", "observability"]
  joinable_networks  = ["ingest", "store", "observe", "mgmt"]
  role_networks      = { riptide = ["ingest", "store", "mgmt"], clickhouse = ["store", "mgmt"], nl6 = ["ingest", "mgmt"], victoriametrics = ["store", "mgmt"], observability = ["observe", "mgmt"] }
  role_count_exactly = { riptide = 1, clickhouse = 1 }
  role_count_at_most = { nl6 = 1, victoriametrics = 1, observability = 1 }

  # The ranges the tool itself uses; the site adds its own (other networks,
  # clusters, overlays) as protected_ranges.
  tool_ranges = {
    "Docker default"   = "172.17.0.0/16"
    "benchmark Docker" = "172.28.0.0/14"
  }
  protected_ranges = merge(local.x.protected_ranges, local.tool_ranges)

  # A site range under a reserved name would be replaced by the tool's own.
  reserved_violations = [
    for n in keys(local.x.protected_ranges) : "protected range ${n}: the name is reserved for the tool's range ${local.tool_ranges[n]}; rename it"
    if contains(keys(local.tool_ranges), n)
  ]

  # --- site and experiment --------------------------------------------------
  # The lab root passes the two files apart; the declaration is their merge.
  # Each key belongs to one of them, so a lab's hosts and networks never land
  # in a committed experiment file.

  split           = var.site != null
  site_keys       = ["hosts", "networks", "protected_ranges"]
  experiment_keys = ["name", "ssh_keys", "riptide", "services"]
  # merge() skips a null site, so this holds apart or together.
  combined = merge(var.site, var.raw)

  misplaced_keys = local.split ? concat(
    [for k in keys(var.raw) : "experiment file: \"${k}\" belongs in the site file" if contains(local.site_keys, k)],
    [for k in keys(var.site) : "site file: \"${k}\" belongs in the experiment file" if contains(local.experiment_keys, k)],
  ) : []

  # --- unknown keys ---------------------------------------------------------

  allowed_keys = {
    experiment = concat(local.experiment_keys, local.site_keys)
    riptide    = ["source", "env", "nl6_discovery"]
    host       = ["provider", "numa", "bridges", "uri", "pool", "node", "address", "datastore"]
    service    = ["role", "host", "numa_node", "vcpus", "memory_gb", "networks", "disk_gb", "hugepages"]
    networks = {
      ingest    = ["vlan", "cidr"]
      store     = ["vlan", "cidr"]
      observe   = ["vlan", "cidr"]
      exporters = ["cidr"]
      mgmt      = ["host_range", "vlan", "cidr", "gateway", "dns"]
    }
  }

  raw_hosts    = try(local.combined.hosts, {})
  raw_networks = try(local.combined.networks, {})
  raw_services = try(local.combined.services, {})

  unknown_keys = concat(
    [for k in keys(local.combined) : "declaration: unknown key \"${k}\"" if !contains(local.allowed_keys.experiment, k)],
    [for k in keys(try(local.combined.riptide, {})) : "riptide: unknown key \"${k}\"" if !contains(local.allowed_keys.riptide, k)],
    flatten([for h, v in local.raw_hosts : [for k in keys(v) : "host ${h}: unknown key \"${k}\"" if !contains(local.allowed_keys.host, k)]]),
    flatten([for s, v in local.raw_services : [for k in keys(v) : "service ${s}: unknown key \"${k}\"" if !contains(local.allowed_keys.service, k)]]),
    [for n in keys(local.raw_networks) : "networks: unknown network \"${n}\"" if !contains(keys(local.allowed_keys.networks), n)],
    flatten([for n, v in local.raw_networks : [for k in keys(v) : "network ${n}: unknown key \"${k}\"" if !contains(local.allowed_keys.networks[n], k)] if contains(keys(local.allowed_keys.networks), n)]),
  )

  # --- names, roles, sources ------------------------------------------------

  name_violations = can(regex("^[a-z0-9-]+$", local.x.name)) ? [] : [
    "experiment name \"${local.x.name}\" may contain only lowercase letters, digits and \"-\"",
  ]

  source_violations = can(regex("^(release:[0-9]+\\.[0-9]+\\.[0-9]+|deb:.+)$", local.x.riptide.source)) ? [] : [
    "riptide source \"${local.x.riptide.source}\" must be release:X.Y.Z or deb:<path>",
  ]

  env_violations = [
    for k in keys(local.x.riptide.env) : "riptide env: \"${k}\" is not an environment variable name"
    if !can(regex("^[A-Za-z_][A-Za-z0-9_]*$", k))
  ]

  # The gateway is not assumed to answer DNS: VLAN 11's does not, and the first
  # real apply failed at package installation on every VM because of it.
  dns_violations = length(coalesce(local.x.networks.mgmt.dns, [])) > 0 ? [] : ["network mgmt: declare dns, the resolvers the VMs install packages through"]

  ssh_keys           = distinct(concat(var.agent_ssh_keys, local.x.ssh_keys))
  ssh_key_violations = length(local.ssh_keys) > 0 ? [] : ["no SSH key: ssh_keys is empty and the SSH agent holds none; apply reaches every VM over SSH as user bench"]

  role_violations = [
    for s, v in local.x.services : "service ${s}: role \"${v.role}\" is not one of ${join(", ", local.roles)}"
    if !contains(local.roles, v.role)
  ]

  role_count_violations = concat(
    [for r, n in local.role_count_exactly : "exactly ${n} ${r} service required, found ${length([for s, v in local.x.services : s if v.role == r])}"
    if length([for s, v in local.x.services : s if v.role == r]) != n],
    [for r, n in local.role_count_at_most : "at most ${n} ${r} service allowed, found ${length([for s, v in local.x.services : s if v.role == r])}"
    if length([for s, v in local.x.services : s if v.role == r]) > n],
  )

  # riptide reads its exporter names from nl6's service discovery endpoint,
  # which needs an nl6 service to read, and a riptide that picks the name label
  # (#957) and boots on the still-empty fleet (#959). No release up to 0.17.0
  # has either; an older one would never answer /readyz, naming neither cause.
  discovery_release = try([for p in split(".", trimprefix(local.x.riptide.source, "release:")) : tonumber(p)], null)
  discovery_violations = !local.x.riptide.nl6_discovery ? [] : concat(
    length([for s, v in local.x.services : s if v.role == "nl6"]) == 0 ? [
      "riptide nl6_discovery: no service has role nl6, so there is no fleet to discover",
    ] : [],
    startswith(local.x.riptide.source, "release:") && try(local.discovery_release[0] == 0 && local.discovery_release[1] <= 17, false) ? [
      "riptide nl6_discovery: ${local.x.riptide.source} predates riptide.discovery.name-labels (#957) and booting on an empty fleet (#959); use deb:<path> or a later release",
    ] : [],
  )

  # --- observe network and observability role --------------------------------

  observe_enabled = local.x.networks.observe != null
  observability   = try(one([for s, v in local.x.services : s if v.role == "observability"]), null)
  observe_joiners = sort([for s, v in local.x.services : s if contains(v.networks, "observe")])

  observe_violations = concat(
    local.observe_enabled && try(local.x.networks.observe.vlan, null) == null ? ["network observe: declare vlan, the observability network's VLAN ID"] : [],
    !local.observe_enabled && length(local.observe_joiners) > 0 ? [
      "network observe: the site declares no observe network, but services join it: ${join(", ", local.observe_joiners)}",
    ] : [],
    [for s, v in local.x.services : "service ${s}: role observability needs disk_gb for the data that survives bench destroy" if v.role == "observability" && v.disk_gb == null],
    # The observability service missing observe is reported by role_networks.
    local.observability == null ? [] : [
      for s, v in local.x.services : "service ${s}: network observe is required while the lab has an observability service"
      if !contains(v.networks, "observe") && v.role != "observability"
    ],
  )

  # --- hosts and topology ---------------------------------------------------

  host_violations = flatten([
    for h, v in local.x.hosts : concat(
      contains(["libvirt", "proxmox"], v.provider) ? [] : ["host ${h}: provider \"${v.provider}\" is not libvirt or proxmox"],
      v.provider == "libvirt" && v.uri == null ? ["host ${h}: a libvirt host needs uri"] : [],
      v.provider == "proxmox" && v.node == null ? ["host ${h}: a Proxmox host needs node"] : [],
      v.provider == "proxmox" && v.datastore == null ? ["host ${h}: a Proxmox host needs datastore for VM disks"] : [],
      length(v.numa) > 0 ? [] : ["host ${h}: numa declares no node"],
    )
  ])

  # host => node (string) => list of cores, each a list of host CPU numbers
  cores = {
    for h, v in local.x.hosts : h => {
      for n, cs in v.numa : n => [for c in cs : [for cpu in split(",", c) : try(tonumber(trimspace(cpu)), -1)]]
    }
  }

  host_cpus = { for h, nodes in local.cores : h => flatten(values(nodes)) }

  # Threads per core: the size of the host's first core. A host whose cores
  # differ in size is rejected below, so the first one speaks for all.
  threads = { for h, nodes in local.cores : h => try(length(values(nodes)[0][0]), 1) }

  topology_violations = flatten([
    for h, nodes in local.cores : concat(
      contains(local.host_cpus[h], -1) ? ["host ${h}: numa lists a CPU that is not a number"] : [],
      length(distinct(local.host_cpus[h])) == length(local.host_cpus[h]) ? [] : ["host ${h}: numa lists a CPU in more than one core"],
      length(distinct([for c in flatten([for n, cs in nodes : [for core in cs : length(core)]]) : c])) <= 1 ? [] : ["host ${h}: cores list different thread counts"],
      [for n, cs in nodes : "host ${h}: NUMA node ${n} declares ${length(cs)} core, needs at least 2 (one is reserved)" if length(cs) < 2],
    )
  ])

  # --- placement ------------------------------------------------------------

  service_names = sort(keys(local.x.services))
  service_index = { for i, s in local.service_names : s => i }

  host_known = { for s, v in local.x.services : s => contains(keys(local.x.hosts), v.host) }
  node_key   = { for s, v in local.x.services : s => tostring(v.numa_node) }
  node_known = { for s, v in local.x.services : s => local.host_known[s] && contains(keys(try(local.cores[v.host], {})), local.node_key[s]) }

  placement_violations = flatten([
    for s, v in local.x.services : concat(
      local.host_known[s] ? [] : ["service ${s}: host \"${v.host}\" is not declared"],
      !local.host_known[s] || local.node_known[s] ? [] : ["service ${s}: host ${v.host} declares no NUMA node ${v.numa_node}"],
      v.vcpus > 0 ? [] : ["service ${s}: vcpus must be positive"],
      v.memory_gb > 0 ? [] : ["service ${s}: memory_gb must be positive"],
      !local.host_known[s] || v.vcpus % local.threads[v.host] == 0 ? [] : [
        "service ${s}: vcpus ${v.vcpus} is not a multiple of ${local.threads[v.host]} threads per core on host ${v.host}",
      ],
    )
  ])

  cores_needed = { for s, v in local.x.services : s => local.host_known[s] ? ceil(v.vcpus / local.threads[v.host]) : 0 }

  # Services sharing a host NUMA node, in name order. Cores are handed out in
  # that order, so adding a service never moves one that sorts before it.
  peers = { for s, v in local.x.services : s => [for p in local.service_names : p if local.x.services[p].host == v.host && local.x.services[p].numa_node == v.numa_node] }

  # The node's last core is reserved for emulator threads and the host.
  usable_cores   = { for s, v in local.x.services : s => local.node_known[s] ? slice(local.cores[v.host][local.node_key[s]], 0, length(local.cores[v.host][local.node_key[s]]) - 1) : [] }
  reserved_core  = { for s, v in local.x.services : s => local.node_known[s] ? local.cores[v.host][local.node_key[s]][length(local.cores[v.host][local.node_key[s]]) - 1] : [] }
  core_offset    = { for s, v in local.x.services : s => sum(concat([0], [for p in local.peers[s] : local.cores_needed[p] if local.service_index[p] < local.service_index[s]])) }
  assigned_cores = { for s, v in local.x.services : s => slice(local.usable_cores[s], min(local.core_offset[s], length(local.usable_cores[s])), min(local.core_offset[s] + local.cores_needed[s], length(local.usable_cores[s]))) }

  capacity_violations = distinct(flatten([
    for s, v in local.x.services : (
      local.node_known[s] && sum(concat([0], [for p in local.peers[s] : local.cores_needed[p]])) > length(local.usable_cores[s])
      ? ["host ${v.host} NUMA node ${v.numa_node}: services ${join(", ", local.peers[s])} need ${sum(concat([0], [for p in local.peers[s] : local.cores_needed[p]]))} cores, ${length(local.usable_cores[s])} available (one of ${length(local.usable_cores[s]) + 1} is reserved)"]
      : []
    )
  ]))

  isolation_violations = flatten([
    for s, v in local.x.services : [
      for p in local.peers[s] : "service ${s} is the system under test and shares host ${v.host} NUMA node ${v.numa_node} with service ${p}"
      if p != s
    ] if v.role == "riptide"
  ])

  # --- networks -------------------------------------------------------------

  bridges = {
    for h, v in local.x.hosts : h => v.provider == "proxmox" ? merge({ for n in local.joinable_networks : n => "vmbr0" }, v.bridges) : v.bridges
  }

  network_violations = flatten([
    for s, v in local.x.services : concat(
      [for n in v.networks : "service ${s}: network \"${n}\" is not one of ${join(", ", local.joinable_networks)}" if !contains(local.joinable_networks, n)],
      [for n in v.networks : "service ${s}: host ${v.host} has no bridge for network ${n}"
      if local.host_known[s] && contains(local.joinable_networks, n) && !contains(keys(try(local.bridges[v.host], {})), n)],
      [for n in lookup(local.role_networks, v.role, []) : "service ${s}: role ${v.role} needs network ${n}" if !contains(v.networks, n)],
    )
  ])

  cidrs = merge(
    {
      ingest    = local.x.networks.ingest.cidr
      store     = local.x.networks.store.cidr
      exporters = local.x.networks.exporters.cidr
      mgmt      = local.x.networks.mgmt.cidr
    },
    local.observe_enabled ? { observe = local.x.networks.observe.cidr } : {},
  )

  mgmt_declared_violations = concat(
    local.x.networks.mgmt.vlan != null ? [] : ["network mgmt: declare vlan, the management network's VLAN ID"],
    local.x.networks.mgmt.cidr != null ? [] : ["network mgmt: declare cidr, the management network's address range"],
  )

  # Every declared range, networks and protected ranges alike, checked once.
  ranges = concat(
    [for n, c in local.cidrs : { label = "network ${n}", cidr = c } if c != null],
    [for n, c in local.x.protected_ranges : { label = "protected range ${n}", cidr = c }],
  )
  cidr_violations = [
    for r in local.ranges : "${r.label}: \"${r.cidr}\" is not an IPv4 CIDR"
    if !(can(cidrhost(r.cidr, 0)) && length(split(".", split("/", r.cidr)[0])) == 4)
  ]
  cidrs_valid = length(local.cidr_violations) == 0 && local.x.networks.mgmt.cidr != null

  # A CIDR's first and last address as integers. Networks and protected ranges
  # are kept apart: a site may name a protected range like a network.
  net_first  = { for n, c in local.cidrs : n => local.cidrs_valid ? sum([for i, o in split(".", cidrhost(c, 0)) : tonumber(o) * pow(256, 3 - i)]) : 0 }
  net_last   = { for n, c in local.cidrs : n => local.cidrs_valid ? local.net_first[n] + pow(2, 32 - tonumber(split("/", c)[1])) - 1 : 0 }
  prot_first = { for n, c in local.protected_ranges : n => local.cidrs_valid ? sum([for i, o in split(".", cidrhost(c, 0)) : tonumber(o) * pow(256, 3 - i)]) : 0 }
  prot_last  = { for n, c in local.protected_ranges : n => local.cidrs_valid ? local.prot_first[n] + pow(2, 32 - tonumber(split("/", c)[1])) - 1 : 0 }

  network_names = sort(keys(local.cidrs))

  overlap_violations = local.cidrs_valid ? concat(
    flatten([for i, a in local.network_names : [
      for b in slice(local.network_names, i + 1, length(local.network_names)) :
      "networks ${a} (${local.cidrs[a]}) and ${b} (${local.cidrs[b]}) overlap"
      if local.net_first[a] <= local.net_last[b] && local.net_first[b] <= local.net_last[a]
    ]]),
    flatten([for a in local.network_names : [
      for p in keys(local.protected_ranges) : "network ${a} (${local.cidrs[a]}) overlaps protected range ${p} (${local.protected_ranges[p]})"
      if local.net_first[a] <= local.prot_last[p] && local.prot_first[p] <= local.net_last[a]
    ]]),
  ) : []

  # Lab networks number services from host 10; a range too small for that
  # would leave the service without an address.
  address_violations = local.cidrs_valid ? flatten([
    for s, v in local.x.services : [
      for n in v.networks : "network ${n} (${local.cidrs[n]}) has no host number ${10 + local.service_index[s]} for service ${s}"
      if contains(["ingest", "store", "observe"], n) && contains(keys(local.cidrs), n) && !can(cidrhost(local.cidrs[n], 10 + local.service_index[s]))
    ]
  ]) : []

  # mgmt host range: "a.b.c.d-e" or "a.b.c.d-a.b.c.e".
  mgmt_range_parts = split("-", local.x.networks.mgmt.host_range)
  mgmt_start_ip    = trimspace(local.mgmt_range_parts[0])
  mgmt_end_ip = length(local.mgmt_range_parts) != 2 ? "" : (
    length(split(".", local.mgmt_range_parts[1])) == 4 ? trimspace(local.mgmt_range_parts[1]) :
    join(".", concat(slice(split(".", local.mgmt_start_ip), 0, 3), [trimspace(local.mgmt_range_parts[1])]))
  )
  mgmt_start = try(sum([for i, o in split(".", local.mgmt_start_ip) : tonumber(o) * pow(256, 3 - i)]), -1)
  mgmt_end   = try(sum([for i, o in split(".", local.mgmt_end_ip) : tonumber(o) * pow(256, 3 - i)]), -1)

  mgmt_violations = local.cidrs_valid ? concat(
    local.mgmt_start < 0 || local.mgmt_end < 0 ? ["mgmt host_range \"${local.x.networks.mgmt.host_range}\" is not a range like 192.0.2.200-229"] : [],
    local.mgmt_start >= 0 && local.mgmt_end >= 0 && (local.mgmt_start < local.net_first.mgmt || local.mgmt_end > local.net_last.mgmt) ? ["mgmt host_range ${local.x.networks.mgmt.host_range} is not inside ${local.cidrs.mgmt}"] : [],
    local.mgmt_start >= 0 && local.mgmt_end >= 0 && local.mgmt_end - local.mgmt_start + 1 < length(local.service_names) ? [
      "mgmt host_range ${local.x.networks.mgmt.host_range} holds ${max(0, local.mgmt_end - local.mgmt_start + 1)} addresses for ${length(local.service_names)} services: ${join(", ", local.service_names)}",
    ] : [],
  ) : []

  violations = concat(
    local.misplaced_keys,
    local.unknown_keys,
    local.name_violations,
    local.source_violations,
    local.env_violations,
    local.ssh_key_violations,
    local.role_violations,
    local.role_count_violations,
    local.discovery_violations,
    local.observe_violations,
    local.host_violations,
    local.topology_violations,
    local.placement_violations,
    local.capacity_violations,
    local.isolation_violations,
    local.network_violations,
    local.mgmt_declared_violations,
    local.reserved_violations,
    local.cidr_violations,
    local.overlap_violations,
    local.address_violations,
    local.mgmt_violations,
    local.dns_violations,
  )

  # --- addresses and routes -------------------------------------------------

  prefix = { for n, c in local.cidrs : n => try(split("/", c)[1], "32") }

  # Lab networks: host number 10 + index. mgmt: range start + index.
  addresses = {
    for s, v in local.x.services : s => {
      for n in v.networks : n => (
        n == "mgmt"
        ? try(cidrhost(local.cidrs.mgmt, local.mgmt_start - local.net_first.mgmt + local.service_index[s]), null)
        : try(cidrhost(local.cidrs[n], 10 + local.service_index[s]), null)
      ) if contains(local.joinable_networks, n) && contains(keys(local.cidrs), n)
    }
  }

  # Locally administered, derived from experiment, service and network, so a
  # re-created VM keeps its MACs and network-config can match NICs by them.
  macs = {
    for s, v in local.x.services : s => {
      for n in local.joinable_networks : n => format("52:54:00:%s:%s:%s",
        substr(md5("${local.x.name}/${s}/${n}"), 0, 2),
        substr(md5("${local.x.name}/${s}/${n}"), 2, 2),
        substr(md5("${local.x.name}/${s}/${n}"), 4, 2),
      ) if contains(v.networks, n)
    }
  }

  mgmt_gateway = try(coalesce(local.x.networks.mgmt.gateway, try(cidrhost(local.cidrs.mgmt, 1), "")), "")
  mgmt_dns     = coalesce(local.x.networks.mgmt.dns, [])

  sut     = try(one([for s, v in local.x.services : s if v.role == "riptide"]), null)
  loadgen = try(one([for s, v in local.x.services : s if v.role == "nl6"]), null)

  routes = {
    for s, v in local.x.services : s => (
      v.role == "riptide" && local.loadgen != null
      ? [{ network = "ingest", to = local.cidrs.exporters, via = try(local.addresses[local.loadgen].ingest, null) }]
      : []
    )
  }

  services = {
    for s, v in local.x.services : s => {
      name        = s
      role        = v.role
      host        = v.host
      provider    = try(local.x.hosts[v.host].provider, null)
      numa_node   = v.numa_node
      vcpus       = v.vcpus
      memory_gb   = v.memory_gb
      disk_gb     = v.disk_gb
      hugepages   = v.hugepages
      networks    = [for n in local.joinable_networks : n if contains(v.networks, n)]
      threads     = try(local.threads[v.host], 1)
      cores       = length(local.assigned_cores[s])
      vcpu_pins   = flatten(local.assigned_cores[s])
      emulator    = local.reserved_core[s]
      addresses   = local.addresses[s]
      macs        = local.macs[s]
      prefix      = local.prefix
      bridges     = { for n in v.networks : n => try(local.bridges[v.host][n], null) if contains(local.joinable_networks, n) }
      vlans       = { ingest = local.x.networks.ingest.vlan, store = local.x.networks.store.vlan, observe = try(local.x.networks.observe.vlan, null), mgmt = local.x.networks.mgmt.vlan }
      routes      = local.routes[s]
      gateway     = local.mgmt_gateway
      nameservers = local.mgmt_dns
    }
  }
}
