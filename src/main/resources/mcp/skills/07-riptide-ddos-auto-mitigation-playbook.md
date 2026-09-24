---
name: "riptide-ddos-auto-mitigation-playbook"
description: "Riptide multi-tier automated mitigation rules generator emitting BGP FlowSpec, RTBH null-routes, and iptables rules."
slash_command: "/riptide-auto-mitigate"
---

# Operational Agent Skill: Riptide Automated Mitigation Rules Generator (`/riptide-auto-mitigate`)

## 1. Scientific Overview & Standards
This skill converts active attack classification output into actionable multi-tier network mitigation rules:
- **BGP FlowSpec (RFC 8955)**: Granular flow filtering at the network edge.
- **RTBH (RFC 3882 / RFC 7999)**: Remote Triggered Black Hole null-routing.

---

## 2. MCP Tool Invocation Sequence

1. **Generate Mitigation Rules**:
   - Tool: `riptide_generate_mitigation_rules`
   - Parameters: `{"target_ip": "<victim_ip>", "attack_type": "<attack_family>"}`

---

## 3. Remediation & Reporting Output

Copy each rule verbatim from the tool result; do not rewrite prefixes or commands. An IPv6 target comes back with `/128`, `ipv6 route` and `ip6tables`, and an IPv4 target with `/32`, `ip route` and `iptables`.

```text
[Tier 1: BGP FlowSpec (RFC 8955)]
<bgp_flowspec>

[Tier 2: RTBH Null-Route (RFC 7999)]
<rtbh_null_route>

[Tier 3: Host Firewall]
<iptables>
```
