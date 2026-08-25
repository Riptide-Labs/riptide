---
sidebar_position: 7
title: Upgrading from 0.8
---

# Upgrading from 0.8

0.9 removes the `riptide.nodes` tree.
Any surviving key, in any spelling (`riptide.nodes.<name>.*`, the old indexed `riptide.nodes[0].*`, camelCase, or the `RIPTIDE_NODES_*` environment form) **fails startup** with an error naming the offending key and pointing here.
The failure is deliberate: nothing reads that tree any more, and a collector that started anyway would run with your whole device configuration silently doing nothing.

## The mechanical path

1. **Convert, do not hand-port.** With the 0.9 jar, against your 0.8 configuration:

   ```bash
   riptide convert /etc/riptide/application.yaml \
       --out-config config-fragment.yaml --out-inventory inventory.yaml
   ```

   The deb/rpm packages and the container image ship the jar without a `riptide` wrapper; there the invocation is:

   ```bash
   java -jar /usr/share/riptide/riptide.jar convert /etc/riptide/config.yaml \
       --out-config config-fragment.yaml --out-inventory inventory.yaml
   ```

   (`/etc/riptide/config.yaml` is where the deb/rpm packages install the configuration.)

   Docker, against the same mount the service uses, **before** switching the service to the 0.9 image:

   ```bash
   docker run --rm -v /srv/riptide:/etc/riptide ghcr.io/riptide-labs/riptide:0.9 \
       -jar /app/riptide.jar convert /etc/riptide/config.yaml \
       --out-config /etc/riptide/config-fragment.yaml --out-inventory /etc/riptide/inventory.yaml
   ```

   (The image's entrypoint is `java`, so the arguments restate `-jar /app/riptide.jar`; without them Docker would try to run a class named `convert`.)

2. **Merge the config fragment** (credential sets, polling profiles) into your `application.yaml`, and set `riptide.inventory.file` to the emitted inventory.
3. **Remove the old keys**: the whole `riptide.nodes` tree, and `riptide.snmp.poll.refresh-interval-ms` / `.snapshot-expiry-ms` if you had them (cadence lives on [polling profiles](configuration/agent-configuration.md#polling-profiles) now). All of them fail startup if left behind.
   **Leave the other `riptide.snmp.poll.*` keys exactly where they are.** `pool-width`, `max-exporters`, `deregister-after` and the dead-endpoint backoff are not retired: they bind in 0.9 as they did in 0.8, the converter does not read or emit them, and they stay in your `application.yaml` untouched. Only the two cadence keys above moved.
4. Start 0.9.
   The converter's output always passes 0.9 validation; if it cannot represent something, it refuses with an error naming the node rather than emitting a file that will not boot.

:::note[The converter reads nested YAML]
It wants the shape Spring writes as a tree — `riptide:` containing `nodes:`, containing the node name — not flat dotted property names, and not a `.properties` file. If your 0.8 configuration is flat, re-indent the `riptide.nodes` tree into a small YAML file and convert that; the rest of your configuration does not need to come with it, since only `riptide.nodes` and `riptide.snmp.poll` are read.

**Configured entirely through environment variables?** Two things are worth knowing before you start. Spring never bound a multi-word node name from an environment variable: `RIPTIDE_NODES_CORE_ROUTER_SUBNET_ADDRESS` resolves to no node at all, so a hyphenated node configured that way was **not active in 0.8 either** — there is nothing to convert, and the variable should simply go. Single-word names (`RIPTIDE_NODES_EDGE_SUBNET_ADDRESS`) did bind; write those out as nested YAML and convert that file. Startup tells you which case you are in.
:::

## What the converter does with your nodes

Every legacy node splits in half: an **agent range** (how to talk to the device) and an **enrichment entry** (what to call its flows), keyed by your old node name so exporter names survive.
Identical credential blocks are deduplicated into named sets; per-node `timeout`/`retries` become polling profiles; a non-default `port` lands on the range.

Two cases deserve attention:

- **Wide v1/v2c ranges are emitted disabled.** 0.9 refuses to send a cleartext community to any in-range address that happens to emit a flow, so a v1/v2c node wider than one address becomes `enabled: false`, with the rationale and both remediations (enumerate the devices, or move the segment to v3) as a comment above the entry. **Its flows are still named**; only polling stops. The credential set is kept, unreferenced, so re-enabling is one line.
- **Range-scoped names survive.** A node that named a whole subnet becomes a prefix enrichment entry with the same coverage; nothing is lost.

## Behaviour changes to expect

- Secret **value** rotation behind `file://` references needs no reload at all; `env://` needs a restart (process environments are immutable); `sops://` values are cached until a main-config reload.
- Each range walks on its profile's own cadence rather than one fleet-wide interval.
- A device inside a credentialed range is polled from its first flow with no per-device configuration (zero-touch onboarding); the registration cap (`riptide.snmp.poll.max-exporters`) and pool width bound the blast radius.
- The inventory file hot-reloads on content change; a rejected edit keeps the last good inventory serving and raises `inventory.reload.stale`.
