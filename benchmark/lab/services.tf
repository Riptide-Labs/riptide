# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Steps that need a running VM, done over SSH on mgmt as user bench with the
# agent's keys. Order: every dependency answers its health check, then riptide
# is installed and its /readyz waited for. riptide exits when ClickHouse does
# not answer within riptide.clickhouse.startup-wait (30 s), so it starts only
# once ClickHouse is up.

locals {
  sut = module.declaration.sut

  # Read apart from riptide_env, which holds the ClickHouse password and so is
  # sensitive as a whole.
  java_opts = lookup(local.x.riptide.env, "JAVA_OPTS", "-Xmx${max(1, floor(local.services[local.sut].memory_gb / 2))}g")

  riptide_env = merge(
    {
      JAVA_OPTS                          = local.java_opts
      RIPTIDE_CLICKHOUSE_ENDPOINT        = "http://${local.services[local.clickhouse].addresses.store}:8123"
      RIPTIDE_CLICKHOUSE_USERNAME        = "default"
      RIPTIDE_CLICKHOUSE_PASSWORD        = random_password.clickhouse.result
      RIPTIDE_CLICKHOUSE_DATABASE        = "riptide"
      RIPTIDE_ENRICHER_HOSTNAMES_ENABLED = "false"
    },
    local.x.riptide.env,
  )

  # systemd EnvironmentFile syntax: every value double-quoted, so JAVA_OPTS
  # with spaces survives as one assignment and the unit's unbraced $JAVA_OPTS
  # splits it into arguments.
  riptide_env_file = join("", [
    for k in sort(keys(local.riptide_env)) :
    "${k}=\"${replace(replace(local.riptide_env[k], "\\", "\\\\"), "\"", "\\\"")}\"\n"
  ])

  wait_cloud_init = {
    for s in keys(local.services) : s =>
    "cloud-init status --wait >/dev/null; rc=$?; [ $rc -eq 0 ] || [ $rc -eq 2 ] || { echo 'service ${s}: cloud-init failed, see /var/log/cloud-init-output.log'; exit 1; }"
  }

  wait_healthy = {
    for s, v in local.services : s => join(" ", [
      "deadline=$(( $(date +%s) + ${var.ready_timeout_seconds} ));",
      "until curl -fsS -o /dev/null --max-time 5 ${local.health_url[v.role]}; do",
      "[ $(date +%s) -lt $deadline ] || { echo 'service ${s}: ${local.health_url[v.role]} did not answer within ${var.ready_timeout_seconds} s'; exit 1; };",
      "sleep 5; done; echo 'service ${s}: ready'",
    ])
  }

  health_url = {
    riptide         = "http://127.0.0.1:8080/readyz"
    clickhouse      = "http://127.0.0.1:8123/ping"
    victoriametrics = "http://127.0.0.1:8428/health"
    nl6             = "http://127.0.0.1:8080/api/v1/status"
  }
}

resource "terraform_data" "riptide" {
  triggers_replace = [
    var.riptide_deb_sha256,
    sha256(local.riptide_env_file),
    local.vm_ids[local.sut],
  ]

  connection {
    type    = "ssh"
    host    = local.services[local.sut].addresses.mgmt
    user    = "bench"
    agent   = true
    timeout = "10m"
  }

  lifecycle {
    precondition {
      condition     = var.riptide_deb != "" && var.riptide_deb_sha256 != ""
      error_message = "riptide_deb is not set: run through benchmark/bin/bench (make bench-plan / bench-apply), which resolves riptide.source and verifies the package."
    }
  }

  # cloud-init installs Java; the package must not race it for the dpkg lock.
  provisioner "remote-exec" {
    inline = [local.wait_cloud_init[local.sut]]
  }

  provisioner "file" {
    source      = var.riptide_deb
    destination = "/tmp/riptide.deb"
  }

  provisioner "file" {
    content     = local.riptide_env_file
    destination = "/tmp/riptide.env"
  }

  provisioner "remote-exec" {
    inline = [
      "set -e",
      "echo '${var.riptide_deb_sha256}  /tmp/riptide.deb' | sha256sum -c -",
      "sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -o Dpkg::Options::=--force-confold /tmp/riptide.deb",
      "sudo install -m 0640 -o root -g riptide /tmp/riptide.env /etc/riptide/riptide.env",
      "rm -f /tmp/riptide.env /tmp/riptide.deb",
      "sudo systemctl enable riptide",
      "sudo systemctl restart riptide",
    ]
  }

  provisioner "remote-exec" {
    inline = [local.wait_healthy[local.sut]]
  }

  depends_on = [terraform_data.ready]
}

# Every service except the SUT, which terraform_data.riptide waits for.
resource "terraform_data" "ready" {
  for_each = { for s, v in local.services : s => v if s != local.sut }

  triggers_replace = [local.vm_ids[each.key]]

  connection {
    type    = "ssh"
    host    = each.value.addresses.mgmt
    user    = "bench"
    agent   = true
    timeout = "10m"
  }

  provisioner "remote-exec" {
    inline = [local.wait_cloud_init[each.key], local.wait_healthy[each.key]]
  }
}
