package org.riptide.repository.postgres;

import org.riptide.config.PostgresConfig;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.FlowException;
import org.riptide.repository.FlowRepository;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;

public class PostgresRepository implements FlowRepository {

    private final PostgresConfig config;
    private final DataSource dataSource;

    public PostgresRepository(final PostgresConfig config, final DataSource dataSource) {
        this.config = Objects.requireNonNull(config);
        this.dataSource = Objects.requireNonNull(dataSource);
        this.init();
    }

    private void init() {
        final var template = new JdbcTemplate(dataSource);
        template.execute("drop table flows");
        template.execute("""
create table public.flows (
    received_at timestamp without time zone,
    timestamp timestamp without time zone,
    bytes integer,
    direction character varying(100),
    dst_addr inet,
    dst_addr_hostname character varying(255),
    dst_mask_length integer,
    dst_port integer,
    engine_id integer,
    engine_type integer,
    delta_switched timestamp without time zone,
    first_switched timestamp without time zone,
    flow_records integer,
    flow_sequence_number bigint,
    input_snmp integer,
    ip_protocol_version integer,
    last_switched timestamp without time zone,
    next_hop inet,
    output_snmp integer,
    packets bigint,
    protocol integer,
    sampling_algorithm character varying(100),
    src_addr inet,
    src_addr_hostname character varying(255),
    src_as bigint,
    src_mask_length integer,
    src_port integer,
    tcp_flags integer,
    tos integer,
    flow_protocol character varying(100),
    vlan integer,
    application character varying(255),
    exporter_addr character varying(255),
    location character varying(255),
    src_locality character varying(100),
    dst_locality character varying(100),
    flow_locality character varying(100),
    clock_correction_ms bigint,
    input_snmp_ifname character varying(255),
    output_snmp_ifname character varying(255)
  );
""");
    }

    private static final String INSERT_SQL = """
    INSERT INTO flows (
                       received_at, timestamp, bytes, direction,
                       dst_addr, dst_addr_hostname, dst_mask_length, dst_port, engine_id, engine_type,
                       delta_switched, first_switched, flow_records, flow_sequence_number,
                       input_snmp, ip_protocol_version, last_switched, next_hop,
                       output_snmp, packets, protocol, sampling_algorithm,
                       src_addr, src_addr_hostname, src_as, src_mask_length,
                       src_port, tcp_flags, tos, flow_protocol,
                       vlan, application, exporter_addr, location,
                       src_locality, dst_locality, flow_locality, clock_correction_ms,
                       input_snmp_ifname, output_snmp_ifname
                   ) 
    VALUES (?, ?, ?, ?,
            ?::INET, ?, ?, ?, ?, ?,
            ?, ?, ?, ?,
            ?, ?, ?, ?::INET,
            ?, ?, ?, ?,
            ?::INET, ?, ?, ?,
            ?, ?, ?, ?,
            ?, ?, ?, ?,
            ?, ?, ?, ?,
            ?, ?
            )
""";

    @Override
    public void persist(Collection<EnrichedFlow> flows) throws FlowException, IOException {
        final var list = new ArrayList<>(flows);
        new JdbcTemplate(dataSource).batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {

            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                final var flow = list.get(i);
                var index = 1;
                ps.setTimestamp(index++, new Timestamp(flow.getReceivedAt().toEpochMilli()));
                ps.setTimestamp(index++, new Timestamp(flow.getTimestamp().toEpochMilli()));
                ps.setLong(index++, flow.getBytes());
                ps.setString(index++, flow.getDirection() == null ? null : flow.getDirection().name());

                // Dst
                ps.setString(index++, cleanIp(flow.getDstAddr().toString()));
                ps.setString(index++, flow.getDstAddrHostname());
                ps.setObject(index++, flow.getDstMaskLen());
                ps.setInt(index++, flow.getDstPort());

                // Engine
                ps.setObject(index++, flow.getEngineId());
                ps.setObject(index++, flow.getEngineType());

                ps.setTimestamp(index++, new Timestamp(flow.getDeltaSwitched().toEpochMilli()));
                ps.setTimestamp(index++, new Timestamp(flow.getFirstSwitched().toEpochMilli()));
                ps.setObject(index++, flow.getFlowRecords());
                ps.setObject(index++, flow.getFlowSeqNum());

                ps.setObject(index++, flow.getInputSnmp());
                ps.setObject(index++, flow.getIpProtocolVersion());
                ps.setTimestamp(index++, new Timestamp(flow.getLastSwitched().toEpochMilli()));
                ps.setString(index++, cleanIp(flow.getNextHop().toString()));

                ps.setObject(index++, flow.getOutputSnmp());
                ps.setObject(index++, flow.getPackets());
                ps.setObject(index++, flow.getProtocol());
                ps.setString(index++, flow.getSamplingAlgorithm() == null ? null : flow.getSamplingAlgorithm().name());

                ps.setString(index++, cleanIp(flow.getSrcAddr().toString()));
                ps.setString(index++, flow.getSrcAddrHostname());
                ps.setObject(index++, flow.getSrcAs());
                ps.setObject(index++, flow.getSrcMaskLen());

                ps.setObject(index++, flow.getSrcPort());
                ps.setObject(index++, flow.getTcpFlags());
                ps.setObject(index++, flow.getTos());
                ps.setString(index++, flow.getFlowProtocol() == null ? null : flow.getFlowProtocol().name());

                ps.setObject(index++, flow.getVlan());
                ps.setString(index++, flow.getApplication());
                ps.setString(index++, cleanIp(flow.getExporterAddr()));
                ps.setString(index++, flow.getLocation());

                ps.setString(index++, flow.getSrcLocality() == null ? null : flow.getSrcLocality().name());
                ps.setString(index++, flow.getDstLocality() == null ? null : flow.getDstLocality().name());
                ps.setString(index++, flow.getFlowLocality() == null ? null : flow.getFlowLocality().name());
                ps.setObject(index++, flow.getClockCorrection() == null ? null : flow.getClockCorrection().toMillis());

                ps.setString(index++, flow.getInputSnmpIfName());
                ps.setString(index++, flow.getOutputSnmpIfName());
            }

            @Override
            public int getBatchSize() {
                return flows.size();
            }
        });
    }

    private static String cleanIp(String string) {
        if (string.startsWith("/")) {
            return string.substring(1);
        }
        return string;
    }
}
