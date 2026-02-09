package org.riptide.flows.parser;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.ie.values.ValueConversionService;
import org.riptide.flows.parser.ipfix.IpFixFlowBuilder;
import org.riptide.flows.parser.ipfix.IpfixRawFlow;
import org.riptide.flows.parser.netflow9.Netflow9FlowBuilder;
import org.riptide.flows.parser.netflow9.Netflow9RawFlow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;

@SpringBootTest
public class Netflow9TimeSwitchedParserTest {

    @Autowired
    @Qualifier("netflow9ValueConversionService")
    private ValueConversionService netflow9ConversionService;

    @Autowired
    @Qualifier("ipfixValueConversionService")
    private ValueConversionService ipfixConversionService;

    @Test
    void verifyNetflow9() {
        verifyIfIndex((in, out, ingress, egress) -> {
            final var raw = new Netflow9RawFlow();
            raw.unixSecs = Instant.ofEpochSecond(1000);
            raw.sysUpTime = Duration.ofSeconds(1000);
            raw.FIRST_SWITCHED = Duration.ofSeconds(2000);
            raw.LAST_SWITCHED = Duration.ofSeconds(3000);
            if (in != null) {
                raw.INPUT_SNMP = in;
            }
            if (out != null) {
                raw.OUTPUT_SNMP = out;
            }
            if (ingress != null) {
                raw.ingressPhysicalInterface = ingress;
            }
            if (egress != null) {
                raw.egressPhysicalInterface = egress;
            }
            return new Netflow9FlowBuilder(netflow9ConversionService).buildFlow(Instant.EPOCH, raw);
        });
    }

    @Test
    void verifyIPFix() {
        verifyIfIndex((in, out, ingress, egress) -> {
            final var raw = new IpfixRawFlow();
            raw.exportTime = Instant.ofEpochSecond(1000); // @unixSecs
            raw.systemInitTimeMilliseconds = Instant.ofEpochMilli(1000); // @sysUpTime

            raw.flowStartSeconds = Instant.ofEpochSecond(2000);
            raw.flowEndSeconds = Instant.ofEpochSecond(3000);
            if (in != null) {
                raw.ingressInterface = in;
            }
            if (out != null) {
                raw.egressInterface = out;
            }
            if (ingress != null) {
                raw.ingressPhysicalInterface = ingress;
            }
            if (egress != null) {
                raw.egressPhysicalInterface = egress;
            }
            return new IpFixFlowBuilder(ipfixConversionService).buildFlow(Instant.EPOCH, raw);
        });
    }

    private interface FlowMessageFactory {
        Flow create(Integer in, Integer out, Integer ingress, Integer egress);
    }

    private static void verifyIfIndex(final FlowMessageFactory flowMessageFactory) {
        Flow m;

        m = flowMessageFactory.create(1, 2, null, null);
        Assertions.assertThat(m.getInputSnmp()).isEqualTo(1);
        Assertions.assertThat(m.getOutputSnmp()).isEqualTo(2);

        m = flowMessageFactory.create(1, 2, 3, 4);
        Assertions.assertThat(m.getInputSnmp()).isEqualTo(3);
        Assertions.assertThat(m.getOutputSnmp()).isEqualTo(4);

        m = flowMessageFactory.create(null, 2, 3, 4);
        Assertions.assertThat(m.getInputSnmp()).isEqualTo(3);
        Assertions.assertThat(m.getOutputSnmp()).isEqualTo(4);

        m = flowMessageFactory.create(1, null, 3, 4);
        Assertions.assertThat(m.getInputSnmp()).isEqualTo(3);
        Assertions.assertThat(m.getOutputSnmp()).isEqualTo(4);

        m = flowMessageFactory.create(null, null, 3, 4);
        Assertions.assertThat(m.getInputSnmp()).isEqualTo(3);
        Assertions.assertThat(m.getOutputSnmp()).isEqualTo(4);
    }

}
