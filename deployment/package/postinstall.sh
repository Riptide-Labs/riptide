#!/bin/sh
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later
set -e

if [ -d /run/systemd/system ]; then
    systemctl daemon-reload || true
fi

# Passive install by design: Riptide cannot do anything useful before
# /etc/riptide/config.yaml points at a ClickHouse, so it is neither enabled
# nor started here.
echo "Riptide is installed but not enabled or started."
echo "Configure /etc/riptide/config.yaml, then run: systemctl enable --now riptide"
