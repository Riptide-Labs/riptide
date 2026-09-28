# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# OpenTofu drops unknown attributes when it converts a value to an object type,
# so the declaration arrives untyped: the unknown-key check reads it as written,
# and the `typed` module converts it for everything else.

variable "raw" {
  description = "The experiment declaration exactly as written in the .tfvars file."
  type        = any
}

variable "site" {
  description = "The site declaration (hosts, networks, protected ranges), when it comes from its own file. Null when raw already holds everything, as in the module's own tests."
  type        = any
  default     = null
}

variable "agent_ssh_keys" {
  description = "Public keys held by the SSH agent that apply connects with; benchmark/bin/bench passes them. Authorized for user bench together with the declaration's ssh_keys."
  type        = list(string)
  default     = []
}
