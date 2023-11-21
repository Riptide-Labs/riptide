package org.riptide.flows.ipfix;

import java.time.Instant;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.RecordBuilder;
import org.riptide.flows.parser.ie.values.DateTimeValue;
import org.riptide.flows.parser.ie.values.UnsignedValue;
import org.riptide.flows.parser.ipfix.IpFixFlowBuilder;

public class FlowEntitiyTimeoutTest {

    @Test
    void testWithoutTimeout() {
         final var record = new RecordBuilder()
                .add(new DateTimeValue("flowStartSeconds", Instant.ofEpochSecond(123)))
                .add(new DateTimeValue("flowEndSeconds", Instant.ofEpochSecond(987)));

        final var flow = new IpFixFlowBuilder().buildFlow(Instant.EPOCH, record.values());

        Assertions.assertThat(flow.getFirstSwitched()).isEqualTo(Instant.ofEpochMilli(123000L));
        Assertions.assertThat(flow.getDeltaSwitched()).isEqualTo(Instant.ofEpochMilli(123000L)); // Timeout is same as first
        Assertions.assertThat(flow.getLastSwitched()).isEqualTo(Instant.ofEpochMilli(987000L));
    }

    @Test
    void testWithActiveTimeout() {
        final var record = new RecordBuilder()
                .add(new DateTimeValue("flowStartSeconds", Instant.ofEpochSecond(123)))
                .add(new DateTimeValue("flowEndSeconds", Instant.ofEpochSecond(987)))
                .add(new UnsignedValue("octetDeltaCount", 10))
                .add(new UnsignedValue("packetDeltaCount", 10))
                .add(new UnsignedValue("flowActiveTimeout", 10))
                .add(new UnsignedValue("flowInactiveTimeout", 300));

        final var flow = new IpFixFlowBuilder().buildFlow(Instant.EPOCH, record.values());

        Assertions.assertThat(flow.getFirstSwitched()).isEqualTo(Instant.ofEpochMilli(123000L));
        Assertions.assertThat(flow.getDeltaSwitched()).isEqualTo(Instant.ofEpochMilli(987000L - 10000L));
        Assertions.assertThat(flow.getLastSwitched()).isEqualTo(Instant.ofEpochMilli(987000L));
    }

    @Test
    void testWithInactiveTimeout() {
        final var record = new RecordBuilder()
                .add(new DateTimeValue("flowStartSeconds", Instant.ofEpochSecond(123)))
                .add(new DateTimeValue("flowEndSeconds", Instant.ofEpochSecond(987)))
                .add(new UnsignedValue("octetDeltaCount", 0))
                .add(new UnsignedValue("packetDeltaCount", 0))
                .add(new UnsignedValue("flowActiveTimeout", 10))
                .add(new UnsignedValue("flowInactiveTimeout", 300));
        final var flow = new IpFixFlowBuilder().buildFlow(Instant.EPOCH, record.values());

        Assertions.assertThat(flow.getFirstSwitched()).isEqualTo(Instant.ofEpochMilli(123000L));
        Assertions.assertThat(flow.getDeltaSwitched()).isEqualTo(Instant.ofEpochMilli(987000L - 300000L));
        Assertions.assertThat(flow.getLastSwitched()).isEqualTo(Instant.ofEpochMilli(987000L));
    }

    @Test
    void testFirstLastSwitchedValues() {
        var record = new RecordBuilder()
                .add(new DateTimeValue("flowStartSeconds", Instant.ofEpochSecond(123)))
                .add(new DateTimeValue("flowEndSeconds", Instant.ofEpochSecond(987)));

        final var flowMessage = new IpFixFlowBuilder().buildFlow(Instant.EPOCH, record.values());

        Assertions.assertThat(flowMessage.getFirstSwitched()).isEqualTo(Instant.ofEpochMilli(123000L));
        Assertions.assertThat(flowMessage.getDeltaSwitched()).isEqualTo(Instant.ofEpochMilli(123000L));
        Assertions.assertThat(flowMessage.getLastSwitched()).isEqualTo(Instant.ofEpochMilli(987000L));

        record = new RecordBuilder()
                .add(new DateTimeValue("systemInitTimeMilliseconds", Instant.ofEpochMilli(100000)))
                .add(new UnsignedValue("flowStartSysUpTime", 2000000))
                .add(new UnsignedValue("flowEndSysUpTime", 4000000));
        final var flow = new IpFixFlowBuilder().buildFlow(Instant.EPOCH, record.values());

        Assertions.assertThat(flow.getFirstSwitched()).isEqualTo(Instant.ofEpochMilli(2000000L + 100000L));
        Assertions.assertThat(flow.getDeltaSwitched()).isEqualTo(Instant.ofEpochMilli(2000000L + 100000L));
        Assertions.assertThat(flow.getLastSwitched()).isEqualTo(Instant.ofEpochMilli(4100000L));
    }
}
