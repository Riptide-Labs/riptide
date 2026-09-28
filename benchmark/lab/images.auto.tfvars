# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Pinned by hand: Dependabot does not read .tfvars. Resolve a new digest with
#   docker buildx imagetools inspect <image>:<tag> --format '{{json .Manifest.Digest}}'
# The ClickHouse image is not here: the lab reads it from
# deployment/clickhouse/compose.yml, so the lab and the compose stack match.

base_image = {
  url    = "https://cloud.debian.org/images/cloud/trixie/20260914-2601/debian-13-genericcloud-amd64-20260914-2601.qcow2"
  file   = "debian-13-genericcloud-amd64-20260914-2601.qcow2"
  sha512 = "95e110dfcdbd0ed8a82a75ed9579802f9950cabf51a810dcc6388e81bc778188713878b9f28d583a0ea602fbf48b35996ae9ad37f584166d8fbd6489df248f53"
}

images = {
  victoriametrics = "docker.io/victoriametrics/victoria-metrics:v1.152.0@sha256:86ca5fdb6d87d56ba047b044039019ba2bd9042b36e35f6ea34e437b6c825cef"
  vmagent         = "docker.io/victoriametrics/vmagent:v1.152.0@sha256:21d51831acfed657c1d4a805353c278f3669b061cb3a2cd46c0d4f1a4402cb1b"
  nl6             = "ghcr.io/labmonkeys-space/nl6:v0.32.0@sha256:65b53edf562ce55d564249d0f278dc84cd4d4b3a68730beb33c06b897158bab3"
}
