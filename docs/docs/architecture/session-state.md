---
title: Exporter identity and session state
sidebar_position: 3
description: How a flow is attributed to its exporter per protocol, what sFlow samples become, and why the per-exporter session state is bounded, how the bounds are sized, and what happens when one is reached.
---

# How exporters are identified and their state bounded

Flows are attributed to an exporter by an identity the sender chooses, and riptide keeps state per identity on the UDP ingest path.
That state is bounded so a sender varying one header field cannot grow it until the heap is gone.

## Exporter identity

| Protocol | Identity | Notes |
| --- | --- | --- |
| IPFIX | Source address plus observation domain (RFC 7011) | Two observation domains behind one exporter IP are distinct identities. |
| NetFlow v9 | Source address plus source ID | Same shape as IPFIX. |
| NetFlow v5 | Source address; the engine type and ID are mapped onto the domain | v5 has no domain concept of its own. |
| sFlow | The datagram's `agent_address` plus `sub_agent_id` | Not the UDP source address, which may be a different management IP, or a shared socket in front of many agents. |

The identity drives node matching: an exporter is matched to a [configured agent range](../reference/agent-configuration.md) by this address, and an inventory entry's `observation-domain` key pins sub-agent IDs for sFlow.

## What an sFlow sample becomes

sFlow v5 is packet sampling, not a flow cache.
Each flow sample becomes one flow whose volume is the statistical estimate (`bytes = frame length × sampling rate`, `packets = sampling rate`) and whose first and last switched times collapse to the receive time.
Sampled headers are decoded down to addresses, ports and TCP flags.
Whatever a truncated or non-IP header does not reveal stays at its floor value and the flow is still persisted, as the [enrichment ladder](enrichment.md#the-enrichment-ladder) describes.
Counter samples are skipped by length; riptide does not interpret them.

## Why session state is bounded

riptide keeps per-exporter state on the UDP ingest path: NetFlow v9 and IPFIX templates, sequence trackers, and the interface names and application names exporters push as option records.
All of it is keyed on the exporter scope identity, `(source address, observation domain)` for v9 and IPFIX and `(agent address, sub-agent ID)` for sFlow.

Every part of that identity is chosen by the sender.
The observation domain is a 32-bit header field, and both halves of the sFlow pair come out of the datagram payload rather than the UDP header.
A sender that varies one of them mints a new identity on every packet, so the state is bounded rather than left to grow.
The four keys that bound it, with their defaults, are on the [receivers reference](../reference/receivers.md#session-state-bounds).

## How the bounds are sized

Worst-case retained state is a product an operator can multiply out, and the reference page tabulates it.
The last two lines of that product are a ceiling, not an expectation: reaching either means an attacker holding all `max-sources` slots at once.
What a single source can spend is about 2.4 MB for interfaces and about 122 MB for applications at the defaults, so about 124 MB together.
A real fleet holds one scope per exporter, its own interfaces and one protocol pack, far below any of these numbers.

The defaults suit real hardware: a per-linecard chassis exporting several observation domains from one address, and a large router carrying up to a thousand interfaces once subinterfaces are counted.
An earlier per-scope interface cap of 128 evicted 372 of a 500-interface router's interfaces, which is why that bound is the generous one.

## What happens when a bound is reached

Behaviour differs by level, on purpose.

| Bound | On reaching it | Effect |
| --- | --- | --- |
| **`max-sources`** | New sources refused; admitted ones keep their state | New exporters are not retained until a slot frees |
| **`max-scopes-per-source`** | That source's least-recently-used scope is displaced | Confined to that source; no other exporter is affected |
| **`max-ifindexes-per-scope`** | That scope's least-recently-used interface is evicted | Degrades only: static pins and live SNMP still resolve the interface, and the flow is still emitted |

Only the source bound refuses; the other two evict least-recently-used within their own level.
Evicting across sources would let whoever sends hardest choose which of your devices stop being monitored, so eviction is always confined to the source that caused it.
Confining it also forces a spoofing sender to sustain traffic on every forged address to hold its slots, which removes the fire-and-forget property of a spray.

A rejection meter climbing steadily on a healthy fleet means the bound is too low for your hardware, not that you are under attack; raise the matching setting.
Rejections are also logged at warning level, rate-limited, with separate limiters per bound so a noisy scope flood cannot mask the more serious source-bound message.
The meters are listed under `flows.session.*` on the [metrics reference](../reference/metrics.md).

`source-idle-timeout` and the receiver's template timeout are separate timers.
The slot and the state it authorises would otherwise expire at different moments, and state retained after its slot is released puts the real total above the configured ceiling.
Keep the idle timeout at or above the template timeout; riptide warns at startup if the template timeout is the longer of the two.

## What bounding is not

Bounding is not authentication.
riptide does not verify that an exporter is who it claims to be, and these limits do not make it safe to expose a flow port to untrusted networks.
They cap the cost of an abusive sender; they do not identify one.

Restrict the flow port to known exporters.
A source ACL constrains the source bound but does not blunt the payload-borne multiplier: a single permitted sender can still vary observation domain or sFlow agent address freely, which is exactly what `max-scopes-per-source` exists to cap.

## Related

- [Receivers reference](../reference/receivers.md#session-state-bounds) for the four keys.
- [Where flows can be lost](loss-accounting.md) for the queues downstream of the parser.
- [Enrichment](enrichment.md) for what the pushed option records feed.
