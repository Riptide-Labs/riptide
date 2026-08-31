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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
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
 * fail by hanging.</p>
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
            try (var stream = new ByteArrayInputStream(this.source.read())) {
                return importer.parse(stream, true);
            } catch (final IOException e) {
                throw new UncheckedIOException("Cannot load classification rules", e);
            }
        };
        this.engine = new AsyncReloadingClassificationEngine(
                new DefaultClassificationEngine(provider, false), this.metrics);
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

        await("the new rules to serve", () -> "beta".equals(classification()));
        assertThat(successes()).as("the startup load and this one").isEqualTo(2);
        assertThat(failures()).isZero();
        assertThat(stale()).isZero();
        assertThat(infos()).as("a change that landed is not visible from a counter alone")
                .anyMatch(message -> message.contains("changed") && message.contains("reloading"));
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
        await("the fixed ruleset to serve", () -> "beta".equals(classification()));
        assertThat(stale()).isZero();
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
    @Test
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
