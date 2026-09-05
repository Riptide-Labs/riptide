/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.riptide.classification.ClassificationEngine.ClassificationRulesReloadedListener;
import org.riptide.classification.ClassificationEngine.Publication;
import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.ClassificationRuleProvider;
import org.riptide.classification.DefaultRule;
import org.riptide.classification.ProtocolType;
import org.riptide.classification.Rule;
import org.riptide.classification.internal.csv.CsvImporter;
import org.riptide.testsupport.LogCapture;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class DefaultClassificationEngineTest {

    /** One valid rule, so a publish has something nameable in it and no ERROR of its own. */
    private static final ClassificationRuleProvider ONE_RULE =
            () -> List.of(DefaultRule.builder().withName("rule1").withPosition(1).withDstPort(80).build());

    /** A ruleset the engine accepts in part: {@code broken} cannot be preprocessed and is rejected. */
    private static final ClassificationRuleProvider ONE_GOOD_ONE_BROKEN = () -> List.of(
            DefaultRule.builder().withName("good").withPosition(1).withDstPort(80).build(),
            DefaultRule.builder().withName("broken").withPosition(2).withDstPort("not-a-port").build());

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void captureLogs() {
        this.logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DefaultClassificationEngine.class);
        this.appender = LogCapture.startedAppender();
        this.logger.addAppender(this.appender);
    }

    @AfterEach
    void detachAppender() {
        this.logger.detachAppender(this.appender);
        this.appender.stop();
    }

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
                .withZone("Default")
                .withSrcPort(0)
                .withDstAddress("192.168.2.1")
                .withDstPort(80)
                .withProtocol(ProtocolType.TCP)
                .build();
        assertThat(engine.classify(classificationRequest)).isEqualTo("XXX");
        assertThat(engine.classify(ClassificationRequest.builder()
                .withZone("Default")
                .withProtocol(ProtocolType.TCP)
                .withSrcAddress("192.168.2.1").withSrcPort(4789)
                .withDstAddress("52.31.45.219").withDstPort(80)
                .build())).isEqualTo("XXX2");
    }

    /**
     * A rule that names an aspect must answer "no" for a flow that lacks it, rather than throw. See
     * #750: {@code Protocols.getProtocol(Integer)} answers null for every protocol number riptide does
     * not map, so "no protocol" arrives off the wire; {@code Threshold.Protocol.compare} already routes
     * that as {@code Order.NA} and only the leaf matchers disagreed, by dereferencing it.
     *
     * <p>Each engine here holds a single rule <em>on purpose</em>. {@code Tree.of} makes a leaf as soon
     * as one rule is left, so the rule's own condition is never turned into a threshold and the leaf
     * matcher is what decides. Add rules and the tree may instead route the request down an "na" child
     * that the rule is not in, in which case the matcher never runs and the row stops testing the leaf.
     *
     * <p>That is a fact about which tree a ruleset happens to build, not a property of the tree, and it
     * cuts both ways: because a leaf can sit on a path carrying no threshold for the aspect it matches,
     * no ruleset is structurally safe from any of these three. Against the bundled ruleset the protocol
     * one is measured live ({@code ClassificationEnricherTest}) and a probe with no ports was measured
     * to classify without reaching a port matcher. The port and address cases are latent there on that
     * evidence, which is a measurement of one ruleset and not a guarantee about the next one.
     */
    @Test
    void aRuleNamingAProtocolDoesNotMatchARequestWithoutOne() throws InterruptedException {
        final var engine = new DefaultClassificationEngine(() -> List.of(
                DefaultRule.builder().withName("tcp").withPosition(1).withProtocol("TCP").build()));

        assertThat(engine.classify(ClassificationRequest.builder()
                .withSrcPort(54321).withDstPort(80).build())).isNull();

        // the control: a request that carries its protocol still reaches the rule that names it
        assertThat(engine.classify(ClassificationRequest.builder()
                .withProtocol(ProtocolType.TCP).withSrcPort(54321).withDstPort(80).build())).isEqualTo("tcp");
        assertThat(engine.classify(ClassificationRequest.builder()
                .withProtocol(ProtocolType.UDP).withSrcPort(54321).withDstPort(80).build())).isNull();
    }

    /**
     * #759: a rule carrying an {@code exporterFilter} names an aspect nothing evaluates. Before this,
     * {@code PreprocessedRule.of} silently dropped the field and {@code Classifier.of} built no matcher
     * for it, so the rule was applied to <em>every</em> exporter — the third wrong answer to the question
     * #757 settled: not a crash, not "no match", but the condition dropped entirely.
     *
     * <p>It is rejected as one rule, not as a failed load. That posture is what
     * {@code docs/docs/deploy/operations.md} promises an operator for a rule the engine cannot use, and
     * it is the reason the check lives here rather than in {@code CsvImporter}: every provider crosses
     * this seam, including {@link ClassificationRuleProvider#forList}, which no importer guard reaches.</p>
     */
    @Test
    void aRuleCarryingAnExporterFilterIsRejectedWhileTheRestKeepsServing() throws InterruptedException {
        final var engine = new DefaultClassificationEngine(() -> List.of(
                DefaultRule.builder().withName("http").withPosition(1).withDstPort(80).build(),
                DefaultRule.builder().withName("scoped").withPosition(2).withDstPort(443)
                        .withExporterFilter("10.0.0.0/8").build()));

        assertThat(engine.getInvalidRules())
                .as("the rule the engine cannot use is rejected, and only that one")
                .extracting(Rule::getName)
                .containsExactly("scoped");

        // the rest of the ruleset serves — this is the half a whole-ruleset refusal would destroy
        assertThat(engine.classify(ClassificationRequest.builder().withDstPort(80).build())).isEqualTo("http");

        // and the rejected rule classifies nothing, rather than claiming every exporter's port 443
        assertThat(engine.classify(ClassificationRequest.builder().withDstPort(443).build())).isNull();

        // the operator's signal: the ERROR names the rule and says why, so "not implemented" is not
        // left to live only in a stack trace. Asserted on the rendered message, not the format string.
        assertThat(this.appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .filteredOn(m -> m.contains("scoped"))
                .as("the rejection must name the rule and the field, not just report a bad rule")
                .anySatisfy(m -> assertThat(m).contains("not valid"));
        assertThat(this.appender.list)
                .filteredOn(e -> e.getLevel() == Level.ERROR)
                .anySatisfy(e -> assertThat(e.getThrowableProxy().getMessage())
                        .contains("exporterFilter")
                        .contains("not implemented")
                        .contains("every exporter"));
    }

    /** The same property for ports, where a null {@code Integer} used to auto-unbox in the leaf. */
    @Test
    void aRuleNamingAPortDoesNotMatchARequestWithoutOne() throws InterruptedException {
        final var engine = new DefaultClassificationEngine(() -> List.of(
                DefaultRule.builder().withName("http").withPosition(1).withDstPort(80).build()));

        assertThat(engine.classify(ClassificationRequest.builder()
                .withProtocol(ProtocolType.TCP).withSrcPort(54321).build())).isNull();

        assertThat(engine.classify(ClassificationRequest.builder()
                .withProtocol(ProtocolType.TCP).withSrcPort(54321).withDstPort(80).build())).isEqualTo("http");
        assertThat(engine.classify(ClassificationRequest.builder()
                .withProtocol(ProtocolType.TCP).withSrcPort(54321).withDstPort(443).build())).isNull();
    }

    /**
     * The conjunction, which is where "no match" and "match anything" give different operator-visible
     * answers. The rows above each leave {@code Classifier.classify} one matcher to run, so a leaf that
     * answered {@code true} for an absent aspect would show up as a name where there should be none.
     * Here the rule names two aspects and the request carries one of them, so the surviving matcher
     * agrees and only the absent one can decide. A rule for TCP port 80 must not claim a flow on port 80
     * whose protocol is unmapped.
     */
    @Test
    void aRuleNamingTwoAspectsDoesNotMatchARequestMissingOneOfThem() throws InterruptedException {
        final var engine = new DefaultClassificationEngine(() -> List.of(
                DefaultRule.builder().withName("tcp-http").withPosition(1)
                        .withProtocol("TCP").withDstPort(80).build()));

        assertThat(engine.classify(ClassificationRequest.builder()
                .withSrcPort(54321).withDstPort(80).build()))
                .as("the port half matches; the absent protocol must still deny the rule")
                .isNull();

        assertThat(engine.classify(ClassificationRequest.builder()
                .withProtocol(ProtocolType.TCP).withSrcPort(54321).build()))
                .as("the protocol half matches; the absent port must still deny the rule")
                .isNull();

        // the control: both aspects present and agreeing still classifies
        assertThat(engine.classify(ClassificationRequest.builder()
                .withProtocol(ProtocolType.TCP).withSrcPort(54321).withDstPort(80).build()))
                .isEqualTo("tcp-http");
    }

    /** The same property for addresses. */
    @Test
    void aRuleNamingAnAddressDoesNotMatchARequestWithoutOne() throws InterruptedException {
        final var engine = new DefaultClassificationEngine(() -> List.of(
                DefaultRule.builder().withName("local").withPosition(1).withDstAddress("192.168.2.1").build()));

        assertThat(engine.classify(ClassificationRequest.builder()
                .withProtocol(ProtocolType.TCP).withSrcAddress("192.168.2.1").withDstPort(80).build())).isNull();

        assertThat(engine.classify(ClassificationRequest.builder()
                .withProtocol(ProtocolType.TCP).withDstAddress("192.168.2.1").withDstPort(80).build()))
                .isEqualTo("local");
        assertThat(engine.classify(ClassificationRequest.builder()
                .withProtocol(ProtocolType.TCP).withDstAddress("192.168.2.2").withDstPort(80).build())).isNull();
    }

    @Test
    void verifyAllPortsToEnsureEngineIsProperlyInitialized() throws InterruptedException {
        final var classificationEngine = new DefaultClassificationEngine(List::of);
        assertThat(IntStream.range(0, 65535))
                .allSatisfy((i) -> assertThatCode(() -> {
                    final var request = ClassificationRequest.builder()
                            .withZone("Default")
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
                .withZone("localhost")
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

    /**
     * The atomic-publish property {@code AsyncReloadingClassificationEngine} rests on, asserted against the engine
     * that actually provides it rather than against a test double that has it by construction. If a rebuild ever
     * published incrementally — or cleared what is serving before starting — a failed reload would silently classify
     * every flow against an empty tree while the wrapper's metrics reported exactly the documented behaviour.
     */
    @Test
    @Timeout(5)
    void verifyFailedReloadLeavesThePreviousRulesetServing() throws InterruptedException {
        final var loads = new AtomicInteger();
        final var engine = new DefaultClassificationEngine(() -> {
            if (loads.getAndIncrement() > 0) {
                throw new IllegalStateException("rules file is unreadable");
            }
            return List.of(
                    DefaultRule.builder().withName("good").withPosition(1).withDstPort(80).build(),
                    DefaultRule.builder().withName("broken").withPosition(2).withDstPort("not-a-port").build());
        });
        final var request = ClassificationRequest.builder().withSrcPort(1234).withDstPort(80).build();
        assertThat(engine.classify(request)).isEqualTo("good");
        assertThat(engine.getInvalidRules()).extracting(Rule::getName).containsExactly("broken");

        assertThatThrownBy(engine::reload).isInstanceOf(IllegalStateException.class);

        assertThat(engine.classify(request)).as("the previous tree keeps classifying").isEqualTo("good");
        assertThat(engine.getInvalidRules()).as("and so does the invalid-rule list it was published with")
                .extracting(Rule::getName).containsExactly("broken");
    }

    /**
     * This engine is the single owner of the listener registrations for the whole stack, and its list used to be a
     * plain {@code ArrayList} mutated with no synchronisation while a reload iterated it. A registration landing
     * mid-fire therefore threw {@code ConcurrentModificationException} out of {@code reload()} — and out of the
     * <em>initial</em> load that is not a lost notification but a permanent outage, because the wrapper above
     * never sets {@code everLoaded} and every caller then blocks or throws.
     */
    @Test
    @Timeout(10)
    void aListenerRegisteringWhileAReloadFiresDoesNotBreakTheReload() throws InterruptedException {
        final var engine = new DefaultClassificationEngine(ONE_RULE, false);
        final var lateSaw = new AtomicInteger();
        final ClassificationRulesReloadedListener late = rules -> lateSaw.incrementAndGet();

        // once, not on every fire: registering again from the second reload would add a second registration of
        // the same listener and the count below would stop meaning "delivered once"
        final var alreadyRegistered = new AtomicBoolean();
        engine.addClassificationRulesReloadedListener(rules -> {
            if (alreadyRegistered.compareAndSet(false, true)) {
                joined(() -> engine.addClassificationRulesReloadedListener(late));
            }
        });

        assertThatCode(engine::reload).doesNotThrowAnyException();

        assertThat(lateSaw)
                .as("no replay: this publish had already captured its listeners when the registration landed")
                .hasValue(0);

        engine.reload();
        assertThat(lateSaw).as("and it is fired by the next publish like any other").hasValue(1);
    }

    @Test
    @Timeout(10)
    void aListenerRemovedWhileAReloadFiresDoesNotBreakTheReload() throws InterruptedException {
        final var engine = new DefaultClassificationEngine(ONE_RULE, false);
        final var removedSaw = new AtomicInteger();
        final var survivorSaw = new AtomicInteger();
        final ClassificationRulesReloadedListener removed = rules -> removedSaw.incrementAndGet();

        // three registrations, not two: the ArrayList iterator this replaced only comodification-checks in
        // next(), so removing one of two left the cursor at the shrunken size and the loop exited quietly
        // instead of throwing. Two listeners would have passed against the defect this pins
        engine.addClassificationRulesReloadedListener(rules -> joined(
                () -> engine.removeClassificationRulesReloadedListener(removed)));
        engine.addClassificationRulesReloadedListener(rules -> survivorSaw.incrementAndGet());
        engine.addClassificationRulesReloadedListener(removed);

        assertThatCode(engine::reload).doesNotThrowAnyException();

        assertThat(survivorSaw).as("the listeners queued behind the removal still got the publish").hasValue(1);
        assertThat(removedSaw)
                .as("the publish had already captured its listeners, so the removal does not cancel this delivery")
                .hasValue(1);

        engine.reload();
        assertThat(removedSaw).as("the removal takes effect from the next publish onwards").hasValue(1);
    }

    /**
     * A consumer's bug is the consumer's problem, not classification's. Unisolated, the first throw ended the walk
     * and escaped {@code reload()}; on the boot load that is the "no rules have ever been loaded" outage this
     * seam exists to report on, reached through the seam itself.
     */
    @Test
    @Timeout(10)
    void aListenerThatThrowsIsLoggedAndTheRemainingListenersStillGetThePublish() throws InterruptedException {
        final var engine = new DefaultClassificationEngine(ONE_RULE, false);
        final var before = new AtomicInteger();
        final var after = new AtomicInteger();
        engine.addClassificationRulesReloadedListener(rules -> before.incrementAndGet());
        engine.addClassificationRulesReloadedListener(rules -> {
            throw new IllegalStateException("listener is broken");
        });
        engine.addClassificationRulesReloadedListener(rules -> after.incrementAndGet());

        assertThatCode(engine::reload).doesNotThrowAnyException();

        assertThat(before).hasValue(1);
        assertThat(after).as("delivery continued past the throw rather than ending on it").hasValue(1);
        // the rules published, so the reload really did succeed rather than merely not throwing
        assertThat(engine.classify(ClassificationRequest.builder().withDstPort(80).build())).isEqualTo("rule1");

        // ONE_RULE is valid, so nothing else in this reload logs an ERROR; the cause is read off the event
        // rather than off a message that could merely be echoing its own input
        assertThat(errors()).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains("Classification rules reloaded listener");
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getMessage()).isEqualTo("listener is broken");
        });
    }

    /**
     * The other half of "throws anything". A consumer's realistic boot failure is not an exception at all: a
     * listener touching a class that is not on the path raises {@link NoClassDefFoundError}, which a
     * {@code catch (Exception)} lets straight through into {@code reload()}. Probed, that produced the exact
     * outage this change exists to remove — {@code classify()} permanently answering "no rules have ever been
     * loaded" while the engine already held a complete ruleset.
     * <p>
     * Deliberately a separate row from the {@code IllegalStateException} one above: the two differ only in the
     * width of one catch clause, and a single row covering both cannot say which width it proved.
     */
    @Test
    @Timeout(10)
    void aListenerThatThrowsAnErrorIsAlsoIsolated() throws InterruptedException {
        final var engine = new DefaultClassificationEngine(ONE_RULE, false);
        final var after = new AtomicInteger();
        engine.addClassificationRulesReloadedListener(rules -> {
            throw new NoClassDefFoundError("org/example/NotOnThePath");
        });
        engine.addClassificationRulesReloadedListener(rules -> after.incrementAndGet());

        assertThatCode(engine::reload).doesNotThrowAnyException();

        assertThat(after).as("delivery continued past the Error").hasValue(1);
        assertThat(engine.classify(ClassificationRequest.builder().withDstPort(80).build()))
                .as("and the rules are serving, which is the outage this prevents").isEqualTo("rule1");
        assertThat(errors()).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage()).contains("Classification rules reloaded listener");
            assertThat(event.getThrowableProxy()).isNotNull();
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(NoClassDefFoundError.class.getName());
        });
    }

    /**
     * The publish happens before the fire, so a callback asking what is published is answered with the ruleset it
     * was just handed rather than the previous one — or, on the boot load, rather than "nothing published yet".
     * <p>
     * Pinned here, where the claim is made, and not only through the reloader three classes away: the ordering is
     * two adjacent statements in {@code reload()} and swapping them is a one-line edit a reader of this class
     * would have no reason to think twice about.
     */
    @Test
    @Timeout(10)
    void aCallbackIsAnsweredWithThePublicationItWasJustHanded() throws InterruptedException {
        final var engine = new DefaultClassificationEngine(ONE_GOOD_ONE_BROKEN, false);
        final var seenFromCallback = new AtomicReference<Optional<Publication>>();
        engine.addClassificationRulesReloadedListener(rules -> seenFromCallback.set(engine.currentPublication()));

        engine.reload();

        assertThat(seenFromCallback.get())
                .as("the publish precedes the fire, so this is never 'nothing published yet'")
                .get()
                .satisfies(publication -> assertThat(publication.invalidRules())
                        .extracting(Rule::getName).containsExactly("broken"));
    }

    /**
     * A publication is a copy, not a view. {@code reload()} hands the record a live {@code ArrayList} it is still
     * holding, and {@code getInvalidRules()} used to return {@code Collections.unmodifiableList(...)} — so
     * dropping the record's defensive copies would quietly hand callers a mutable list and let a rule provider
     * reaching into what it returned change a ruleset that is already serving.
     */
    @Test
    @Timeout(10)
    void aPublicationIsACopyAndCannotBeChangedThroughTheListItCameFrom() throws InterruptedException {
        final List<Rule> handedOver = new ArrayList<>(ONE_GOOD_ONE_BROKEN.getRules());
        final var engine = new DefaultClassificationEngine(() -> handedOver, false);
        engine.reload();
        final var publication = engine.currentPublication().orElseThrow();

        handedOver.clear();

        assertThat(publication.rules()).as("the publication kept its own copy of the ruleset")
                .extracting(Rule::getName).containsExactly("good", "broken");
        final Rule intruder = DefaultRule.builder().withName("intruder").build();
        assertThatThrownBy(() -> publication.rules().add(intruder))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> engine.getInvalidRules().add(intruder))
                .as("the guarantee getInvalidRules() made before this change")
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * The accessor a listener uses to learn what it was just handed, and the only way a consumer registering after
     * the boot load can see that load at all — nothing is replayed to a late registrant.
     * <p>
     * Asserted on a rejected rule's name, deliberately. An empty invalid-rule list cannot distinguish a real read
     * from the field's default, and the "nothing published yet" arm has to be distinguishable from "published, and
     * every rule was accepted" — which is why this returns an {@code Optional} and not a list.
     */
    @Test
    @Timeout(10)
    void theCurrentPublicationTellsNothingPublishedApartFromAPublishedRuleset() throws InterruptedException {
        final var engine = new DefaultClassificationEngine(ONE_GOOD_ONE_BROKEN, false);

        assertThat(engine.currentPublication()).as("the constructor did not load, so nothing is published").isEmpty();

        engine.reload();

        assertThat(engine.currentPublication()).get().satisfies(publication -> {
            assertThat(publication.rules()).as("the ruleset it was handed, rejected rules included")
                    .extracting(Rule::getName).containsExactly("good", "broken");
            assertThat(publication.invalidRules()).as("and which of them classify nothing")
                    .extracting(Rule::getName).containsExactly("broken");
        });
    }

    /**
     * The reuse #707 is about, against the ruleset that makes it worth having. The bundled tree
     * costs about 1.0s to build since #746 — 1.2s before it — and tens of seconds under the coverage
     * agent, and a full suite built it twice before the cache landed.
     *
     * <p>Deliberately runs against the process-wide cache rather than a private one. A private
     * cache would force a build here, which would put a <em>third</em> bundled build into the
     * suite and break the very count this change is measured by. Identity is the assertion that
     * survives either way: whether this row builds the tree or is served one another class built,
     * a second engine over the same rules getting the same object is a build that did not happen.
     * The 5-minute bound is for the case where this row is the one that builds — the same
     * instrumented cost, and the same reasoning, as the bundled row in
     * {@link ClassificationRuleReloaderTest}.
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void aSecondEngineOverTheSameRulesServesTheSameTree() throws InterruptedException {
        final ClassificationRuleProvider bundled = () -> {
            try (var stream = DefaultClassificationEngineTest.class.getResourceAsStream("/classification-rules.csv")) {
                return new CsvImporter().parse(stream, true);
            } catch (final IOException e) {
                throw new UncheckedIOException("cannot read the bundled ruleset", e);
            }
        };
        assertThat(bundled.getRules()).as("the ruleset whose build cost this is about").hasSizeGreaterThan(6000);

        final var first = new DefaultClassificationEngine(bundled);
        final var second = new DefaultClassificationEngine(bundled);

        assertThat(second.getTree())
                .as("the same rules, so the same tree object and no second build")
                .isSameAs(first.getTree());

        final var sample = List.of(
                ClassificationRequest.builder().withProtocol(ProtocolType.UDP).withDstPort(123).build(),
                ClassificationRequest.builder().withProtocol(ProtocolType.TCP).withDstPort(80).build(),
                ClassificationRequest.builder().withProtocol(ProtocolType.TCP).withDstPort(22).build(),
                ClassificationRequest.builder().withProtocol(ProtocolType.TCP).withSrcPort(443).build());
        assertThat(sample).allSatisfy(request ->
                assertThat(second.classify(request)).as("%s", request).isEqualTo(first.classify(request)));
        assertThat(first.classify(sample.get(0))).as("and the sample is really classifying").isEqualTo("ntp");
    }

    /**
     * A build that threw published nothing, so it must have cached nothing either — otherwise the
     * next reload of those rules is served whatever a half-finished build left behind.
     */
    @Test
    @Timeout(10)
    void anInterruptedBuildIsNotCached() throws InterruptedException {
        final var cache = new DecisionTreeCache();
        final var engine = new DefaultClassificationEngine(ONE_RULE, false, cache);

        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(engine::reload).isInstanceOf(InterruptedException.class);
        } finally {
            // Tree.of clears the flag on its way out, so this is normally already false. It is not
            // if the assertion above failed first, and this fork runs every remaining test on this
            // same thread — an interrupt flag left set here would fail them somewhere else entirely.
            Thread.interrupted();
        }

        assertThat(cache.get(ONE_RULE.getRules()))
                .as("a build that did not finish leaves nothing behind").isEmpty();

        engine.reload();

        assertThat(cache.get(ONE_RULE.getRules())).as("and the next reload builds it").isPresent();
        assertThat(engine.classify(ClassificationRequest.builder().withDstPort(80).build())).isEqualTo("rule1");
    }

    /**
     * A hit skips {@code Tree.of}, and {@code Tree.of} holds the only interrupt check on this path.
     * Without one of its own the cache silently changes {@code reload()}'s contract: a reload on an
     * interrupted thread would publish, fan out to listeners and return normally where it used to
     * throw. That is not cosmetic — {@code AsyncReloadingClassificationEngine} treats
     * {@code InterruptedException} as "a shutdown, not a failure" and moves no counter, so a
     * shutdown-time reload that hit would instead be counted on
     * {@code classification.reload.successes}.
     */
    @Test
    @Timeout(10)
    void aReloadServedFromTheCacheStillHonoursAnInterrupt() throws InterruptedException {
        final var cache = new DecisionTreeCache();
        final var engine = new DefaultClassificationEngine(ONE_RULE, true, cache);
        assertThat(cache.get(ONE_RULE.getRules())).as("so the next reload is a hit").isPresent();

        final var publishes = new AtomicInteger();
        engine.addClassificationRulesReloadedListener(rules -> publishes.incrementAndGet());

        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(engine::reload)
                    .as("a hit must not quietly complete a reload the build path would have refused")
                    .isInstanceOf(InterruptedException.class);
        } finally {
            Thread.interrupted();
        }

        assertThat(publishes)
                .as("and it must not publish on the way out, or the reload counts as a success")
                .hasValue(0);
    }

    /**
     * The strings this change is measured by are a contract, and nothing else enforces them. The
     * headline number is {@code grep -c "rules    : 6248"} over a suite log: a build must emit
     * exactly one such line and a hit none, or the count silently starts answering a different
     * question — in either direction, and while staying green.
     */
    @Test
    @Timeout(10)
    void aBuildLogsTheCountedLineAndAHitDoesNot() throws InterruptedException {
        final var cache = new DecisionTreeCache();

        new DefaultClassificationEngine(ONE_RULE, true, cache);

        assertThat(countedBuildLines()).as("a build is counted exactly once").hasSize(1);
        this.appender.list.clear();

        new DefaultClassificationEngine(ONE_RULE, true, cache);

        assertThat(countedBuildLines()).as("a hit must not look like a build to grep -c").isEmpty();
        assertThat(logged()).as("but it must still be visible as a reuse")
                .anySatisfy(message -> assertThat(message)
                        .contains("reused the cached flow classification decision tree"));
    }

    private List<String> logged() {
        return this.appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** What {@code grep -c "rules    : "} would count in a suite log — four spaces, as in the grep. */
    private List<String> countedBuildLines() {
        return logged().stream()
                .flatMap(String::lines)
                .filter(line -> line.contains("rules    : "))
                .toList();
    }

    /**
     * Two engines started on the same unseen ruleset at once. Both may build it — no lock is held
     * across a build, deliberately — and the property that matters here is that each caller ends up
     * with a usable tree.
     *
     * <p>This row does not test the cache's thread safety, and should not be read as doing so: both
     * threads miss, and a miss neither relinks the access order nor evicts, so nothing contends.
     * The contending pair is
     * {@link DecisionTreeCacheTest#concurrentHitsAndEvictingPutsDoNotCorruptTheCache}.
     */
    @Test
    @Timeout(30)
    void twoThreadsRacingOnTheSameNewRulesetBothGetACorrectTree() throws Exception {
        final var cache = new DecisionTreeCache();
        final var ready = new CountDownLatch(2);
        final var go = new CountDownLatch(1);
        final var pool = Executors.newFixedThreadPool(2);
        try {
            final Callable<DefaultClassificationEngine> build = () -> {
                ready.countDown();
                go.await();
                return new DefaultClassificationEngine(ONE_RULE, true, cache);
            };
            final var first = pool.submit(build);
            final var second = pool.submit(build);
            ready.await();
            go.countDown();

            final var request = ClassificationRequest.builder().withDstPort(80).build();
            assertThat(first.get().classify(request)).isEqualTo("rule1");
            assertThat(second.get().classify(request)).isEqualTo("rule1");
        } finally {
            pool.shutdownNow();
        }
    }

    private List<ILoggingEvent> errors() {
        return this.appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
    }

    /**
     * Runs {@code action} on another thread and waits for it, so "on one thread while the reload thread iterates"
     * is a fact of the test rather than a timing hope. Safe to call from inside a callback: the engine holds no
     * lock while firing.
     */
    private static void joined(final Runnable action) {
        final var thread = new Thread(action, "listener-registrar");
        thread.start();
        try {
            thread.join();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the concurrent registration", e);
        }
    }
}
