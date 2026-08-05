---
name: "riptide-interface-capacity-analysis"
description: "Riptide interface bandwidth utilization, 95th percentile billing, and link headroom projection skill."
slash_command: "/riptide-capacity-plan"
---

# Operational Agent Skill: Riptide Interface Capacity & 95th Percentile Analysis (`/riptide-capacity-plan`)

## 1. Scientific Overview & Formulas
This skill evaluates network interface throughput against configured SNMP interface speeds:
- **Link Utilization Formula**:
  $$\text{Utilization \%} = \frac{\text{Bytes} \times 8}{\text{Window Seconds} \times \text{IfSpeed}} \times 100$$
- **Directional Isolation**: Separates `INGRESS` vs `EGRESS` utilization using Riptide's enriched SNMP interface names (`inputSnmpIfName`, `outputSnmpIfName`) and aliases (`inputSnmpIfAlias`).

---

## 2. MCP Tool Invocation Sequence

1. **Query Interface Throughput**:
   - Tool: `riptide_get_interface_utilization`
   - Parameters: `{"time_range_minutes": 60, "limit": 20}`

---

## 3. Remediation & Reporting Output

```markdown
### Riptide Interface Capacity Summary

- **Exporter / Router**: `core-sw-01` (`10.0.0.1`)
- **Interface**: `xe-0/0/1` (`Uplink to Core ISP`)
- **Configured Speed**: `10 Gbps`
- **Current Ingress Throughput**: `8.4 Gbps` (**84% Utilization**)
- **Headroom Remaining**: `1.6 Gbps`
- **Recommended Action**: Initiate upgrade request to 40G/100G uplink or balance traffic across `xe-0/0/2`.
```
