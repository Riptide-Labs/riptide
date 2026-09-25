---
sidebar_position: 2
title: Upgrading from 0.8
description: Convert a 0.8 riptide.nodes configuration with the bundled converter, remove the keys 0.9 and later do not read, and what changes in behaviour.
---

# Upgrade from 0.8

0.9 removed the `riptide.nodes` tree.
A surviving key in any spelling, `riptide.nodes.<name>.*`, the indexed `riptide.nodes[0].*`, camelCase or `RIPTIDE_NODES_*`, fails startup with a report naming the key.

## Prerequisites

- The current jar. The converter is part of it, see [subcommands](../../guides/plain-jar.md#subcommands).
- The 0.8 configuration as nested YAML: `riptide:` containing `nodes:` containing the node names. A flat properties file is not read; re-indent the `riptide.nodes` and `riptide.snmp.poll` trees into a small YAML file first, since only those two are read.

## Steps

1. Convert. With the packaged jar:

   ```bash
   java -jar /usr/share/riptide/riptide.jar convert /etc/riptide/config.yaml \
       --out-config config-fragment.yaml --out-inventory inventory.yaml
   ```

   Expected output, for a configuration with one v3 host and one v2c subnet:

   ```text
   Converted 2 node(s): 2 credential set(s), 1 polling profile(s), 2 agent range(s), 2 enrichment entry/entries.
   Disabled 1 range ('campus', 10.20.40.0/24): v2c credentials on a range wider than one address. Its flows are still named; only polling stops.
   Re-enable a disabled range by enumerating its devices as single addresses, or by moving the segment to v3. Both are in the comment above each entry.
   Wrote credential sets and polling profiles to config-fragment.yaml
   Wrote agent ranges and enrichment entries to inventory.yaml
   Before starting 0.9, remove 'riptide.nodes' from your application config, and the retired 'riptide.snmp.poll.refresh-interval-ms' and '.snapshot-expiry-ms' keys if you had them. Both fail startup: leaving either in place means the collector will not come up.
   ```

   In Docker, against the mount the service uses and before switching the service to the new image:

   ```bash
   docker run --rm -v /srv/riptide:/etc/riptide ghcr.io/riptide-labs/riptide:%%VERSION%% \
       -jar /app/riptide.jar convert /etc/riptide/config.yaml \
       --out-config /etc/riptide/config-fragment.yaml --out-inventory /etc/riptide/inventory.yaml
   ```

   The image's entrypoint is `java`, so the arguments restate `-jar /app/riptide.jar`.

2. Merge **`config-fragment.yaml`** into the main configuration and set `riptide.inventory.file` to the emitted inventory. The fragment holds the credential sets and polling profiles:

   ```yaml
   riptide:
     snmp:
       credentials:
         credentials-1:
           version: "v2c"
           community: "env://RIPTIDE_SNMP_COMMUNITY"
         credentials-2:
           version: "v3"
           security-name: "monitoring"
           auth-protocol: "hmac192sha256"
           auth-passphrase: "vault://secret/snmp/core#auth"
           priv-protocol: "aes256"
           priv-passphrase: "vault://secret/snmp/core#priv"
       polling:
         default:
           refresh-interval: PT1M
           timeout: 500
           retries: 1
   ```

3. Remove the keys this release does not read. Three fail startup, together in one report; three are ignored with a log line, which is easy to miss.

   | Key | Effect |
   | --- | --- |
   | the whole `riptide.nodes` tree | fails startup |
   | `riptide.snmp.poll.refresh-interval-ms`, `riptide.snmp.poll.snapshot-expiry-ms` | fails startup; cadence lives on [polling profiles](../../reference/agent-configuration.md#settings) |
   | `riptide.snmp.agents`, `riptide.exporters` in the main configuration | fails startup; they belong in the inventory file, or `riptide.exporters` in the [discovery](../../reference/discovery.md) endpoint |
   | `riptide.snmp.config.definitions` | ignored, logged as an error |
   | `riptide.snmp.cache.retention-ms`, `.negative-retention-ms`, `.dead-endpoint-retention-ms` | ignored, logged as a warning |

   The other `riptide.snmp.poll.*` keys, `pool-width`, `max-exporters`, `deregister-after` and the dead-endpoint back-off, are current and stay where they are. The converter neither reads nor emits them.

   Expected output when all three failing kinds are still present:

   ```text
   Configuration carries 3 key(s) this release does not read:

     riptide.nodes tree (1): riptide.nodes.core-router.subnet-address
       -> riptide.nodes was removed in 0.9: exporter names, interface pins and SNMP credentials now come from the credential sets and polling profiles in the main config plus the inventory file (riptide.inventory.file).

   Convert it, do not delete it:
       riptide convert <your-config.yaml> --out-config config.yaml --out-inventory inventory.yaml

   The converter deduplicates credential blocks, keeps every exporter name, and refuses rather than emitting anything 0.9 will not start on. Then remove the riptide.nodes tree from your configuration. The 0.9 release notes carry the full upgrade guide.

     retired per-agent poll keys (1): riptide.snmp.poll.refresh-interval-ms
       -> refresh and expiry moved into named polling profiles: configure riptide.snmp.polling.<name>.refresh-interval / .snapshot-expiry and reference the profile from agent ranges. Fleet-level riptide.snmp.poll.* keys are unaffected.

     inventory trees in the main configuration (1): riptide.snmp.agents.10.20.30.7.credentials
       -> riptide.snmp.agents and riptide.exporters live only in the dedicated inventory file named by riptide.inventory.file — they bind to nothing here and would be silently ignored.
   ```

4. Start the new version and check the inventory line in the log:

   ```text
   org.riptide.inventory.Inventory          : Inventory loaded from /etc/riptide/inventory.yaml: 2 agent ranges, 2 enrichment entries
   ```

   The converter's output passes validation. Where it cannot represent a node it refuses with an error naming the node instead of emitting a file that will not start.

## What the converter writes

Every 0.8 node splits into an agent range, how to talk to the device, and an enrichment entry, what to call its flows, keyed by the old node name so exporter names survive.
Identical credential blocks become one named set.
A non-default `port` lands on the range.

| Input | Output |
| --- | --- |
| A node with a single address and v3 credentials | An agent range referencing the shared credential set, and an enrichment entry with the node's pin and interface pins |
| A node with a v1 or v2c community on a subnet | An enrichment entry with the same coverage, so its flows keep their name, and an agent range written `enabled: false` with the rationale and both remedies as a comment above it. The credential set is kept, unreferenced, so re-enabling is one line. The node's `timeout` and `retries` are dropped, so re-enabling also means writing them into a polling profile by hand |
| A node that named a whole subnet | A prefix enrichment entry with the same coverage |
| `riptide.snmp.poll.refresh-interval-ms` | `refresh-interval` on the `default` polling profile |
| One field in two spellings, such as `subnet-address` and `subnetAddress` on one node | Refused, naming both |
| A field the converter does not know | Refused, naming the node and the field |

The inventory half from the run above:

```yaml
riptide:
  snmp:
    agents:
      # Range 'campus' used v2c credentials. Disabled: the cleartext
      # community would be sent to any in-range address that emits a flow.
      # Either enumerate the devices as single addresses, or migrate the
      # segment to v3. Its credentials are kept as 'credentials-1' so re-enabling
      # is one line. (FR-9)
      "10.20.40.0/24":
        enabled: false
      "10.20.30.7":
        credentials: credentials-2
        polling: default
  exporters:
    "campus":
      address: "10.20.40.0/24"
    "core-router":
      address: "10.20.30.7"
      observation-domain: 42
      interfaces:
        "3":
          name: "ge-0/0/3"
          alias: "Peering with AS64500"
          high-speed: 10000
```

### Where the output goes

| Invocation | Documents | Summary | Diagnostics |
| --- | --- | --- | --- |
| with `--out-config` and `--out-inventory` | the two files | stdout | stderr |
| with one of them | that file; the other document on stdout | stderr | stderr |
| without them | stdout, separated by `---` | stderr | stderr |

Diagnostics never reach stdout, so `riptide convert nodes.yaml > new.yaml` writes valid YAML to split by hand.
The file is not loadable as either configuration on its own: fed to riptide whole, its inventory half fails startup as a misplaced tree.
If the converted cadence expires snapshots faster than it refreshes them, the summary says so and names both keys, including the one it took from the default. That is a warning, not an error.

### Environment-variable configurations

Spring never bound a multi-word node name from an environment variable: `RIPTIDE_NODES_CORE_ROUTER_SUBNET_ADDRESS` resolved to no node, so such a node was not active in 0.8 either and the variable can go.
A single-word name such as `RIPTIDE_NODES_EDGE_SUBNET_ADDRESS` did bind; write those nodes out as nested YAML and convert that file.

## Behaviour changes

| Area | 0.8 | 0.9 and later |
| --- | --- | --- |
| Poll cadence | one fleet-wide interval | each range walks on its profile's cadence |
| Which device is polled | named nodes only | any device inside a credentialed range, from its first flow; `riptide.snmp.poll.max-exporters` and `pool-width` bound it |
| Which credentials poll a device covered by two nodes | whichever the first flow after start-up selected, because the poller held one registration per address | the most specific range, by longest prefix, whatever observation domain arrives; `riptide convert` names every node this applies to |
| Observation-domain pin | scoped naming and polling | scopes naming and interface pins only; agent ranges carry no pin, because one address has one SNMP agent |
| Inventory edits | restart | hot reload on content change once `riptide.config.reload-interval` is set, see [config hot-reload](../hot-reload.md); a rejected file keeps the last good inventory and raises `inventory.reload.stale` |
| Secret value rotation | | `file://` needs no reload, `env://` needs a restart, `sops://` is cached until the next config reload, see [secret references](../../reference/secret-references.md#rotation) |

