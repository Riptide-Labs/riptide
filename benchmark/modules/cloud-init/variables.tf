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

variable "pyroscope_url" {
  description = "Pyroscope push URL on the observe network, or empty when the lab has no observability service."
  type        = string
  default     = ""
}

variable "prometheus_jobs" {
  description = "Prometheus scrape jobs by name, each a list of targets with their labels, on the observe network. Only read by the observability role."
  type        = map(list(object({ target = string, labels = map(string) })))
  default     = {}
}

variable "grafana_admin_password" {
  description = "Grafana admin password. Only read by the observability role."
  type        = string
  sensitive   = true
  default     = ""
}

variable "grafana_dashboards" {
  description = "Dashboards to provision, file name to JSON. Only read by the observability role."
  type        = map(string)
  default     = {}
}

variable "alert_rules" {
  description = "Prometheus rule file content, or empty. Only read by the observability role."
  type        = string
  default     = ""
}
