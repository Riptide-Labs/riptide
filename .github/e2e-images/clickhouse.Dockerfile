# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Single source of truth for the ClickHouse image used by the IT and e2e tiers.
# Dependabot bumps the FROM line; the tests (ContainerImages) and the CI pre-pull
# step read it from here. Never built into an image.
FROM clickhouse/clickhouse-server:26.7
