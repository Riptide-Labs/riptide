# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Steps that need a running VM, done over SSH on mgmt as user bench with the
# agent's keys. Order: the observability VM answers first (so no early scrape
# or profile is lost), then every dependency, then riptide is installed and
# its /readyz waited for. riptide exits when ClickHouse does
# not answer within riptide.clickhouse.startup-wait (30 s), so it starts only
# once ClickHouse is up.

locals {
  sut = module.declaration.sut

  # Null on a rejected declaration too, whose services map is empty: every
  # consumer then checks this one condition and never indexes local.services.
  observability = local.ok ? module.declaration.observability : null
  obs_address   = try(local.services[local.observability].addresses.observe, null)
  pyroscope_url = local.obs_address == null ? "" : "http://${local.obs_address}:4040"
  sut_observe   = try(local.services[local.sut].addresses.observe, null)

  # Where riptide's management server listens: the experiment's own value,
  # else the observe address while profiling, else riptide's 0.0.0.0 default.
  # Readiness is checked there, so the two cannot disagree.
  sut_bind  = lookup(local.x.riptide.env, "RIPTIDE_MANAGEMENT_BIND_ADDRESS", local.pyroscope_url == "" ? "0.0.0.0" : local.sut_observe)
  sut_ready = local.sut_bind == "0.0.0.0" ? coalesce(local.sut_observe, "127.0.0.1") : local.sut_bind

  # Read apart from riptide_env, which holds the ClickHouse password and so is
  # sensitive as a whole. Profiling needs native access; the flag is appended
  # to the declared value once, never replacing it.
  profiling_flag = "--enable-native-access=ALL-UNNAMED"
  declared_java  = lookup(local.x.riptide.env, "JAVA_OPTS", "-Xmx${max(1, floor(try(local.services[local.sut].memory_gb, 2) / 2))}g")
  java_opts      = local.pyroscope_url == "" || strcontains(local.declared_java, local.profiling_flag) ? local.declared_java : "${local.declared_java} ${local.profiling_flag}"

  # nl6's service discovery names every simulated exporter by its sysName. Over
  # mgmt, which carries control traffic, never the measured ingest path.
  loadgen = try(one([for s, v in local.services : s if v.role == "nl6"]), null)
  nl6_discovery = local.x.riptide.nl6_discovery && local.loadgen != null ? {
    RIPTIDE_DISCOVERY_URL         = "http://${local.services[local.loadgen].addresses.mgmt}:8080/api/v1/prometheus/sd"
    RIPTIDE_DISCOVERY_NAME_LABELS = "__meta_nl6_sys_name"
  } : {}

  riptide_env = merge(
    {
      JAVA_OPTS                          = local.java_opts
      RIPTIDE_CLICKHOUSE_ENDPOINT        = "http://${try(local.services[local.clickhouse].addresses.store, "")}:8123"
      RIPTIDE_CLICKHOUSE_USERNAME        = "default"
      RIPTIDE_CLICKHOUSE_PASSWORD        = random_password.clickhouse.result
      RIPTIDE_CLICKHOUSE_DATABASE        = "riptide"
      RIPTIDE_ENRICHER_HOSTNAMES_ENABLED = "false"
    },
    local.pyroscope_url == "" ? {} : {
      RIPTIDE_PROFILING_ENABLED       = "true"
      PYROSCOPE_SERVER_ADDRESS        = local.pyroscope_url
      RIPTIDE_MANAGEMENT_BIND_ADDRESS = local.sut_bind
    },
    local.nl6_discovery,
    # The experiment's own values win; JAVA_OPTS is merged above.
    { for k, v in local.x.riptide.env : k => v if k != "JAVA_OPTS" },
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
    for s, v in local.services : s => join("\n", [
      for url in local.health_urls[s] : join(" ", [
        "deadline=$(( $(date +%s) + ${var.ready_timeout_seconds} ));",
        "until curl -fsS -o /dev/null --max-time 5 ${url}; do",
        "[ $(date +%s) -lt $deadline ] || { echo 'service ${s}: ${url} did not answer within ${var.ready_timeout_seconds} s'; exit 1; };",
        "sleep 5; done; echo 'service ${s}: ${url} ready'",
      ])
    ])
  }

  # Rendered cloud-init documents per service, for bin/bench-reseed.
  seed_dir  = "${abspath(local.run_dir)}/seed"
  seed_docs = ["user-data", "meta-data", "network-config"]
  # The mgmt address each running VM had after the last apply: a changed
  # network-config takes effect only at the re-run's reboot.
  # try, not a conditional: the inventory object and {} differ in type.
  applied_inventory = try(jsondecode(file("${local.run_dir}/inventory.json")), {})
  reseed = {
    for s, v in local.services : s => join(" ", [
      for a in [
        abspath("${path.module}/../bin/bench-reseed"), s, v.role,
        try(local.applied_inventory.services[s].addresses.mgmt, v.addresses.mgmt), v.addresses.mgmt,
        "${local.seed_dir}/${s}", "${abspath(local.run_dir)}/known_hosts", tostring(var.ready_timeout_seconds),
      ] : "'${a}'"
    ])
  }

  # riptide is checked where it listens; the rest on the VM itself.
  health_urls = {
    for s, v in local.services : s => {
      riptide         = ["http://${local.sut_ready}:8080/readyz"]
      clickhouse      = ["http://127.0.0.1:8123/ping"]
      victoriametrics = ["http://127.0.0.1:8428/health"]
      nl6             = ["http://127.0.0.1:8080/api/v1/status"]
      observability   = ["http://127.0.0.1:9090/-/ready", "http://127.0.0.1:4040/ready", "http://127.0.0.1:3000/api/health"]
    }[v.role]
  }
}

# A running guest never re-reads its cloud-init medium, so a changed document
# is pushed over SSH instead: bin/bench-reseed re-runs cloud-init in place and
# leaves a VM that already consumed the current documents alone. Files, not
# arguments: user-data holds the lab's passwords.
resource "local_sensitive_file" "seed" {
  for_each = toset(flatten([for s in keys(local.services) : [for d in local.seed_docs : "${s}/${d}"]]))

  filename             = "${local.seed_dir}/${each.key}"
  file_permission      = "0600"
  directory_permission = "0700"
  content = {
    "user-data"      = module.cloud_init[split("/", each.key)[0]].user_data
    "meta-data"      = module.cloud_init[split("/", each.key)[0]].meta_data
    "network-config" = module.cloud_init[split("/", each.key)[0]].network_config
  }[split("/", each.key)[1]]
}

# The observability VM first, so it answers before any other VM reboots.
resource "terraform_data" "observability_cloud_init" {
  for_each = { for s, v in local.services : s => v if s == local.observability }

  triggers_replace = [for d in local.seed_docs : local_sensitive_file.seed["${each.key}/${d}"].content_sha256]

  provisioner "local-exec" {
    command = local.reseed[each.key]
  }

  depends_on = [module.libvirt_vm, module.proxmox_vm]
}

resource "terraform_data" "cloud_init" {
  for_each = { for s, v in local.services : s => v if s != local.observability }

  triggers_replace = [for d in local.seed_docs : local_sensitive_file.seed["${each.key}/${d}"].content_sha256]

  provisioner "local-exec" {
    command = local.reseed[each.key]
  }

  depends_on = [module.libvirt_vm, module.proxmox_vm, terraform_data.observability_ready]
}

resource "terraform_data" "riptide" {
  count = local.ok ? 1 : 0

  # A re-created ClickHouse starts empty, and riptide creates its schema only
  # at startup, so a new ClickHouse VM reinstalls and restarts riptide too.
  # So does a re-run of cloud-init on either VM: riptide exits when ClickHouse
  # is gone for 30 s, as it is while its VM reboots.
  triggers_replace = [
    var.riptide_deb_sha256,
    sha256(local.riptide_env_file),
    local.vm_ids[local.sut],
    local.vm_ids[local.clickhouse],
    terraform_data.cloud_init[local.sut].id,
    terraform_data.cloud_init[local.clickhouse].id,
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

# The observability VM, before anything it should measure starts.
resource "terraform_data" "observability_ready" {
  for_each = { for s, v in local.services : s => v if s == local.observability }

  triggers_replace = [local.vm_ids[each.key], terraform_data.observability_cloud_init[each.key].id]

  # A destroy-time provisioner may only read self. bench destroy powers the VM
  # off, and Pyroscope's unflushed blocks would reach the kept disk as empty
  # files that fail every later query, so the containers stop first.
  input = {
    host = each.value.addresses.mgmt
    stop = "sudo systemctl stop grafana pyroscope prometheus && sync"
  }

  connection {
    type    = "ssh"
    host    = self.input.host
    user    = "bench"
    agent   = true
    timeout = "10m"
  }

  # Replacing this resource ran the stop below; when bench-reseed then found
  # nothing to re-run, no reboot started the containers again.
  provisioner "remote-exec" {
    inline = [local.wait_cloud_init[each.key], "sudo systemctl start grafana pyroscope prometheus", local.wait_healthy[each.key]]
  }

  provisioner "remote-exec" {
    when       = destroy
    on_failure = continue
    inline     = [self.input.stop]
  }
}

# Every service except the SUT, which terraform_data.riptide waits for.
resource "terraform_data" "ready" {
  for_each = { for s, v in local.services : s => v if s != local.sut && s != local.observability }

  # A re-run of cloud-init reboots the VM, so its health is checked again.
  triggers_replace = [local.vm_ids[each.key], terraform_data.cloud_init[each.key].id]

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

  depends_on = [terraform_data.observability_ready]
}
