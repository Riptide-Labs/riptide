#!/usr/bin/env bash
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Installs the freshly built deb/rpm in Debian and Rocky containers and
# asserts: the Java 25 dependency resolves from the distro repos, file layout
# and ownership match the design, and the app boots as the riptide user.
# Invoked via `make packages-smoke` with the mangled package version.
set -euo pipefail
cd "$(dirname "$0")/../.."

VERSION="${1:?usage: smoke-test.sh <package-version>}"

test -f "target/riptide_${VERSION}_all.deb" || { echo "deb for ${VERSION} missing — run make packages first"; exit 1; }
ls target/riptide-"${VERSION}"-*.noarch.rpm >/dev/null || { echo "rpm for ${VERSION} missing — run make packages first"; exit 1; }

# Shared assertions; runs inside both containers after package installation.
ASSERTIONS='
  java --version | head -1 | grep -q "openjdk 25" || { echo "FAIL: no Java 25 runtime"; exit 1; }
  test -f /usr/share/riptide/riptide.jar
  test -f /usr/lib/systemd/system/riptide.service
  [ "$(stat -c "%U:%G %a" /etc/riptide/config.yaml)" = "root:riptide 640" ]
  [ "$(stat -c "%U:%G %a" /etc/riptide/riptide.env)" = "root:riptide 640" ]
  id riptide >/dev/null
  runuser -u riptide -- timeout 30 java -jar /usr/share/riptide/riptide.jar >/tmp/boot.log 2>&1 || true
  grep -q "Starting RiptideApplication" /tmp/boot.log || { echo "FAIL: app did not boot"; cat /tmp/boot.log; exit 1; }
  echo "assertions OK"
'

echo "=== smoke: debian:trixie (apt dependency resolution + install) ==="
docker run --rm -v "$PWD/target:/pkgs:ro" -e "VERSION=${VERSION}" debian:trixie bash -euc "
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -q
  apt-get install -yq /pkgs/riptide_\${VERSION}_all.deb systemd
  systemd-analyze verify /usr/lib/systemd/system/riptide.service
  ${ASSERTIONS}
"

echo "=== smoke: rockylinux:9 (dnf dependency resolution + install) ==="
docker run --rm -v "$PWD/target:/pkgs:ro" -e "VERSION=${VERSION}" rockylinux:9 bash -euc "
  dnf install -yq /pkgs/riptide-\${VERSION}-*.noarch.rpm
  ${ASSERTIONS}
"

echo "=== smoke: OK (deb + rpm) ==="
