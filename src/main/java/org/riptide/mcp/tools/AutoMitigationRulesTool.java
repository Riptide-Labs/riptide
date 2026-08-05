/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.tools;

import org.riptide.classification.IpAddr;
import org.riptide.mcp.protocol.McpToolDefinition;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool for generating multi-tier mitigation rules (BGP FlowSpec, RTBH null-routes, iptables).
 */
@Component
public class AutoMitigationRulesTool {

    public McpToolDefinition getDefinition() {
        return McpToolDefinition.builder()
                .name("riptide_generate_mitigation_rules")
                .description("Generates multi-tier BGP FlowSpec, RTBH null-route, and iptables mitigation rules for a target victim IP.")
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "target_ip", Map.of("type", "string", "description", "Target victim IP address"),
                                "attack_type", Map.of("type", "string", "description", "Classified attack family (e.g. TCP SYN Flood, UDP Amplification)")
                        ),
                        "required", List.of("target_ip", "attack_type")
                ))
                .build();
    }

    public List<Map<String, Object>> execute(final Map<String, Object> params) {
        final String rawIp = String.valueOf(params.get("target_ip"));
        final String ip;
        try {
            ip = IpAddr.of(rawIp.trim()).toString();
        } catch (final Exception e) {
            return List.of(Map.of("error", "Invalid target IP address parameter: " + rawIp));
        }

        final String attackType = String.valueOf(params.getOrDefault("attack_type", "Volumetric Flood"));

        final Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("target_ip", ip);
        rules.put("attack_type", attackType);

        if (attackType.toLowerCase().contains("syn")) {
            rules.put("bgp_flowspec", "match destination-prefix " + ip + "/32 protocol tcp flags syn -> rate-limit 0");
            rules.put("iptables", "iptables -A INPUT -d " + ip + " -p tcp --tcp-flags SYN,ACK SYN -j DROP");
        } else if (attackType.toLowerCase().contains("udp") || attackType.toLowerCase().contains("dns")) {
            rules.put("bgp_flowspec", "match destination-prefix " + ip + "/32 protocol udp -> rate-limit 0");
            rules.put("iptables", "iptables -A INPUT -d " + ip + " -p udp -j DROP");
        } else {
            rules.put("bgp_flowspec", "match destination-prefix " + ip + "/32 -> rate-limit 0");
            rules.put("iptables", "iptables -A INPUT -d " + ip + " -j DROP");
        }

        rules.put("rtbh_null_route", "ip route " + ip + "/32 Null0 tag 666");
        rules.put("cloud_scrubbing", "Diversion CNAME: " + ip.replace('.', '-') + ".scrubbing.riptide.space");

        return List.of(rules);
    }
}
