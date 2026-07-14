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
| `type` | `netflow5` · `netflow9` · `ipfix` · `sflow` · `multi` |
| `host` | bind address, e.g. `0.0.0.0` |
| `port` | UDP port (IPFIX also listens on TCP; sFlow's conventional port is 6343) |

A `multi` receiver parses all four protocols on one port, telling them apart by their
version words; per-protocol flags (`netflow5`, `netflow9`, `ipfix`, `sflow`, all
defaulting to `true`) switch individual parsers off.

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

**sFlow identity lives in the payload**: the datagram's `agent_address` plus
`sub_agent_id` — *not* the UDP source address, which may be a different management IP
entirely (or a shared socket in front of many agents). Node matching runs against the
agent address, and the node `observation-domain` key pins sub-agent IDs.

## sFlow semantics

sFlow v5 is packet sampling, not a flow cache. Each flow sample becomes one flow whose
volume is the statistical estimate (`bytes = frame length × sampling rate`,
`packets = sampling rate`) and whose first/last-switched collapse to the receive time.
Sampled headers are decoded down to addresses, ports, and TCP flags; whatever a
truncated or non-IP header doesn't reveal stays at its floor value and the flow is
still persisted (see [Enrichment](../enrichment.md)). Counter samples are skipped —
riptide does not interpret them.
