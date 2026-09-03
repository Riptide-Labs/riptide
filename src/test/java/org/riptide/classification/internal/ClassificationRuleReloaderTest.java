/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.riptide.classification.ClassificationEngine;
import org.riptide.classification.ClassificationEngine.Publication;
import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.ClassificationRuleProvider;
import org.riptide.classification.Protocols;
import org.riptide.classification.internal.csv.CsvImporter;
import org.riptide.config.ClassificationConfig;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.riptide.classification.internal.ClassificationRulesTestSupport.HEADER;
import static org.riptide.classification.internal.ClassificationRulesTestSupport.rules;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The scheduled rules reload (#655), against a real HTTP server: the source is fetched,
 * changed content reaches the decision tree, unchanged content does not, and none of the
 * ways a remote source can misbehave — 404, a non-200 status, connection refused, a server
 * that accepts and never answers, one that dribbles bytes to defeat a per-read timeout,
 * one that answers with more bytes than a ruleset can be, a body that will not parse —
 * stops flows being classified.
 *
 * <p>The schedule is set to an hour and the cycles are driven by hand, so every row here
 * is deterministic; that the interval is read at all, and that nothing is scheduled
 * without it, is pinned by {@code ClassificationReloadIntervalTest} and
 * {@code ReloaderDisabledMetricsTest} through the real Spring context. Neither of those
 * lets a schedule fire either: {@code HttpRulesRefreshOnIntervalTest} is the only test
 * that does, and it is where "applies without a restart" is actually observed.</p>
 *
 * <p>The class-level timeout is not decoration: the hung-server and dribbling-server rows
 * fail by hanging, and 60s is sized for rows that otherwise finish in fractions of a
 * second. It does not fit every row: {@link #anIntervalAgainstTheBundledClasspathRulesetPollsHarmlessly}
 * builds the real bundled decision tree, which costs tens of seconds under the coverage agent
 * this suite runs with and about 1.5s without it, and so carries its own 5-minute method-level
 * bound, sized in the comment directly above it. The 60s governs the body of every other row,
 * and no bound at all covers setUp or tearDown.</p>
 */
@Timeout(60)
class ClassificationRuleReloaderTest {


    /** What the server answers next; {@code null} is a 404. */
    private volatile String body = rules("alpha");
    /** When set, the server accepts the request and never answers it. */
    private volatile boolean hang;
    /** When set, the server answers 200 and then sends one byte at a time, forever. */
    private volatile boolean dribble;
    /** When set, the server answers 200 with more bytes than a ruleset may have. */
    private volatile boolean oversized;
    /** Anything but 200 is answered with this status and no body. */
    private volatile int status = 200;
    private final AtomicInteger requests = new AtomicInteger();

    /**
     * A rules file saved with a UTF-8 BOM still loads, through the production composition.
     *
     * <p>This is the half of #725 the issue predicted and put in the wrong file. It reasoned about
     * inventory YAML, where SnakeYAML strips a BOM on every overload and nothing was ever broken.
     * The rules file is CSV: commons-csv does not strip one, so the first header becomes
     * {@code \uFEFFname}, {@code CsvImporter}'s header comparison fails, and its message prints the
     * expected and actual headers with the difference invisible. At boot that is a startup failure.
     *
     * <p>Driven through {@code ClassificationRulesSource.read()} rather than the importer alone,
     * because that is where the strip has to live: {@code ClassificationRuleReloader} discards the
     * bytes the watch loop hands it and has the engine re-read through this method, so a fix
     * applied only in the watch loop would have missed both this path and boot.</p>
     */
    @Test
    void aRulesetSavedWithAByteOrderMarkStillParses() throws Exception {
        final ClassificationConfig config = new ClassificationConfig();
        config.setRules(new org.springframework.core.io.ByteArrayResource(
                ("\uFEFF" + rules("bom-tolerated")).getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        final byte[] read = new ClassificationRulesSource(config).read();

        assertThat(read[0]).as("the BOM is gone before any parser sees it")
                .isNotEqualTo((byte) 0xEF);
        try (var stream = new ByteArrayInputStream(read)) {
            assertThat(new CsvImporter().parse(stream, true))
                    .as("commons-csv does not strip a BOM, so this is the assertion that would fail")
                    .singleElement()
                    .satisfies(rule -> assertThat(rule.getName()).isEqualTo("bom-tolerated"));
        }
    }

    /** A ruleset that fetches and parses, and whose second rule the engine then rejects. */
    private static final String ONE_GOOD_ONE_BROKEN =
            HEADER + "good;;;;;80;;false\n" + "broken;;;;;not-a-port;;false\n";

    /** Run on the reload thread before each rule read; a row that has to order the boot load replaces it. */
    private volatile Runnable beforeRuleRead = () -> { };

    /** How the wrapper is built; a row that has to order the boot load against the reloader replaces it. */
    private BiFunction<ClassificationEngine, MetricRegistry, AsyncReloadingClassificationEngine> wrapper =
            AsyncReloadingClassificationEngine::new;

    @TempDir
    Path tempDir;

    private HttpServer server;
    private ExecutorService serverExecutor;
    private MetricRegistry metrics;
    private ClassificationConfig config;
    private ClassificationRulesSource source;
    private AsyncReloadingClassificationEngine engine;
    private ClassificationRuleReloader reloader;
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() throws IOException {
        this.serverExecutor = Executors.newCachedThreadPool();
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.createContext("/rules.csv", exchange -> {
            this.requests.incrementAndGet();
            if (this.hang) {
                // accepted and never answered: the fetch has to give up on its own
                sleepQuietly(5_000);
                exchange.close();
                return;
            }
            if (this.dribble) {
                // 200, then one byte per interval, forever: every byte resets a per-read
                // timeout, so only a deadline across the whole response ends this
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    for (int i = 0; i < 600; i++) {
                        out.write('x');
                        out.flush();
                        sleepQuietly(100);
                    }
                } catch (final IOException expected) {
                    // the reader gave up, which is the property under test
                }
                return;
            }
            if (this.oversized) {
                final byte[] chunk = new byte[64 * 1024];
                // exactly one byte past the ceiling, and exactly the length declared: a
                // short body would stall the reader on its read timeout instead, which
                // counts the same failure for a different reason
                long remaining = ClassificationRulesSource.MAX_BYTES + 1L;
                exchange.sendResponseHeaders(200, remaining);
                try (OutputStream out = exchange.getResponseBody()) {
                    while (remaining > 0) {
                        final int write = (int) Math.min(chunk.length, remaining);
                        out.write(chunk, 0, write);
                        remaining -= write;
                    }
                } catch (final IOException expected) {
                    // the reader refused past the ceiling and closed
                }
                return;
            }
            if (this.status != 200) {
                exchange.sendResponseHeaders(this.status, -1);
                exchange.close();
                return;
            }
            final String current = this.body;
            if (current == null) {
                // a real 404 body, so the error-stream drain has something to drain
                final byte[] notFound = "no such ruleset\n".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, notFound.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(notFound);
                }
                return;
            }
            final byte[] bytes = current.getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        // a pool, not the default caller-runs dispatcher: the hung request must not block
        // the ones the same test makes afterwards
        this.server.setExecutor(this.serverExecutor);
        this.server.start();

        this.metrics = new MetricRegistry();
        this.logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ClassificationRuleReloader.class);
        this.appender = new ListAppender<>();
        this.appender.start();
        this.logger.addAppender(this.appender);
    }

    @AfterEach
    void tearDown() {
        if (this.reloader != null) {
            this.reloader.stop();
        }
        if (this.engine != null) {
            this.engine.shutdown();
        }
        stopServer();
        // guarded: a setUp that fails before these exist must report its own cause, not an
        // NPE from the teardown that ran after it
        if (this.serverExecutor != null) {
            this.serverExecutor.shutdownNow();
        }
        if (this.logger != null && this.appender != null) {
            this.logger.detachAppender(this.appender);
            this.appender.stop();
        }
    }

    private URI rulesUri() {
        return URI.create("http://127.0.0.1:" + this.server.getAddress().getPort() + "/rules.csv");
    }

    /** Builds the production stack — bounded source, provider, engine, reloader — and starts it. */
    private void buildStack(final Resource rules, final Duration interval) throws Exception {
        buildStack(rules, interval, Duration.ofMillis(300));
    }

    private void buildStack(final Resource rules, final Duration interval, final Duration timeout) throws Exception {
        buildEngine(rules, interval, timeout);
        startReloaderOnly();
    }

    /**
     * The stack below the reloader, so a row can order the boot load against the registration itself.
     * <p>
     * The engine reads through the same {@link ClassificationRulesSource} the reloader is then given, which is
     * the production wiring and not an incidental one: the publish log names that source's location, and a
     * fixture pairing an unrelated provider with a configured source would make that sentence false in the
     * fixture while it stayed true in production.
     */
    private void buildEngine(final Resource rules, final Duration interval, final Duration timeout) throws Exception {
        this.config = new ClassificationConfig();
        this.config.setRules(rules);
        this.config.setReloadInterval(interval);
        // 300ms by default, not the production timeout: the hung- and dribbling-server rows
        // would otherwise wait out the real one, and what they test is that the fetch is
        // bounded at all. The production constant is pinned separately, by
        // theProductionConstructorBoundsTheConnectionItOpens
        this.source = new ClassificationRulesSource(this.config, timeout);
        final CsvImporter importer = new CsvImporter();
        final ClassificationRuleProvider provider = () -> {
            this.beforeRuleRead.run();
            try (var stream = new ByteArrayInputStream(this.source.read())) {
                return importer.parse(stream, true);
            } catch (final IOException e) {
                throw new UncheckedIOException("Cannot load classification rules", e);
            }
        };
        this.engine = this.wrapper.apply(new DefaultClassificationEngine(provider, false), this.metrics);
    }

    /** The reloader over an already-built engine, so a row can publish before the registration happens. */
    private void startReloaderOnly() {
        this.reloader = new ClassificationRuleReloader(this.config, this.engine, this.source, this.metrics);
        this.reloader.start();
    }

    private void startReloader(final Duration interval) throws Exception {
        buildStack(new UrlResource(rulesUri()), interval);
        // classify() blocks until the construction-time load settles, so this is the wait
        assertThat(classification()).isEqualTo("alpha");
    }

    @Test
    void changedRulesClassifyWithoutARestart() throws Exception {
        startReloader(Duration.ofHours(1));

        this.body = rules("beta");
        this.reloader.poll();

        // On the counter, not on the classification: the engine publishes the new tree inside
        // delegate.reload() and only then runs onReloadSucceeded, which writes stale and the
        // counter. Waiting on classify() therefore returns while both still hold their old values,
        // and the three assertions below would read them mid-flight. Same direction as #699, which
        // is the same defect one class over.
        await("the reload to be counted", () -> successes() >= 2);
        assertThat(classification()).as("the new rules serve").isEqualTo("beta");
        assertThat(successes()).as("the startup load and this one").isEqualTo(2);
        assertThat(failures()).isZero();
        assertThat(stale()).isZero();
        assertThat(infos()).as("a change that landed is not visible from a counter alone")
                .anyMatch(message -> message.contains("changed") && message.contains("reloading"));
    }

    /**
     * The consumer the listener seam exists for (#685). A ruleset that fetches and parses but whose rules the
     * engine cannot all preprocess is the case no other signal covers: the fetch succeeded so nothing warns here,
     * the load succeeded so {@code classification.reload.successes} moves and the stale gauge stays 0, and the
     * operator's edit is half-live with no line saying which half.
     *
     * <p>Asserted on the rejected rule's <em>name</em> and on both counts, not on an empty list: the reloader
     * reaches these through {@code currentPublication()}, whose default is an empty invalid-rule list, so an
     * assertion that could pass against a default would not be reading anything.</p>
     */
    @Test
    void aRejectedRuleInAPublishedRulesetIsNamedByTheReloader() throws Exception {
        startReloader(Duration.ofHours(1));

        this.body = ONE_GOOD_ONE_BROKEN;
        this.reloader.poll();

        // the listener fires inside the engine's reload(), so a counted success is strictly after the log
        await("the reload to be counted", () -> successes() >= 2);

        assertThat(warnings())
                .as("the seam's whole point: which of the operator's rules are classifying nothing")
                .containsExactly("Classification rules from %s published: 2 rules, of which 1 were rejected "
                        .formatted(this.source.describe()) + "and classify nothing: broken");
        assertThat(classification()).as("the accepted half of the ruleset is serving").isEqualTo("good");
        assertThat(failures()).as("a rejected rule is not a failed reload").isZero();
        assertThat(stale()).isZero();
    }

    /**
     * The clean arm, and the three things in it a constant would satisfy: that a publish with nothing rejected is
     * reported at all, that the count is read rather than assumed, and that the location is the one
     * {@link ClassificationRulesSource#describe()} produces.
     *
     * <p>That last one is not cosmetic. {@code describe()} is the only place applying the userinfo redaction —
     * {@code credentialsInTheLocationAreNotLogged} pins that it does — and {@code operations.md} promises tokens
     * are redacted wherever the location is logged. A line built from the raw resource instead would satisfy an
     * assertion that only looked for a host.</p>
     */
    @Test
    void aCleanPublishIsReportedWithItsRuleCountAndTheRedactedLocation() throws Exception {
        // Credentials in the location, deliberately. Against a plain URL, describe() and the resource's own
        // toString() render identically, so an assertion on the sentence cannot tell which of them built it —
        // measured: swapping describe() for the raw resource survived that version of this test. Userinfo is
        // the one case where the two differ, and it is the case the docs make a promise about.
        this.body = HEADER + "a;;;;;80;;false\n" + "b;;;;;81;;false\n" + "c;;;;;82;;false\n";
        buildStack(new UrlResource(URI.create("http://ops:s3cr3t@127.0.0.1:"
                + this.server.getAddress().getPort() + "/rules.csv")), Duration.ofHours(1), Duration.ofSeconds(10));
        await("the boot load to be counted", () -> successes() == 1);

        // three rules, so a count hard-coded to the one-rule fixture, or reading the rejected count, cannot pass
        assertThat(publishLines())
                .contains("Classification rules from %s published: 3 rules, none rejected"
                        .formatted(this.source.describe()));
        assertThat(this.source.describe()).as("which is the redacted form").contains("***@");
        assertThat(publishLines()).as("so the token never reaches the log")
                .noneMatch(message -> message.contains("s3cr3t"));
        assertThat(warnings()).as("nothing was rejected, so nothing warns").isEmpty();
    }

    /**
     * A wholesale rejection — a reordered column, a changed delimiter — rejects every rule the ruleset has. The
     * names are capped so one physical log line cannot carry thousands of them at boot and again on every reload;
     * the count stays exact, because that is the number an operator acts on.
     */
    @Test
    void aWholesaleRejectionNamesTheFirstRulesAndCountsTheRest() throws Exception {
        startReloader(Duration.ofHours(1));

        final StringBuilder body = new StringBuilder(HEADER);
        for (int i = 0; i < 25; i++) {
            body.append("bad-%d;;;;;not-a-port;;false\n".formatted(i));
        }
        this.body = body.toString();
        this.reloader.poll();
        await("the reload to be counted", () -> successes() >= 2);

        assertThat(warnings()).singleElement().satisfies(message -> {
            assertThat(message).as("the count is exact").contains("25 rules, of which 25 were rejected");
            assertThat(message).as("the first names are there to start from").contains("bad-0", "bad-19");
            assertThat(message).as("and the tail is summarised, not printed").contains("and 5 more")
                    .doesNotContain("bad-20");
        });
    }

    /**
     * The seam's third defect, closed for the only consumer there is. {@code AsyncReloadingClassificationEngine}'s
     * constructor submits the boot load, so by the time this bean's {@code @PostConstruct} runs that load has
     * usually already published — and nothing is replayed to a listener that registered afterwards, by design. A
     * consumer that only waited for a callback would therefore never learn what is serving, which is precisely the
     * case where an operator most wants to know: at startup, having just edited the ruleset.
     *
     * <p>The ordering is forced rather than hoped for. The boot load is waited out <em>before</em> the reloader
     * exists, so no callback can possibly fire for that publication and only the pull can report it — asserted on
     * an empty log first, so the assertion below cannot be satisfied by a line that was already there.</p>
     */
    @Test
    void aPublishThatLandedBeforeTheReloaderRegisteredIsStillReported() throws Exception {
        this.body = ONE_GOOD_ONE_BROKEN;
        buildEngine(new UrlResource(rulesUri()), Duration.ZERO, Duration.ofSeconds(10));
        await("the boot load to publish", () -> successes() == 1);
        assertThat(warnings()).as("nothing was registered while that load ran, so its callback is gone").isEmpty();

        startReloaderOnly();

        assertThat(warnings())
                .as("the pull is the only path that could have reported it")
                .anyMatch(message -> message.contains("rejected") && message.contains("broken"));
    }

    /**
     * Registration comes before the pull, and the order is the property rather than an accident of how the two
     * statements were typed. Reversed, a publish landing between them is reported by <em>neither</em> path: the
     * pull has already taken its answer, and the fire's listener snapshot was captured before the registration.
     *
     * <p>Forced by gating the boot load on the pull having read: the publish therefore lands strictly inside the
     * gap. In the correct order the listener is in the snapshot when the fire runs and the callback reports it; in
     * the reversed one nothing is, and the log stays empty.</p>
     */
    @Test
    void aPublishLandingBetweenTheRegistrationAndThePullIsStillReported() throws Exception {
        this.body = ONE_GOOD_ONE_BROKEN;
        final CountDownLatch pullHasRead = new CountDownLatch(1);
        final CountDownLatch published = new CountDownLatch(1);
        final AtomicBoolean gateUsed = new AtomicBoolean();

        this.beforeRuleRead = () -> awaitLatch(pullHasRead, "the pull to have taken its answer");
        this.wrapper = (delegate, registry) -> new AsyncReloadingClassificationEngine(delegate, registry) {
            @Override
            public Optional<Publication> currentPublication() {
                // the answer is taken FIRST and returned unchanged, so the publish below lands after this read
                // — that is what makes it a publish "in the gap" rather than one the pull could have seen
                final Optional<Publication> answer = super.currentPublication();
                // one-shot, and the pull is provably the first caller: the boot load cannot publish, and so
                // cannot reach any callback, until this line releases it
                if (gateUsed.compareAndSet(false, true)) {
                    pullHasRead.countDown();
                    awaitLatch(published, "the boot load to publish");
                }
                return answer;
            }
        };
        buildEngine(new UrlResource(rulesUri()), Duration.ZERO, Duration.ofSeconds(10));
        // ahead of the reloader's listener in the fire order, so it signals that the publish has happened
        this.engine.addClassificationRulesReloadedListener(rules -> published.countDown());

        startReloaderOnly();
        await("the boot load to settle", () -> successes() == 1);

        assertThat(warnings())
                .as("the listener was registered before the gap, so the fire still reached it")
                .anyMatch(message -> message.contains("rejected") && message.contains("broken"));
    }

    /**
     * Exactly once, with the callback provably first. The registration returns only after the boot load has been
     * counted — which happens after every listener has been delivered — so the callback has reported before the
     * pull runs at all, and the pull must add nothing.
     */
    @Test
    void aBootPublishTheCallbackReportsFirstIsNotReportedAgainByThePull() throws Exception {
        this.body = ONE_GOOD_ONE_BROKEN;
        final CountDownLatch registered = new CountDownLatch(1);

        this.beforeRuleRead = () -> awaitLatch(registered, "the reloader to register");
        this.wrapper = (delegate, registry) -> new AsyncReloadingClassificationEngine(delegate, registry) {
            @Override
            public void addClassificationRulesReloadedListener(final ClassificationRulesReloadedListener listener) {
                super.addClassificationRulesReloadedListener(listener);
                registered.countDown();
                // the counter moves in onReloadSucceeded, strictly after every listener has been delivered,
                // so this returns with the callback's report already written
                awaitCondition(() -> successes() == 1, "the boot load to be counted");
            }
        };
        buildEngine(new UrlResource(rulesUri()), Duration.ZERO, Duration.ofSeconds(10));

        startReloaderOnly();

        assertThat(warnings()).as("the callback reported, and the pull that followed added nothing").hasSize(1);
        assertThat(warnings().getFirst()).contains("broken");
    }

    /**
     * Exactly once, with the pull provably first. A listener registered ahead of the reloader's parks the fire
     * after the publish and before the reloader's own callback, so the pull reports while that callback is
     * demonstrably still pending — asserted at that moment — and the callback must then add nothing.
     */
    @Test
    void aBootPublishThePullReportsFirstIsNotReportedAgainByTheCallback() throws Exception {
        this.body = ONE_GOOD_ONE_BROKEN;
        final CountDownLatch registered = new CountDownLatch(1);
        final CountDownLatch firing = new CountDownLatch(1);
        final CountDownLatch fireRelease = new CountDownLatch(1);

        final AtomicBoolean gateArmed = new AtomicBoolean();

        this.beforeRuleRead = () -> awaitLatch(registered, "the reloader to register");
        this.wrapper = (delegate, registry) -> new AsyncReloadingClassificationEngine(delegate, registry) {
            @Override
            public void addClassificationRulesReloadedListener(final ClassificationRulesReloadedListener listener) {
                super.addClassificationRulesReloadedListener(listener);
                // only the reloader's registration is gated. Gating the blocker's too would release the boot
                // load before the reloader had registered, so the fire's snapshot would not contain its
                // listener and the callback this row is about would never run at all — which is exactly how
                // an earlier version of this test passed while proving nothing
                if (!gateArmed.get()) {
                    return;
                }
                registered.countDown();
                awaitLatch(firing, "the fire to reach the blocker");
            }
        };
        buildEngine(new UrlResource(rulesUri()), Duration.ZERO, Duration.ofSeconds(10));
        // registered before the reloader's, so it is ahead of it in the fire order and holds the fire there
        this.engine.addClassificationRulesReloadedListener(rules -> {
            firing.countDown();
            awaitLatch(fireRelease, "the test to release the fire");
        });

        gateArmed.set(true);
        startReloaderOnly();

        assertThat(warnings()).as("the pull reported while the callback is provably still parked").hasSize(1);

        fireRelease.countDown();
        await("the boot load to settle", () -> successes() == 1);

        assertThat(warnings()).as("and the callback for that same publication added nothing").hasSize(1);
        assertThat(warnings().getFirst()).contains("broken");
    }

    /**
     * The dedupe is "this same publish", not "a ruleset that compares equal". {@code DefaultRule} is a Lombok
     * {@code @Data}, so two loads of unchanged bytes produce publications that are {@code equals} — and an
     * operator who asked for a reload has to see that it happened.
     */
    @Test
    void aReloadRepublishingAnIdenticalRulesetIsReportedAgain() throws Exception {
        startReloader(Duration.ofHours(1));
        final int afterBoot = publishLines().size();

        // straight at the engine, because the trigger's content hash would skip unchanged bytes — which is
        // exactly why the equal-but-not-identical case has to be reachable some other way
        this.engine.reload();
        await("the second load to be counted", () -> successes() == 2);

        assertThat(publishLines()).as("the same rules published twice are two publishes")
                .hasSize(afterBoot + 1);
    }

    /** A stopped reloader is off the seam: the engine keeps publishing and this stops narrating it. */
    @Test
    void aStoppedReloaderReportsNothingMore() throws Exception {
        startReloader(Duration.ofHours(1));
        final int afterBoot = publishLines().size();

        this.reloader.stop();
        this.engine.reload();
        await("the reload after the stop to be counted", () -> successes() == 2);

        assertThat(publishLines()).as("deregistered, so nothing was written for that publish")
                .hasSize(afterBoot);
    }

    /** Waits on a latch, failing the test rather than hanging it, and never swallowing an interrupt. */
    private static void awaitLatch(final CountDownLatch latch, final String what) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for " + what);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for " + what, e);
        }
    }

    /** {@link #await} for the gates above, which run where a checked exception cannot be thrown. */
    private static void awaitCondition(final BooleanSupplier condition, final String what) {
        try {
            await(what, condition);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for " + what, e);
        }
    }

    /** The content hash decides, not the clock: an unchanged source is never rebuilt. */
    @Test
    void unchangedRulesAreNeverRebuilt() throws Exception {
        startReloader(Duration.ofHours(1));
        final int afterStartup = this.requests.get();

        for (int i = 0; i < 5; i++) {
            this.reloader.poll();
        }
        // a negative property against an asynchronous engine: a rebuild this cycle handed
        // over would land on the reload thread, not on this one, so give it a window to
        // land in before asserting that it never did
        Thread.sleep(200);

        assertThat(successes()).as("only the startup load ever published").isEqualTo(1);
        assertThat(this.requests.get() - afterStartup)
                .as("one fetch per cycle and no second read by the engine")
                .isEqualTo(5);
        assertThat(stale()).isZero();
    }

    /** A 404 is absence, not failure: warn once, keep classifying, count nothing. */
    @Test
    void aSourceThatIsNotThereSkipsAndWarnsOnce() throws Exception {
        startReloader(Duration.ofHours(1));

        this.body = null;
        this.reloader.poll();
        this.reloader.poll();
        this.reloader.poll();

        assertThat(warnings()).hasSize(1);
        assertThat(warnings().getFirst()).contains("are not there");
        assertThat(failures()).as("absence is not a failed reload").isZero();
        assertThat(stale()).as("a skip decides nothing about what is serving").isZero();
        assertThat(classification()).as("the last good rules keep classifying").isEqualTo("alpha");
    }

    /** A 200 with no usable body never commits an empty ruleset. */
    @Test
    void anEmptyResponseSkipsAndWarnsOnce() throws Exception {
        startReloader(Duration.ofHours(1));

        this.body = "";
        this.reloader.poll();
        this.reloader.poll();

        assertThat(warnings()).hasSize(1);
        assertThat(warnings().getFirst()).contains("empty or whitespace-only");
        assertThat(failures()).isZero();
        assertThat(classification()).isEqualTo("alpha");
    }

    /** A 5xx is a transport failure naming the code, not a rules problem. */
    @Test
    void aServerErrorIsAFailureNamingTheCode() throws Exception {
        startReloader(Duration.ofHours(1));

        this.status = 503;
        this.reloader.poll();

        assertThat(failures()).isEqualTo(1);
        assertThat(warnings()).anyMatch(message -> message.contains("HTTP 503"));
        assertThat(classification()).isEqualTo("alpha");
    }

    /**
     * A redirect this fetch does not follow is the row that needs the status check: the
     * JDK raises a 5xx on its own, but a 3xx it cannot follow simply yields an empty body —
     * which without the check reads as "the endpoint served an empty ruleset", warns about
     * a ruleset problem, and counts nothing.
     */
    @Test
    void aRedirectThisFetchDoesNotFollowIsAFailureNotAnEmptyRuleset() throws Exception {
        startReloader(Duration.ofHours(1));

        this.status = 307;
        this.reloader.poll();

        assertThat(failures()).as("a redirect nobody followed fetched no ruleset").isEqualTo(1);
        assertThat(warnings()).anyMatch(message -> message.contains("HTTP 307"));
        assertThat(warnings()).noneMatch(message -> message.contains("empty or whitespace-only"));
        assertThat(classification()).isEqualTo("alpha");
    }

    /**
     * An unreachable source: the fetch fails, and the failure is the trigger's half of the
     * one metric family — the engine never sees it, because nothing was ever handed to it.
     */
    @Test
    void anUnreachableSourceKeepsTheLastGoodRulesServing() throws Exception {
        startReloader(Duration.ofHours(1));
        stopServer();

        this.reloader.poll();

        assertThat(failures()).isEqualTo(1);
        assertThat(this.engine.isStale()).as("the engine was never asked to reload").isFalse();
        assertThat(stale()).as("the gauge carries the fetch half too").isEqualTo(1);
        assertThat(warnings()).anyMatch(message -> message.contains("Fetching the classification rules"));
        assertThat(classification()).isEqualTo("alpha");
    }

    /** A server that accepts and never answers must not park the schedule. */
    @Test
    void aHangingSourceGivesUpInsteadOfStallingTheSchedule() throws Exception {
        startReloader(Duration.ofHours(1));

        this.hang = true;
        final Duration elapsed = timePoll();

        assertThat(elapsed).as("bounded by the fetch timeout, not by the server").isLessThan(Duration.ofSeconds(3));
        assertThat(failures()).isEqualTo(1);
        assertThat(classification()).isEqualTo("alpha");

        // and the next cycle runs normally: the timeout ended the cycle, not the schedule
        this.hang = false;
        this.body = rules("beta");
        this.reloader.poll();
        await("the recovered cycle to serve the new rules", () -> "beta".equals(classification()));
    }

    /**
     * The hung server is the easy half. A server that sends one byte every 100ms resets a
     * 300ms per-read timeout on every one of them, so only a deadline across the whole
     * response ends the cycle — this is the row that tells a real bound from a per-read one.
     */
    @Test
    void aDribblingSourceIsBoundedByTheWholeResponseNotByEachRead() throws Exception {
        startReloader(Duration.ofHours(1));

        this.dribble = true;
        final Duration elapsed = timePoll();

        assertThat(elapsed).as("a byte at a time must not extend the cycle indefinitely")
                .isLessThan(Duration.ofSeconds(5));
        assertThat(failures()).isEqualTo(1);
        assertThat(classification()).isEqualTo("alpha");
    }

    /**
     * A response larger than any ruleset is refused rather than buffered. The realistic
     * failure without this is an {@code OutOfMemoryError} on the poll thread, and an
     * {@code Error} out of {@code poll()} cancels the schedule for the process lifetime.
     */
    @Test
    void anOversizedResponseIsRefusedInsteadOfBuffered() throws Exception {
        // the one row that needs a real timeout: eight megabytes do not cross a loopback
        // socket inside 300ms, and a deadline that fired first would count the same failure
        // for the wrong reason
        buildStack(new UrlResource(rulesUri()), Duration.ofHours(1), Duration.ofSeconds(10));
        assertThat(classification()).isEqualTo("alpha");

        this.oversized = true;
        this.reloader.poll();

        assertThat(failures()).isEqualTo(1);
        assertThat(warnings()).anyMatch(message -> message.contains("larger than"));
        assertThat(classification()).as("nothing oversized ever reached the parser").isEqualTo("alpha");
    }

    /**
     * Rules that fetch fine and will not parse: the engine's half of the family. The fetch
     * succeeded, so the trigger latches nothing — the gauge reads 1 only because it ORs
     * what the engine latched.
     */
    @Test
    void malformedRulesKeepTheLastGoodRulesServing() throws Exception {
        startReloader(Duration.ofHours(1));

        this.body = "this is not a rules header\nnonsense\n";
        this.reloader.poll();

        await("the failed load to settle", () -> failures() == 1);
        assertThat(classification()).isEqualTo("alpha");
        assertThat(this.engine.isStale()).isTrue();
        assertThat(stale()).isEqualTo(1);
        assertThat(warnings()).as("the fetch worked, so the reloader says nothing about it").isEmpty();
    }

    /**
     * The posture the class javadoc and the operator docs state: bytes that failed to load
     * are attempted <em>once</em>. Re-attempting them every interval would rebuild nothing,
     * count a failure per cycle and bury the first, real one — the property the file
     * reloaders pin as {@code theSameBadContentIsAttemptedOnlyOnce}.
     */
    @Test
    void aRulesetThatFailedToLoadIsNotReAttemptedUntilItChanges() throws Exception {
        startReloader(Duration.ofHours(1));

        this.body = "this is not a rules header\nnonsense\n";
        this.reloader.poll();
        await("the failed load to settle", () -> failures() == 1);
        final int afterFirstAttempt = this.requests.get();

        for (int i = 0; i < 4; i++) {
            this.reloader.poll();
        }
        Thread.sleep(200);

        assertThat(failures()).as("counted once, not once per interval").isEqualTo(1);
        assertThat(this.requests.get() - afterFirstAttempt)
                .as("polled, but never handed to the engine again")
                .isEqualTo(4);
        assertThat(stale()).as("held at 1 by the engine until a later ruleset loads").isEqualTo(1);

        // and fixing the ruleset is an ordinary change, picked up on the next poll
        this.body = rules("beta");
        this.reloader.poll();
        // On the gauge here, and it is a real wait because the failure above latched it to 1 —
        // asserted three lines up. The engine clears it in onReloadSucceeded, after the publish,
        // so waiting on the classification would return with stale still 1 (#699).
        await("the recovering reload to clear staleness", () -> stale() == 0);
        assertThat(classification()).as("the fixed ruleset serves").isEqualTo("beta");
    }

    /**
     * The one state where flows actually fail: no rules ever loaded, because the resource
     * was not readable when the engine first looked. Every other row here starts from a
     * healthy load, and the documented recovery is the schedule.
     */
    @Test
    void aScheduleRecoversAnInitialLoadThatNeverSucceeded() throws Exception {
        this.body = null;
        buildStack(new UrlResource(rulesUri()), Duration.ofHours(1));

        assertThatThrownBy(this::classification)
                .as("nothing ever loaded, so classification is unavailable")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("no rules have ever been loaded");
        assertThat(stale()).isEqualTo(1);

        this.body = rules("alpha");
        this.reloader.poll();

        await("classification to recover", () -> "alpha".equals(classification()));
        assertThat(stale()).as("recovered, so nothing is stale any more").isZero();
    }

    /**
     * A local resource that is <em>there and unreadable</em> — a permission denial reads as
     * {@code FileNotFoundException}, exactly like a file that is not there. Skipping it
     * forever would count nothing and tell the operator to make a present file reappear.
     */
    @Test
    void aLocalResourceThatIsThereButUnreadableIsAFailureNotASkip() throws Exception {
        startReloader(Duration.ofHours(1));

        final Path denied = Files.writeString(this.tempDir.resolve("rules.csv"), rules("beta"));
        this.config.setRules(new FileSystemResource(denied.toFile()) {
            @Override
            public InputStream getInputStream() throws IOException {
                // what the JDK raises for a file: URL whose file cannot be opened; the
                // resource itself still exists, which is the whole distinction
                throw new FileNotFoundException(denied + " (Permission denied)");
            }
        });

        this.reloader.poll();

        assertThat(failures()).as("present and unreadable is a failure").isEqualTo(1);
        assertThat(warnings()).anyMatch(message -> message.contains("Permission denied"));
        assertThat(warnings()).noneMatch(message -> message.contains("are not there"));
        assertThat(classification()).isEqualTo("alpha");
    }

    /** Shutdown mid-poll: nothing fetched, nothing counted, nothing latched. */
    @Test
    void anInterruptedCycleConsumesNothing() throws Exception {
        startReloader(Duration.ofHours(1));
        final int afterStartup = this.requests.get();
        this.body = rules("beta");

        Thread.currentThread().interrupt();
        try {
            this.reloader.poll();
        } finally {
            Thread.interrupted();
        }

        assertThat(this.requests.get()).as("the source was never touched").isEqualTo(afterStartup);
        assertThat(failures()).isZero();
        assertThat(stale()).isZero();
        assertThat(classification()).isEqualTo("alpha");

        // the change was never consumed, so the next clean cycle serves it
        this.reloader.poll();
        await("the unconsumed change to serve", () -> "beta".equals(classification()));
    }

    /** No interval: no schedule, and no dead gauge to claim there is one. */
    @Test
    void withoutAnIntervalNothingIsScheduled() throws Exception {
        startReloader(Duration.ZERO);
        final int afterStartup = this.requests.get();

        this.body = rules("beta");
        this.reloader.poll();

        assertThat(this.metrics.getGauges()).doesNotContainKey("classification.reload.dead");
        assertThat(this.requests.get()).as("nothing polls the source").isEqualTo(afterStartup);
        assertThat(classification()).isEqualTo("alpha");
    }

    /** The other arm of the same gate: a negative interval is disabled, not a schedule. */
    @Test
    void aNegativeIntervalIsAlsoDisabled() throws Exception {
        startReloader(Duration.ofSeconds(-30));
        final int afterStartup = this.requests.get();

        this.body = rules("beta");
        this.reloader.poll();

        assertThat(this.metrics.getGauges()).doesNotContainKey("classification.reload.dead");
        assertThat(this.requests.get()).isEqualTo(afterStartup);
    }

    /** A stopped schedule is a visible corpse, the same way the file reloaders' are. */
    @Test
    void aStoppedScheduleReadsAsDead() throws Exception {
        startReloader(Duration.ofHours(1));
        assertThat(dead()).as("a live schedule is not a corpse").isZero();

        this.reloader.stop();

        assertThat(dead()).isEqualTo(1);
    }

    /**
     * The likeliest misconfiguration: an interval against the bundled {@code classpath:}
     * ruleset. It is polled like anything else and, inside a packaged jar, can never
     * change — so it commits nothing and says nothing, forever. Pinned so the docs' advice
     * to point the interval at a file or a URL is describing real behaviour.
     */
    // Bounded here rather than by the class's 60s, which fits rows finishing in fractions of
    // a second: the hung-server and dribbling-server rows are 0.33s each. This row loads the
    // real bundled ruleset, and building the decision tree from it is nearly its whole cost.
    //
    // Almost all of that cost is the coverage agent, not the build. Measured standalone on the
    // project classpath, Tree.of over this ruleset takes 1.4-1.7s and produces a 15,530-leaf
    // tree. The same harness under the JaCoCo agent produces the same tree in 33.9-50.9s.
    // jacoco:prepare-agent attaches to every surefire JVM, so this row pays the instrumented
    // price and a booting collector does not. A recursive loop that matches every candidate
    // threshold against every rule is close to the worst case for per-instruction
    // instrumentation, which is why the factor is roughly 20-40x rather than a few percent.
    // Quote the range, not a midpoint: the five runs this bound was raised for were terminated
    // AT 60s on a ~1.5s build, so the loaded-machine end of that spread is above 40x, and it is
    // the end that caused the flake.
    //
    // That also explains the spread: completed runs have landed anywhere between roughly 25s
    // and 55s on identical code, and CI is consistently faster than a developer machine. It is
    // agent overhead varying with load, not the row. The individual runs are recorded on #706
    // rather than copied here, where they would rot. 5 minutes is more than 5x the slowest
    // completion seen, all of them instrumented.
    //
    // The five failures that prompted this are not measurements of the row. Each was
    // terminated at the 60s bound, so together they put a floor under the cost and leave the
    // ceiling unknown. The ~180s floated in #706 is under 4x the slowest completion, which is
    // thin against a spread this wide.
    //
    // A bound is still needed at all because classify() blocks on the load, so a reload that
    // never settles would park the row forever.
    //
    // #706 is the alarm this silences, not the flake itself: the row is still nearly the
    // whole cost of the class and still slows under load, the bound simply no longer fires.
    //
    // The exit condition is the instrumented cost, which is not what #707 tracks. #707 is the
    // residue that survived measuring this: an uncached, superlinear build that a boot pays
    // about 1.5s for. Closing it would not shorten this row unless the fix is a cache or a
    // faster build — and one route canvassed there, making classify() non-blocking, leaves the
    // build exactly as slow.
    //
    // The condition for removing this annotation is stated in instrumented terms, because that
    // is what the bound has to survive: when this row lands under ~30s with margin on a loaded
    // machine, the class's 60s fits it again. An uninstrumented threshold cannot express it —
    // 25x of 1.5s already fits under 60s today, so any bar written that way is satisfied on
    // arrival and gates nothing. A cache, a cheaper build, or a suite that no longer runs under
    // the agent all get there. Removing it means editing two places, here and the class
    // javadoc, which also names this bound.
    //
    // What this bound does not cover:
    // - CI, where this has never failed. Runners complete the whole class faster than this
    //   machine does, so CI had headroom under the old bound and all five failures were
    //   local. This fixes a developer-machine flake.
    // - Lifecycle methods, which no bound covers. On junit-jupiter 6.0.3 a class-level
    //   @Timeout governs test method bodies only, and nothing here sets the separate
    //   junit.jupiter.execution.timeout.*.default properties, so setUp and tearDown are
    //   unbounded. Probed, not assumed: a 3s @BeforeEach under a class-level @Timeout(1)
    //   passes.
    // - A regression in the build cost, whose only mechanical detector this removes. Under
    //   60s a build growing to ~150s failed this row; it now passes silently under the new
    //   bound, because every assertion below reads counters and warnings, never a clock. A
    //   duration ceiling was considered, and timePoll() above makes one cheap, but the spread
    //   is wide enough that a non-flaky ceiling would sit near half the bound and catch
    //   little the bound would not. And a ceiling here would measure the agent, not the build,
    //   so it could not detect the regression #707 is about even if it were tight.
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void anIntervalAgainstTheBundledClasspathRulesetPollsHarmlessly() throws Exception {
        buildStack(new ClassPathResource("classification-rules.csv"), Duration.ofHours(1));
        assertThat(this.engine.classify(ClassificationRequest.builder()
                        .withProtocol(Protocols.getProtocol("udp")).withDstPort(123).build()))
                .as("the bundled ruleset is serving").isEqualTo("ntp");

        this.reloader.poll();
        this.reloader.poll();

        assertThat(successes()).as("the startup load, and nothing since").isEqualTo(1);
        assertThat(failures()).isZero();
        assertThat(warnings()).isEmpty();
    }

    /**
     * Credentials in the location are the natural move once the docs say the endpoint
     * carries no authentication — and the location is logged at INFO on startup and in
     * every failure WARN.
     */
    @Test
    void credentialsInTheLocationAreNotLogged() throws Exception {
        final ClassificationConfig withUserInfo = new ClassificationConfig();
        withUserInfo.setRules(new UrlResource(URI.create(
                "http://ops:s3cr3t@127.0.0.1:" + this.server.getAddress().getPort() + "/rules.csv")));

        final String described = new ClassificationRulesSource(withUserInfo).describe();

        assertThat(described).doesNotContain("s3cr3t").doesNotContain("ops:");
        assertThat(described).as("still identifies the endpoint").contains("127.0.0.1").contains("***@");
    }

    /**
     * The production timeout, observed on the connection the production constructor
     * actually configures. Every behavioural row above injects its own 300ms, so nothing
     * else reaches the constant — and {@code URLConnection} reads a zero timeout as
     * "wait forever", which is exactly the value a careless edit would leave behind.
     */
    @Test
    void theProductionConstructorBoundsTheConnectionItOpens() throws Exception {
        final ClassificationConfig production = new ClassificationConfig();
        production.setRules(new UrlResource(rulesUri()));

        final URLConnection connection = new ClassificationRulesSource(production)
                .openBounded(rulesUri().toURL());

        assertThat(connection.getConnectTimeout()).as("0 means wait forever").isPositive();
        assertThat(connection.getReadTimeout()).as("0 means wait forever").isPositive();
        assertThat(connection.getUseCaches()).as("a cached response would hide a change from the hash").isFalse();
    }

    private Duration timePoll() {
        final long start = System.nanoTime();
        this.reloader.poll();
        return Duration.ofNanos(System.nanoTime() - start);
    }

    /** Idempotent: one row stops the server mid-test, and the teardown stops it again. */
    private void stopServer() {
        if (this.server != null) {
            this.server.stop(0);
            this.server = null;
        }
    }

    private static void sleepQuietly(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String classification() {
        return this.engine.classify(ClassificationRequest.builder().withDstPort(80).build());
    }

    private long failures() {
        return this.metrics.counter("classification.reload.failures").getCount();
    }

    private long successes() {
        return this.metrics.counter("classification.reload.successes").getCount();
    }

    private int stale() {
        return gauge("classification.reload.stale");
    }

    private int dead() {
        return gauge("classification.reload.dead");
    }

    private int gauge(final String name) {
        final Gauge<?> registered = this.metrics.getGauges().get(name);
        assertThat(registered).as("%s is registered", name).isNotNull();
        return (Integer) registered.getValue();
    }

    private List<String> warnings() {
        return eventsAt(Level.WARN);
    }

    /**
     * Every line reporting a publish, at whichever level that publish was reported. Counting these rather than
     * all INFOs keeps the exactly-once rows from being satisfied by the unrelated startup and change lines.
     */
    private List<String> publishLines() {
        return this.appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("published:"))
                .toList();
    }

    private List<String> infos() {
        return eventsAt(Level.INFO);
    }

    private List<String> eventsAt(final Level level) {
        return this.appender.list.stream()
                .filter(event -> event.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static void await(final String what, final BooleanSupplier condition) throws InterruptedException {
        ClassificationRulesTestSupport.await(what, Duration.ofSeconds(10), condition);
    }
}
