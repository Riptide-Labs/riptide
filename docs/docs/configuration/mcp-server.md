---
sidebar_position: 7
title: Model Context Protocol (MCP) Server
description: Native Java Model Context Protocol (MCP) server integration, SecretRef token authentication, and auto-shipped Agent Skills.
---

# Model Context Protocol (MCP) Server

Riptide includes an embedded, native Java **Model Context Protocol (MCP)** server component (`org.riptide.mcp.*`).
This component allows AI agent frameworks (**Google Antigravity (AGY)**, **Claude CLI**, and custom LLM applications) to directly query ClickHouse network flow telemetry, analyze traffic spikes, and execute automated DDoS triage without requiring external Node.js or Python adapter runtimes.

---

## Key Capabilities

- **Zero-Dependency Native Java Executable**: Ships directly inside `riptide-flows-*.jar`.
- **Dual Transport Engine (`stdio` & `sse`)**: Non-blocking IPC loop over standard input/output streams for local CLI hosts (`antigravity-cli`, `claude`) and HTTP Server-Sent Events (`/mcp/sse`) on port 8081 for remote LLM agent clients.
- **Integrated `SecretRef` Token Authentication**: Optional token authorization dynamically resolved from environment variables (`env://`), local files (`file:///`), HashiCorp Vault (`vault://`), or SOPS (`sops://`).
- **1-Minute Rollup Query Router**: Queries spanning $\ge 60$ minutes are automatically routed to ClickHouse `SummingMergeTree` rollups (`flows_by_application_1m`, `flows_by_conversation_1m`, `flows_by_exporter_iface_1m`, `flows_by_geo_asn_1m`).
  The rollups carry `samplingInterval` and `flowProtocol`, so a sampling-corrected query is the same expression whichever table a request lands on, including on deployments receiving sFlow: each row is scaled by its own protocol's factor, and sFlow's is `1` because its counters arrive pre-scaled. The tools themselves report counters as the exporter reported them and do not apply the correction — see [sampling-corrected volume](receivers#sampling-corrected-volume-beyond-raw-retention).
- **7 Auto-Shipped Agent Skills**: Pre-packaged Markdown skill files embedded under `classpath*:mcp/skills/*.md` exposed automatically as MCP Prompts (`prompts/list`) and Resources (`resources/list`).

---

## Configuration Properties

Configure the MCP server in `/etc/riptide/config.yaml` or `application.properties`:

```properties
# Enable the embedded MCP Server
riptide.mcp.enabled=true

# Transport mode: stdio (default) or sse
riptide.mcp.transport=stdio

# HTTP SSE transport bind address and port (applicable when transport=sse).
# Loopback by default; the port is deliberately not 8080, which riptide.management.port uses.
riptide.mcp.bind-address=127.0.0.1
riptide.mcp.sse-port=8081

# Maximum concurrent SSE stream sessions (each holds a socket for its lifetime)
riptide.mcp.max-sse-sessions=64

# Keep-alive comment interval on an idle SSE stream. Also bounds how long a session whose
# client vanished without closing the connection stays open: the keep-alive write fails and
# the session is released.
riptide.mcp.sse-keep-alive-interval=15s

# ClickHouse Query Safety Controls
riptide.mcp.query-timeout-seconds=5
riptide.mcp.max-result-rows=50

# Read-only ClickHouse identity for MCP queries (SecretRef supported).
# Required in provisioned deployments — see "ClickHouse Credentials" below.
# Unset falls back to riptide.clickhouse.username / password.
riptide.mcp.clickhouse.username=bi_acme
riptide.mcp.clickhouse.password=vault://secret/riptide/clickhouse#bi_acme

# Optional Authentication (SecretRef supported)
# Stdio transport defaults to false (inherits process-level OS trust)
riptide.mcp.auth.enabled=false
riptide.mcp.auth.tokens[0]=file:///etc/riptide/mcp-tokens.txt
# riptide.mcp.auth.tokens[1]=vault://secret/riptide/mcp#token
```

---

## ClickHouse Credentials

MCP only ever reads.
In a provisioned (multi-tenant) deployment, point it at the tenant reader rather than the ingest writer:

```properties
riptide.mcp.clickhouse.username=bi_acme
riptide.mcp.clickhouse.password=vault://secret/riptide/clickhouse#bi_acme
```

The `bi_<tenant>` user holds the `flow_reader` role, which `riptide onboard` already grants SELECT on `flows` and on every rollup, and which carries the `readonly = 2` / `allow_ddl = 0` hardening.
It is also already named on every tenant row policy, so no re-provisioning is needed to enable MCP.

Leaving this unset reuses `riptide.clickhouse.username` / `password`.
That is correct for single-tenant manage mode, where the same user reads and writes.
In provisioned mode it means querying as `writer_<tenant>`, which holds only INSERT on the rollups: rollup-routed tools (any query spanning 60 minutes or more) then fail with `ACCESS_DENIED`.
Granting the writer SELECT on the rollups instead would hand the ingest credential a tenant-wide read surface it does not otherwise have; use the reader.

---

## Security Notes for the SSE Transport

The SSE endpoint speaks unauthenticated JSON-RPC unless `riptide.mcp.auth.enabled=true`, and every tool it exposes reads flow telemetry.
It therefore binds `127.0.0.1` by default and logs a warning at startup when it runs without authentication.

Before setting `riptide.mcp.bind-address` to anything reachable, enable token authentication.
Tokens are read from the `Authorization: Bearer <token>` header only; query parameters such as `?token=` are not accepted, because they end up in proxy and access logs.
No CORS headers are sent, so a browser page cannot reach the endpoint cross-origin.

---

## Auto-Shipped Agent Skills

Riptide automatically discovers and registers 7 domain-standard network engineering skills at startup:

| Command | Skill ID | Description | Grounding Framework |
| :--- | :--- | :--- | :--- |
| `/riptide-investigate-ddos` | `riptide-ddos-mitigation-triage` | Scientific DDoS attack family classification and entropy analysis. | **RFC 4732**, **Shannon Entropy** ($\Delta H < -1.5$), **TCP Flag Histograms**, **NIST SP 800-189** |
| `/riptide-cause-analysis` | `riptide-cause-analysis-triage` | Compares current 15m traffic windows against 24h baselines. | Comparative Anomaly Detection |
| `/riptide-capacity-plan` | `riptide-interface-capacity-analysis` | Evaluates interface bandwidth saturation & 95th percentile headroom. | Enriched SNMP `ifSpeed` & 95th Billing |
| `/riptide-peering-analysis` | `riptide-peering-geo-analysis` | BGP ASN and geographic traffic breakdown for transit optimization. | BGP Origin AS & Geo-IP |
| `/riptide-app-audit` | `riptide-application-performance-triage` | Audits application protocol distribution and public/private locality. | RFC 6335 & NetFlow Application ID |
| `/riptide-trace-host` | `riptide-host-forensic-investigation` | Forensic walk detailing peer conversation matrix, active ports, and VLANs. | Flow Record Forensics |
| `/riptide-auto-mitigate` | `riptide-ddos-auto-mitigation-playbook` | Generates multi-tier BGP FlowSpec, RTBH, iptables, and cloud scrubbing rules. | **RFC 8955 (FlowSpec)**, **RFC 7999 (RTBH)** |

---

## Standard MCP Tools

The server exposes 6 vendor-neutral flow query tools:

- `riptide_get_top_talkers`: Aggregates volume by application, host IP, or protocol.
- `riptide_get_interface_utilization`: Queries exporter bandwidth utilization and SNMP link speeds.
- `riptide_trace_host_flow`: Traces conversation peers, ports, and flow duration for a specific IP.
- `riptide_get_geo_asn_distribution`: Queries volume by Autonomous System Number (ASN) and country.
- `riptide_detect_traffic_spikes`: Isolates PPS/BPS volumetric traffic anomalies.
- `riptide_generate_mitigation_rules`: Emits BGP FlowSpec, RTBH null-routes, and iptables rules.

---

## Connecting Client Hosts

### 1. Google Antigravity (AGY / `antigravity-cli`)
Add Riptide to your AGY MCP configuration:
```bash
agy mcp add riptide java -jar /usr/share/riptide/riptide-flows.jar --riptide.mcp.enabled=true --riptide.mcp.transport=stdio
```

### 2. Claude CLI
Add Riptide to `~/.claude/claude_desktop_config.json`:
```json
{
  "mcpServers": {
    "riptide": {
      "command": "java",
      "args": [
        "-jar",
        "/usr/share/riptide/riptide-flows.jar",
        "--riptide.mcp.enabled=true",
        "--riptide.mcp.transport=stdio"
      ]
    }
  }
}
```
