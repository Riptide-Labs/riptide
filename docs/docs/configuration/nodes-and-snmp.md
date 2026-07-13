---
sidebar_position: 2
title: Nodes & SNMP
---

# Nodes & SNMP

The node model (`riptide.nodes.<name>`) matches exporters and carries the SNMP agent
configuration used to enrich their flows. It is deliberately thin: interface metadata is
held in a TTL cache, never on the node.

## Node shape

Nodes are a **name-keyed map** — the same idiom as receivers. The name is the node's
identity in logs and error messages (kebab-case: letters, digits, dashes):

```properties
riptide.nodes.core-router.subnet-address=10.20.30.0/24
riptide.nodes.core-router.observation-domain=42   # optional pin (observation domain / sFlow sub-agent ID); omit = matches any
riptide.nodes.core-router.snmp.port=161           # default 161
riptide.nodes.core-router.snmp.timeout=500        # ms, default 500
riptide.nodes.core-router.snmp.retries=1          # default 1
```

**Matching is order-free** (think routing table):

1. a node **pinned** to the flow's observation domain beats a wildcard node
2. among the remaining candidates the **longest subnet prefix wins** (`/24` beats `/16`;
   a bare host address is most specific)
3. a **true tie** — two nodes with the same subnet and the same pinning — fails startup
   with both node names; declaration order never decides anything

For NetFlow/IPFIX the matched address is the UDP source and the pin is the observation
domain (source ID). For **sFlow** both come from the datagram payload: subnets match
the `agent_address` — which may differ from the UDP source — and `observation-domain`
pins the `sub_agent_id`.

A node without an `snmp` block matches flows but is not polled — it can still enrich
interfaces statically via the `interfaces` map (see below).

## Static interface mapping

Nodes may pin interface metadata without (or in addition to) SNMP:

```properties
riptide.nodes.core-router.interfaces.10.name=eth0
riptide.nodes.core-router.interfaces.10.alias=Uplink to AS64500
riptide.nodes.core-router.interfaces.10.high-speed=10000
```

Fields set here **override** live SNMP values per field; SNMP fills the rest
([enrichment ladder](../enrichment.md#the-enrichment-ladder)).

:::tip Keep the inventory in its own file

Stock Spring lets you split the node map out of the main configuration:

```properties
spring.config.import=optional:file:/etc/riptide/nodes.yaml
```

:::

## SNMP v1 / v2c

Community-based — two settings:

```properties
riptide.nodes.access-switches.snmp.snmp-version=v2c      # or: v1
riptide.nodes.access-switches.snmp.community=env://RIPTIDE_SNMP_COMMUNITY
```

## SNMP v3 (USM)

The security level is **implicit** in which credentials you configure:

**noAuthNoPriv** — security name only:

```properties
riptide.nodes.core-router.snmp.snmp-version=v3
riptide.nodes.core-router.snmp.security-name=monitoring
```

**authNoPriv** — add authentication:

```properties
riptide.nodes.core-router.snmp.auth-protocol=hmac192sha256
riptide.nodes.core-router.snmp.auth-passphrase=vault://secret/snmp/core-router#authPassphrase
```

**authPriv** — add privacy on top:

```properties
riptide.nodes.core-router.snmp.priv-protocol=aes256
riptide.nodes.core-router.snmp.priv-passphrase=vault://secret/snmp/core-router#privPassphrase
```

The **engine ID is never configured** — it is discovered at runtime (RFC 3414).

### Protocol values

| Setting | Values |
|---|---|
| `auth-protocol` | `md5` · `sha1` · `hmac128sha224` · `hmac192sha256` · `hmac256sha384` · `hmac384sha512` |
| `priv-protocol` | `des` · `_3des` · `aes128` · `aes192` · `aes256` · `aes192with3DESKeyExtension` · `aes256with3DESKeyExtension` |

Recommended pairing: `hmac192sha256` (or stronger) with `aes256` — this avoids the
non-standard 3DES key-extension variants some vendors require for AES-192/256 with
SHA-1.

## Complete YAML example

```yaml
riptide:
  nodes:
    core-router:
      subnet-address: 10.20.30.0/24
      observation-domain: 42
      snmp:
        snmp-version: v3
        security-name: monitoring
        auth-protocol: hmac192sha256
        auth-passphrase: vault://secret/snmp/core-router#authPassphrase
        priv-protocol: aes256
        priv-passphrase: vault://secret/snmp/core-router#privPassphrase
    access-switches:               # v2c fallback: the /16 matches wherever no /24 does
      subnet-address: 10.20.0.0/16
      snmp:
        snmp-version: v2c
        community: env://RIPTIDE_SNMP_COMMUNITY
```

Environment variables work too (`RIPTIDE_NODES_COREROUTER_SNMP_COMMUNITY`), with one
caveat: Spring flattens map keys arriving from the environment (case and dashes are
lost), so prefer file-based definition for nodes.

## Migration from earlier shapes

- The pre-0.1.0 `riptide.snmp.config.definitions[*]` tree moved to `riptide.nodes`
  (SNMP settings nested under `snmp.`) — legacy keys log an explicit startup error.
- The interim indexed list form (`riptide.nodes[0].…`) moved to the name-keyed map —
  indexed keys **fail startup** with an explicit error (they would otherwise half-bind
  into a node named after the index).
