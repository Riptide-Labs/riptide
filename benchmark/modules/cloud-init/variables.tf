# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

variable "experiment" {
  description = "Experiment name."
  type        = string
}

variable "service" {
  description = "One entry of the declaration module's `services` output."
  type = object({
    name        = string
    role        = string
    disk_gb     = optional(number)
    addresses   = map(string)
    prefix      = map(string)
    macs        = map(string)
    routes      = list(object({ network = string, to = string, via = string }))
    gateway     = string
    nameservers = list(string)
  })
}

variable "ssh_keys" {
  description = "Public keys for the bench user."
  type        = list(string)
}

variable "images" {
  description = "Digest-pinned container images by role component: clickhouse, victoriametrics, nl6, prometheus, pyroscope, grafana."
  type        = map(string)
}

variable "clickhouse_password" {
  description = "Password of the ClickHouse default user."
  type        = string
  sensitive   = true
}

variable "clickhouse_files" {
  description = "ClickHouse config.xml and users.xml contents, from deployment/clickhouse/container-fs/clickhouse."
  type        = object({ config_xml = string, users_xml = string })
}

variable "exporters_cidr" {
  description = "Range nl6 allocates simulated exporters from."
  type        = string
}
