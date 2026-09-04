/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.clickhouse.client.api.Client;
import com.codahale.metrics.MetricRegistry;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.riptide.config.ClickhouseConfig;
import org.riptide.e2e.ContainerImages;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.schema.FlowsSchema;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;
import org.riptide.testsupport.LogCapture;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.riptide.repository.clickhouse.ClickhouseItFlows.flow;

/**
 * What happens to the rows a refused insert would have dropped (#548), against a real server.
 *
 * <p>{@code PoisonBatchProbeIT} measured the failure this feature answers: a batch carrying one row
 * the server refuses loses all of it, and the refusal is legible. This asserts the answer — the
 * batch is kept in {@code flows_dead_letter}, {@code flows} is unchanged, and the payload
 * deserialises back into the flow that was refused.
 *
 * <p><b>What this does not cover, and cannot.</b> A batch lost to a <em>severed transport</em> is
 * not rescued by any of this: the dead-letter write goes to the same server over the same client, so
 * it fails too. #663 measured that a severed transport has two shapes and neither is reachable by a
 * design that writes to ClickHouse; rescuing that case needs a local spool, which is a different
 * design. The degradation is covered here ({@link #anAbsentDeadLetterTableDegradesToTheOldBehaviour})
 * because it is the same fallback — what differs is only what caused it.
 *
 * <p>The cross-tenant read is deliberately <em>not</em> here. It needs the real
 * {@code tenant_pinned} barrier, provisioned per-tenant readers and the row policies {@code onboard}
 * creates, all of which {@code TenantOnboardingIT} already has a container configured for — see
 * {@code deadLettersAreIsolatedByTenantLikeFlows} there. Repeating that fixture would cost a
 * fifteenth ClickHouse container to assert something the provisioned path is the only honest place
 * to assert.
 *
 * <p>Two databases, because "the table is not there" is a state and not an error to simulate:
 * {@link #POISON_DB} is manage-mode provisioned and has it, {@link #UNMIGRATED_DB} is built by hand
 * with {@code flows} alone and is what every deployment provisioned before this change looks like.
 *
 * <p>{@link Timeout.ThreadMode#SEPARATE_THREAD} for the reason {@code PoisonBatchProbeIT} gives: the
 * default mode enforces its bound by interrupting the test thread and reports only once that thread
 * returns, so a wait that does not answer an interrupt would still burn the job.
 */
@Testcontainers
@Timeout(value = 3, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
public class DeadLetterIT {

    @Container
    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>(ContainerImages.clickhouse())
            .withEnv("CLICKHOUSE_DB", "riptide")
            .withEnv("CLICKHOUSE_USER", "riptide")
            .withEnv("CLICKHOUSE_PASSWORD", "riptide")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    private static final SecretResolvers RESOLVERS = SecretResolvers.defaults();

    /** Manage-mode provisioned: it has the dead-letter table because riptide created it. */
    private static final String POISON_DB = "dead_letter";

    /** Built by hand with {@code flows} alone — a deployment provisioned before #548. */
    private static final String UNMIGRATED_DB = "dead_letter_unmigrated";

    /** The tenant the synthetic barrier admits. */
    private static final String GOOD_TENANT = "ok";

    /** A tenant it refuses. One row carrying this poisons the whole insert. */
    private static final String BAD_TENANT = "rejected";

    private static Client admin;

    private Logger flusherLog;
    private ListAppender<ILoggingEvent> logEvents;
    private Level originalLevel;

    /** The repository's own log, which is where the latched "table is not present" warning goes. */
    private Logger repositoryLog;
    private ListAppender<ILoggingEvent> repositoryLogEvents;
    private Level repositoryOriginalLevel;

    /**
     * Both databases, and the synthetic barrier on each.
     *
     * <p>The constraint is {@code CHECK tenant = 'ok'} rather than the shipped
     * {@code CHECK tenant = getSetting('SQL_tenant')}, for the reason {@code PoisonBatchProbeIT}
     * states: the real one needs a {@code custom_settings_prefixes} server snippet and a
     * {@code CONST}-pinned writer per tenant. What is measured here is what riptide does with a
     * refusal, and both constraints refuse an insert identically —
     * {@code TenantWriteBarrierIT.crossTenantWriteRejectedWith469} pins that on the shipped
     * barrier, against this same server.</p>
     */
    @BeforeAll
    static void provision() throws Exception {
        admin = new Client.Builder().addEndpoint(endpoint())
                .setUsername("riptide").setPassword("riptide").build();

        // Manage mode creates the whole schema, dead-letter table included. Started and stopped
        // here rather than kept: each test builds its own repository in validate mode, so the DDL
        // runs exactly once and no test's start() can quietly repair what another broke.
        final var managed = repositoryOn(POISON_DB, true);
        managed.start();
        managed.stop();
        barrier(POISON_DB);

        // And the un-migrated deployment, assembled from the two statements riptide shipped before
        // the dead-letter table existed. Deliberately NOT manage mode, which would create it.
        admin.execute(FlowsSchema.createDatabase(UNMIGRATED_DB)).get();
        admin.execute(FlowsSchema.createFlowsTable(UNMIGRATED_DB)).get();
        barrier(UNMIGRATED_DB);
    }

    private static void barrier(final String database) throws Exception {
        admin.execute("ALTER TABLE " + FlowsSchema.qualifiedFlows(database)
                + " ADD CONSTRAINT IF NOT EXISTS probe_tenant CHECK tenant = '" + GOOD_TENANT + "'").get();
    }

    @AfterEach
    void detachLog() {
        if (this.flusherLog != null) {
            if (this.logEvents != null) {
                this.flusherLog.detachAppender(this.logEvents);
                this.logEvents.stop();
            }
            this.flusherLog.setLevel(this.originalLevel);
        }
        if (this.repositoryLog != null) {
            if (this.repositoryLogEvents != null) {
                this.repositoryLog.detachAppender(this.repositoryLogEvents);
                this.repositoryLogEvents.stop();
            }
            this.repositoryLog.setLevel(this.repositoryOriginalLevel);
        }
    }

    /**
     * The acceptance criterion: every row of a refused batch is readable from the dead-letter table
     * afterwards, and {@code flows} is unchanged.
     *
     * <p>Both halves are asserted as deltas against the same snapshot, because the database is
     * shared with the other tests in this class and a bare count would be a claim about the suite's
     * history rather than about this batch.</p>
     */
    @Test
    void aRefusedBatchIsReadableFromTheDeadLetterTableAndFlowsIsUnchanged() throws Exception {
        final long flowsBefore = count(FlowsSchema.qualifiedFlows(POISON_DB));
        final long deadBefore = count(FlowsSchema.qualifiedDeadLetter(POISON_DB));
        final var registry = new MetricRegistry();

        final List<EnrichedFlow> batch = poisonedBatch(4, 2);
        flushThrough(POISON_DB, registry, batch);

        Assertions.assertThat(count(FlowsSchema.qualifiedFlows(POISON_DB)))
                .as("a refused batch must leave flows exactly as it found it")
                .isEqualTo(flowsBefore);
        Assertions.assertThat(count(FlowsSchema.qualifiedDeadLetter(POISON_DB)) - deadBefore)
                .as("every row of the batch is kept, not just the one the server named")
                .isEqualTo(batch.size());
        Assertions.assertThat(counter(registry, "deadLetteredRows")).isEqualTo(batch.size());
        Assertions.assertThat(counter(registry, "failedRows"))
                .as("a dead-lettered row still did not reach flows")
                .isEqualTo(batch.size());
        Assertions.assertThat(counter(registry, "deadLetterFailedRows")).isZero();
    }

    /**
     * The stored payload deserialises to a flow equal to the one that was dropped.
     *
     * <p>Read back off the server, not out of the byte array the codec produced: {@code
     * DeadLetterPayloadTest} already pins the round trip in memory, and what this adds is the whole
     * path through {@code JSONEachRow}, the client's encoding, and a {@code String} column — where a
     * payload can be truncated, re-escaped, or have its own quoting eaten.</p>
     */
    @Test
    void theStoredPayloadDeserialisesToTheFlowThatWasRefused() throws Exception {
        final var registry = new MetricRegistry();
        final EnrichedFlow refused = flow(BAD_TENANT, "org", 21001);
        final Instant before = Instant.now().minusSeconds(5);

        flushThrough(POISON_DB, registry, List.of(refused));

        final Map<String, String> row = deadLetter(POISON_DB, BAD_TENANT, 21001);
        Assertions.assertThat(DeadLetterPayload.deserialise(row.get("payload")))
                .as("a dead letter that cannot be read back off the server is a slower drop")
                .isEqualTo(refused);
        Assertions.assertThat(row.get("error"))
                .as("the operator's only record of why the server would not take the rows")
                .contains("VIOLATED_CONSTRAINT");
        Assertions.assertThat(Instant.parse(row.get("failedAt").replace(' ', 'T') + "Z"))
                .as("failedAt is written in the server's own basic format against a UTC clock")
                .isAfter(before);
    }

    /**
     * A batch spanning tenants produces one dead letter per flow, each carrying its own tenant.
     *
     * <p>This is why the design is one row per flow rather than one per batch: the flusher drains a
     * single queue across every exporter, so a per-batch row could carry no single correct
     * {@code tenant} — and a row policy filters rows by exactly that column.</p>
     */
    @Test
    void aBatchSpanningTenantsGivesEachRowItsOwnTenant() throws Exception {
        final var registry = new MetricRegistry();
        final List<EnrichedFlow> batch = List.of(
                flow("tenant_a", "org", 22001),
                flow("tenant_b", "org", 22002),
                flow("tenant_a", "org", 22003));

        flushThrough(POISON_DB, registry, batch);

        Assertions.assertThat(tenantsOfPorts(POISON_DB, 22001, 22003))
                .as("each dead letter carries the tenant of the flow it was cut from, not the"
                        + " batch's or the writer's")
                .containsExactly("tenant_a", "tenant_b", "tenant_a");
    }

    /**
     * A deployment whose dead-letter table does not exist keeps collecting, counts the rows exactly
     * as it did before #548, and says why once.
     *
     * <p>The acceptance criterion this answers is the one that protects every existing deployment:
     * the table arrives with a re-onboard, and until then a refused batch must cost what it always
     * cost and nothing more.</p>
     */
    @Test
    void anAbsentDeadLetterTableDegradesToTheOldBehaviour() throws Exception {
        captureFlusherLog();
        final var registry = new MetricRegistry();
        final List<EnrichedFlow> batch = poisonedBatch(3, 1);
        final long before = count(FlowsSchema.qualifiedFlows(UNMIGRATED_DB));

        try (var flusher = startedFlusher(UNMIGRATED_DB, registry)) {
            flusher.offer(batch, () -> counter(registry, "deadLetterFailedRows") > 0);

            Assertions.assertThat(counter(registry, "failedRows"))
                    .as("exactly today's behaviour: the batch is charged in full")
                    .isEqualTo(batch.size());
            Assertions.assertThat(counter(registry, "deadLetterFailedRows")).isEqualTo(batch.size());
            Assertions.assertThat(counter(registry, "deadLetteredRows")).isZero();
            Assertions.assertThat(this.logEvents.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .as("the cause is logged once, naming the batch — not once per row")
                    .filteredOn(message -> message.contains("Could not keep the " + batch.size() + " flows"))
                    .hasSize(1);

            // A SECOND refused batch degrades identically — and is not re-asked of the server.
            // The absence is latched after the first UNKNOWN_TABLE, so an un-migrated deployment
            // does not spend a round trip per refused batch being told what it was told last time.
            // The latch is observable here as the repository reporting it exactly once.
            flusher.offer(poisonedBatch(2, 1), () -> counter(registry, "deadLetterFailedRows") > 3);
            Assertions.assertThat(counter(registry, "deadLetterFailedRows"))
                    .as("the second refused batch still degrades, so the latch skips the round trip"
                            + " and not the accounting")
                    .isEqualTo(batch.size() + 2);
            Assertions.assertThat(this.repositoryLogEvents.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .filteredOn(message -> message.contains("is not present in database"))
                    .as("reported once, not once per refused batch, for the whole life of the process")
                    .hasSize(1);

            // THE SAME flusher, still running. Building a second repository here would have proved
            // only that the database still accepts writes — which no failure in this test could have
            // stopped — instead of that the flusher survived the failed dead-letter write.
            flusher.offer(List.of(flow(GOOD_TENANT, "org", 23001)),
                    () -> countQuietly(FlowsSchema.qualifiedFlows(UNMIGRATED_DB)) == before + 1);
        }
        Assertions.assertThat(count(FlowsSchema.qualifiedFlows(UNMIGRATED_DB))).isEqualTo(before + 1);
    }

    /**
     * The flusher keeps running after a dead-letter write that <em>succeeded</em>, too.
     *
     * <p>Its sibling above covers the failure arm. This one covers the success arm, which no test at
     * either tier reached: the flusher returns from {@code deadLetterOrCount} through a different
     * path, and "the batch was kept" would be a poor thing to be true of the last batch a collector
     * ever flushed.</p>
     */
    @Test
    void theFlusherKeepsRunningAfterADeadLetterThatSucceeded() throws Exception {
        final var registry = new MetricRegistry();
        final List<EnrichedFlow> poisoned = poisonedBatch(2, 1);
        final long healthyBefore = count(FlowsSchema.qualifiedFlows(POISON_DB));

        try (var flusher = startedFlusher(POISON_DB, registry)) {
            flusher.offer(poisoned, () -> counter(registry, "deadLetteredRows") >= poisoned.size());
            flusher.offer(List.of(flow(GOOD_TENANT, "org", 26001)),
                    () -> countQuietly(FlowsSchema.qualifiedFlows(POISON_DB)) == healthyBefore + 1);
        }

        Assertions.assertThat(counter(registry, "deadLetteredRows")).isEqualTo(poisoned.size());
        Assertions.assertThat(count(FlowsSchema.qualifiedFlows(POISON_DB)))
                .as("the batch after a successful dead letter still lands")
                .isEqualTo(healthyBefore + 1);
    }

    /** A batch the server accepts writes nothing to the dead-letter table. */
    @Test
    void aSuccessfulInsertLeavesTheDeadLetterTableAlone() throws Exception {
        final long deadBefore = count(FlowsSchema.qualifiedDeadLetter(POISON_DB));
        final var registry = new MetricRegistry();
        final long flowsBefore = count(FlowsSchema.qualifiedFlows(POISON_DB));

        try (var flusher = startedFlusher(POISON_DB, registry)) {
            flusher.offer(List.of(flow(GOOD_TENANT, "org", 24001)),
                    () -> countQuietly(FlowsSchema.qualifiedFlows(POISON_DB)) == flowsBefore + 1);
        }

        Assertions.assertThat(count(FlowsSchema.qualifiedDeadLetter(POISON_DB)))
                .as("nothing failed, so nothing is kept — otherwise the table would fill with"
                        + " rows that are also in flows and the counter would mean nothing")
                .isEqualTo(deadBefore);
        Assertions.assertThat(counter(registry, "deadLetteredRows")).isZero();
        Assertions.assertThat(counter(registry, "failedRows")).isZero();
    }

    /**
     * Queue a batch, start the flusher, and wait for it to be done with it.
     *
     * <p>Through {@code BatchingFlowRepository} rather than by calling {@code deadLetter} directly,
     * because the acceptance criterion is about what happens <em>when the flusher runs</em>: the
     * catch clause, the counters and the fallback are all in that class, and a test that called the
     * repository's method would assert none of them.</p>
     *
     * <p>The rows are offered before {@code start()} so the flusher finds them waiting and drains
     * them as one batch — the same deterministic arrangement {@code BatchingFlowRepositoryTest} uses,
     * and the only way to be sure the batch under test is a batch rather than two.</p>
     */
    private void flushThrough(final String database, final MetricRegistry registry,
            final List<EnrichedFlow> batch) throws Exception {
        try (var flusher = startedFlusher(database, registry)) {
            flusher.offer(batch, () -> counter(registry, "deadLetteredRows") >= batch.size());
        }
    }

    /**
     * A started {@code BatchingFlowRepository} that can be handed more than one batch.
     *
     * <p>Held across batches on purpose. The no-wedge property is about <em>this</em> flusher
     * surviving, and a helper that built a fresh repository per batch would re-test that the
     * database still accepts writes — which nothing in these tests could have broken — while saying
     * nothing about the thread that had just failed.</p>
     *
     * <p>{@code maxLatency} rather than {@code maxRows} is what triggers each flush, since the
     * batches differ in size; it is short enough that a batch drains promptly and long enough that
     * two offers made in sequence are not merged into one.</p>
     */
    private Flusher startedFlusher(final String database, final MetricRegistry registry) {
        final var config = new ClickhouseConfig.BatchConfig();
        config.setMaxRows(10_000);
        config.setMaxLatency(Duration.ofMillis(200));
        config.setQueueCapacity(1_000);
        config.setShutdownGracePeriod(Duration.ofSeconds(2));
        final var repository = new BatchingFlowRepository(repositoryOn(database, false), config, registry);
        repository.start();
        return new Flusher(repository);
    }

    /** One live flusher, offering batches to it and waiting for each to be done. */
    private record Flusher(BatchingFlowRepository repository) implements AutoCloseable {

        void offer(final List<EnrichedFlow> batch, final BooleanSupplier done) throws Exception {
            this.repository.persist(batch);
            await("the flusher finished with a batch of " + batch.size(), done);
            // The queue is server-side and this config sends async_insert=0, but a measurement that
            // depended on that staying true would read a buffered insert as an absent one — the same
            // guard PoisonBatchProbeIT and ClickhouseRepositoryIT make.
            admin.execute("SYSTEM FLUSH ASYNC INSERT QUEUE").get();
        }

        @Override
        public void close() {
            this.repository.stop();
        }
    }

    /** A batch of {@code size} flows with the poison row at {@code position} (1-based). */
    private static List<EnrichedFlow> poisonedBatch(final int size, final int position) throws Exception {
        Assertions.assertThat(position)
                .as("the poison row must sit inside the batch, or this builds a clean one and every"
                        + " assertion about a refusal reports the wrong cause")
                .isBetween(1, size);
        final List<EnrichedFlow> batch = new ArrayList<>();
        for (int i = 1; i <= size; i++) {
            batch.add(flow(i == position ? BAD_TENANT : GOOD_TENANT, "org", 25000 + i));
        }
        return batch;
    }

    private static ClickhouseRepository repositoryOn(final String database, final boolean manage) {
        final var config = new ClickhouseConfig();
        config.setEndpoint(endpoint());
        config.setUsername(SecretRef.of("riptide"));
        config.setPassword(SecretRef.of("riptide"));
        config.setDatabase(database);
        config.setManageSchema(manage);
        // The direct path, like PoisonBatchProbeIT: a coalesced insert acknowledges on buffer
        // append, so a refusal would arrive after persist() returned and the flusher would never
        // see it — there would be nothing to dead-letter.
        config.setAsyncInserts(false);
        return new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config, RESOLVERS);
    }

    /** One dead letter, found by the source port its payload carries. */
    private static Map<String, String> deadLetter(final String database, final String tenant,
            final int srcPort) throws Exception {
        final Map<String, String> row = new LinkedHashMap<>();
        final String sql = "SELECT tenant, toString(failedAt) AS failedAt, error, payload FROM "
                + FlowsSchema.qualifiedDeadLetter(database)
                + " WHERE tenant = '" + tenant + "' AND JSONExtractInt(payload, 'srcPort') = " + srcPort;
        try (var rows = admin.queryRecords(sql).get()) {
            rows.forEach(record -> {
                row.put("tenant", record.getString("tenant"));
                row.put("failedAt", record.getString("failedAt"));
                row.put("error", record.getString("error"));
                row.put("payload", record.getString("payload"));
            });
        }
        Assertions.assertThat(row)
                .as("no dead letter found for tenant '%s' port %d, so this test measured nothing: %s",
                        tenant, srcPort, sql)
                .isNotEmpty();
        return row;
    }

    /**
     * The tenant of each dead letter in a source-port range, in payload order.
     *
     * <p>Ordered by the port inside the payload rather than by the table's own key, because the key
     * is {@code (tenant, failedAt)} and every row of one batch shares both — so the table's order
     * says nothing about which flow a row came from, which is exactly the pairing under test.</p>
     */
    private static List<String> tenantsOfPorts(final String database, final int from, final int to)
            throws Exception {
        final List<String> tenants = new ArrayList<>();
        try (var rows = admin.queryRecords("SELECT tenant, JSONExtractInt(payload, 'srcPort') AS p FROM "
                + FlowsSchema.qualifiedDeadLetter(database)
                + " WHERE p BETWEEN " + from + " AND " + to + " ORDER BY p").get()) {
            rows.forEach(record -> tenants.add(record.getString("tenant")));
        }
        return tenants;
    }

    private static long count(final String table) throws Exception {
        try (var rows = admin.queryRecords("SELECT count() AS v FROM " + table).get()) {
            for (final var row : rows) {
                return row.getLong("v");
            }
        }
        throw new AssertionError("count() returned no record for " + table);
    }

    /** {@link #count} for a polling predicate, where a transient read failure is not a verdict. */
    private static long countQuietly(final String table) {
        try {
            return count(table);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } catch (final Exception e) {
            return -1;
        }
    }

    private static long counter(final MetricRegistry registry, final String name) {
        return registry.counter(MetricRegistry.name("persister", "batch", name)).getCount();
    }

    private void captureFlusherLog() {
        this.flusherLog = (Logger) LoggerFactory.getLogger(BatchingFlowRepository.class);
        this.originalLevel = this.flusherLog.getLevel();
        this.flusherLog.setLevel(Level.TRACE);
        this.logEvents = LogCapture.startedAppender();
        this.flusherLog.addAppender(this.logEvents);

        this.repositoryLog = (Logger) LoggerFactory.getLogger(ClickhouseRepository.class);
        this.repositoryOriginalLevel = this.repositoryLog.getLevel();
        this.repositoryLog.setLevel(Level.TRACE);
        this.repositoryLogEvents = LogCapture.startedAppender();
        this.repositoryLog.addAppender(this.repositoryLogEvents);
    }

    /**
     * Poll until the condition holds, failing with what was waited for.
     *
     * <p>A deadline rather than the progress-based wait the e2e tier uses: that helper is
     * package-private to {@code org.riptide.e2e}, and what is waited on here is a flusher on the
     * same host answering in milliseconds, not an ingest whose slowness is the thing being told
     * apart from a stall.</p>
     */
    private static void await(final String description, final BooleanSupplier condition)
            throws InterruptedException {
        final Instant deadline = Instant.now().plus(AWAIT_BUDGET);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        Assertions.fail("Timed out after %s waiting for %s".formatted(AWAIT_BUDGET, description));
    }

    /** Generous against a loaded runner, and well inside the class {@link Timeout}. */
    private static final Duration AWAIT_BUDGET = Duration.ofSeconds(30);

    private static String endpoint() {
        return "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123);
    }
}
