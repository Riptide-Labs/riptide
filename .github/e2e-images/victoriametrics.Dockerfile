# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Single source of truth for the VictoriaMetrics image used by SnmpMetricsIT. Pinned by digest
# as well as tag so Dependabot updates both parts. Not built; only the FROM line is read.
FROM victoriametrics/victoria-metrics:v1.152.0@sha256:86ca5fdb6d87d56ba047b044039019ba2bd9042b36e35f6ea34e437b6c825cef
