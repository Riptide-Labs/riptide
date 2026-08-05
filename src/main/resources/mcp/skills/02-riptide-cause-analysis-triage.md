---
name: "riptide-cause-analysis-triage"
description: "Riptide baseline cause analysis skill comparing current traffic windows against historical baselines to isolate root causes of bandwidth shifts."
slash_command: "/riptide-cause-analysis"
---

# Operational Agent Skill: Riptide Baseline Cause Analysis & Traffic Triage (`/riptide-cause-analysis`)

## 1. Scientific Overview & Methodology
This skill implements comparative baseline analysis to answer the operational question *"Why did traffic change on link or subnet X?"*:
- **Baseline Window Comparison**: Compares traffic across the current window ($T_0$: `now() - 15m`) against a historical reference window ($T_1$: `now() - 24h - 15m`).
- **Delta Vector Ranking**: Computes relative variance $\Delta V = \frac{V_{T_0} - V_{T_1}}{V_{T_1}} \times 100\%$ for `application`, `srcAddr`, `dstAddr`, `srcAs`, and `inputSnmpIfName`.

---

## 2. MCP Tool Invocation Sequence

1. **Query Aggregated Baseline Window**:
   - Tool: `riptide_get_top_talkers`
   - Parameters: `{"time_range_minutes": 15, "group_by": "application"}`
2. **Query Baseline Reference Window**:
   - Tool: `riptide_get_top_talkers`
   - Parameters: `{"time_range_minutes": 1455, "group_by": "application"}`

---

## 3. Remediation & Reporting Output

```markdown
### Riptide Baseline Cause Analysis Summary

- **Primary Contributor**: `Application: HTTPS` / `Host: 10.42.100.5`
- **Volume Shift**: `+45.2 Gbps` (+340% increase over 24h baseline)
- **Affected Exporter Interface**: `core-router-01` (`xe-0/0/1` - `Uplink to ISP-A`)
- **Recommended Action**: Inspect host 10.42.100.5 for large data export or schedule QoS rate-limiting.
```
