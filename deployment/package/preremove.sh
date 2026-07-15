#!/bin/sh
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later
set -e

# Stop and disable only on real removal, not upgrade: deb prerm gets "remove",
# rpm %preun gets "0".
if [ "$1" = "remove" ] || [ "$1" = "0" ]; then
    if [ -d /run/systemd/system ]; then
        # deb-systemd-invoke respects policy-rc.d (container image builds)
        if command -v deb-systemd-invoke >/dev/null 2>&1; then
            deb-systemd-invoke stop riptide.service >/dev/null 2>&1 || true
        else
            systemctl stop riptide.service >/dev/null 2>&1 || true
        fi
    fi
    if command -v systemctl >/dev/null 2>&1; then
        systemctl disable riptide.service >/dev/null 2>&1 || true
    fi
fi
