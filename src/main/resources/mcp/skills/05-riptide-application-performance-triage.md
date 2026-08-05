---
name: "riptide-application-performance-triage"
description: "Riptide application protocol distribution, unclassified traffic audit, and public/private locality audit skill."
slash_command: "/riptide-app-audit"
---

# Operational Agent Skill: Riptide Application Performance & Protocol Audit (`/riptide-app-audit`)

## 1. Scientific Overview & Methodology
This skill audits application classification and traffic locality:
- **Application Rollup Query**: Queries Riptide's `flows_by_application_1m` table for `application` and `protocol`.
- **Unclassified Traffic Audit**: Identifies flows where `application IS NULL`.
- **Locality Verification**: Validates `flowLocality` (`PUBLIC` vs `PRIVATE`).

---

## 2. MCP Tool Invocation Sequence

1. **Query Application Distribution**:
   - Tool: `riptide_get_top_talkers`
   - Parameters: `{"time_range_minutes": 60, "group_by": "application"}`

---

## 3. Remediation & Reporting Output

```markdown
### Riptide Application Performance Summary

- **Top Classified Application**: `HTTPS` — `45% of total bytes`
- **Unclassified Traffic**: `12% of total bytes` (Target Ports: `8443`, `9090`)
- **Locality Violations**: `0 detected`
- **Recommended Action**: Add classification rules for port 8443 (Custom Web Service) in `classification-rules.csv`.
```
