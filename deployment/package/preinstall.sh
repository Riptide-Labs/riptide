#!/bin/sh
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later
set -e

# Runs before unpack on both families (deb preinst / rpm %pre) so the group
# exists when the package manager applies the declared root:riptide ownership
# on /etc/riptide.
if ! getent group riptide >/dev/null; then
    groupadd --system riptide
fi
if ! getent passwd riptide >/dev/null; then
    useradd --system --gid riptide --no-create-home --home-dir /nonexistent \
        --shell /usr/sbin/nologin --comment "Riptide NetFlow analysis engine" riptide
fi
