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
  container_roles = ["clickhouse", "nl6", "victoriametrics", "observability"]
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
          "-v /etc/bench/clickhouse/prometheus.xml:/etc/clickhouse-server/config.d/prometheus.xml:ro",
        ]
        cmd = []
      }
    } : null,
    local.s.role == "nl6" ? {
      nl6 = {
        description = "nl6 load generator for benchmark ${var.experiment}"
        image       = var.images.nl6
        args        = ["--privileged", "--device /dev/net/tun"]
        cmd         = var.pyroscope_url == "" ? [] : ["-profiling-pyroscope=${var.pyroscope_url}"]
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
    } : null,
    local.s.role == "observability" ? {
      prometheus = {
        description = "Prometheus for benchmark ${var.experiment}"
        image       = var.images.prometheus
        args        = ["-v /etc/bench/prometheus:/etc/prometheus:ro", "-v ${local.data_dir}/prometheus:/prometheus"]
        # Half the data disk: Pyroscope and Grafana share it.
        cmd = ["--config.file=/etc/prometheus/prometheus.yml", "--storage.tsdb.path=/prometheus", "--storage.tsdb.retention.size=${floor(coalesce(local.s.disk_gb, 2) / 2)}GB", "--web.listen-address=:9090"]
      }
      pyroscope = {
        description = "Pyroscope for benchmark ${var.experiment}"
        image       = var.images.pyroscope
        args        = ["-v ${local.data_dir}/pyroscope:/data"]
        cmd         = ["-retention-period=720h"]
      }
      grafana = {
        description = "Grafana for benchmark ${var.experiment}"
        image       = var.images.grafana
        args = [
          "-e GF_SECURITY_ADMIN_PASSWORD__FILE=/etc/grafana/admin-password",
          "-e GF_AUTH_ANONYMOUS_ENABLED=false",
          "-v /etc/bench/grafana/admin-password:/etc/grafana/admin-password:ro",
          "-v /etc/bench/grafana/provisioning:/etc/grafana/provisioning:ro",
          "-v /etc/bench/grafana/dashboards:/var/lib/grafana-dashboards:ro",
          "-v ${local.data_dir}/grafana:/var/lib/grafana",
        ]
        cmd = []
      }
    } : null,
  )

  prometheus_config = {
    global     = { scrape_interval = "10s", evaluation_interval = "10s" }
    rule_files = var.alert_rules == "" ? [] : ["/etc/prometheus/riptide-alerts.yml"]
    scrape_configs = [
      for job in sort(keys(var.prometheus_jobs)) : {
        job_name       = job
        metrics_path   = "/metrics"
        static_configs = [for t in var.prometheus_jobs[job] : { targets = [t.target], labels = t.labels }]
      }
    ]
  }

  # Same uids as the compose stack's self-monitoring datasources.
  grafana_datasources = {
    apiVersion = 1
    datasources = [
      { name = "Prometheus", uid = "riptide-prometheus", type = "prometheus", url = "http://localhost:9090", jsonData = { timeInterval = "10s" } },
      { name = "Pyroscope", uid = "riptide-pyroscope", type = "grafana-pyroscope-datasource", url = "http://localhost:4040" },
    ]
  }

  grafana_dashboard_provider = {
    apiVersion = 1
    providers  = [{ name = "riptide", type = "file", folder = "Riptide", options = { path = "/var/lib/grafana-dashboards" } }]
  }

  write_files = concat(
    [
      { path = "/etc/docker/daemon.json", permissions = "0644", content = jsonencode(local.docker_daemon) },
    ],
    # node_exporter listens only on observe, so metrics never leave over
    # mgmt, ingest or store. A drop-in, not /etc/default: that file is the
    # package's conffile, and cloud-init writes files before installing it.
    contains(keys(local.s.addresses), "observe") ? [
      {
        path        = "/etc/systemd/system/prometheus-node-exporter.service.d/10-bench-listen.conf"
        permissions = "0644"
        content     = "[Service]\nExecStart=\nExecStart=/usr/bin/prometheus-node-exporter --web.listen-address=${local.s.addresses.observe}:9100 $ARGS\n"
      },
    ] : [],
    [for name, text in local.unit : { path = "/etc/systemd/system/${name}.service", permissions = "0644", content = text }],
    local.s.role == "riptide" ? [
      { path = "/etc/sysctl.d/60-bench-riptide.conf", permissions = "0644", content = "net.core.rmem_max = 33554432\nnet.core.rmem_default = 33554432\n" },
    ] : [],
    local.s.role == "clickhouse" ? [
      { path = "/etc/bench/clickhouse/config.xml", permissions = "0644", content = var.clickhouse_files.config_xml },
      { path = "/etc/bench/clickhouse/users.xml", permissions = "0644", content = var.clickhouse_files.users_xml },
      { path = "/etc/bench/clickhouse.env", permissions = "0600", content = "TZ=UTC\nCLICKHOUSE_USER=default\nCLICKHOUSE_DB=riptide\nCLICKHOUSE_PASSWORD=${var.clickhouse_password}\n" },
      # Prometheus metrics for the lab's Prometheus; the compose stack's config.xml stays untouched.
      {
        path        = "/etc/bench/clickhouse/prometheus.xml"
        permissions = "0644"
        content     = "<clickhouse>\n  <prometheus>\n    <endpoint>/metrics</endpoint>\n    <port>9363</port>\n    <metrics>true</metrics>\n    <events>true</events>\n    <asynchronous_metrics>true</asynchronous_metrics>\n  </prometheus>\n</clickhouse>\n"
      },
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
    local.s.role == "observability" ? concat(
      [
        { path = "/etc/bench/prometheus/prometheus.yml", permissions = "0644", content = yamlencode(local.prometheus_config) },
        # 0644: the Grafana container user (472) reads it through a bind mount; it exists only on this VM.
        { path = "/etc/bench/grafana/admin-password", permissions = "0644", content = var.grafana_admin_password },
        { path = "/etc/bench/grafana/provisioning/datasources/lab.yml", permissions = "0644", content = yamlencode(local.grafana_datasources) },
        { path = "/etc/bench/grafana/provisioning/dashboards/lab.yml", permissions = "0644", content = yamlencode(local.grafana_dashboard_provider) },
      ],
      var.alert_rules == "" ? [] : [{ path = "/etc/bench/prometheus/riptide-alerts.yml", permissions = "0644", content = var.alert_rules }],
      [for f in sort(keys(var.grafana_dashboards)) : { path = "/etc/bench/grafana/dashboards/${f}", permissions = "0644", content = var.grafana_dashboards[f] }],
    ) : [],
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
      # Large files (the dashboards) travel compressed in the user-data.
      write_files = [
        for f in local.write_files : length(f.content) > 4096
        ? { path = f.path, permissions = f.permissions, encoding = "gz+b64", content = base64gzip(f.content) }
        : f
      ]
      runcmd = concat(
        [
          ["sysctl", "--system"],
          ["mkdir", "-p", local.data_dir],
        ],
        [for name in keys(local.units) : ["mkdir", "-p", "${local.data_dir}/${name}"]],
        # Each container writes as its image's user.
        local.s.role == "observability" ? [
          ["chown", "-R", "65534:65534", "${local.data_dir}/prometheus"],
          ["chown", "-R", "10001:10001", "${local.data_dir}/pyroscope"],
          ["chown", "-R", "472:472", "${local.data_dir}/grafana"],
        ] : [],
        local.uses_docker ? [["systemctl", "restart", "docker"], ["systemctl", "daemon-reload"]] : [],
        contains(keys(local.s.addresses), "observe") ? [["systemctl", "daemon-reload"], ["systemctl", "restart", "prometheus-node-exporter"]] : [],
        [for name in keys(local.units) : ["systemctl", "enable", "--now", "${name}.service"]],
      )
    },
    local.has_data_disk ? {
      # overwrite = false: a reattached data disk (the observability VM's)
      # keeps its data.
      fs_setup = [{ label = "benchdata", filesystem = "ext4", device = "/dev/vdb", overwrite = false }]
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
