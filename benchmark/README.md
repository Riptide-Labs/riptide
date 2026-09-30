# Benchmark labs

Build a riptide benchmark stack from one declaration: `make bench-apply EXP=<name>`.
OpenTofu places every service on a declared host and NUMA node, pins it to whole physical cores, attaches it to one network per traffic class and returns when every health check answers.
The system under test (SUT) never shares a NUMA node with another service; a declaration that tries is rejected before any VM exists.

Driving load, capturing measurements and writing reports stay with the benchmark tooling (`benchmark-capture`, `benchmark-report`).

A declaration comes from two files:

| File | In git | Holds |
| --- | --- | --- |
| **`benchmark/site.tfvars`** | no | Your lab: hosts, their addresses, storage, NUMA layout and bridges; networks and resolvers; protected address ranges. |
| **`benchmark/experiments/<name>.tfvars`** | yes | One experiment: the riptide build and which service runs on which host key and NUMA node, with what resources. |

A key in the wrong file is rejected, so a lab's addresses cannot end up in a committed experiment.

## Prerequisites

- OpenTofu 1.9 or later (provider `for_each`), `cosign`, `jq`, `curl`, `shellcheck` and an SSH agent holding the key you connect with.
- SSH as root to every libvirt host and Proxmox node, with the agent's key.
- For Proxmox, nothing by default: `bench` asks the node for a `root@pam` login ticket over root SSH on every run.
  Proxmox accepts `cpu.affinity` only from `root@pam` logged in that way or with a password; an API token, even `root@pam`'s, is refused with `only root can set 'affinity' config`.
  To use a password instead, set **`PROXMOX_VE_USERNAME=root@pam`** and **`PROXMOX_VE_PASSWORD`**.
- **`PROXMOX_VE_INSECURE=true`** when the node's API certificate is self-signed.
  **`PROXMOX_VE_ENDPOINT`** defaults to `https://<address or node>:8006/`.
- A route from your workstation to the `mgmt` network: apply installs riptide and waits for health checks over SSH on `mgmt`.

### One-time lab setup

1. Create a VLAN for `ingest`, `store` and `observe` (24, 25 and 26 in the example) and trunk them, and the `mgmt` VLAN, to every host.
   `observe` is needed only for an observability service.
2. On a libvirt host, create an address-less bridge per VLAN, for example with netplan: VLAN subinterfaces `<nic>.24`, `<nic>.25` and `<nic>.26` in bridges `br-vlan24`, `br-vlan25` and `br-vlan26`.
   A Proxmox node needs nothing: a NIC tagged on `vmbr0` gets a `vmbr0v<tag>` bridge when `vmbr0` is not VLAN-aware.
3. On a libvirt host that runs Docker, move Docker's address pools out of `172.24.0.0/14` in **`/etc/docker/daemon.json`**:

   ```json
   { "default-address-pools": [{ "base": "172.28.0.0/14", "size": 24 }] }
   ```

4. Pick an unused `mgmt` host range, one address per service, and resolvers the VMs can reach from `mgmt`.
   The `mgmt` gateway is not assumed to answer DNS.

## Describe your lab

1. Copy the example site file:

   ```bash
   cp benchmark/site.example.tfvars benchmark/site.tfvars
   ```

2. Print each host's NUMA layout on the host itself and paste it into the host's `numa`:

   ```bash
   ssh root@pve-1.example.org sh -s < benchmark/bin/numa-topology
   ```

   Expected output, for a two-socket host that interleaves its nodes:

   ```text
   numa = {
     0 = ["0,24", "2,26", "4,28", "6,30", "8,32", "10,34", "12,36", "14,38", "16,40", "18,42", "20,44", "22,46"]
     1 = ["1,25", "3,27", "5,29", "7,31", "9,33", "11,35", "13,37", "15,39", "17,41", "19,43", "21,45", "23,47"]
   }
   ```

   Each entry is one physical core, written as its thread siblings.

3. Keep the host keys the experiments use (`pve-1`, `kvm-1` in `flow-capacity`) and point them at your machines.
   `BENCH_SITE=<path>` selects another site file; `.gitignore` covers any `benchmark/site*.tfvars` but the example.

### Site file

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| **`hosts.<h>.provider`** | string | required | `libvirt` or `proxmox`. |
| **`hosts.<h>.numa`** | map(list(string)) | required | Output of `bin/numa-topology`. The last core of each node is reserved for emulator threads and the host. |
| **`hosts.<h>.uri`** | string | required on libvirt | libvirt connection URI, for example `qemu+ssh://root@kvm-1.example.org/system`. |
| **`hosts.<h>.pool`** | string | `default` | libvirt storage pool for disks. |
| **`hosts.<h>.node`** | string | required on Proxmox | Proxmox node name. |
| **`hosts.<h>.datastore`** | string | required on Proxmox | Datastore for VM disks, for example `local-zfs`. Snippets and the base image go to `local`. |
| **`hosts.<h>.address`** | string | node name | SSH and API address of a Proxmox node. |
| **`hosts.<h>.bridges`** | map(string) | `vmbr0` per network on Proxmox | Host bridge per network. Required on libvirt. |
| **`networks.ingest.vlan`**, **`networks.store.vlan`** | number | required | VLAN IDs. |
| **`networks.observe.vlan`** | number | required when `observe` is declared | VLAN of the out-of-band network: every scrape and profile push. Needed for an observability service. |
| **`networks.ingest.cidr`**, **`.store.cidr`**, **`.observe.cidr`**, **`.exporters.cidr`** | string | `172.24.0.0/16`, `172.25.0.0/16`, `172.26.0.0/16`, `172.27.0.0/16` | Address ranges. |
| **`networks.mgmt.vlan`**, **`networks.mgmt.cidr`** | number, string | required | The management VLAN and its range. |
| **`networks.mgmt.host_range`** | string | required | Addresses for the VMs, `192.0.2.200-229` form. |
| **`networks.mgmt.dns`** | list(string) | required | Resolvers the VMs use; cloud-init installs packages through them. |
| **`networks.mgmt.gateway`** | string | first host of `cidr` | Default route. |
| **`protected_ranges`** | map(string) | `{}` | Name to CIDR of ranges no lab network may overlap: other networks, clusters, overlays, and DN42 (`172.20.0.0/14`) if the site peers into it. The Docker ranges `172.17.0.0/16` and `172.28.0.0/14` are always protected, under names a site cannot reuse. |

`exporters` is not a VLAN: nl6 allocates its simulated exporters from that range inside the loadgen VM, and the SUT routes it through the loadgen's `ingest` address.

## Declare an experiment

1. Copy **`benchmark/experiments/flow-capacity.tfvars`** to `benchmark/experiments/<name>.tfvars` and set `name` to `<name>`.
   The file name and `name` must match.
2. Check the declarations without any host:

   ```bash
   make bench-check
   ```

   Expected output (last lines):

   ```text
   == lab: tests/java_opts.tftest.hcl with ../site.example.tfvars,tests/java-opts.tfvars
   Success! 1 passed, 0 failed.
   ```

   `bench-check` plans every committed experiment's layout against `site.example.tfvars`, not your site file.

### Experiment file

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| **`name`** | string | required | Names the workspace, every VM (`bench-<name>-<service>`) and the `exp-<name>` label. Lowercase letters, digits and `-`. |
| **`riptide.source`** | string | required | `release:X.Y.Z` (downloaded and verified with cosign) or `deb:<path>` (relative to the repository root). |
| **`riptide.env`** | map(string) | `{}` | Lines of `/etc/riptide/riptide.env`: `JAVA_OPTS` and `RIPTIDE_*` overrides, for example receivers. |
| **`ssh_keys`** | list(string) | `[]` | Keys for user `bench` in addition to the SSH agent's. |
| **`services.<s>.role`** | string | required | `riptide` (exactly one), `clickhouse` (exactly one), `nl6`, `victoriametrics`, `observability` (at most one each). `observability` needs `disk_gb`; when it exists every service joins `observe`. |
| **`services.<s>.host`**, **`.numa_node`** | string, number | required | Placement: a host key from the site file and one of its NUMA nodes. |
| **`services.<s>.vcpus`** | number | required | A multiple of the host's threads per core. The service gets `vcpus / threads` whole cores. |
| **`services.<s>.memory_gb`**, **`.disk_gb`**, **`.hugepages`** | number, number, bool | required, none, `false` | `disk_gb` adds a data disk mounted at `/var/lib/bench`. |
| **`services.<s>.networks`** | list(string) | required | From `ingest`, `store`, `observe`, `mgmt`. riptide needs `ingest`, `store` and `mgmt`; nl6 `ingest` and `mgmt`; clickhouse and victoriametrics `store` and `mgmt`; observability `observe` and `mgmt`. |

A key neither table names is rejected, so a typo cannot fall back to a default.

## Build the lab

1. Plan:

   ```bash
   export PROXMOX_VE_INSECURE=true
   make bench-plan EXP=flow-capacity
   ```

   Expected output (last lines, on hosts without the base image):

   ```text
   Plan: 36 to add, 0 to change, 0 to destroy.
   ```

   `bench` resolves `riptide.source` first: a release is downloaded to `benchmark/.cache/` and verified against its cosign bundle and the release workflow's identity, every run.
   When a libvirt host is declared, it also downloads the Debian base image to `benchmark/.cache/` and checks it against the SHA-512 pinned in **`benchmark/lab/images.auto.tfvars`**; Proxmox nodes download it themselves and check the same SHA-512.
   A package or image that fails verification stops the command before OpenTofu runs.

2. Apply:

   ```bash
   make bench-apply EXP=flow-capacity
   ```

   Expected output (last lines, 2026-09-28, base images already on both hosts):

   ```text
   Apply complete! Resources: 32 added, 0 changed, 0 destroyed.
   bench: flow-capacity ready in 204 s; inventory at .../benchmark/runs/flow-capacity/inventory.json
   ```

   OpenTofu asks for approval.
   For an unattended run, pass tofu arguments through: `make bench-apply EXP=flow-capacity BENCH_TOFU_ARGS=-auto-approve`, or `benchmark/bin/bench apply flow-capacity -auto-approve`.
   `make` hands **`BENCH_TOFU_ARGS`** to `bench-apply` and `bench-destroy` only; `tofu plan` rejects `-auto-approve`.

   Apply waits for cloud-init and the observability VM (Prometheus, Pyroscope, Grafana) first, then for ClickHouse `/ping`, VictoriaMetrics `/health` and nl6 `/api/v1/status`, installs riptide over SSH, and waits for its `/readyz`.
   A service that misses its deadline (**`ready_timeout_seconds`**, 600) fails the apply with its name and URL.

3. Read the run files in `benchmark/runs/<name>/`:

   | File | Contents |
   | --- | --- |
   | `inventory.json` | Per service: host, NUMA node, pinned CPUs, emulator CPUs, addresses, MACs. riptide version and SHA-256, image digests, and the `sut` fields of a `benchmark-capture` manifest. |
   | `ssh_config` | `Host bench-<name>-<service>` entries: `ssh -F benchmark/runs/<name>/ssh_config bench-<name>-sut`. Host keys go to `known_hosts` next to it, since a rebuilt VM has a new key. |
   | `prometheus-jobs.json` | The scrape jobs the lab's Prometheus runs, every target with its labels. |
   | `grafana-admin` | Grafana's admin password, mode 0600. |
   | `seed/<service>/` | The service's rendered cloud-init documents, mode 0600: they hold the lab's passwords. See [Change a running lab](#change-a-running-lab). |
   | `applied-site.tfvars`, `applied.tfvars` | The site and experiment files apply used; destroy reads them. |

4. List the experiment's VMs on every declared host:

   ```bash
   make bench-list EXP=flow-capacity
   ```

   Expected output:

   ```text
   HOST         BACKEND   VM                                       STATE
   pve-1        proxmox   bench-flow-capacity-sut                  running
   pve-1        proxmox   bench-flow-capacity-clickhouse           running
   kvm-1        libvirt   bench-flow-capacity-loadgen              running
   kvm-1        libvirt   bench-flow-capacity-metrics              running
   kvm-1        libvirt   bench-flow-capacity-observe              running
   ```

   Proxmox VMs are matched by the `exp-<name>` tag through `pvesh`; libvirt domains by their metadata, read with `virsh metadata <domain> https://riptide-labs.github.io/benchmark/1`.

## Watch the lab

With an observability service, the lab measures and profiles itself on the `observe` network; `mgmt` carries none of it.
A lab without one collects no telemetry: VictoriaMetrics belongs to the system under test and only riptide writes to it.

| What | Where |
| --- | --- |
| Grafana, with `riptide-health`, `riptide-stage-detail` and `riptide-profiling` | `http://<observability mgmt address>:3000`, user `admin`, password in `runs/<name>/grafana-admin` |
| Prometheus | `http://<observability mgmt address>:9090` |
| Pyroscope | `http://<observability mgmt address>:4040` |

The addresses are in `runs/<name>/inventory.json` under `observability`.
Prometheus scrapes every 10 s: node_exporter on every VM (it listens only on `observe`), riptide, VictoriaMetrics, ClickHouse (port 9363), itself and Pyroscope, every target labelled with `experiment`, `service`, `role` and `host`.
riptide and nl6 push continuous profiles to Pyroscope.
VictoriaMetrics is part of the system under test: only riptide writes to it.

## Change a running lab

Apply again after editing the experiment, the site file or the cloud-init module.
A VM whose rendered cloud-init documents (user-data, network-config, meta-data) changed gets them in place:

1. Apply compares, over SSH, the user-data the guest consumed with the new one.
   user-data carries a digest of the other two documents, so this one comparison covers all three.
2. If they differ, apply writes the documents to `/var/lib/cloud/seed/nocloud/` in the guest.
   It also adds `/etc/cloud/cloud.cfg.d/90-bench-seed.cfg`, which stops cloud-init reading the attached cloud-init medium, since that medium can be stale.
3. It runs `cloud-init clean` and reboots the VM, then waits for cloud-init and the VM's health checks again.
   On the observability VM, Grafana, Pyroscope and Prometheus stop cleanly first.
   After the SUT or ClickHouse reboots, riptide is restarted and its `/readyz` waited for.

The root disk, the data disk and the SSH host keys stay, and a VM that already runs the current documents is not rebooted.
The first apply of a running lab created before this behaviour existed reboots every VM once.

A re-run adds and overwrites. It never undoes anything.
A file dropped from `write_files`, a removed package or a removed unit stays on the root disk.
To start a libvirt VM from a clean root disk, replace its root volume and domain:

```bash
benchmark/bin/bench apply <name> -auto-approve \
  '-replace=module.libvirt_vm["<service>"].libvirt_volume.root' \
  '-replace=module.libvirt_vm["<service>"].libvirt_domain.this'
```

On Proxmox, replace `module.proxmox_vm["<service>"].proxmox_virtual_environment_vm.this`, which imports a new root disk.

## Remove the lab

```bash
make bench-destroy EXP=flow-capacity
```

Expected output (last line):

```text
Destroy complete! Resources: 32 destroyed.
```

Destroy removes the experiment's VMs, disks, cloud-init media and snippets and keeps its downloaded base images and the observability data disk, so the next apply skips the download and Prometheus and Pyroscope keep their history.
Grafana, Pyroscope and Prometheus are stopped before their VM is removed, since a powered-off Pyroscope leaves empty blocks that fail every later query.
It uses the files saved in `runs/<name>/`, not the editable ones: a host deleted from the site file after apply still has its VMs removed.
A re-apply may add hosts but refuses to drop one, or to change its provider, while it still has VMs: destroy first.
`benchmark/bin/bench purge <name>` also removes the base images, the observability data, the workspace and the run files.

## Limits

- One experiment per host at a time.
  Checks run within one declaration, so two experiments can pin the same cores.
- On Proxmox, `cpu.affinity` pins the whole QEMU process to the service's cores, so emulator threads share them; the guest sees `vcpus` cores without SMT topology.
  libvirt pins each vCPU to one thread and emulator threads to the reserved core.
- The `dmacvicar/libvirt` provider's signature is not checked, because its registry entry carries no GPG key; `.terraform.lock.hcl` pins its hashes.
- The Debian base image in **`benchmark/lab/images.auto.tfvars`** is bumped by hand; Dependabot does not read `.tfvars`.
  Container images live in **`benchmark/images/compose.yml`**, which Dependabot keeps current; ClickHouse is read from `deployment/clickhouse/compose.yml`.
- nl6 hardcodes its veth pair at `10.254.0.1` and `10.254.0.2`.
  The link stays inside the loadgen VM and is never routed out.
- The Grafana dashboards come from this checkout, not from the riptide release under test.
  A release older than the checkout lacks series they read: on 2026-09-28, 21 of the 36 Prometheus panel queries returned nothing with `release:0.16.2`, against 4 with a package built from the checkout.
- On Proxmox, the kept observability volume is allocated by a name without a format extension; ZFS and LVM-thin were verified, Ceph RBD was not.
  A file-based datastore (directory, NFS, CIFS) needs a `.raw` or `.qcow2` name, so `pvesm alloc` fails there.
- Prometheus keeps at most half the observability data disk. Pyroscope 2.3.1 has no size cap for its storage, so its retention is derived from the disk: half the disk at 2 GB a day, at most 30 days (25 days for 100 GB).
  2 GB a day is twice the 0.96 GB a day measured at 4.2k flows/s on 2026-09-28; a higher profile rate can take more than its half.
- `make bench-check` runs no host.
  A green check says nothing about VMs booting, pinning on a real host or services answering.

## Open questions

- A libvirt-only experiment has only been planned, not applied: the lab it was verified on has one libvirt host, and the SUT needs a NUMA node of its own.
