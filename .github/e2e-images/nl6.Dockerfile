# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Single source of truth for the nl6 flow-generator image used by the e2e tier.
# Pinned by digest as well as tag: a tag can be repointed at different bytes,
# and these images gate the e2e tier. Dependabot updates both parts of the
# FROM line; the tests (ContainerImages) and the CI pre-pull
# step read it from here. Never built into an image.
FROM ghcr.io/labmonkeys-space/nl6:v0.22.1@sha256:6fcf1cf611494c755ad54d15f3f1c23bd415d8f865a227f4f503b21172cc3e69
