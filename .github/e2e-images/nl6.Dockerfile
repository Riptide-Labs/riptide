# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Single source of truth for the nl6 flow-generator image used by the e2e tier.
# Dependabot bumps the FROM line; the tests (ContainerImages) and the CI pre-pull
# step read it from here. Never built into an image.
FROM ghcr.io/labmonkeys-space/nl6:v0.22.1
