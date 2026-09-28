# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

locals {
  run_dir = "${path.module}/../runs/${local.name}"

  # release:X.Y.Z names it; a local riptide_<version>_all.deb names it in its file name.
  riptide_version = (startswith(local.x.riptide.source, "release:")
    ? trimprefix(local.x.riptide.source, "release:")
  : try(regex("riptide_([^_]+)_all\\.deb$", var.riptide_deb)[0], null))

  inventory = {
    experiment = local.name
    riptide = {
      source  = local.x.riptide.source
      version = local.riptide_version
      sha256  = var.riptide_deb_sha256
    }
    # The `sut` fields of a benchmark-capture run manifest that the
    # declaration knows. jvm.gc, jvm.flags and jvm.version come from the
    # running process, and the capture reads them there.
    sut = {
      provisioner      = "ssh-package"
      version_identity = { version = local.riptide_version, package_version = local.riptide_version }
      jvm              = { heap = local.java_opts }
      db_version       = local.images.clickhouse
      config_delta     = "sha256:${nonsensitive(sha256(local.riptide_env_file))} of /etc/riptide/riptide.env"
    }
    images     = local.images
    base_image = var.base_image
    hosts = {
      for h, v in local.x.hosts : h => {
        provider = v.provider
        uri      = v.uri
        node     = v.node
        numa     = v.numa
      }
    }
    services = {
      for s, v in local.services : s => {
        role          = v.role
        host          = v.host
        provider      = v.provider
        vm            = "bench-${local.name}-${s}"
        numa_node     = v.numa_node
        vcpus         = v.vcpus
        memory_gb     = v.memory_gb
        disk_gb       = v.disk_gb
        pinned_cpus   = v.vcpu_pins
        emulator_cpus = v.emulator
        addresses     = v.addresses
        macs          = v.macs
      }
    }
  }

  ssh_config = join("\n", [
    for s in sort(keys(local.services)) : join("\n", [
      "Host bench-${local.name}-${s}",
      "  HostName ${local.services[s].addresses.mgmt}",
      "  User bench",
      # A rebuilt VM has a new host key; keep this run's keys apart from
      # ~/.ssh/known_hosts. bench destroy removes the file.
      "  UserKnownHostsFile ${abspath(local.run_dir)}/known_hosts",
      "  StrictHostKeyChecking accept-new",
      "",
    ])
  ])

  scrape_targets_file = [
    for job in sort(keys(local.scrape_targets)) : {
      targets = local.scrape_targets[job]
      labels  = { job = job, experiment = local.name }
    }
  ]
}

resource "local_file" "inventory" {
  filename        = "${local.run_dir}/inventory.json"
  content         = jsonencode(local.inventory)
  file_permission = "0644"
  depends_on      = [terraform_data.riptide]
}

resource "local_file" "ssh_config" {
  filename        = "${local.run_dir}/ssh_config"
  content         = local.ssh_config
  file_permission = "0644"
  depends_on      = [terraform_data.riptide]
}

resource "local_file" "scrape_targets" {
  filename        = "${local.run_dir}/scrape-targets.json"
  content         = jsonencode(local.scrape_targets_file)
  file_permission = "0644"
  depends_on      = [terraform_data.riptide]
}

output "services" {
  description = "Per service: placement, addresses and MACs."
  value       = local.inventory.services
}

output "hosts" {
  description = "Declared hosts, for bench list."
  value = {
    for h, v in local.x.hosts : h => {
      provider = v.provider
      uri      = v.uri
      node     = v.node
      address  = v.address
    }
  }
}

output "vms" {
  description = "Created VMs: domain name on libvirt, VM id on Proxmox, and their labels."
  value = merge(
    { for s, m in module.libvirt_vm : s => { name = m.name, labels = m.labels } },
    { for s, m in module.proxmox_vm : s => { name = m.name, vm_id = m.vm_id, labels = m.tags } },
  )
}
