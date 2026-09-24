/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.tools;

import com.google.common.net.InetAddresses;
import org.riptide.mcp.config.ConditionalOnMcpEnabled;
import org.riptide.mcp.protocol.McpToolDefinition;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MCP tool for generating multi-tier mitigation rules (BGP FlowSpec, RTBH null-routes, iptables).
 */
@ConditionalOnMcpEnabled
@Component
public class AutoMitigationRulesTool implements McpTool {

    @Override
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

    @Override
    public List<Map<String, Object>> execute(final Map<String, Object> params) {
        final Map<String, Object> safeParams = params != null ? params : Map.of();
        final Object rawIpObj = safeParams.get("target_ip");
        if (rawIpObj == null) {
            return List.of(Map.of("error", "Missing required parameter 'target_ip'"));
        }

        final String rawIp = String.valueOf(rawIpObj);
        final InetAddress address;
        try {
            address = ToolParams.ipLiteral(rawIp);
        } catch (final IllegalArgumentException e) {
            return List.of(Map.of("error", "Invalid target IP address parameter: " + rawIp));
        }
        final boolean v6 = address instanceof Inet6Address;
        final String ip = InetAddresses.toAddrString(address);
        final String host = ip + (v6 ? "/128" : "/32");
        final String firewall = v6 ? "ip6tables" : "iptables";

        final String attackType = String.valueOf(safeParams.getOrDefault("attack_type", "Volumetric Flood"));

        final Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("target_ip", ip);
        rules.put("attack_type", attackType);

        if (attackType.toLowerCase(Locale.ROOT).contains("syn")) {
            rules.put("bgp_flowspec", "match destination-prefix " + host + " protocol tcp flags syn -> rate-limit 0");
            rules.put("iptables", firewall + " -A INPUT -d " + ip + " -p tcp --tcp-flags SYN,ACK SYN -j DROP");
        } else if (attackType.toLowerCase(Locale.ROOT).contains("udp") || attackType.toLowerCase(Locale.ROOT).contains("dns")) {
            rules.put("bgp_flowspec", "match destination-prefix " + host + " protocol udp -> rate-limit 0");
            rules.put("iptables", firewall + " -A INPUT -d " + ip + " -p udp -j DROP");
        } else {
            rules.put("bgp_flowspec", "match destination-prefix " + host + " -> rate-limit 0");
            rules.put("iptables", firewall + " -A INPUT -d " + ip + " -j DROP");
        }

        rules.put("rtbh_null_route", (v6 ? "ipv6 route " : "ip route ") + host + " Null0 tag 666");

        return List.of(rules);
    }
}
