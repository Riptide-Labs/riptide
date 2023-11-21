package org.riptide.flows.netflow9;


import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.IllegalFlowException;
import org.riptide.flows.parser.RecordBuilder;
import org.assertj.core.api.Assertions;
import org.riptide.flows.parser.ie.values.UnsignedValue;
import org.riptide.flows.parser.netflow9.Netflow9FlowBuilder;

public class FlowEntitiyTimeoutTest {

    @Test
    void verifyWithoutTimeout() throws IllegalFlowException {
        final var record = new RecordBuilder()
                .add(new UnsignedValue("@unixSecs", 0))
                .add(new UnsignedValue("@sysUpTime", 0))
                .add(new UnsignedValue("FIRST_SWITCHED", 123000))
                .add(new UnsignedValue("LAST_SWITCHED", 987000));

        final var flowMessage = new Netflow9FlowBuilder().buildFlow(Instant.EPOCH, record.values());
        Assertions.assertThat(flowMessage.getFirstSwitched()).isEqualTo(Instant.ofEpochMilli(123000L));
        Assertions.assertThat(flowMessage.getDeltaSwitched()).isEqualTo(Instant.ofEpochMilli(123000L));
        Assertions.assertThat(flowMessage.getLastSwitched()).isEqualTo(Instant.ofEpochMilli(987000L));

    }

    @Test
    void verifyWithActiveTimeout() throws IllegalFlowException {
        final var record = new RecordBuilder()
                .add(new UnsignedValue("@unixSecs", 0))
                .add(new UnsignedValue("@sysUpTime", 0))
                .add(new UnsignedValue("FIRST_SWITCHED", 123000))
                .add(new UnsignedValue("LAST_SWITCHED", 987000))
                .add(new UnsignedValue("IN_BYTES", 10))
                .add(new UnsignedValue("IN_PKTS", 10))
                .add(new UnsignedValue("FLOW_ACTIVE_TIMEOUT", 10))
                .add(new UnsignedValue("FLOW_INACTIVE_TIMEOUT", 300));

        final var flowMessage = new Netflow9FlowBuilder().buildFlow(Instant.EPOCH, record.values());
        Assertions.assertThat(flowMessage.getFirstSwitched()).isEqualTo(Instant.ofEpochMilli(123000L));
        Assertions.assertThat(flowMessage.getDeltaSwitched()).isEqualTo(Instant.ofEpochMilli(987000L - 10000L));
        Assertions.assertThat(flowMessage.getLastSwitched()).isEqualTo(Instant.ofEpochMilli(987000L));
    }

    @Test
    void verifyWithInactiveTimeout() throws IllegalFlowException {
        final var record = new RecordBuilder()
                .add(new UnsignedValue("@unixSecs", 0))
                .add(new UnsignedValue("@sysUpTime", 0))
                .add(new UnsignedValue("FIRST_SWITCHED", 123000))
                .add(new UnsignedValue("LAST_SWITCHED", 987000))
                .add(new UnsignedValue("IN_BYTES", 0))
                .add(new UnsignedValue("IN_PKTS", 0))
                .add(new UnsignedValue("FLOW_ACTIVE_TIMEOUT", 10))
                .add(new UnsignedValue("FLOW_INACTIVE_TIMEOUT", 300));

        final var flowMessage = new Netflow9FlowBuilder().buildFlow(Instant.EPOCH, record.values());
        Assertions.assertThat(flowMessage.getFirstSwitched()).isEqualTo(Instant.ofEpochMilli(123000L));
        Assertions.assertThat(flowMessage.getDeltaSwitched()).isEqualTo(Instant.ofEpochMilli(987000L - 300000L));
        Assertions.assertThat(flowMessage.getLastSwitched()).isEqualTo(Instant.ofEpochMilli(987000L));
    }
}
