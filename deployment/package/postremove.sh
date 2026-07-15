#!/bin/sh
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later
set -e

if [ -d /run/systemd/system ]; then
    systemctl daemon-reload || true
fi
