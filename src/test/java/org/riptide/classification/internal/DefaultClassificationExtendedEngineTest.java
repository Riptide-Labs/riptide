package org.riptide.classification.internal;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.DefaultRule;
import org.riptide.classification.IpAddr;
import org.riptide.classification.ProtocolType;
import org.riptide.classification.internal.value.IpRange;
import org.riptide.utils.Tuple;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

public class DefaultClassificationExtendedEngineTest {

    private DefaultClassificationEngine engine;

    @BeforeEach
    public void init() throws InterruptedException {
        // Define Rule set
        this.engine = new DefaultClassificationEngine(() -> List.of(
                DefaultRule.builder().withName("SSH").withDstPort("22").withPosition(1).build(),
                DefaultRule.builder().withName("HTTP_CUSTOM").withDstAddress("192.168.0.1").withDstPort("80").withPosition(2).build(),
                DefaultRule.builder().withName("HTTP").withDstPort("80").withPosition(3).build(),
                DefaultRule.builder().withName("DUMMY").withDstAddress("192.168.1.0-192.168.1.255,10.10.5.3,192.168.0.0/24").withDstPort("8000-9000,80,8080").withPosition(4).build(),
                DefaultRule.builder().withName("RANGE-TEST").withDstPort("7000-8000").withPosition(5).build(),
                DefaultRule.builder().withName("OpenNMS").withDstPort("8980").withPosition(6).build(),
                DefaultRule.builder().withName("OpenNMS Monitor").withDstPort("1077").withSrcPort("5347").withSrcAddress("10.0.0.5").withPosition(7).build()
        ));
    }

    @TestFactory
    // Verify concrete mappings
    Stream<DynamicTest> verifyBasicExtendedRules() throws InterruptedException {
        return Stream.of(
                Tuple.of("SSH", ClassificationRequest.builder()
                        .withLocation("Default")
                        .withSrcPort(0)
                        .withDstPort(22)
                        .withDstAddress("127.0.0.1")
                        .withProtocol(ProtocolType.TCP).build()),
                Tuple.of("HTTP_CUSTOM", ClassificationRequest.builder()
                        .withLocation("Default")
                        .withSrcPort(0)
                        .withDstPort(80)
                        .withDstAddress("192.168.0.1")
                        .withProtocol(ProtocolType.TCP).build()),
                Tuple.of("HTTP", ClassificationRequest.builder()
                        .withLocation("Default")
                        .withSrcPort(0)
                        .withDstPort(80)
                        .withDstAddress("192.168.0.2")
                        .withProtocol(ProtocolType.TCP).build()),
                Tuple.of(null, ClassificationRequest.builder()
                        .withLocation("Default")
                        .withSrcPort(0)
                        .withDstPort(5000)
                        .withDstAddress("localhost")
                        .withProtocol(ProtocolType.UDP).build()),
                Tuple.of(null, ClassificationRequest.builder()
                        .withLocation("Default")
                        .withSrcPort(0)
                        .withDstPort(5000)
                        .withDstAddress("localhost")
                        .withProtocol(ProtocolType.TCP).build()),
                Tuple.of("OpenNMS", ClassificationRequest.builder()
                        .withLocation("Default")
                        .withSrcPort(0)
                        .withDstPort(8980)
                        .withDstAddress("127.0.0.1")
                        .withProtocol(ProtocolType.TCP).build()),
                Tuple.of("OpenNMS", ClassificationRequest.builder()
                        .withLocation("Default")
                        .withSrcPort(0)
                        .withDstPort(8980)
                        .withDstAddress("127.0.0.1")
                        .withProtocol(ProtocolType.UDP).build()),
                Tuple.of("OpenNMS Monitor", ClassificationRequest.builder()
                        .withLocation("Default")
                        .withSrcAddress("10.0.0.5")
                        .withSrcPort(5347)
                        .withDstPort(1077)
                        .withProtocol(ProtocolType.TCP).build()),
                Tuple.of("HTTP", ClassificationRequest.builder()
                        .withLocation("Default")
                        .withSrcAddress(IpAddr.of("10.0.0.5"))
                        .withSrcPort(5347)
                        .withDstPort(80)
                        .withDstAddress("192.168.0.2")
                        .withProtocol(ProtocolType.TCP).build()),
                Tuple.of("DUMMY", ClassificationRequest.builder()
                        .withLocation("Default")
                        .withSrcAddress("127.0.0.1")
                        .withDstAddress("10.10.5.3")
                        .withSrcPort(5213)
                        .withDstPort(8080)
                        .withProtocol(ProtocolType.TCP).build())
        ).map(tuple -> {
            final var expectedResult = tuple.first();
            final var classificationRequest = tuple.second();
            return DynamicTest.dynamicTest("Verify request %s results in %s".formatted(classificationRequest, expectedResult), () -> {
                final var result = engine.classify(classificationRequest);
                Assertions.assertThat(result).isEqualTo(expectedResult);
            });
        });
    }

    @Test
    void verifyIpRange() {
        // Verify IP Range
        var ipAddresses = IpRange.of("192.168.1.0", "192.168.1.255");
        for (var ipAddress : ipAddresses) {
            final var classificationRequest = ClassificationRequest.builder()
                    .withLocation("Default")
                    .withSrcPort(0)
                    .withDstPort(8080)
                    .withDstAddress(ipAddress)
                    .withProtocol(ProtocolType.TCP).build();
            assertThat(engine.classify(classificationRequest)).isEqualTo("DUMMY");

            // Populate src address and port. Result must be the same
            final var newRequest = ClassificationRequest.from(classificationRequest)
                    .withSrcAddress("10.0.0.1")
                    .withSrcPort(5123).build();
            assertThat(engine.classify(newRequest)).isEqualTo("DUMMY");
        }
    }

    @Test
    void verifyCidrExpression() {
        // Verify CIDR expression
        for (var ipAddress : IpRange.of("192.168.0.0", "192.168.0.255")) {
            final var classificationRequest = ClassificationRequest.builder()
                    .withLocation("Default")
                    .withSrcPort(0)
                    .withSrcAddress((IpAddr) null)
                    .withDstPort(8080)
                    .withDstAddress(ipAddress)
                    .withProtocol(ProtocolType.TCP).build();
            assertThat(engine.classify(classificationRequest)).isEqualTo("DUMMY");
        }
    }

    @Test
    void verifyPortRange() {
        // Verify Port Range
        assertThat(IntStream.range(7000, 8000))
                .allSatisfy(i -> {
                    final var request = ClassificationRequest.builder()
                            .withLocation("Default")
                            .withSrcPort(0)
                            .withDstPort(i)
                            .withDstAddress("192.168.0.2")
                            .withProtocol(ProtocolType.TCP)
                            .build();
                    assertThat(engine.classify(request)).isEqualTo("RANGE-TEST");
                });
    }

    @Test
    void verifyPortRangeWithFieldsPopuplated() {
        // Verify Port Range with Src fields populated. Result must be the same as verifyPortRange
        IntStream.range(7000, 8000).forEach(src -> {
            IntStream.range(7000, 8000).forEach(dst -> {
                final ClassificationRequest classificationRequest = ClassificationRequest.builder()
                        .withLocation("Default")
                        .withProtocol(ProtocolType.TCP)
                        .withSrcAddress("10.0.0.1").withSrcPort(src)
                        .withDstAddress("192.168.0.2").withDstPort(dst).build();
                assertThat(engine.classify(classificationRequest)).isEqualTo("RANGE-TEST");
            });
        });
    }
}
