/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.ConnectionInitiationException;
import com.clickhouse.client.api.DataTransferException;
import com.clickhouse.client.api.ServerException;
import com.google.common.base.Throwables;
import org.assertj.core.api.Assertions;
import org.assertj.core.description.LazyTextDescription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.riptide.config.ClickhouseConfig;
import org.riptide.e2e.ContainerImages;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.schema.FlowsSchema;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.catchThrowable;
import static org.riptide.repository.clickhouse.ClickhouseItFlows.flow;

/**
 * What one rejected row does to the batch around it, and how that failure differs from a lost
 * transport (#548).
 *
 * <p>#548 must choose between bisecting a poisoned batch and dead-lettering it, and both rest on
 * facts nobody had measured: whether a refused insert can leave a partial write — in which case
 * re-inserting a bisected half double-counts (PQ-5) — and whether a rejected row is distinguishable
 * from a dropped connection, which {@code BatchingFlowRepository.flush} treats identically
 * today (PQ-6).</p>
 *
 * <p>Both questions are asked through {@code ClickhouseRepository.persist}, not raw SQL: the
 * question is what riptide's own path does, and a probe issuing its own INSERT would measure a path
 * production never takes.</p>
 *
 * <p><b>What this is not.</b> A regression test against the server pinned in
 * {@code .github/e2e-images/clickhouse.Dockerfile}, not a proof. It says the batch behaves this way
 * on the ClickHouse it runs against, which is what makes a version bump surface a change rather than
 * let #548's design rest on a stale measurement. It does not implement #548, and it says nothing
 * about whether riptide can map a reported row index back to its own batch.</p>
 *
 * <p><b>And PQ-5's answer is for a single-block insert only.</b> {@code ClickhouseRepository} states
 * the risk it was written to settle as: a refused insert is atomic on the buffered path but "can
 * leave whole blocks committed on a direct one <em>once a batch exceeds
 * {@code max_insert_block_size}</em>". {@link #BATCH_SIZE} is six, six orders of magnitude below the
 * server default, so this probe never crosses that boundary — and {@code max_insert_block_size}
 * appears exactly once in the whole repository, in that comment: nothing sets it, lowers it, or
 * builds a batch that spans two blocks. So "a refused batch leaves no row anywhere" is measured
 * where a partial write is impossible by construction, and is <em>not</em> an answer for the 10,000-row
 * batches {@code BatchingFlowRepository} actually flushes ({@code ClickhouseConfig.maxRows}). #548
 * must not read this as a general atomicity guarantee.</p>
 *
 * <p><b>What the class-level {@link Timeout} covers, and what it does not.</b> This fixture severs a
 * live transport on purpose, and {@code persist} waits on a client this issue must not change, so
 * the class bound is the backstop for a wait that never answers. Note what the severing itself does
 * <em>not</em> produce: {@link Relay} closes its sockets, which sends FIN, so the client is refused
 * or reset promptly rather than left hanging — see that class's javadoc. The open-and-unanswered
 * socket, the state that actually makes an unbounded wait wait forever, is what pausing the
 * container would give, and this fixture deliberately does not do that. Every statement the fixture
 * issues itself is separately bounded (see {@link #awaiting}, and note what that bound required).
 * {@link Timeout.ThreadMode#SEPARATE_THREAD} on purpose: the default mode enforces the bound by
 * interrupting the test thread and only reports once that thread returns, so a wait that does not
 * answer an interrupt would still burn the job. Two limits, both real:
 * <ul>
 *   <li>It does not cover starting the container. {@code @Timeout} applies to test and lifecycle
 *       methods; the {@code @Container} field is started by the Testcontainers extension's own
 *       callback, outside both. A container that never becomes healthy is bounded by
 *       {@link Wait#forHttp} below (60s), not by this.</li>
 *   <li>{@code SEPARATE_THREAD} reports the timeout without killing the thread that overran it. The
 *       wedged wait — and the sockets under it — survives to the end of the JVM. What the mode buys
 *       is a <em>result</em> instead of a cancelled job, not a cleaned-up test.</li>
 * </ul>
 * <p>The precedent for a class-level bound in this package is {@code ViewProbePolicyTest}; this is
 * the first {@code *IT} to carry one.</p>
 *
 * <p><b>Cost.</b> Extracting this from {@code ClickhouseRepositoryIT} added a twelfth ClickHouse
 * container to the IT tier — twelve being the number of {@code ContainerImages.clickhouse()} call
 * sites under {@code src/test/java}, this one included, so a thirteenth arriving falsifies the
 * sentence. Observed once at 5.2s to start on the pinned image with the layer already cached: one
 * observation on one machine, not a fleet figure, and a cold pull or a loaded runner moves it.
 * That is job wall-clock and nothing else: {@code E2eTestSupport.SUITE_BUDGET} bounds the sum of
 * {@code awaitCount} waits, and its counter is advanced in exactly one place — inside
 * {@code awaitCount} itself — so a container start is not charged against it.</p>
 */
@Testcontainers
@Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
public class PoisonBatchProbeIT {

    @Container
    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>(ContainerImages.clickhouse())
            .withEnv("CLICKHOUSE_DB", "riptide")
            .withEnv("CLICKHOUSE_USER", "riptide")
            .withEnv("CLICKHOUSE_PASSWORD", "riptide")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    private static final SecretResolvers RESOLVERS = SecretResolvers.defaults();

    /**
     * The bound on every wait this fixture makes itself.
     *
     * <p>Generous against a cold container and a slow runner, finite against a socket that is open
     * and never answers — the state the transient test builds on purpose. Well inside the class
     * {@link Timeout}, so a hung query fails on its own before the class bound fires.</p>
     */
    private static final long QUERY_TIMEOUT_SECONDS = 30;

    /**
     * The class owns its own container, so this database name only has to be stable, not unique.
     * It stays named for what it holds because both tests here share it and both provision against
     * it, and a reader tracing a leftover constraint should land on the probe that added it.
     */
    private static final String POISON_DB = "poison_probe";

    /** The tenant the probe's CHECK constraint admits. */
    private static final String GOOD_TENANT = "ok";

    /** A tenant the constraint refuses — one row of a batch carrying this poisons the whole insert. */
    private static final String BAD_TENANT = "rejected";

    /** Where the poison row sits, 1-based, so the reported index can be checked rather than guessed. */
    private static final int POISON_POSITION = 5;
    private static final int BATCH_SIZE = 6;

    /**
     * Wait for one statement's answer, bounded, naming the statement if the bound is reached.
     *
     * <p>{@link CompletableFuture#get(long, TimeUnit)} throws a bare {@link TimeoutException} whose
     * message is null: unwrapped, a hung query reports as a stack trace carrying no SQL, no table,
     * and no indication that a query was involved at all. Naming what timed out is what makes the
     * bound worth reading.</p>
     *
     * <p><b>This bound only exists because the probe's client is built with
     * {@code useAsyncRequests(true)}.</b> {@code Client.runAsyncOperation} reads
     * {@code ClientConfigProperties.ASYNC_OPERATIONS}, which defaults to {@code "false"}, and on
     * that default it calls the supplier <em>on the calling thread</em> and hands back
     * {@code CompletableFuture.completedFuture(...)}. Waiting on an already-completed future bounds
     * nothing — the blocking has already happened inside {@code execute}/{@code queryRecords} before
     * this method is reached. Measured: with the bound set to zero seconds and the default client,
     * the probe still passed; with the async client it fails naming the statement. So the flag is
     * what makes every claim about a bounded wait in this class true.</p>
     *
     * <p>What it does not do: the wait moves to a pool thread, which stays blocked after this method
     * gives up — the same limit the class {@link Timeout} has. And it covers only the statements
     * <em>this fixture</em> issues. {@code ClickhouseRepository.persist} waits on its own client,
     * which this issue must not change, so that wait is unbounded and the class {@link Timeout} is
     * its only backstop.</p>
     */
    private static <T> T awaiting(final String statement, final CompletableFuture<T> future) throws Exception {
        try {
            return future.get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (final TimeoutException e) {
            throw new AssertionError(
                    "no answer within " + QUERY_TIMEOUT_SECONDS + "s to: " + statement, e);
        }
    }

    private static ClickhouseConfig configFor(final String database) {
        final var config = new ClickhouseConfig();
        config.setEndpoint("http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123));
        config.setUsername(SecretRef.of("riptide"));
        config.setPassword(SecretRef.of("riptide"));
        config.setDatabase(database);
        config.setManageSchema(true);
        // The direct path on purpose: ClickhouseRepository documents a refused insert as atomic on
        // the coalesced path but able to leave whole blocks committed on a direct one, so the
        // direct path is the one PQ-5 has to be measured on. Off really is off, pinned by
        // ClickhouseRepositoryIT.turningAsyncInsertsOffSendsTheSettingRatherThanStayingSilent (#664).
        config.setAsyncInserts(false);
        return config;
    }

    /** A started repository against {@link #POISON_DB} and a client to measure its rows with. */
    private record PoisonProbe(ClickhouseRepository repository, Client client) implements AutoCloseable {
        @Override
        public void close() {
            // try/finally, or a throwing stop() would strand the client — the same one-failure-
            // strands-the-rest bug this change fixes in Relay.close().
            //
            // What this does NOT release: ClickhouseRepository does not override
            // FlowRepository.stop(), so the first call is a no-op and the HTTP connection pool the
            // repository built in start() is never closed. Each test here therefore leaks one pool
            // for the life of the JVM. That is a bigger leak than the sockets this change fixes,
            // and it is left alone deliberately: closing it means changing production code, which
            // is #548's business, not this fixture's. The call below is the fixture's side of the
            // lifecycle so that it becomes correct the day stop() does something.
            try {
                this.repository.stop();
            } finally {
                this.client.close();
            }
        }
    }

    /**
     * A started repository over {@link #POISON_DB} whose {@code flows} table refuses a tenant.
     *
     * <p>The constraint is synthetic — {@code CHECK tenant = 'ok'} — where the shipped barrier is
     * {@code CHECK tenant = getSetting('SQL_tenant')} ({@link TenantWriteBarrierIT}). Reusing the
     * real one is not free: it needs a {@code custom_settings_prefixes: SQL_} server-config snippet
     * mounted into the container and a per-tenant {@code CONST}-pinned writer provisioned per test,
     * neither of which this container has. What this probe measures is what the server does to the
     * batch around a refused row, and that is a property of the refusal itself: both constraints
     * fail the insert with the same code and name the offending row the same way. The second half of
     * that claim is not an assumption — {@code TenantWriteBarrierIT.crossTenantWriteRejectedWith469}
     * pins {@code "is violated at row 1."} on the shipped barrier, against the same server this
     * probe asserts a row index on.</p>
     *
     * <p>{@code IF NOT EXISTS} so provisioning is idempotent. Only the rejected-row test calls this
     * today, so nothing currently runs it twice; the guard costs one clause and removes the standing
     * rule that exactly one test in this class may provision — which is the kind of rule a second
     * probe added later discovers by failing on a duplicate constraint.</p>
     */
    private static PoisonProbe provisionPoisonProbe() throws Exception {
        final var config = configFor(POISON_DB);
        final var repository = new ClickhouseRepository(
                new ClickhouseRepository$FlowMapperImpl(), config, RESOLVERS);
        Client client = null;
        try {
            repository.start();
            client = new Client.Builder().addEndpoint(config.getEndpoint())
                    .setUsername("riptide").setPassword("riptide").setDefaultDatabase(POISON_DB)
                    // Load-bearing, not a preference: see awaiting(). Without it every future this
                    // fixture waits on is already complete and its bound can never fire.
                    .useAsyncRequests(true)
                    .build();
            final String alter = "ALTER TABLE " + FlowsSchema.qualifiedFlows(POISON_DB)
                    + " ADD CONSTRAINT IF NOT EXISTS probe_tenant CHECK tenant = '" + GOOD_TENANT + "'";
            awaiting(alter, client.execute(alter));
            return new PoisonProbe(repository, client);
        } catch (final AssertionError | Exception e) {
            // The guard starts at start(), not at the ALTER. Any of the three steps failing returns
            // no PoisonProbe, so the caller's try-with-resources never runs and whatever was already
            // opened leaks — in the one fixture whose subject is leaks. AssertionError is named
            // because awaiting() reports a breached bound by throwing one, and an Error is not an
            // Exception, so the timeout path is exactly what a bare catch here would miss.
            if (client != null) {
                client.close();
            }
            repository.stop();
            throw e;
        }
    }

    /**
     * How much has landed in the probe's base table and in every rollup behind it, keyed by table.
     *
     * <p>Two different measures, because the two engines fail a naive count differently:</p>
     * <ul>
     *   <li>The base table is a plain {@code MergeTree}. Its merges never collapse rows, so
     *       {@code count()} already cannot be moved by one — and {@code FINAL} is refused there
     *       outright (code 181, {@code ILLEGAL_FINAL}).</li>
     *   <li>The rollup targets are {@code SummingMergeTree}, where {@code count()} is not stable:
     *       two rows sharing a sorting key count as two until the merge runs and as one afterwards.
     *       {@code sum(flowCount)} is: summing the measure is exactly what the engine does on merge,
     *       so the value is the same before and after, and it moves by one per landed row.</li>
     * </ul>
     *
     * <p><b>Why not {@code count() FINAL} on the rollups.</b> It is merge-invariant too, and it is
     * blind to the one thing PQ-5 exists to catch. Every rollup's sorting key is
     * {@code tenant, organisation, toStartOfMinute(timestamp), zone} plus that rollup's dimensions.
     * {@code srcPort} is the only field this probe varies <em>deliberately</em>, and it is in none of
     * them. The one key field that moves on its own is the timestamp: {@code ClickhouseItFlows.flow}
     * stamps {@code Instant.now()}, so the rows this fixture writes share a group only while they
     * land inside the same minute — which they do, the test running in seconds, except across a
     * minute boundary. Within that group {@code count() FINAL} reads 1
     * whether none or all five of the poisoned batch's healthy rows landed. Measured on the pinned
     * image: three same-key inserts give {@code count()} = 3 and {@code count() FINAL} = 1. The
     * rollup half is PQ-5's only unique detection power, since the base count already covers a
     * base-table landing, so a rollup measure that cannot move is the whole assertion gone.</p>
     *
     * <p><b>The limit of the summed measure.</b> {@code sum(flowCount)} sees a landed row only
     * because the rollup views set {@code flowCount = count()}, one per raw row. It would not see a
     * partial write that landed rows carrying {@code flowCount = 0}, which no view this schema
     * creates can produce. And the base table's plain {@code count()} is merge-invariant only while
     * that table stays a non-collapsing engine; a future {@code ReplacingMergeTree} there would need
     * the same treatment as the rollups.</p>
     *
     * <p>The async-insert queue is flushed first, as
     * {@code ClickhouseRepositoryIT.asyncInsertsOptInStillFeedsTheRollups} does: this probe's config
     * sends {@code async_insert=0}, but a measurement that depends on that staying true would report
     * a buffered insert as an absent one.</p>
     */
    private static Map<String, Long> poisonRowMeasures(final Client client) throws Exception {
        // Named, not read back from the schema: a list sized against itself can only agree with
        // itself, and a rollup dropped from the schema would then drop out of "nothing landed
        // anywhere" without a word.
        Assertions.assertThat(FlowsSchema.rollupTableNames())
                .as("the probe must measure the base table AND every rollup, or 'nothing landed' is a"
                        + " statement about one table while the others go unchecked")
                .containsExactlyInAnyOrder(FlowsSchema.ROLLUP_BY_APPLICATION,
                        FlowsSchema.ROLLUP_BY_CONVERSATION, FlowsSchema.ROLLUP_BY_EXPORTER_IFACE,
                        FlowsSchema.ROLLUP_BY_GEO_ASN);
        final Map<String, String> queries = new LinkedHashMap<>();
        queries.put(FlowsSchema.qualifiedFlows(POISON_DB),
                "SELECT count() AS v FROM " + FlowsSchema.qualifiedFlows(POISON_DB));
        FlowsSchema.rollupTableNames().forEach(rollup -> {
            final String table = FlowsSchema.qualifiedRollup(POISON_DB, rollup);
            queries.put(table, "SELECT sum(flowCount) AS v FROM " + table);
        });

        awaiting("SYSTEM FLUSH ASYNC INSERT QUEUE", client.execute("SYSTEM FLUSH ASYNC INSERT QUEUE"));

        final Map<String, Long> measures = new LinkedHashMap<>();
        for (final Map.Entry<String, String> query : queries.entrySet()) {
            try (var rows = awaiting(query.getValue(), client.queryRecords(query.getValue()))) {
                for (final var row : rows) {
                    measures.put(query.getKey(), row.getLong("v"));
                }
            }
            // An empty result would otherwise leave the key absent and NPE on the caller's
            // unboxing, reporting a fixture fault as a null pointer somewhere else entirely.
            Assertions.assertThat(measures)
                    .as("%s returned no record, so this snapshot measured nothing", query.getValue())
                    .containsKey(query.getKey());
        }
        return measures;
    }

    private static List<EnrichedFlow> batchWithPoisonAt(final int position) throws Exception {
        // The fixture's own consistency, asserted before the server is asked anything: a position
        // outside the batch builds a batch with no poison in it, and every assertion about a
        // refusal would then report the wrong cause. Its caller builds the batch outside its
        // catchThrowable, so this error fails the test as itself rather than as a cause nested
        // under "the refusal carries no ServerException".
        Assertions.assertThat(position)
                .as("the poison row must sit inside the batch (1..%d), or this builds a clean batch"
                        + " and every assertion about a refusal reports the wrong cause", BATCH_SIZE)
                .isBetween(1, BATCH_SIZE);
        final List<EnrichedFlow> batch = new ArrayList<>();
        for (int i = 1; i <= BATCH_SIZE; i++) {
            batch.add(flow(i == position ? BAD_TENANT : GOOD_TENANT, "org", 20000 + i));
        }
        return batch;
    }

    /**
     * What one rejected row does to the batch around it, and whether the refusal is legible (#548).
     */
    @Test
    void aRejectedRowTakesTheWholeBatchWithItAndSaysWhichRowItWas() throws Exception {
        try (var probe = provisionPoisonProbe()) {
            final String flows = FlowsSchema.qualifiedFlows(POISON_DB);

            // A clean row first: if the constraint refused everything, every "nothing landed"
            // assertion below would pass while proving nothing at all. Measured as a delta, because
            // the transport probe shares this database and may have landed its own row first.
            final Map<String, Long> before = poisonRowMeasures(probe.client());
            probe.repository().persist(List.of(flow(GOOD_TENANT, "org", 19999)));
            final Map<String, Long> afterHealthy = poisonRowMeasures(probe.client());

            Assertions.assertThat(afterHealthy.get(flows))
                    .as("a healthy row must land, or the fixture rejects everything and asserts nothing")
                    .isEqualTo(before.get(flows) + 1);
            // And every rollup behind it must be alive. Without this, four absent, ungranted or
            // mis-shaped materialized views read 0 in both snapshots and PQ-5 passes over four dead
            // tables — the same failure the rollupTableNames() name-guard prevents, one layer down:
            // that guard pins which tables are measured, this one pins that measuring them means
            // something.
            for (final String rollup : FlowsSchema.rollupTableNames()) {
                final String table = FlowsSchema.qualifiedRollup(POISON_DB, rollup);
                Assertions.assertThat(afterHealthy.get(table))
                        .as("%s must aggregate the healthy row, or PQ-5 below compares a dead table"
                                + " with itself and passes on a rollup nothing can reach", table)
                        .isEqualTo(before.get(table) + 1);
            }

            // Built outside catchThrowable on purpose: the fixture guard inside it throws an
            // AssertionError, and caught here it would be reported as "the refusal carries no
            // ServerException" — the wrong cause, with the real one buried in the message.
            final List<EnrichedFlow> poisoned = batchWithPoisonAt(POISON_POSITION);
            final Throwable refusal = catchThrowable(() -> probe.repository().persist(poisoned));

            Assertions.assertThat(refusal)
                    .as("a batch carrying a constraint-violating row must be refused, not silently kept")
                    .isNotNull();
            final ServerException server = serverExceptionIn(refusal);
            Assertions.assertThat(server)
                    .as("the refusal must carry a ServerException, or no error code is readable and this"
                            + " probe settles nothing: %s", refusal)
                    .isNotNull();

            // PQ-5: atomic across the base table and every rollup behind it. The version is read
            // lazily — a .as(String, Object...) argument is evaluated on every run, passing or not,
            // and reading it eagerly cost a round trip per assertion on the green path.
            Assertions.assertThat(poisonRowMeasures(probe.client()))
                    .as(new LazyTextDescription(() -> "PQ-5 [#548] on ClickHouse "
                            + serverVersionOf(probe.client()) + ": a refused batch must leave no row"
                            + " anywhere, or a bisect could re-insert a half that partly landed and"
                            + " double-count"))
                    .isEqualTo(afterHealthy);

            // PQ-6, half one: the code a rejected row answers.
            Assertions.assertThat(server.getCode())
                    .as(new LazyTextDescription(() -> "PQ-6 [#548]: rejected-row code on ClickHouse "
                            + serverVersionOf(probe.client()) + " is " + server.getCode()))
                    .isEqualTo(ClickhouseServerErrors.VIOLATED_CONSTRAINT);

            // PQ-6, the other direction. Without it "a transient failure is not a ServerException"
            // is only half a discrimination: a refusal that also carried a transport exception would
            // leave a branch keying on one quarantining nothing and retrying a poison batch forever.
            Assertions.assertThat(Throwables.getCausalChain(refusal))
                    .as("PQ-6 [#548]: a rejected row must carry NEITHER of the transport exceptions"
                            + " the lost-transport half keys on, or the two are not separable: %s", refusal)
                    .doesNotHaveAnyElementsOfTypes(
                            ConnectionInitiationException.class, DataTransferException.class);

            // The offending row is named, so #548 may need neither a bisect nor a dead-letter. The
            // trailing period is part of the pin, not decoration: "row 5" alone is a prefix of
            // "row 50", so an unanchored match would accept a message naming a different row.
            Assertions.assertThat(server.getMessage())
                    .as("the refusal names which row offended, at the position the batch put it")
                    .contains("violated at row " + POISON_POSITION + ".");
        }
    }

    /**
     * A lost transport answers a different failure from a refused row, so #548 can branch (#548).
     *
     * <p>The half that matters: if the two were equal, quarantining a poison row would quarantine a
     * network blip too, and the 10,000 rows it carried would be discarded rather than retried.</p>
     *
     * <p>Measured on {@code persist}, the call {@code BatchingFlowRepository.flush} branches on, from
     * a repository that started healthy: a refused connect at bootstrap is a different path and may
     * surface a different exception.</p>
     *
     * <p><b>A severed transport has two shapes, not one.</b> Which one the client raises depends on
     * whether its pool hands the next insert the connection it already held or opens a fresh one,
     * and nothing in this fixture decides that:</p>
     * <ul>
     *   <li>{@code ConnectionInitiationException} ← {@code HttpHostConnectException} — a fresh
     *       connect to a listener that is gone;</li>
     *   <li>{@code DataTransferException} ← {@code SocketException: Connection reset} — a write onto
     *       the pooled connection the relay closed underneath it.</li>
     * </ul>
     * <p>Both were measured on the pinned image: 14/14 the first shape on an idle machine, and the
     * second shape 1 in 8 runs with the box loaded. It is not new here — the same probe as it stood
     * in {@code ClickhouseRepositoryIT} produced it 2 in 10 loaded runs — so the single-shape
     * assertion this test used to make was passing on an idle runner rather than being true. This
     * matters to #548 beyond the fixture: a branch keying on {@code ConnectionInitiationException}
     * alone would classify a reset pooled connection as a poison batch and quarantine the 10,000
     * good rows it carried. What the two shapes share is what the branch may key on — the thrown
     * type itself, over an {@link IOException} cause, with no {@code ServerException} anywhere in the
     * chain — and that is what is asserted below.</p>
     */
    @Test
    void aTransientFailureIsDistinguishableFromARejectedRow() throws Exception {
        final Throwable transientFailure;
        try (var relay = new Relay(CLICKHOUSE.getHost(), CLICKHOUSE.getMappedPort(8123))) {
            final var config = configFor(POISON_DB);
            config.setEndpoint(relay.endpoint());
            final var repository = new ClickhouseRepository(
                    new ClickhouseRepository$FlowMapperImpl(), config, RESOLVERS);
            try {
                repository.start();
                // Through the relay first, so the failure below is the relay closing and not a relay
                // that never worked.
                repository.persist(List.of(flow(GOOD_TENANT, "org", 19998)));

                relay.close();
                transientFailure = catchThrowable(() ->
                        repository.persist(List.of(flow(GOOD_TENANT, "org", 19997))));
            } finally {
                repository.stop();
            }
        }

        Assertions.assertThat(transientFailure)
                .as("a repository whose server went away must fail, or this test compares nothing")
                .isNotNull();
        Assertions.assertThat(serverExceptionIn(transientFailure))
                .as("PQ-6 [#548]: a lost transport must not surface as a ServerException, or the"
                        + " rejected-row code %d is not the only thing a branch could key on: %s",
                        ClickhouseServerErrors.VIOLATED_CONSTRAINT, transientFailure)
                .isNull();
        // Asserted on the THROWN type, not on its cause chain, because the thrown type is the fact
        // #548 needs: flush() catches what persist throws in ONE clause with one outcome
        // (FlowException | IOException | RuntimeException), so it cannot branch on the cause today —
        // the thrown type is what a branch would have to key on. Both shapes are unchecked,
        // so persist's ExecutionException wrap never sees them and they leave persist as themselves —
        // and a chain assertion cannot tell that from a build that wrapped them in a FlowException,
        // where every assertion here would still pass while a branch keying on the thrown type never
        // fires and quarantines 10,000 good rows.
        //
        // A closed set of two, not a supertype: ClickHouseException is their only common ancestor and
        // ServerException is its child, so keying on it would assert nothing. A third shape must fail
        // here and be reported.
        Assertions.assertThat(transientFailure)
                .as("PQ-6 [#548]: a lost transport must THROW one of the client's two transport"
                        + " exceptions — a refused fresh connect or a reset pooled one — because that"
                        + " is the type a retry-versus-quarantine branch sees")
                .isInstanceOfAny(ConnectionInitiationException.class, DataTransferException.class);
        Assertions.assertThat(Throwables.getCausalChain(transientFailure))
                .as("PQ-6 [#548]: and it is carried over an I/O failure, so the branch has a second"
                        + " signal that this was the wire and not a server verdict: %s", transientFailure)
                .hasAtLeastOneElementOfType(IOException.class);
    }

    /**
     * A TCP relay in front of the container, so a started repository can lose its transport without
     * the shared container being paused or stopped under the other test in this class.
     *
     * <p><b>What {@code close()} produces.</b> It closes the listener and every socket it has
     * relayed, in both directions. {@link Socket#close()} sends FIN, not RST — there is no
     * {@code setSoLinger(true, 0)} here. What the client then sees is not one thing: if its pool
     * opens a fresh connection it is refused outright, the listener being gone, and if it reuses the
     * connection it already held it writes into a socket closed underneath it and reads a reset.
     * Both shapes occur on this fixture and the test above asserts both; see its javadoc for the
     * measured rates. This is the shape of a ClickHouse whose front door went away. Pausing the
     * container instead would leave the socket open and unanswered, which is a different failure and
     * costs the client's socket timeout multiplied by its retry count — assumed from the client's
     * configuration, not measured here.</p>
     *
     * <p>Bound to loopback only: {@link #endpoint()} hands out {@code 127.0.0.1} regardless, so a
     * wildcard bind bought nothing and offered every host on the network a proxy into a container
     * whose {@code default} user has no password.</p>
     */
    private static final class Relay implements AutoCloseable {

        /** The default {@link ServerSocket} backlog; named because the bind address needs the arity. */
        private static final int BACKLOG = 50;

        /**
         * Bound on the outbound connect. The accept loop is single-threaded, so an unbounded connect
         * to a target that takes the SYN and never finishes would stop the relay accepting anything
         * at all — including the connection the test is waiting on, which would then hang rather
         * than fail.
         */
        private static final int CONNECT_TIMEOUT_MS = 10_000;

        private final ServerSocket listener;
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();

        /**
         * Set before the listener is closed, so the accept loop can tell a deliberate teardown from
         * a genuine failure, and so a connection accepted mid-teardown closes itself.
         */
        private volatile boolean closed;

        Relay(final String targetHost, final int targetPort) throws IOException {
            this.listener = new ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress());
            Thread.ofVirtual().start(() -> {
                while (!this.closed && !this.listener.isClosed()) {
                    final Socket inbound;
                    try {
                        inbound = this.listener.accept();
                    } catch (final IOException cannotAccept) {
                        // Not "this connection failed" but "this relay cannot accept at all": the
                        // teardown close, or something like EMFILE. Looping on it would spin this
                        // virtual thread hot until the class timeout, so stop accepting instead.
                        return;
                    }
                    Socket outbound = null;
                    try {
                        // Both registered before they are used, so a close() racing a slow connect
                        // still reaches them.
                        register(inbound);
                        outbound = new Socket();
                        register(outbound);
                        outbound.connect(new InetSocketAddress(targetHost, targetPort), CONNECT_TIMEOUT_MS);
                        pump(inbound, outbound);
                        pump(outbound, inbound);
                    } catch (final IOException | RuntimeException relayFailed) {
                        // This one connection could not be relayed; keep accepting. Closed here
                        // because this relay is torn down by the test rather than by a finally, so
                        // an abandoned pair would leak descriptors for the whole run. Harmless if
                        // close() already took them: closing twice is a no-op.
                        //
                        // RuntimeException is in the clause because catching only IOException let an
                        // unchecked throw kill this virtual thread silently. The relay then accepted
                        // nothing further and the test blocked to the class timeout rather than
                        // failing with a cause.
                        closeQuietly(inbound);
                        closeQuietly(outbound);
                    }
                }
            });
        }

        /**
         * Track a socket so {@code close()} reaches it, closing it immediately if the teardown has
         * already walked past this point.
         *
         * <p>The ordering is what makes the race safe: {@code close()} sets {@link #closed} and
         * then iterates, while this adds and then reads the flag. A socket added before the
         * iteration reaches it is closed there; one added after is closed here. Without this, a
         * connection accepted between the listener close and the iteration survived the teardown
         * with a live relay behind it, and the transient test intermittently found the transport
         * still working.</p>
         */
        private void register(final Socket socket) {
            this.sockets.add(socket);
            if (this.closed) {
                closeQuietly(socket);
            }
        }

        private static void pump(final Socket from, final Socket to) {
            Thread.ofVirtual().start(() -> {
                try {
                    from.getInputStream().transferTo(to.getOutputStream());
                    // EOF on this leg: propagate the half-close instead of leaving the peer waiting
                    // on a stream that will never produce another byte.
                    to.shutdownOutput();
                } catch (final IOException ignored) {
                    // The relay was closed under it; nothing to forward any more.
                }
            });
        }

        String endpoint() {
            return "http://127.0.0.1:" + this.listener.getLocalPort();
        }

        @Override
        public void close() {
            this.closed = true;
            closeQuietly(this.listener);
            for (final Socket socket : this.sockets) {
                // Guarded per socket: an unguarded loop abandoned every socket after the first one
                // whose close threw, which is exactly the leak this fixture must not have while it
                // asserts about a severed transport.
                closeQuietly(socket);
            }
        }

        /** Close-and-forget: this fixture's teardown has nothing useful to do with a close failure. */
        private static void closeQuietly(final Closeable closeable) {
            if (closeable == null) {
                return;
            }
            try {
                closeable.close();
            } catch (final IOException ignored) {
                // Already broken, already being torn down.
            }
        }
    }

    /**
     * The first {@link ServerException} in a cause chain, or null when there is none.
     *
     * <p>Walked with Guava rather than by hand: {@code getCausalChain} bounds the walk and rejects a
     * cycle itself, where a hand-rolled loop needs a reference comparison Error Prone refuses.</p>
     */
    private static ServerException serverExceptionIn(final Throwable thrown) {
        return Throwables.getCausalChain(thrown).stream()
                .filter(ServerException.class::isInstance)
                .map(ServerException.class::cast)
                .findFirst()
                .orElse(null);
    }

    /**
     * The server that answered, read from it rather than from the image tag.
     *
     * <p>Only ever called while a failure description is being rendered, so it must not throw: an
     * assertion that failed on row measures has to report the row measures, not a secondary failure
     * from the version lookup. A lookup that cannot answer names why in place of the version.</p>
     *
     * <p>{@link AssertionError} is caught explicitly, and it is the case that matters rather than a
     * defensive flourish: {@link #awaiting} reports a breached bound by throwing one, and an
     * {@code Error} is not an {@link Exception}. Catching only {@code Exception} here left the single
     * failure this contract exists to absorb — a stalled {@code version()} during failure rendering —
     * as the one that would escape and replace the real assertion failure.</p>
     */
    private static String serverVersionOf(final Client client) {
        final String query = "SELECT version() AS v";
        try (var rows = awaiting(query, client.queryRecords(query))) {
            for (final var row : rows) {
                return row.getString("v");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown (interrupted)";
        } catch (final AssertionError | Exception e) {
            return "unknown (" + e + ")";
        }
        return "unknown (version() returned no rows)";
    }
}
