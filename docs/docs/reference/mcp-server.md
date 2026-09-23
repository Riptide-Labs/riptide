---
sidebar_position: 12
title: MCP server
description: Settings, tools, result shape, transports and error codes of the embedded Model Context Protocol server.
---

# MCP server reference

Riptide embeds a Model Context Protocol (MCP) server that lets an AI agent query the flow tables in ClickHouse.
It is off by default and only ever reads.

## Settings

Set them in **`/etc/riptide/config.yaml`**, as environment variables, or as `--key=value` arguments.

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| **`riptide.mcp.enabled`** | bool | `false` | Starts the server and registers the skills. |
| **`riptide.mcp.transport`** | `stdio` or `sse` | `stdio` | `stdio` reads JSON-RPC lines from stdin and answers on stdout. `sse` serves HTTP on the address and port below. |
| **`riptide.mcp.bind-address`** | address | `127.0.0.1` | Listen address of the `sse` transport. |
| **`riptide.mcp.sse-port`** | int | `8081` | Port of the `sse` transport. `8080` belongs to `riptide.management.port` in the same process. |
| **`riptide.mcp.max-sse-sessions`** | int | `64` | Open SSE streams. The next stream request gets `503`. |
| **`riptide.mcp.sse-keep-alive-interval`** | duration | `15s` | Keep-alive comment on an idle stream. A client that vanished is noticed when that write fails, so this is also how long its session lingers. |
| **`riptide.mcp.query-timeout-seconds`** | int | `5` | Sent as ClickHouse `max_execution_time` and used as the client-side wait. |
| **`riptide.mcp.max-result-rows`** | int | `50` | Rows read from a ClickHouse response. Further rows are discarded. |
| **`riptide.mcp.clickhouse.username`** | [secret reference](secret-references.md) | `riptide.clickhouse.username` | ClickHouse identity for MCP queries. Resolved once at startup. |
| **`riptide.mcp.clickhouse.password`** | secret reference | `riptide.clickhouse.password` while the MCP username is also unset; otherwise empty | Password of that identity. Username and password move together: setting the reader username without a password authenticates with an empty one. Resolved once at startup. |
| **`riptide.mcp.auth.enabled`** | bool | `false` | Requires a token on every request. |
| **`riptide.mcp.auth.tokens[n]`** | secret reference | none | Accepted tokens. Resolved once when the server starts. A token that fails to resolve is logged and skipped. With `auth.enabled=true` and no usable token every request is refused. |

In `stdio` mode the log moves to stderr so that stdout carries only JSON-RPC frames.
Set **`riptide.logging.console-target`** yourself to override that.

## ClickHouse identity

| Deployment | Set `riptide.mcp.clickhouse.*` to | Reason |
| --- | --- | --- |
| Single-tenant manage mode | leave unset | The ingest user reads and writes anyway. |
| Provisioned (multi-tenant) | `bi_<tenant>@<database>` | The reader holds `flow_reader@<database>`: SELECT on `flows` and every rollup, `readonly = 2`, `allow_ddl = 0`, and it is already on every row policy. The ingest writer holds only INSERT on the rollups, so a rollup-routed query as the writer fails with `ACCESS_DENIED`. |

The `@` is written literally in a properties or YAML value.
Only a URL-embedded credential needs `%40`.
A database onboarded before the rename still has the unqualified `bi_<tenant>` account until you re-run `onboard`, see [Object names carry their database](../architecture/multi-tenancy.md#object-names-carry-their-database).

Example for a tenant `acme` in database `riptide`:

```properties
riptide.mcp.clickhouse.username=bi_acme@riptide
riptide.mcp.clickhouse.password=vault://secret/riptide/clickhouse#bi_acme
```

## Tools

Every tool returns a JSON array of rows, serialised as one `text` content item.
Arguments arrive untyped. A numeric argument that is missing or malformed falls back to its default, and one out of range is clamped.

| Tool | Parameters | Reads | Returns per row |
| --- | --- | --- | --- |
| **`riptide_get_top_talkers`** | `time_range_minutes` (int, default `15`, max `43200`), `group_by` (one of `application`, `protocol`, `srcAddr`, `dstAddr`, `srcAs`, `dstAs`; default `application`) | `flows`, or the matching rollup at 60 minutes or more | the `group_by` column, `total_bytes`, `total_packets`; 20 rows |
| **`riptide_get_interface_utilization`** | `time_range_minutes` (default `15`), `limit` (int, default `20`, max `500`) | `flows`, or `flows_by_exporter_iface_1m` at 60 minutes or more | `exporterAddr`, `exporterName`, `inputSnmp`, `outputSnmp`, `total_bytes` |
| **`riptide_trace_host_flow`** | `ip_address` (required, IPv4 or IPv6), `time_range_minutes` (default `15`) | `flows` only | `srcAddr`, `dstAddr`, `srcPort`, `dstPort`, `protocol`, `application`, `tcpFlags`, `bytes`, `packets`; 50 rows |
| **`riptide_get_geo_asn_distribution`** | `time_range_minutes` (default `60`) | `flows`, or `flows_by_geo_asn_1m` at 60 minutes or more | `dstAs`, `dstCountry`, `total_bytes`; 20 rows |
| **`riptide_detect_traffic_spikes`** | `time_range_minutes` (default `15`) | `flows`, or `flows_by_conversation_1m` at 60 minutes or more | `dstAddr`, `total_packets`, `total_bytes`, `flow_count`; 20 rows |
| **`riptide_generate_mitigation_rules`** | `target_ip` (required), `attack_type` (string, default `Volumetric Flood`) | nothing | `target_ip`, `attack_type`, `bgp_flowspec`, `iptables`, `rtbh_null_route`, `cloud_scrubbing`; one row |

The `tools/list` schema marks `time_range_minutes`, `group_by` and `attack_type` as required, but a missing value takes the default above.

The rollup tables are `SummingMergeTree` tables and carry `samplingInterval` and `flowProtocol`, so the sampling-corrected scaling expression is the same whichever table answers.
The tools report counters as the exporter sent them and do not apply that correction, see [sampling-corrected volume](../guides/sampling-corrected-volume.md).
A rollup whose shape does not match this version is declined and the query falls back to `flows`, see [rollups](clickhouse.md#rollups).

### Result array

The array holds data rows and at most two non-row entries.
A client iterating rows skips an entry carrying either key.

| Entry | When | Example |
| --- | --- | --- |
| `error` | the tool refused an argument, ClickHouse refused the query, or the query timed out | `{"error": "Invalid group_by dimension. Allowed: [...]"}`; the order of the listed dimensions varies between runs, so match on the prefix only |
| `coverage_warning` | the table holds fewer minutes than asked for, or the request exceeded the 43200-minute cap | see below |

A request for 129600 minutes against a table holding two days:

```json
{"coverage_warning": "answered from `riptide`.flows_by_exporter_iface_1m, which holds data from 2026-09-21 01:12:00. This answer covers 3421 of the 129600 minutes you asked for, and riptide caps a single query at 43200 minutes. The rest is not missing from your network."}
```

An empty table produces rows only, never a warning.

## Prompts and resources

Seven skills ship inside the jar under `mcp/skills/*.md`.
Each is listed by `prompts/list` under its name and by `resources/list` as `resource://riptide/skills/<name>` with MIME type `text/markdown`.
`prompts/get` and `resources/read` return the Markdown unchanged.

| Prompt name | Slash command | Description |
| --- | --- | --- |
| **`riptide-ddos-mitigation-triage`** | `/riptide-investigate-ddos` | DDoS attack classification from RFC 4732 taxonomy, Shannon entropy, TCP flag histograms and NIST SP 800-189 amplification heuristics. |
| **`riptide-cause-analysis-triage`** | `/riptide-cause-analysis` | Compares the current 15-minute window against the same window 24 hours earlier. |
| **`riptide-interface-capacity-analysis`** | `/riptide-capacity-plan` | Interface utilisation against SNMP `ifSpeed`, 95th percentile and headroom projection. |
| **`riptide-peering-geo-analysis`** | `/riptide-peering-analysis` | ASN and country breakdown for transit decisions. |
| **`riptide-application-performance-triage`** | `/riptide-app-audit` | Application protocol distribution, unclassified traffic and public/private locality. |
| **`riptide-host-forensic-investigation`** | `/riptide-trace-host` | Peer matrix, active ports, VLANs and flow durations for one host. |
| **`riptide-ddos-auto-mitigation-playbook`** | `/riptide-auto-mitigate` | BGP FlowSpec (RFC 8955), RTBH (RFC 7999), iptables and cloud scrubbing rules. |

The slash command is appended to the prompt description as `[Command: /...]`. Whether a client exposes it under that name is up to the client.

## JSON-RPC methods

The server speaks protocol version `2024-11-05` and answers `initialize` with that version whatever the client asked for.

| Method | Result |
| --- | --- |
| `initialize` | `protocolVersion`, `capabilities` (`tools`, `prompts`, `resources`), `serverInfo` (`riptide-flows-mcp`, the riptide version) |
| `ping` | `{}` |
| `tools/list` | the six tool definitions |
| `tools/call` | `content` with one `text` item holding the result array |
| `prompts/list`, `prompts/get` | the skills as prompts |
| `resources/list`, `resources/read` | the skills as resources |
| `notifications/*` | no response |

## Connect Claude Code

Prerequisites: the DEB or RPM package is installed, so the jar is at `/usr/share/riptide/riptide.jar` and its ClickHouse settings are in `/etc/riptide/config.yaml`.

1. Register the server:

   ```bash
   claude mcp add riptide -- java -jar /usr/share/riptide/riptide.jar --riptide.mcp.enabled=true --riptide.mcp.transport=stdio
   ```

   Expected output:

   ```text
   Added stdio MCP server riptide with command: java -jar /usr/share/riptide/riptide.jar --riptide.mcp.enabled=true --riptide.mcp.transport=stdio to local config
   ```

2. Verify:

   ```bash
   claude mcp get riptide
   ```

   Expected output:

   ```text
   riptide:
     Scope: Local config (private to you in this project)
     Status: ✔ Connected
     Type: stdio
     Command: java
     Args: -jar /usr/share/riptide/riptide.jar --riptide.mcp.enabled=true --riptide.mcp.transport=stdio
   ```

Each Claude Code session starts its own riptide process.
Any other client that launches a stdio server takes the same command and arguments.

To drive the stdio transport by hand, write one JSON-RPC object per line:

```bash
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"shell","version":"0"}}}' \
  | java -jar /usr/share/riptide/riptide.jar --riptide.mcp.enabled=true --riptide.mcp.transport=stdio 2>/dev/null
```

Expected output:

```text
{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{"resources":{},"prompts":{},"tools":{}},"serverInfo":{"name":"riptide-flows-mcp","version":"0.15.0"}}}
```

## Connect over HTTP (SSE)

:::warning
With `riptide.mcp.auth.enabled=false` the endpoint answers anyone who can reach it, and every tool reads flow telemetry.
Enable token authentication before setting `riptide.mcp.bind-address` to a non-loopback address.
The server logs a warning at startup while it runs unauthenticated.
:::

Settings:

```properties
riptide.mcp.enabled=true
riptide.mcp.transport=sse
riptide.mcp.auth.enabled=true
riptide.mcp.auth.tokens[0]=file:///etc/riptide/mcp-token
```

Expected log lines at startup:

```text
Riptide MCP Server HTTP/SSE Transport listening at http://127.0.0.1:8081/mcp/sse
```

The token travels in the `Authorization: Bearer <token>` header.
A `?token=` query parameter is ignored, because it would land in proxy and access logs.
On the stdio transport a token goes in `params._meta.authToken` of each request instead.
No CORS headers are sent, so a browser page cannot call the endpoint cross-origin.

### `GET /mcp/sse`

Opens the event stream.
The first frame names the URL to POST requests to.

```bash
curl -N -H 'Authorization: Bearer e6f1c0b2a9d84f3d9c1b7a5e2f4d6c8a' http://127.0.0.1:8081/mcp/sse
```

Expected output:

```text
event: endpoint
data: /mcp/sse?sessionId=ff2bd462-c1dd-45ca-b56d-8a11ef85a301

```

Every response to a request POSTed with that `sessionId` arrives on the stream as `event: message`, and a `: keep-alive` comment follows each idle interval.

| Status | Meaning |
| --- | --- |
| `200` | Stream open. |
| `401` | Auth is enabled and the token is missing or wrong. |
| `503` | `riptide.mcp.max-sse-sessions` streams are open, or the process is shutting down. |

### `POST /mcp/sse`

| Parameter | In | Required | Description |
| --- | --- | --- | --- |
| `sessionId` | query | no | An open stream. With it the response goes to the stream and the POST returns `202` with an empty body. Without it, or with a closed session, the response is the POST body. A notification (no `id`, or a `notifications/*` method) always gets `202` and no response. |
| `Authorization` | header | when auth is enabled | `Bearer <token>` |
| body | body | yes | one JSON-RPC 2.0 request |

```bash
curl -s -X POST -H 'Authorization: Bearer e6f1c0b2a9d84f3d9c1b7a5e2f4d6c8a' -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"riptide_generate_mitigation_rules","arguments":{"target_ip":"203.0.113.10","attack_type":"TCP SYN Flood"}}}' \
  http://127.0.0.1:8081/mcp/sse
```

Expected output:

```json
{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"[{\"target_ip\":\"203.0.113.10\",\"attack_type\":\"TCP SYN Flood\",\"bgp_flowspec\":\"match destination-prefix 203.0.113.10/32 protocol tcp flags syn -> rate-limit 0\",\"iptables\":\"iptables -A INPUT -d 203.0.113.10 -p tcp --tcp-flags SYN,ACK SYN -j DROP\",\"rtbh_null_route\":\"ip route 203.0.113.10/32 Null0 tag 666\",\"cloud_scrubbing\":\"Diversion CNAME: 203-0-113-10.scrubbing.riptide.space\"}]"}]}}
```

| Status | Meaning |
| --- | --- |
| `200` | Response in the body. |
| `202` | Response queued on the stream named by `sessionId`, or the request was a notification. |
| `400` | Empty body, or the body is not JSON. |
| `401` | Auth is enabled and the token is missing or wrong. |
| `500` | The handler threw; the body is a `-32603 Internal error: ...` object. |

Any other HTTP method on `/mcp/sse` gets `405`.

## Error catalog

JSON-RPC errors come back in an `error` object. Tool-level errors come back as an `error` row inside a successful result.

| Code | Message | Probable cause | Recovery |
| --- | --- | --- | --- |
| **`-32001`** | `Unauthorized: invalid MCP authentication token` | No bearer token, a wrong one, or `auth.enabled=true` with no token that resolved | Send the token from one of `riptide.mcp.auth.tokens`; check the startup log for `Failed to resolve MCP auth secret reference` |
| **`-32000`** | `Too many active MCP SSE sessions` | `max-sse-sessions` streams are open, often a client reconnect loop | Close streams, or raise the limit |
| **`-32000`** | `Server is shutting down` | Stream requested during shutdown | Reconnect after restart |
| **`-32600`** | `Empty payload`, `Invalid Request` | Empty POST body, or no `method` | Send one JSON-RPC 2.0 object |
| **`-32700`** | `Parse error: ...` | Body is not valid JSON | Fix the body |
| **`-32601`** | `Method not found: ...` | Unsupported method | Use one from the methods table |
| **`-32602`** | `Tool not found: ...`, `Prompt not found: ...`, `Resource not found: ...`, `Missing tool name in params` | Wrong name or URI | Take the name from `tools/list`, `prompts/list` or `resources/list` |
| **`-32603`** | `Tool execution error: ...`, `Internal error: ...` | A tool threw, or the SSE handler threw (HTTP `500`) | See the riptide log |
| row | `Missing required parameter 'ip_address'`, `Missing required parameter 'target_ip'` | Required argument absent | Pass it |
| row | `Invalid IP address parameter: ...`, `Invalid target IP address parameter: ...` | Not an IPv4 or IPv6 literal | Pass a literal address, not a hostname |
| row | `Invalid group_by dimension. Allowed: [...]` | `group_by` outside the allowed set | Use one of the listed dimensions |
| row | `ClickHouse client is unavailable.` | The MCP service was built without a ClickHouse client. The client bean exists whenever `riptide.mcp.enabled=true`, so this is not expected in a running collector | Report it with the startup log |
| row | a ClickHouse or timeout message | Query refused or slower than `query-timeout-seconds`; `ACCESS_DENIED` means the identity lacks SELECT on the table | Use the reader identity, or raise the timeout |

## Open questions

- An earlier revision listed Google Antigravity (`agy mcp add ...`) as a client. No reference for that CLI could be verified, so it was removed.
- `riptide_get_geo_asn_distribution` falls back to 60 minutes when `time_range_minutes` is absent, but the coverage check then compares against a 15-minute request. Which default is intended is `(unverified)`.
