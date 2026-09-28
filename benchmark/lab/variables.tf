# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

variable "site" {
  description = "The lab's hosts, networks and protected ranges, from benchmark/site.tfvars (not in git; see site.example.tfvars). Untyped on purpose: modules/declaration checks its keys and converts it."
  type        = any
}

variable "experiment" {
  description = "The experiment, from benchmark/experiments/<name>.tfvars: services placed on the site's host keys, and the riptide build. Untyped on purpose: modules/declaration checks its keys and converts it."
  type        = any
}

variable "agent_ssh_keys" {
  description = "Public keys held by the SSH agent, authorized for user bench. Set by benchmark/bin/bench from `ssh-add -L`."
  type        = list(string)
  default     = []
}

variable "riptide_deb" {
  description = "Local path of the riptide .deb to install. Set by benchmark/bin/bench from riptide.source after verification; not written by hand."
  type        = string
  default     = ""
}

variable "riptide_deb_sha256" {
  description = "SHA-256 of riptide_deb. Set by benchmark/bin/bench."
  type        = string
  default     = ""
}

variable "base_image" {
  description = "Debian 13 genericcloud image every VM boots from."
  type = object({
    url    = string
    file   = string
    sha512 = string
  })
}

variable "base_image_path" {
  description = "Local copy of base_image, checked against its SHA-512; libvirt hosts upload it. Set by benchmark/bin/bench."
  type        = string
  default     = ""
}

variable "images" {
  description = "Digest-pinned container images: victoriametrics, vmagent, nl6. The ClickHouse image is read from deployment/clickhouse/compose.yml."
  type        = map(string)
}

variable "ready_timeout_seconds" {
  description = "How long apply waits for a service's health check before failing."
  type        = number
  default     = 600
}
