# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

output "user_data" {
  description = "cloud-init user-data (#cloud-config)."
  value       = "#cloud-config\n${yamlencode(local.user_data)}"
  sensitive   = true
}

output "network_config" {
  description = "cloud-init network-config, version 2, NICs matched by MAC and named after their network."
  value       = yamlencode(local.network_config)
}

output "meta_data" {
  description = "cloud-init meta-data."
  value       = yamlencode({ "instance-id" = "bench-${var.experiment}-${var.service.name}", "local-hostname" = "bench-${var.experiment}-${var.service.name}" })
}

output "units" {
  description = "systemd units this role runs, by name; for tests and the inventory."
  value       = local.unit
}

output "files" {
  description = "Paths and contents of every file written, for tests. Contents of secret files are included; the output is sensitive."
  value       = { for f in local.write_files : f.path => f.content }
  sensitive   = true
}
