# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# The Debian image every VM boots from, pinned by hand: Dependabot does not
# read .tfvars. Container images live in benchmark/images/compose.yml, which
# Dependabot keeps current; ClickHouse comes from deployment/clickhouse/compose.yml.

base_image = {
  url    = "https://cloud.debian.org/images/cloud/trixie/20260914-2601/debian-13-genericcloud-amd64-20260914-2601.qcow2"
  file   = "debian-13-genericcloud-amd64-20260914-2601.qcow2"
  sha512 = "95e110dfcdbd0ed8a82a75ed9579802f9950cabf51a810dcc6388e81bc778188713878b9f28d583a0ea602fbf48b35996ae9ad37f584166d8fbd6489df248f53"
}
