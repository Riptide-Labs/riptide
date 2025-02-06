package org.riptide.flows.ipfix;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ValueConversionService;
import org.riptide.flows.parser.ipfix.IpFixFlowBuilder;
import org.riptide.flows.parser.ipfix.IpfixRawFlow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@SpringBootTest
public class FlowTimeoutTest {
    @Autowired
    private ValueConversionService conversionService;

    @Test
    void testWithoutTimeout() {
        final var raw = new IpfixRawFlow();
        raw.exportTime = Instant.EPOCH;
        raw.flowStartSeconds = Instant.ofEpochSecond(123);
        raw.flowEndSeconds = Instant.ofEpochSecond(987);

        final var flow = new IpFixFlowBuilder(conversionService).buildFlow(Instant.EPOCH, raw);

        Assertions.assertThat(flow.getFirstSwitched()).isEqualTo(Instant.ofEpochMilli(123000L));
        Assertions.assertThat(flow.getDeltaSwitched()).isEqualTo(Instant.ofEpochMilli(123000L)); // Timeout is same as first
        Assertions.assertThat(flow.getLastSwitched()).isEqualTo(Instant.ofEpochMilli(987000L));
    }

    @Test
    void testWithActiveTimeout() {
        final var raw = new IpfixRawFlow();
        raw.exportTime = Instant.EPOCH;
        raw.flowStartSeconds = Instant.ofEpochSecond(123);
        raw.flowEndSeconds = Instant.ofEpochSecond(987);
        raw.octetDeltaCount = 10L;
        raw.packetDeltaCount = 10L;
        raw.flowActiveTimeout = Duration.ofSeconds(10);
        raw.flowInactiveTimeout = Duration.ofSeconds(300L);

        final var flow = new IpFixFlowBuilder(conversionService).buildFlow(Instant.EPOCH, raw);

        Assertions.assertThat(flow.getFirstSwitched()).isEqualTo(Instant.ofEpochMilli(123000L));
        Assertions.assertThat(flow.getDeltaSwitched()).isEqualTo(Instant.ofEpochMilli(987000L - 10000L));
        Assertions.assertThat(flow.getLastSwitched()).isEqualTo(Instant.ofEpochMilli(987000L));
    }

    @Test
    void testWithInactiveTimeout() {
        final var raw = new IpfixRawFlow();
        raw.exportTime = Instant.EPOCH;
        raw.flowStartSeconds = Instant.ofEpochSecond(123);
        raw.flowEndSeconds = Instant.ofEpochSecond(987);
        raw.octetDeltaCount = 0L;
        raw.packetDeltaCount = 0L;
        raw.flowActiveTimeout = Duration.ofSeconds(10);
        raw.flowInactiveTimeout = Duration.ofSeconds(300);

        final var flow = new IpFixFlowBuilder(conversionService).buildFlow(Instant.EPOCH, raw);

        Assertions.assertThat(flow.getFirstSwitched()).isEqualTo(Instant.ofEpochMilli(123000L));
        Assertions.assertThat(flow.getDeltaSwitched()).isEqualTo(Instant.ofEpochMilli(987000L - 300000L));
        Assertions.assertThat(flow.getLastSwitched()).isEqualTo(Instant.ofEpochMilli(987000L));
    }

    @Test
    void testFirstLastSwitchedValues1() {
        final var raw = new IpfixRawFlow();
        raw.exportTime = Instant.EPOCH;
        raw.flowStartSeconds = Instant.ofEpochSecond(123);
        raw.flowEndSeconds = Instant.ofEpochSecond(987);

        final var flowMessage = new IpFixFlowBuilder(conversionService).buildFlow(Instant.EPOCH, raw);

        Assertions.assertThat(flowMessage.getFirstSwitched()).isEqualTo(Instant.ofEpochMilli(123000L));
        Assertions.assertThat(flowMessage.getDeltaSwitched()).isEqualTo(Instant.ofEpochMilli(123000L));
        Assertions.assertThat(flowMessage.getLastSwitched()).isEqualTo(Instant.ofEpochMilli(987000L));
    }

    @Test
    void testFirstLastSwitchedValues2() {
        final var raw = new IpfixRawFlow();
        raw.exportTime = Instant.EPOCH;
        raw.systemInitTimeMilliseconds = Instant.ofEpochMilli(100000);
        raw.flowStartSysUpTime = Duration.of(2000000, ChronoUnit.MILLIS);
        raw.flowEndSysUpTime = Duration.of(4000000, ChronoUnit.MILLIS);

        final var flow = new IpFixFlowBuilder(conversionService).buildFlow(Instant.EPOCH, raw);

        Assertions.assertThat(flow.getFirstSwitched()).isEqualTo(Instant.ofEpochMilli(2000000L + 100000L));
        Assertions.assertThat(flow.getDeltaSwitched()).isEqualTo(Instant.ofEpochMilli(2000000L + 100000L));
        Assertions.assertThat(flow.getLastSwitched()).isEqualTo(Instant.ofEpochMilli(4100000L));
    }
}
