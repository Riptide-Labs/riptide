package org.riptide.flows.parser;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.ie.values.UnsignedValue;
import org.riptide.flows.parser.ipfix.IpFixFlowBuilder;
import org.riptide.flows.parser.netflow9.Netflow9FlowBuilder;

import java.time.Instant;
import java.util.Optional;


public class NMS14130_Test {

    @Test
    void verifyNetflow9() {
        verifyIfIndex((in, out, ingress, egress) -> {
            final RecordEnrichment enrichment = (address -> Optional.empty());
            final var record = new RecordBuilder();
            record.add(new UnsignedValue("@unixSecs", 1000));
            record.add(new UnsignedValue("@sysUpTime", 1000));

            record.add(new UnsignedValue("FIRST_SWITCHED", 2000));
            record.add(new UnsignedValue("LAST_SWITCHED", 3000));
            if (in != null) {
                record.add(new UnsignedValue("INPUT_SNMP", in));
            }
            if (out != null) {
                record.add(new UnsignedValue("OUTPUT_SNMP", out));
            }
            if (ingress != null) {
                record.add(new UnsignedValue("ingressPhysicalInterface", ingress));
            }
            if (egress != null) {
                record.add(new UnsignedValue("egressPhysicalInterface", egress));
            }
            return new Netflow9FlowBuilder().buildFlow(Instant.EPOCH, record.values(), enrichment);
        });
    }

    @Test
    void verifyIPFix() {
        verifyIfIndex((in, out, ingress, egress) -> {
            final RecordEnrichment enrichment = (address -> Optional.empty());
            final var record = new RecordBuilder();
            record.add(new UnsignedValue("@unixSecs", 1000));
            record.add(new UnsignedValue("@sysUpTime", 1000));

            record.add(new UnsignedValue("flowStartSeconds", 2000));
            record.add(new UnsignedValue("flowEndSeconds", 3000));
            if (in != null) {
                record.add(new UnsignedValue("ingressInterface", in));
            }
            if (out != null) {
                record.add(new UnsignedValue("egressInterface", out));
            }
            if (ingress != null) {
                record.add(new UnsignedValue("ingressPhysicalInterface", ingress));
            }
            if (egress != null) {
                record.add(new UnsignedValue("egressPhysicalInterface", egress));
            }
            return new IpFixFlowBuilder().buildFlow(Instant.EPOCH, record.values(), enrichment);
        });
    }

    private interface FlowMessageFactory {
        Flow create(final Integer in, final Integer out, final Integer ingress, final Integer egress);
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
