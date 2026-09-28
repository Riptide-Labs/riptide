# Benchmark labs

Build a riptide benchmark stack from one declaration: `make bench-apply EXP=<name>`.
OpenTofu places every service on a declared host and NUMA node, pins it to whole physical cores, attaches it to one network per traffic class and returns when every health check answers.
The system under test (SUT) never shares a NUMA node with another service; a declaration that tries is rejected before any VM exists.

Driving load, capturing measurements and writing reports stay with the benchmark tooling (`benchmark-capture`, `benchmark-report`).

## Prerequisites

- OpenTofu 1.9 or later (provider `for_each`), `cosign`, `jq`, `curl`, `shellcheck` and an SSH agent holding the key you connect with.
- SSH as root to every libvirt host and Proxmox node, with the agent's key.
- For Proxmox, nothing by default: `bench` asks the node for a `root@pam` login ticket over root SSH on every run.
  Proxmox accepts `cpu.affinity` only from `root@pam` logged in that way or with a password; an API token, even `root@pam`'s, is refused with `only root can set 'affinity' config`.
  To use a password instead, set **`PROXMOX_VE_USERNAME=root@pam`** and **`PROXMOX_VE_PASSWORD`**.
- **`PROXMOX_VE_INSECURE=true`** when the node's API certificate is self-signed, as on `lechuck`.
  **`PROXMOX_VE_ENDPOINT`** defaults to `https://<address or node>:8006/`.
- A route from your workstation to the `mgmt` network: apply installs riptide and waits for health checks over SSH on `mgmt`.

### One-time lab setup

Done for mad-monkey and `lechuck` on 2026-09-28; repeat for a new host.

1. Create VLAN 24 (`ingest`, `172.24.0.0/16`) and VLAN 25 (`store`, `172.25.0.0/16`) and trunk both to mad-monkey and `lechuck`.
   `mgmt` is VLAN 11.
2. On mad-monkey, create a bridge per VLAN: `br-vlan24`, `br-vlan25`, in **`/etc/netplan/20-bench-vlans.yaml`** (VLAN subinterfaces `enp2s0.24` and `enp2s0.25`, no address).
   `br0` already carries VLAN 11.
3. On mad-monkey, move Docker's address pools out of `172.24.0.0/14` in **`/etc/docker/daemon.json`**:

   ```json
   { "default-address-pools": [{ "base": "172.28.0.0/14", "size": 24 }] }
   ```

4. Check that the `mgmt` host range you declare is unused, for example `192.168.11.200-229`, and find resolvers the VMs can reach from it: `192.168.11.1` routes but does not answer DNS; `192.168.10.16` and `192.168.10.53` do.

On `lechuck`, `vmbr0` is not VLAN-aware; Proxmox then creates a `vmbr0v<tag>` bridge per tagged NIC on its own.

## Declare an experiment

1. Print each host's NUMA layout on the host itself:

   ```bash
   ssh root@lechuck.labmonkeys.tech sh -s < benchmark/bin/numa-topology
   ```

   Expected output:

   ```text
   numa = {
     0 = ["0,24", "2,26", "4,28", "6,30", "8,32", "10,34", "12,36", "14,38", "16,40", "18,42", "20,44", "22,46"]
     1 = ["1,25", "3,27", "5,29", "7,31", "9,33", "11,35", "13,37", "15,39", "17,41", "19,43", "21,45", "23,47"]
   }
   ```

   Each entry is one physical core, written as its thread siblings.
   `lechuck` interleaves its nodes: node 0 holds the even CPUs.

2. Copy **`benchmark/experiments/flow-capacity.tfvars`** to `benchmark/experiments/<name>.tfvars` and set `name` to `<name>`.
   The file name and `name` must match.
3. Check the declaration without any host:

   ```bash
   make bench-check
   ```

   Expected output (last lines):

   ```text
   == lab: tests/rejected.tftest.hcl with tests/rejected.tfvars
   Success! 1 passed, 0 failed.
   ```

### Declaration

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| **`name`** | string | required | Names the workspace, every VM (`bench-<name>-<service>`) and the `exp-<name>` label. Lowercase letters, digits and `-`. |
| **`riptide.source`** | string | required | `release:X.Y.Z` (downloaded and verified with cosign) or `deb:<path>` (relative to the repository root). |
| **`riptide.env`** | map(string) | `{}` | Lines of `/etc/riptide/riptide.env`: `JAVA_OPTS` and `RIPTIDE_*` overrides, for example receivers. |
| **`ssh_keys`** | list(string) | `[]` | Keys for user `bench` in addition to the SSH agent's. |
| **`hosts.<h>.provider`** | string | required | `libvirt` or `proxmox`. |
| **`hosts.<h>.numa`** | map(list(string)) | required | Output of `bin/numa-topology`. The last core of each node is reserved for emulator threads and the host. |
| **`hosts.<h>.uri`** | string | required on libvirt | libvirt connection URI, for example `qemu+ssh://root@mad-monkey.labmonkeys.tech/system`. |
| **`hosts.<h>.pool`** | string | `default` | libvirt storage pool for disks. |
| **`hosts.<h>.node`** | string | required on Proxmox | Proxmox node name. |
| **`hosts.<h>.datastore`** | string | required on Proxmox | Datastore for VM disks, for example `scummbar`. Snippets and the base image go to `local`. |
| **`hosts.<h>.address`** | string | node name | SSH address of a Proxmox node, for snippet upload. |
| **`hosts.<h>.bridges`** | map(string) | `vmbr0` per network on Proxmox | Host bridge per network. Required on libvirt. |
| **`networks.ingest.vlan`**, **`networks.store.vlan`** | number | required | VLAN IDs. |
| **`networks.<n>.cidr`** | string | `172.24.0.0/16`, `172.25.0.0/16`, `172.26.0.0/16` (exporters), `192.168.11.0/24` (mgmt) | Address range. |
| **`networks.mgmt.host_range`** | string | required | Addresses for the VMs, `192.168.11.200-229` form. |
| **`networks.mgmt.dns`** | list(string) | required | Resolvers the VMs use; cloud-init installs packages through them. |
| **`networks.mgmt.vlan`**, **`.gateway`** | number, string | `11`, first host | |
| **`services.<s>.role`** | string | required | `riptide` (exactly one), `clickhouse` (exactly one), `nl6`, `victoriametrics` (at most one each). |
| **`services.<s>.host`**, **`.numa_node`** | string, number | required | Placement. |
| **`services.<s>.vcpus`** | number | required | A multiple of the host's threads per core. The service gets `vcpus / threads` whole cores. |
| **`services.<s>.memory_gb`**, **`.disk_gb`**, **`.hugepages`** | number, number, bool | required, none, `false` | `disk_gb` adds a data disk mounted at `/var/lib/bench`. |
| **`services.<s>.networks`** | list(string) | required | From `ingest`, `store`, `mgmt`. riptide needs all three; nl6 needs `ingest` and `mgmt`; the others `store` and `mgmt`. |

`exporters` is not a VLAN: nl6 allocates its simulated exporters from that range inside the loadgen VM, and the SUT routes it through the loadgen's `ingest` address.
A key the table does not name is rejected, so a typo cannot fall back to a default.

## Build the lab

1. Plan:

   ```bash
   export PROXMOX_VE_INSECURE=true
   make bench-plan EXP=flow-capacity
   ```

   Expected output (last lines):

   ```text
   Plan: 28 to add, 0 to change, 0 to destroy.
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
   Apply complete! Resources: 25 added, 0 changed, 0 destroyed.
   bench: flow-capacity ready in 132 s; inventory at .../benchmark/runs/flow-capacity/inventory.json
   ```

   OpenTofu asks for approval.
   For an unattended run, pass tofu arguments through: `make bench-apply EXP=flow-capacity BENCH_TOFU_ARGS=-auto-approve`, or `benchmark/bin/bench apply flow-capacity -auto-approve`.
   `make` hands **`BENCH_TOFU_ARGS`** to `bench-apply` and `bench-destroy` only; `tofu plan` rejects `-auto-approve`.

   Apply waits for cloud-init and then for ClickHouse `/ping`, VictoriaMetrics `/health` and nl6 `/api/v1/status`, installs riptide over SSH, and waits for its `/readyz`.
   A service that misses its deadline (**`ready_timeout_seconds`**, 600) fails the apply with its name and URL.

3. Read the run files in `benchmark/runs/<name>/`:

   | File | Contents |
   | --- | --- |
   | `inventory.json` | Per service: host, NUMA node, pinned CPUs, emulator CPUs, addresses, MACs. riptide version and SHA-256, image digests, and the `sut` fields of a `benchmark-capture` manifest. |
   | `ssh_config` | `Host bench-<name>-<service>` entries: `ssh -F benchmark/runs/<name>/ssh_config bench-<name>-sut`. Host keys go to `known_hosts` next to it, since a rebuilt VM has a new key. |
   | `scrape-targets.json` | Prometheus file-SD targets for riptide `/metrics` and every node_exporter. |
   | `applied.tfvars` | The declaration apply used; destroy reads it. |

4. List the experiment's VMs on every declared host:

   ```bash
   make bench-list EXP=flow-capacity
   ```

   Expected output:

   ```text
   HOST         BACKEND   VM                                       STATE
   lechuck      proxmox   bench-flow-capacity-sut                  running
   lechuck      proxmox   bench-flow-capacity-clickhouse           running
   mad-monkey   libvirt   bench-flow-capacity-loadgen              running
   mad-monkey   libvirt   bench-flow-capacity-metrics              running
   ```

   Proxmox VMs are matched by the `exp-<name>` tag through `pvesh`; libvirt domains by their metadata, read with `virsh metadata <domain> https://riptide-labs.github.io/benchmark/1`.

## Remove the lab

```bash
make bench-destroy EXP=flow-capacity
```

Expected output (last line):

```text
Destroy complete! Resources: 21 destroyed.
```

Destroy removes the experiment's VMs, disks, cloud-init media and snippets and keeps its downloaded base images, so the next apply skips the download.
It uses `runs/<name>/applied.tfvars`, not the editable file: a host deleted from the declaration after apply still has its VMs removed.
A re-apply may add hosts but refuses to drop one, or to change its provider, while it still has VMs: destroy first.
`benchmark/bin/bench purge <name>` also removes the base images, the workspace and the run files.

## Limits

- One experiment per host at a time.
  Checks run within one declaration, so two experiments can pin the same cores.
- On Proxmox, `cpu.affinity` pins the whole QEMU process to the service's cores, so emulator threads share them; the guest sees `vcpus` cores without SMT topology. libvirt pins each vCPU to one thread and emulator threads to the reserved core.
- The `dmacvicar/libvirt` provider's signature is not checked, because its registry entry carries no GPG key; `.terraform.lock.hcl` pins its hashes.
- Image digests in **`benchmark/lab/images.auto.tfvars`** are bumped by hand; Dependabot does not read `.tfvars`.
  The ClickHouse image is read from `deployment/clickhouse/compose.yml`.
- nl6 hardcodes its veth pair at `10.254.0.1` and `10.254.0.2` (DN42 space).
  The link stays inside the loadgen VM and is never routed out.
- `make bench-check` runs no host.
  A green check says nothing about VMs booting, pinning on a real host or services answering.

## Open questions

- A libvirt-only experiment has only been planned, not applied: the lab has one libvirt host, and the SUT needs a NUMA node of its own.
