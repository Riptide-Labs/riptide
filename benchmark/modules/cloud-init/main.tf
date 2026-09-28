# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later

# Renders cloud-init user-data and network-config for one benchmark VM. Every
# document is built as an HCL value and encoded, never templated as text.
#
# riptide itself is not installed here: the lab root copies the verified .deb
# over SSH after boot, so the SUT needs no route to GitHub.

locals {
  s = var.service

  data_dir = "/var/lib/bench"

  # Container roles run their images with host networking under systemd.
  container_roles = ["clickhouse", "nl6", "victoriametrics"]
  uses_docker     = contains(local.container_roles, local.s.role)

  # Docker's default pools reach 172.24.0.0/14 on a host with enough networks,
  # so every VM moves them to 172.28.0.0/14, which the declaration protects.
  docker_daemon = {
    "default-address-pools" = [{ base = "172.28.0.0/14", size = 24 }]
    "bip"                   = "172.28.255.1/24"
  }

  unit = {
    for name, spec in local.units : name => join("\n", concat(
      [
        "[Unit]",
        "Description=${spec.description}",
        "After=docker.service network-online.target",
        "Requires=docker.service",
        "Wants=network-online.target",
        "",
        "[Service]",
        "ExecStartPre=-/usr/bin/docker rm -f ${name}",
        "ExecStart=/usr/bin/docker run --rm --name ${name} --network host ${join(" ", spec.args)} ${spec.image} ${join(" ", spec.cmd)}",
      ],
      [for c in lookup(spec, "post", []) : "ExecStartPost=${c}"],
      [
        "ExecStop=/usr/bin/docker stop ${name}",
        "Restart=on-failure",
        "RestartSec=5",
        "",
        "[Install]",
        "WantedBy=multi-user.target",
        "",
      ],
    ))
  }

  units = merge(
    local.s.role == "clickhouse" ? {
      clickhouse = {
        description = "ClickHouse for benchmark ${var.experiment}"
        image       = var.images.clickhouse
        args = [
          "--ulimit nofile=262144:262144",
          "--env-file /etc/bench/clickhouse.env",
          "-v ${local.data_dir}/clickhouse:/var/lib/clickhouse",
          "-v /etc/bench/clickhouse/config.xml:/etc/clickhouse-server/config.d/config.xml:ro",
          "-v /etc/bench/clickhouse/users.xml:/etc/clickhouse-server/users.d/users.xml:ro",
        ]
        cmd = []
      }
    } : null,
    local.s.role == "nl6" ? {
      nl6 = {
        description = "nl6 load generator for benchmark ${var.experiment}"
        image       = var.images.nl6
        args        = ["--privileged", "--device /dev/net/tun"]
        cmd         = []
        # nl6 creates veth-sim-host but does not route its simulated exporters.
        post = ["/usr/local/sbin/bench-exporters-route"]
      }
    } : null,
    local.s.role == "victoriametrics" ? {
      victoriametrics = {
        description = "VictoriaMetrics for benchmark ${var.experiment}"
        image       = var.images.victoriametrics
        args        = ["-v ${local.data_dir}/victoriametrics:/storage"]
        cmd         = ["-storageDataPath=/storage", "-retentionPeriod=90d", "-httpListenAddr=:8428"]
      }
      vmagent = {
        description = "vmagent for benchmark ${var.experiment}"
        image       = var.images.vmagent
        args        = ["-v /etc/bench/vmagent:/etc/vmagent:ro", "-v ${local.data_dir}/vmagent:/tmp/vmagent"]
        cmd         = ["-promscrape.config=/etc/vmagent/scrape.yml", "-remoteWrite.url=http://127.0.0.1:8428/api/v1/write", "-remoteWrite.tmpDataPath=/tmp/vmagent", "-httpListenAddr=:8429"]
      }
    } : null,
  )

  scrape_config = {
    global = { scrape_interval = "15s" }
    scrape_configs = [
      for job in sort(keys(var.scrape_targets)) : {
        job_name       = job
        static_configs = [{ targets = var.scrape_targets[job] }]
      }
    ]
  }

  write_files = concat(
    [
      { path = "/etc/docker/daemon.json", permissions = "0644", content = jsonencode(local.docker_daemon) },
    ],
    [for name, text in local.unit : { path = "/etc/systemd/system/${name}.service", permissions = "0644", content = text }],
    local.s.role == "riptide" ? [
      { path = "/etc/sysctl.d/60-bench-riptide.conf", permissions = "0644", content = "net.core.rmem_max = 33554432\nnet.core.rmem_default = 33554432\n" },
    ] : [],
    local.s.role == "clickhouse" ? [
      { path = "/etc/bench/clickhouse/config.xml", permissions = "0644", content = var.clickhouse_files.config_xml },
      { path = "/etc/bench/clickhouse/users.xml", permissions = "0644", content = var.clickhouse_files.users_xml },
      { path = "/etc/bench/clickhouse.env", permissions = "0600", content = "TZ=UTC\nCLICKHOUSE_USER=default\nCLICKHOUSE_DB=riptide\nCLICKHOUSE_PASSWORD=${var.clickhouse_password}\n" },
    ] : [],
    local.s.role == "nl6" ? [
      { path = "/etc/sysctl.d/60-bench-nl6.conf", permissions = "0644", content = "net.ipv4.ip_forward = 1\n" },
      {
        path        = "/usr/local/sbin/bench-exporters-route"
        permissions = "0755"
        content     = <<-EOT
          #!/bin/sh
          # nl6 creates veth-sim-host at startup; wait for it, then route the
          # simulated exporters to its namespace end.
          for i in $(seq 1 60); do
            ip link show veth-sim-host >/dev/null 2>&1 && break
            sleep 1
          done
          exec ip route replace ${var.exporters_cidr} via 10.254.0.2 dev veth-sim-host
        EOT
      },
    ] : [],
    local.s.role == "victoriametrics" ? [
      { path = "/etc/bench/vmagent/scrape.yml", permissions = "0644", content = yamlencode(local.scrape_config) },
    ] : [],
  )

  packages = concat(
    ["prometheus-node-exporter", "curl", "ca-certificates", "jq"],
    local.uses_docker ? ["docker.io"] : [],
    # Installed here so the SSH step only has the .deb left to install.
    local.s.role == "riptide" ? ["openjdk-25-jre-headless"] : [],
  )

  has_data_disk = local.s.disk_gb != null

  user_data = merge(
    {
      hostname          = "bench-${var.experiment}-${local.s.name}"
      preserve_hostname = false
      ssh_pwauth        = false
      users = [{
        name                = "bench"
        groups              = ["sudo"]
        sudo                = "ALL=(ALL) NOPASSWD:ALL"
        shell               = "/bin/bash"
        lock_passwd         = true
        ssh_authorized_keys = var.ssh_keys
      }]
      package_update = true
      packages       = local.packages
      write_files    = local.write_files
      runcmd = concat(
        [
          ["sysctl", "--system"],
          ["mkdir", "-p", local.data_dir],
        ],
        [for name in keys(local.units) : ["mkdir", "-p", "${local.data_dir}/${name}"]],
        local.uses_docker ? [["systemctl", "restart", "docker"], ["systemctl", "daemon-reload"]] : [],
        [for name in keys(local.units) : ["systemctl", "enable", "--now", "${name}.service"]],
      )
    },
    local.has_data_disk ? {
      fs_setup = [{ label = "benchdata", filesystem = "ext4", device = "/dev/vdb" }]
      mounts   = [["LABEL=benchdata", local.data_dir, "ext4", "defaults,nofail", "0", "2"]]
    } : null,
  )

  network_config = {
    version = 2
    ethernets = {
      for n, addr in local.s.addresses : n => merge(
        {
          match      = { macaddress = local.s.macs[n] }
          "set-name" = n
          addresses  = ["${addr}/${local.s.prefix[n]}"]
        },
        n == "mgmt" ? {
          nameservers = { addresses = local.s.nameservers }
        } : null,
        length(concat(
          n == "mgmt" ? [1] : [],
          [for r in local.s.routes : 1 if r.network == n],
          )) > 0 ? {
          routes = concat(
            n == "mgmt" ? [{ to = "default", via = local.s.gateway }] : [],
            [for r in local.s.routes : { to = r.to, via = r.via } if r.network == n],
          )
        } : null,
      )
    }
  }
}
