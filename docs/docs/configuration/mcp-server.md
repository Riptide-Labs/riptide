---
sidebar_position: 7
title: Model Context Protocol (MCP) Server
description: Native Java Model Context Protocol (MCP) server integration, SecretRef token authentication, and auto-shipped Agent Skills.
---

{/*
Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
SPDX-License-Identifier: GPL-3.0-or-later
*/}

# Model Context Protocol (MCP) Server

Riptide includes an embedded, native Java **Model Context Protocol (MCP)** server component (`org.riptide.mcp.*`). This component allows AI agent frameworks—such as **Google Antigravity (AGY)**, **Claude CLI**, and custom LLM applications—to directly query ClickHouse network flow telemetry, analyze traffic spikes, and execute automated DDoS triage without requiring external Node.js or Python adapter runtimes.

---

## Key Capabilities

- **Zero-Dependency Native Java Executable**: Ships directly inside `riptide-flows-*.jar`.
- **Dual Transport Engine**:
  - **Stdio IPC (`stdio`)**: Fast, non-blocking background IPC loop over standard input/output streams for local CLI hosts (`antigravity-cli`, `claude`).
  - **Server-Sent Events (`sse`)**: HTTP SSE endpoint at `/mcp/sse` and message receiver at `/mcp/messages`.
- **Integrated `SecretRef` Token Authentication**: Authorize access tokens dynamically resolved from environment variables (`env://`), local files (`file://`), HashiCorp Vault (`vault://`), or SOPS (`sops://`).
- **1-Minute Rollup Query Router**: Queries spanning $\ge 60$ minutes are automatically routed to ClickHouse `SummingMergeTree` rollups (`flows_by_application_1m`, `flows_by_exporter_iface_1m`, `flows_by_geo_asn_1m`).
- **7 Auto-Shipped Agent Skills**: Pre-packaged Markdown skill files embedded under `classpath*:mcp/skills/*.md` exposed automatically as MCP Prompts (`prompts/list`) and Resources (`resources/list`).

---

## Configuration Properties

Configure the MCP server in `/etc/riptide/config.yaml` or `application.properties`:

```properties
# Enable the embedded MCP Server
riptide.mcp.enabled=true

# Transport mode: stdio (default) or sse
riptide.mcp.transport=stdio

# ClickHouse Query Safety Controls
riptide.mcp.query-timeout-seconds=5
riptide.mcp.max-result-rows=500

# Authentication (SecretRef supported)
riptide.mcp.auth.tokens[0]=file:/etc/riptide/mcp-tokens.txt
# riptide.mcp.auth.tokens[1]=vault://secret/riptide/mcp#token
```

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
