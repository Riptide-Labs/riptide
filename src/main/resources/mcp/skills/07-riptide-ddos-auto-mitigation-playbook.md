---
name: "riptide-ddos-auto-mitigation-playbook"
description: "Riptide multi-tier automated mitigation rules generator emitting BGP FlowSpec, RTBH null-routes, iptables, and cloud scrubbing redirection rules."
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

```text
[Tier 1: BGP FlowSpec (RFC 8955)]
match destination-prefix <victim_ip>/32 protocol tcp flags syn -> rate-limit 0

[Tier 2: RTBH Null-Route (RFC 7999)]
ip route <victim_ip>/32 Null0 tag 666

[Tier 3: Host Firewall (iptables)]
iptables -A INPUT -p tcp --dport 80 -m tcp --tcp-flags SYN,ACK SYN -j DROP
```
