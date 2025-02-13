package org.riptide.flows.parser;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.stream.Stream;

@SpringBootTest
public class SnmpInputOutputParserTest {

    @Autowired
    @Qualifier("netflow9ValueConversionService")
    private ValueConversionService netflow9ConversionService;

    @Autowired
    @Qualifier("ipfixValueConversionService")
    private ValueConversionService ipfixConversionService;

    @ParameterizedTest
    @MethodSource("provideTestInputs")
    void verifyNetflow9(Integer in, Integer out, Integer ingress, Integer egress, Integer expectedInputSnmp, Integer expectedOutputSnmp) {
        final var flow = createNetflow9TestFlow(in, out, ingress, egress);
        Assertions.assertThat(flow.getInputSnmp()).isEqualTo(expectedInputSnmp);
        Assertions.assertThat(flow.getOutputSnmp()).isEqualTo(expectedOutputSnmp);
    }

    @ParameterizedTest
    @MethodSource("provideTestInputs")
    void verifyIpfix(Integer in, Integer out, Integer ingress, Integer egress, Integer expectedInputSnmp, Integer expectedOutputSnmp) {
        final var flow = createIpfixTestFlow(in, out, ingress, egress);
        Assertions.assertThat(flow.getInputSnmp()).isEqualTo(expectedInputSnmp);
        Assertions.assertThat(flow.getOutputSnmp()).isEqualTo(expectedOutputSnmp);
    }

    private Flow createNetflow9TestFlow(Integer in, Integer out, Integer ingress, Integer egress) {
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
    }

    private Flow createIpfixTestFlow(Integer in, Integer out, Integer ingress, Integer egress) {
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
    }

    private static Stream<Arguments> provideTestInputs() {
        return Stream.of(
                // (in, out, ingress, egress, expectedSnmpInput, expectedSnmpOutput)
                Arguments.of(1, 2, null, null, 1, 2),
                Arguments.of(1, 2, 3, 4, 3, 4),
                Arguments.of(null, 2, 3, 4, 3, 4),
                Arguments.of(1, null, 3, 4, 3, 4),
                Arguments.of(null, null, 3, 4, 3, 4)
        );
    }

}
