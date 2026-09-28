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

  # Lab images from the Dependabot-tracked manifest; ClickHouse from the
  # compose stack, so the SUT's dependency matches what operators run.
  lab_images       = { for k, v in yamldecode(file("${path.module}/../images/compose.yml")).services : k => v.image }
  clickhouse_image = yamldecode(file("${path.module}/../../deployment/clickhouse/compose.yml")).services.clickhouse.image
  images           = merge(local.lab_images, { clickhouse = local.clickhouse_image })

  clickhouse = try(one([for s, v in local.services : s if v.role == "clickhouse"]), null)

  # Prometheus's scrape jobs, every target on its observe address with the
  # labels a query needs to pick a slice of the lab.
  target_labels = { for s, v in local.services : s => { experiment = local.name, service = s, role = v.role, host = v.host } }
  prometheus_jobs = local.observability == null ? {} : merge(
    { node = [for s in sort(keys(local.services)) : { target = "${local.services[s].addresses.observe}:9100", labels = local.target_labels[s] }] },
    { riptide = [{ target = "${local.services[module.declaration.sut].addresses.observe}:8080", labels = local.target_labels[module.declaration.sut] }] },
    { clickhouse = [{ target = "${local.services[local.clickhouse].addresses.observe}:9363", labels = local.target_labels[local.clickhouse] }] },
    { for s, v in local.services : "victoriametrics" => [{ target = "${v.addresses.observe}:8428", labels = local.target_labels[s] }] if v.role == "victoriametrics" },
    { prometheus = [{ target = "localhost:9090", labels = local.target_labels[local.observability] }] },
    { pyroscope = [{ target = "localhost:4040", labels = local.target_labels[local.observability] }] },
  )

  # The self-monitoring dashboards, read at plan time so a lab shows the
  # repository's current ones.
  grafana_dashboards = {
    for f in ["riptide-health.json", "riptide-stage-detail.json", "riptide-profiling.json"] :
    f => file("${path.module}/../../deployment/clickhouse/container-fs/grafana/provisioning/dashboards/${f}")
  }
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

resource "random_password" "grafana" {
  length  = 32
  special = false
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
  pyroscope_url       = local.pyroscope_url

  # Read by the module for the observability role only.
  prometheus_jobs        = local.prometheus_jobs
  grafana_admin_password = random_password.grafana.result
  grafana_dashboards     = local.grafana_dashboards
  alert_rules            = file("${path.module}/../../deployment/clickhouse/container-fs/prometheus/riptide-alerts.yml")
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

# --- observability data -----------------------------------------------------
# The observability service's data disk outlives its VM: bench destroy
# excludes these resources, so a re-applied lab keeps its measurement
# history; bench purge removes them.

resource "libvirt_volume" "observability_data" {
  for_each = { for s, v in local.libvirt_services : s => v if v.role == "observability" }
  provider = libvirt.host[each.value.host]

  # Distinct from the -data.qcow2 a VM creates for itself, so the two can
  # never share a name in one pool.
  name     = "bench-${local.name}-${each.key}-observability-data.qcow2"
  pool     = local.x.hosts[each.value.host].pool
  capacity = each.value.disk_gb * 1073741824
  target   = { format = { type = "qcow2" } }

  depends_on = [terraform_data.checks]
}

# Owned by holder VMID 999999, so destroying the observability VM leaves it:
# Proxmox deletes only the volumes a VM's own VMID owns. Allocated and freed
# over the root SSH that snippet upload already uses.
resource "terraform_data" "proxmox_observability_data" {
  for_each = { for s, v in local.proxmox_services : s => v if v.role == "observability" }

  input = {
    target    = coalesce(local.x.hosts[each.value.host].address, local.x.hosts[each.value.host].node)
    datastore = local.x.hosts[each.value.host].datastore
    volume    = "vm-999999-bench-${local.name}-${each.key}-data"
    size      = "${each.value.disk_gb}G"
    # The whole volid, so a volume whose name only starts the same does not count.
    exists = "pvesm list ${local.x.hosts[each.value.host].datastore} --vmid 999999 | awk '{print $1}' | grep -qx '${local.x.hosts[each.value.host].datastore}:vm-999999-bench-${local.name}-${each.key}-data'"
  }

  connection {
    type  = "ssh"
    host  = self.input.target
    user  = "root"
    agent = true
  }

  # Idempotent: a volume kept by an earlier bench destroy is reused.
  provisioner "remote-exec" {
    inline = ["${self.input.exists} || pvesm alloc ${self.input.datastore} 999999 ${self.input.volume} ${self.input.size}"]
  }

  # No existence check: on a ZFS pool (PVE 9.2.2), freeing a volume already
  # gone reports it removed and exits 0.
  provisioner "remote-exec" {
    when   = destroy
    inline = ["pvesm free ${self.input.datastore}:${self.input.volume}"]
  }

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
  data_volume = each.value.role == "observability" ? {
    pool = libvirt_volume.observability_data[each.key].pool
    name = libvirt_volume.observability_data[each.key].name
  } : null
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
  data_volume = each.value.role == "observability" ? {
    # input, not output: known at plan, and the reference still orders the
    # VM after the volume.
    datastore = terraform_data.proxmox_observability_data[each.key].input.datastore
    path      = terraform_data.proxmox_observability_data[each.key].input.volume
  } : null
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
