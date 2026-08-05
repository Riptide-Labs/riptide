---
name: "riptide-peering-geo-analysis"
description: "Riptide BGP Autonomous System Number (ASN) and geographic traffic breakdown skill for transit optimization."
slash_command: "/riptide-peering-analysis"
---

# Operational Agent Skill: Riptide BGP Peering & Geo-ASN Transit Analysis (`/riptide-peering-analysis`)

## 1. Scientific Overview & Methodology
This skill analyzes cross-border and Autonomous System traffic flows to optimize transit costs:
- **1-Minute Rollup Query Acceleration**: Queries Riptide's `flows_by_geo_asn_1m` table to evaluate `srcAs`, `dstAs`, `srcAsOrg`, `dstAsOrg`, `srcCountry`, and `dstCountry`.

---

## 2. MCP Tool Invocation Sequence

1. **Query Geo-ASN Rollups**:
   - Tool: `riptide_get_geo_asn_distribution`
   - Parameters: `{"time_range_minutes": 1440}`

---

## 3. Remediation & Reporting Output

```markdown
### Riptide BGP Peering & Geo-ASN Summary

- **Top Egress Destination AS**: `AS13335 (Cloudflare Inc.)` — `12.4 TB / 24h`
- **Second Egress Destination AS**: `AS15169 (Google LLC)` — `8.1 TB / 24h`
- **Top Egress Country**: `United States (US)` — `65% of total egress`
- **Peering Recommendation**: Establish direct IXP peering with AS13335 at Equinix NY to eliminate IP transit costs.
```
