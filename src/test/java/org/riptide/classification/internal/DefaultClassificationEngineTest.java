package org.riptide.classification.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.DefaultRule;
import org.riptide.classification.ProtocolType;
import org.riptide.classification.Rule;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

public class DefaultClassificationEngineTest {

    @Test
    void verifyRuleEngineBasic() throws InterruptedException {
        final var engine = new DefaultClassificationEngine(() -> List.of(
                DefaultRule.builder().withName("rule1").withPosition(1).withSrcPort(80).build(),
                DefaultRule.builder().withName("rule2").withPosition(2).withDstPort(443).build(),
                DefaultRule.builder().withName("rule3").withPosition(3).withSrcPort(8888).withDstPort(9999).build(),
                DefaultRule.builder().withName("rule4").withPosition(4).withSrcPort(8888).withDstPort(80).build(),
                DefaultRule.builder().withName("rule5").withPosition(5).build()
        ));
        assertThat(engine.classify(ClassificationRequest.builder().withSrcPort(9999).withDstPort(443).build())).isEqualTo("rule2");
        assertThat(engine.classify(ClassificationRequest.builder().withSrcPort(8888).withDstPort(9999).build())).isEqualTo("rule3");
        assertThat(engine.classify(ClassificationRequest.builder().withSrcPort(8888).withDstPort(80).build())).isEqualTo("rule4");
    }

    @Test
    void verifyRuleEngineWithOmnidirectionals() throws InterruptedException {
        final var engine = new DefaultClassificationEngine(() -> List.of(
                DefaultRule.builder().withName("rule1").withSrcPort(80).withOmnidirectional(true).build(),
                DefaultRule.builder().withName("rule2").withDstPort(443).withOmnidirectional(true).build(),
                DefaultRule.builder().withName("rule3").withSrcPort(8080).withDstPort(8443).withOmnidirectional(true).build(),
                DefaultRule.builder().withName("rule4").withSrcPort(1337).build(),
                DefaultRule.builder().withName("rule5").withDstPort(7331).build()
        ));
        assertThat(engine.classify(ClassificationRequest.builder().withSrcPort(9999).withDstPort(80).build())).isEqualTo("rule1");
        assertThat(engine.classify(ClassificationRequest.builder().withSrcPort(80).withDstPort(9999).build())).isEqualTo("rule1");

        assertThat(engine.classify(ClassificationRequest.builder().withSrcPort(443).withDstPort(9999).build())).isEqualTo("rule2");
        assertThat(engine.classify(ClassificationRequest.builder().withSrcPort(9999).withDstPort(443).build())).isEqualTo("rule2");

        assertThat(engine.classify(ClassificationRequest.builder().withSrcPort(8080).withDstPort(8443).build())).isEqualTo("rule3");
        assertThat(engine.classify(ClassificationRequest.builder().withSrcPort(8443).withDstPort(8080).build())).isEqualTo("rule3");

        assertThat(engine.classify(ClassificationRequest.builder().withSrcPort(1337).withDstPort(9999).build())).isEqualTo("rule4");
        assertThat(engine.classify(ClassificationRequest.builder().withSrcPort(9999).withDstPort(1337).build())).isNull();

        assertThat(engine.classify(ClassificationRequest.builder().withSrcPort(9999).withDstPort(7331).build())).isEqualTo("rule5");
        assertThat(engine.classify(ClassificationRequest.builder().withSrcPort(7331).withDstPort(9999).build())).isNull();
    }

    @Test
    void verifyAddressRuleWins() throws InterruptedException {
        final var engine = new DefaultClassificationEngine(() -> List.of(
                DefaultRule.builder().withName("HTTP").withDstPort(80).withPosition(1).build(),
                DefaultRule.builder().withName("XXX2").withSrcAddress("192.168.2.1").withSrcPort(4789).build(),
                DefaultRule.builder().withName("XXX").withDstAddress("192.168.2.1").build()
        ));
        final var classificationRequest = ClassificationRequest.builder()
                .withLocation("Default")
                .withSrcPort(0)
                .withDstAddress("192.168.2.1")
                .withDstPort(80)
                .withProtocol(ProtocolType.TCP)
                .build();
        assertThat(engine.classify(classificationRequest)).isEqualTo("XXX");
        assertThat(engine.classify(ClassificationRequest.builder()
                .withLocation("Default")
                .withProtocol(ProtocolType.TCP)
                .withSrcAddress("192.168.2.1").withSrcPort(4789)
                .withDstAddress("52.31.45.219").withDstPort(80)
                .build())).isEqualTo("XXX2");
    }

    @Test
    void verifyAllPortsToEnsureEngineIsProperlyInitialized() throws InterruptedException {
        final var classificationEngine = new DefaultClassificationEngine(List::of);
        assertThat(IntStream.range(0, 65535))
                .allSatisfy((i) -> assertThatCode(() -> {
                    final var request = ClassificationRequest.builder()
                            .withLocation("Default")
                            .withSrcPort(0)
                            .withDstPort(i)
                            .withDstAddress("127.0.0.1")
                            .withProtocol(ProtocolType.TCP)
                            .build();
                    classificationEngine.classify(request);
                }).doesNotThrowAnyException());
    }

    // See NMS-12429
    @Test
    void verifyDoesNotRunOutOfMemory() throws InterruptedException {
        final List<Rule> rules = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final var rule = DefaultRule.builder().withName("rule1").withPosition(i + 1).withProtocol("UDP").withDstAddress("192.168.0." + i).build();
            rules.add(rule);
        }
        final var engine = new DefaultClassificationEngine(() -> rules);
        final var request = ClassificationRequest.builder()
                .withLocation("localhost")
                .withSrcPort(1234)
                .withSrcAddress("127.0.0.1")
                .withDstPort(80)
                .withDstAddress("192.168.0.1")
                .withProtocol(ProtocolType.UDP)
                .build();
        engine.classify(request);
    }

    @Test
    @Timeout(5)
    void verifyInitializesQuickly() throws InterruptedException {
        new DefaultClassificationEngine(() -> List.of(DefaultRule.builder().withName("Test").withSrcPort("0-10000").build()));
    }
}
