---
sidebar_position: 2
title: Nodes & SNMP
---

# Nodes & SNMP

The node model (`riptide.nodes[]`) matches exporters and carries the SNMP agent
configuration used to enrich their flows. It is deliberately thin: interface metadata is
held in a TTL cache, never on the node.

## Node shape

```properties
riptide.nodes[0].label=core-router          # optional; defaults to the subnet
riptide.nodes[0].subnet-address=10.20.30.0/24
riptide.nodes[0].observation-domain=42      # optional pin; omit = matches any domain
riptide.nodes[0].snmp.port=161              # default 161
riptide.nodes[0].snmp.timeout=500           # ms, default 500
riptide.nodes[0].snmp.retries=1             # default 1
```

**Matching rule:** a node pinned to the flow's observation domain beats a wildcard node
for the same subnet; otherwise the first subnet match in list order wins. A node without
an `snmp` block matches flows but is not polled.

## SNMP v1 / v2c

Community-based — two settings:

```properties
riptide.nodes[0].snmp.snmp-version=v2c      # or: v1
riptide.nodes[0].snmp.community=env://RIPTIDE_SNMP_COMMUNITY
```

## SNMP v3 (USM)

The security level is **implicit** in which credentials you configure:

**noAuthNoPriv** — security name only:

```properties
riptide.nodes[1].snmp.snmp-version=v3
riptide.nodes[1].snmp.security-name=monitoring
```

**authNoPriv** — add authentication:

```properties
riptide.nodes[1].snmp.auth-protocol=hmac192sha256
riptide.nodes[1].snmp.auth-passphrase=vault://secret/snmp/core-router#authPassphrase
```

**authPriv** — add privacy on top:

```properties
riptide.nodes[1].snmp.priv-protocol=aes256
riptide.nodes[1].snmp.priv-passphrase=vault://secret/snmp/core-router#privPassphrase
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
    - label: core-router
      subnet-address: 10.20.30.0/24
      observation-domain: 42
      snmp:
        snmp-version: v3
        security-name: monitoring
        auth-protocol: hmac192sha256
        auth-passphrase: vault://secret/snmp/core-router#authPassphrase
        priv-protocol: aes256
        priv-passphrase: vault://secret/snmp/core-router#privPassphrase
    - label: access-switches       # v2c wildcard fallback for the same address space
      subnet-address: 10.20.0.0/16
      snmp:
        snmp-version: v2c
        community: env://RIPTIDE_SNMP_COMMUNITY
```

## Migration from `riptide.snmp.config`

The pre-0.1.0 `riptide.snmp.config.definitions[*]` tree has moved to `riptide.nodes[*]`
(SNMP settings nested under `snmp.`). Legacy keys are ignored and Riptide logs an
explicit error at startup when it finds them.
