# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Single source of truth for the Pyroscope image used by ProfilingLabelsIT (#921)
# and, through the same pin, the compose stack. Pinned by digest as well as tag;
# Dependabot updates both parts of the FROM line; the tests (ContainerImages)
# and the CI pre-pull step read it from here. Never built into an image.
FROM docker.io/grafana/pyroscope:2.3.1@sha256:86a9ee7448487409ead8ada78789de7b78b739711b92b5d7224a1a54abf3eeb2
