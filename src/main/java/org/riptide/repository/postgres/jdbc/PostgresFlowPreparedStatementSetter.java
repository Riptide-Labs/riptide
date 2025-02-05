package org.riptide.repository.postgres.jdbc;

import org.riptide.pipeline.EnrichedFlow;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

import static org.riptide.repository.postgres.PostgresUtils.cleanIp;
import static org.riptide.repository.postgres.PostgresUtils.nullSafeTimestamp;

public class PostgresFlowPreparedStatementSetter implements BatchPreparedStatementSetter {

    private final List<EnrichedFlow> flows;

    public PostgresFlowPreparedStatementSetter(List<EnrichedFlow> flows) {
        this.flows = Objects.requireNonNull(flows);
    }

    @Override
    public void setValues(PreparedStatement ps, int i) throws SQLException {
        final var flow = flows.get(i);
        applyValues(ps, flow, 0);
    }

    @Override
    public int getBatchSize() {
        return flows.size();
    }

    public static void applyValues(PreparedStatement ps, EnrichedFlow flow, int offset) throws SQLException {
        var index = 1 + offset;
        ps.setTimestamp(index++, nullSafeTimestamp(flow.getReceivedAt()));
        ps.setTimestamp(index++, nullSafeTimestamp(flow.getTimestamp()));
        ps.setObject(index++, flow.getBytes());
        ps.setString(index++, flow.getDirection() == null ? null : flow.getDirection().name());
        ps.setString(index++, cleanIp(flow.getDstAddr().toString()));
        ps.setString(index++, flow.getDstAddrHostname());
        ps.setObject(index++, flow.getDstMaskLen());
        ps.setObject(index++, flow.getDstPort());
        ps.setObject(index++, flow.getEngineId());
        ps.setObject(index++, flow.getEngineType());
        ps.setTimestamp(index++, nullSafeTimestamp(flow.getDeltaSwitched()));
        ps.setTimestamp(index++, nullSafeTimestamp(flow.getFirstSwitched()));
        ps.setObject(index++, flow.getFlowRecords());
        ps.setObject(index++, flow.getFlowSeqNum());
        ps.setObject(index++, flow.getInputSnmp());
        ps.setObject(index++, flow.getIpProtocolVersion());
        ps.setTimestamp(index++, nullSafeTimestamp(flow.getLastSwitched()));
        ps.setString(index++, cleanIp(flow.getNextHop()));
        ps.setObject(index++, flow.getOutputSnmp());
        ps.setObject(index++, flow.getPackets());
        ps.setObject(index++, flow.getProtocol());
        ps.setString(index++, flow.getSamplingAlgorithm() == null ? null : flow.getSamplingAlgorithm().name());
        ps.setString(index++, cleanIp(flow.getSrcAddr()));
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
}
