---
sidebar_position: 1
title: Receivers
---

# Receivers

Receivers are the flow listeners. None are configured by default — the daemon starts no
listeners until you define them. Entries defined in the bundled `application.properties`
merge with (and cannot be removed by) external configuration.

Each receiver has a free-form name and three settings:

| Setting | Values |
|---|---|
| `type` | `netflow5` · `netflow9` · `ipfix` |
| `host` | bind address, e.g. `0.0.0.0` |
| `port` | UDP port (IPFIX also listens on TCP) |

```properties
riptide.receivers.ipfix.type=ipfix
riptide.receivers.ipfix.host=0.0.0.0
riptide.receivers.ipfix.port=4739

riptide.receivers.nf9.type=netflow9
riptide.receivers.nf9.host=0.0.0.0
riptide.receivers.nf9.port=2055
```

Or as YAML (`/etc/riptide/config.yaml`):

```yaml
riptide:
  receivers:
    ipfix:
      type: ipfix
      host: 0.0.0.0
      port: 4739
    nf9:
      type: netflow9
      host: 0.0.0.0
      port: 2055
```

## Exporter identity

Flows are attributed to their exporter by **source address plus observation domain**
(IPFIX, RFC 7011) or **source ID** (NetFlow v9) — two observation domains behind one
exporter IP are distinct identities. NetFlow v5 has no such concept; its engine type/ID
are mapped onto the domain. The identity drives node matching — see
[Nodes & SNMP](nodes-and-snmp.md).
