# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Single source of truth for the ClickHouse image used by the IT and e2e tiers.
# Pinned by digest as well as tag: a tag can be repointed at different bytes,
# and these images gate the e2e tier. Dependabot updates both parts of the
# FROM line; the tests (ContainerImages) and the CI pre-pull
# step read it from here. Never built into an image.
FROM clickhouse/clickhouse-server:26.7@sha256:f90a77560f72b10802106ee49e9870e41668cbc496e280c3911f6e3b216657f3
