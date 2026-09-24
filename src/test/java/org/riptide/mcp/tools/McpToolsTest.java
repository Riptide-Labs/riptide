/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.riptide.config.ClickhouseConfig;
import org.riptide.mcp.config.McpProperties;
import org.riptide.mcp.service.RiptideMcpService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class McpToolsTest {

    private RiptideMcpService mockMcpService;

    @BeforeEach
    public void setUp() {
        final ClickhouseConfig chConfig = new ClickhouseConfig();
        chConfig.setDatabase("riptide_test");
        final McpProperties properties = new McpProperties();
        mockMcpService = new RiptideMcpService(null, chConfig, properties, new ObjectMapper());
    }

    @Test
    public void testsAutoMitigationRulesTool() {
        final AutoMitigationRulesTool tool = new AutoMitigationRulesTool();
        assertThat(tool.getDefinition().getName()).isEqualTo("riptide_generate_mitigation_rules");

        final List<Map<String, Object>> result = tool.execute(Map.of("target_ip", "192.0.2.100", "attack_type", "TCP SYN Flood"));
        assertThat(result).hasSize(1);
        final Map<String, Object> rules = result.get(0);
        assertThat(rules.get("target_ip")).isEqualTo("192.0.2.100");
        assertThat(rules.get("bgp_flowspec"))
                .isEqualTo("match destination-prefix 192.0.2.100/32 protocol tcp flags syn -> rate-limit 0");
        assertThat(rules.get("iptables"))
                .isEqualTo("iptables -A INPUT -d 192.0.2.100 -p tcp --tcp-flags SYN,ACK SYN -j DROP");
        assertThat(rules.get("rtbh_null_route")).isEqualTo("ip route 192.0.2.100/32 Null0 tag 666");
    }

    /** #880: an IPv6 target got IPv4 syntax in every rule, a /32 included. */
    @Test
    public void mitigationRulesForAnIpv6TargetUseIpv6Syntax() {
        final Map<String, Object> rules = new AutoMitigationRulesTool()
                .execute(Map.of("target_ip", "2001:DB8:0:0:0:0:0:10", "attack_type", "UDP Amplification")).get(0);

        assertThat(rules.get("target_ip")).isEqualTo("2001:db8::10");
        assertThat(rules.get("bgp_flowspec"))
                .isEqualTo("match destination-prefix 2001:db8::10/128 protocol udp -> rate-limit 0");
        assertThat(rules.get("iptables")).isEqualTo("ip6tables -A INPUT -d 2001:db8::10 -p udp -j DROP");
        assertThat(rules.get("rtbh_null_route")).isEqualTo("ipv6 route 2001:db8::10/128 Null0 tag 666");
    }

    /** #880: the scrubbing diversion named a host under the project's own domain, with nothing behind it. */
    @Test
    public void mitigationRulesNameNoScrubbingTarget() {
        final Map<String, Object> rules = new AutoMitigationRulesTool()
                .execute(Map.of("target_ip", "203.0.113.10", "attack_type", "Volumetric Flood")).get(0);

        assertThat(rules).containsOnlyKeys("target_ip", "attack_type", "bgp_flowspec", "iptables", "rtbh_null_route");
    }

    /** mcp-server.md: "Pass a literal address, not a hostname". A name used to be resolved instead. */
    @Test
    public void mitigationRulesRefuseAHostname() {
        assertThat(new AutoMitigationRulesTool().execute(Map.of("target_ip", "localhost", "attack_type", "x")))
                .containsExactly(Map.of("error", "Invalid target IP address parameter: localhost"));
    }

    @Test
    public void testsTopTalkersToolValidation() {
        final TopTalkersTool tool = new TopTalkersTool(mockMcpService);
        assertThat(tool.getDefinition().getName()).isEqualTo("riptide_get_top_talkers");

        final List<Map<String, Object>> invalidResult = tool.execute(Map.of("group_by", "invalidDimension"));
        assertThat(invalidResult.get(0)).containsKey("error");
    }

    @Test
    public void testsHostTraceToolValidation() {
        final HostTraceTool tool = new HostTraceTool(mockMcpService);
        assertThat(tool.getDefinition().getName()).isEqualTo("riptide_trace_host_flow");

        final List<Map<String, Object>> missingIp = tool.execute(Map.of());
        assertThat(missingIp.get(0)).containsKey("error");

        final List<Map<String, Object>> invalidIp = tool.execute(Map.of("ip_address", "not-an-ip"));
        assertThat(invalidIp.get(0)).containsKey("error");
    }

    /** The address is formatted into SQL: a name must be refused before any query, not resolved. */
    @Test
    public void hostTraceRefusesAHostnameWithoutQuerying() {
        final var recording = new RecordingMcpService();
        assertThat(new HostTraceTool(recording).execute(Map.of("ip_address", "localhost")))
                .containsExactly(Map.of("error", "Invalid IP address parameter: localhost"));
        assertThat(recording.lastSql).isNull();
    }

    /** An IPv6 zone has no meaning in a flow column and must not reach the SQL text. */
    @Test
    public void hostTraceDropsAnIpv6Zone() {
        final var recording = new RecordingMcpService();
        new HostTraceTool(recording).execute(Map.of("ip_address", "fe80::1%en0"));
        assertThat(recording.lastSql)
                .contains("srcAddr = 'fe80:0:0:0:0:0:0:1'")
                .doesNotContain("%en0");
    }

    @Test
    public void testsInterfaceUtilizationToolDefinition() {
        final InterfaceUtilizationTool tool = new InterfaceUtilizationTool(mockMcpService);
        assertThat(tool.getDefinition().getName()).isEqualTo("riptide_get_interface_utilization");
    }

    @Test
    public void testsGeoAsnToolDefinition() {
        final GeoAsnTool tool = new GeoAsnTool(mockMcpService);
        assertThat(tool.getDefinition().getName()).isEqualTo("riptide_get_geo_asn_distribution");
    }

    @Test
    public void testsTrafficSpikesToolDefinition() {
        final TrafficSpikesTool tool = new TrafficSpikesTool(mockMcpService);
        assertThat(tool.getDefinition().getName()).isEqualTo("riptide_detect_traffic_spikes");
    }

    /**
     * On the rollup route a row is a minute's worth of pre-aggregated flows, so the flow count has
     * to come from the summed measure. COUNT(*) there would count SummingMergeTree parts, which
     * undercounts and shifts as merges run — during a volumetric attack, the case this tool exists
     * for.
     */
    @Test
    public void countsFlowsWithTheSummedMeasureOnTheRollupRoute() {
        final RecordingMcpService recording = new RecordingMcpService();
        final TrafficSpikesTool tool = new TrafficSpikesTool(recording);

        tool.execute(Map.of("time_range_minutes", 1440));

        assertThat(recording.lastSql).contains("`riptide_test`.flows_by_conversation_1m");
        assertThat(recording.lastSql).contains("SUM(flowCount) AS flow_count");
        assertThat(recording.lastSql).doesNotContain("COUNT(*)");
    }

    @Test
    public void countsFlowRowsOnTheRawRoute() {
        final RecordingMcpService recording = new RecordingMcpService();
        final TrafficSpikesTool tool = new TrafficSpikesTool(recording);

        tool.execute(Map.of("time_range_minutes", 15));

        assertThat(recording.lastSql).contains("`riptide_test`.flows ");
        assertThat(recording.lastSql).contains("COUNT(*) AS flow_count");
    }

    /**
     * Every tool that routes to a table goes through the coverage-reporting entry point (#609).
     *
     * <p>Driven through the tools rather than through {@code RiptideMcpService}, because a test on
     * the service passes while a tool that never calls it stays broken — and the issue named only
     * two of the four routing tools, so the set had to be found rather than taken.</p>
     */
    @Test
    public void everyToolTakingATimeRangeReportsCoverage() {
        record Routing(String name, McpTool tool, RecordingMcpService service) { }
        final List<Routing> routing = new java.util.ArrayList<>();
        for (final String name : List.of("topTalkers", "trafficSpikes", "geoAsn",
                "interfaceUtilization", "hostTrace")) {
            final var recording = new RecordingMcpService();
            routing.add(new Routing(name, switch (name) {
                case "topTalkers" -> new TopTalkersTool(recording);
                case "trafficSpikes" -> new TrafficSpikesTool(recording);
                case "geoAsn" -> new GeoAsnTool(recording);
                case "interfaceUtilization" -> new InterfaceUtilizationTool(recording);
                // takes a range and always reads raw flows: the tool most exposed to answering
                // short, and the one a QueryRouter grep does not find
                default -> new HostTraceTool(recording);
            }, recording));
        }

        for (final Routing entry : routing) {
            // hostTrace needs an address before it will query at all; the others ignore the extra key
            entry.tool().execute(Map.of("time_range_minutes", 1440, "ip_address", "10.0.0.1"));
            assertThat(entry.service().lastTable)
                    .as("%s must route through executeRangeQuery, or its answers never say they are"
                            + " short", entry.name())
                    .isNotNull();
            assertThat(entry.service().lastSql)
                    .as("%s must still pass its own SQL, not the coverage probe", entry.name())
                    .contains(entry.service().lastTable);
        }
    }

    /** Captures the SQL a tool builds instead of running it. */
    private static final class RecordingMcpService extends RiptideMcpService {
        private String lastSql;
        private String lastTable;

        private RecordingMcpService() {
            super(null, databaseNamed("riptide_test"), new McpProperties(), new ObjectMapper());
        }

        private static ClickhouseConfig databaseNamed(final String database) {
            final ClickhouseConfig config = new ClickhouseConfig();
            config.setDatabase(database);
            return config;
        }

        @Override
        public List<Map<String, Object>> executeQuery(final String sqlQuery) {
            this.lastSql = sqlQuery;
            return List.of();
        }

        /**
         * Recorded at this seam, not at {@code executeQuery}, because a routed tool now makes two
         * round trips: its own query and the coverage probe behind it. Capturing the last
         * {@code executeQuery} would record the probe and assert nothing about the tool.
         */
        @Override
        public List<Map<String, Object>> executeRangeQuery(final String sqlQuery, final String table,
                                                            final int effectiveMinutes,
                                                            final int requestedMinutes) {
            this.lastSql = sqlQuery;
            this.lastTable = table;
            return List.of();
        }
    }
}
