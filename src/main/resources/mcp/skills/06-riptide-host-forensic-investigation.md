---
name: "riptide-host-forensic-investigation"
description: "Riptide target host forensic walk detailing peer matrix, active ports, VLANs, and flow durations."
slash_command: "/riptide-trace-host"
---

# Operational Agent Skill: Riptide Host Forensic Walk (`/riptide-trace-host`)

## 1. Scientific Overview & Methodology
This skill executes a forensic investigation for a target IP address:
- **Conversation Matrix**: Reconstructs source and destination interactions (`srcAddr` $\leftrightarrow$ `dstAddr`).
- **Active Duration Calculation**: $\text{Duration} = \text{lastSwitched} - \text{firstSwitched}$.

---

## 2. MCP Tool Invocation Sequence

1. **Trace Target Host Flow**:
   - Tool: `riptide_trace_host_flow`
   - Parameters: `{"ip_address": "<target_ip>", "time_range_minutes": 30}`

---

## 3. Remediation & Reporting Output

```markdown
### Riptide Host Forensic Walk Summary

- **Target IP**: `192.168.10.45`
- **Active Peer Count**: `14 destination hosts`
- **Top Peer**: `10.42.0.1` (`bytes: 2.1 GB`, `application: SSH`)
- **Flow Duration Range**: `2.4s to 1800s`
- **VLAN / Zone**: `VLAN 100` / `internal_sec`
```
