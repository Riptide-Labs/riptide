# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

module "declaration" {
  source         = "../modules/declaration"
  site           = var.site
  raw            = var.experiment
  agent_ssh_keys = var.agent_ssh_keys
}

locals {
  x    = module.declaration.experiment
  name = local.x.name

  # A rejected declaration plans nothing, so no lookup below can fail on it
  # before terraform_data.checks lists every violation.
  ok       = length(module.declaration.violations) == 0
  services = local.ok ? module.declaration.services : {}

  libvirt_services = { for s, v in local.services : s => v if v.provider == "libvirt" }
  proxmox_services = { for s, v in local.services : s => v if v.provider == "proxmox" }

  clickhouse_image = yamldecode(file("${path.module}/../../deployment/clickhouse/compose.yml")).services.clickhouse.image
  images           = merge(var.images, { clickhouse = local.clickhouse_image })

  clickhouse = try(one([for s, v in local.services : s if v.role == "clickhouse"]), null)

  scrape_targets = local.ok ? {
    node    = [for s in sort(keys(local.services)) : "${local.services[s].addresses.mgmt}:9100"]
    riptide = ["${local.services[module.declaration.sut].addresses.mgmt}:8080"]
  } : {}
}

# Every rule in modules/declaration reports here, so one failed plan lists
# every problem at once and no VM is created while any remains.
resource "terraform_data" "checks" {
  input = module.declaration.violations

  lifecycle {
    precondition {
      condition     = length(module.declaration.violations) == 0
      error_message = "The declaration is rejected:\n- ${join("\n- ", module.declaration.violations)}"
    }
  }
}

resource "random_password" "clickhouse" {
  length  = 32
  special = false
}

module "cloud_init" {
  source   = "../modules/cloud-init"
  for_each = local.services

  experiment          = local.name
  service             = each.value
  ssh_keys            = module.declaration.ssh_keys
  images              = local.images
  clickhouse_password = random_password.clickhouse.result
  exporters_cidr      = module.declaration.cidrs.exporters
  scrape_targets      = each.value.role == "victoriametrics" ? local.scrape_targets : {}
  clickhouse_files = {
    config_xml = file("${path.module}/../../deployment/clickhouse/container-fs/clickhouse/config.xml")
    users_xml  = file("${path.module}/../../deployment/clickhouse/container-fs/clickhouse/users.xml")
  }
}

# --- base images ------------------------------------------------------------
# Named per experiment and excluded from `bench destroy`, so re-applying an
# experiment reuses its image instead of downloading it again. Both backends
# get the image checked against base_image.sha512: libvirt uploads the copy
# bin/bench verified, Proxmox checks its own download.

resource "libvirt_volume" "base" {
  for_each = local.ok ? local.libvirt_hosts : {}
  provider = libvirt.host[each.key]

  name   = "bench-${local.name}-${var.base_image.file}"
  pool   = local.x.hosts[each.key].pool
  target = { format = { type = "qcow2" } }
  create = { content = { url = var.base_image_path } }

  lifecycle {
    precondition {
      condition     = var.base_image_path != ""
      error_message = "base_image_path is not set: run through benchmark/bin/bench (make bench-plan / bench-apply), which downloads the base image and checks its SHA-512."
    }
  }

  depends_on = [terraform_data.checks]
}

resource "proxmox_download_file" "base" {
  for_each = local.ok ? local.proxmox_hosts : {}
  provider = proxmox.pve["pve"]

  node_name          = local.x.hosts[each.key].node
  datastore_id       = "local"
  content_type       = "import"
  file_name          = "bench-${local.name}-${var.base_image.file}"
  url                = var.base_image.url
  checksum           = var.base_image.sha512
  checksum_algorithm = "sha512"

  depends_on = [terraform_data.checks]
}

# --- VMs --------------------------------------------------------------------

module "libvirt_vm" {
  source    = "../modules/libvirt-vm"
  for_each  = local.libvirt_services
  providers = { libvirt = libvirt.host[each.value.host] }

  experiment       = local.name
  service          = each.value
  pool             = local.x.hosts[each.value.host].pool
  base_volume_path = libvirt_volume.base[each.value.host].path
  cloud_init = {
    user_data      = module.cloud_init[each.key].user_data
    meta_data      = module.cloud_init[each.key].meta_data
    network_config = module.cloud_init[each.key].network_config
  }

  depends_on = [terraform_data.checks]
}

module "proxmox_vm" {
  source    = "../modules/proxmox-vm"
  for_each  = local.proxmox_services
  providers = { proxmox = proxmox.pve["pve"] }

  experiment    = local.name
  service       = each.value
  node          = local.x.hosts[each.value.host].node
  datastore     = local.x.hosts[each.value.host].datastore
  base_image_id = proxmox_download_file.base[each.value.host].id
  cloud_init = {
    user_data      = module.cloud_init[each.key].user_data
    meta_data      = module.cloud_init[each.key].meta_data
    network_config = module.cloud_init[each.key].network_config
  }

  depends_on = [terraform_data.checks]
}

locals {
  vm_ids = merge(
    { for s, m in module.libvirt_vm : s => m.id },
    { for s, m in module.proxmox_vm : s => tostring(m.vm_id) },
  )
}
