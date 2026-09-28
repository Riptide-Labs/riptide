# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

output "violations" {
  description = "Every reason the declaration is rejected, each naming the service, host, network or key at fault. Empty when valid."
  value       = local.violations
}

output "experiment" {
  description = "The typed declaration with defaults applied."
  value       = local.x
}

output "services" {
  description = "Per service: placement (pinned host CPUs, emulator CPUs, guest topology), addresses per network, bridges, VLANs and routes."
  value       = local.services
}

output "cidrs" {
  description = "CIDR per network, defaults applied."
  value       = local.cidrs
}

output "sut" {
  description = "Name of the riptide service."
  value       = local.sut
}

output "ssh_keys" {
  description = "Keys authorized for user bench: the agent's, then the declaration's."
  value       = local.ssh_keys
}

output "observability" {
  description = "Name of the observability service, or null."
  value       = local.observability
}

output "observe_enabled" {
  description = "Whether the site declares the observe network."
  value       = local.observe_enabled
}
