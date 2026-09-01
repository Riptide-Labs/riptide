/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import com.clickhouse.client.api.Client;
import org.assertj.core.api.Assertions;
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
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.catchThrowable;
import static org.riptide.repository.clickhouse.ClickhouseItFlows.flow;

/**
 * PQ-5 where it can actually fail, and where it cannot (#548, #700).
 *
 * <p>{@link PoisonBatchProbeIT} answers PQ-5 for a six-row batch against a stock server, which is
 * the regime where a partial write is impossible by construction. {@code ClickhouseRepository}
 * states the risk as: a refused insert is atomic on the buffered path but "can leave whole blocks
 * committed on a direct one once a batch exceeds {@code max_insert_block_size}". #548 has to choose
 * between bisecting a poisoned batch and dead-lettering it on exactly this fact, because a bisect
 * re-inserts a half and double-counts whatever the original left behind.</p>
 *
 * <p><b>What is settled: a refused insert is not always atomic.</b> With the parse size lowered and
 * row-squashing pinned to it, a six-row batch whose fifth row violates a CHECK leaves the earlier
 * blocks committed. With only the parse size lowered, the same batch commits nothing. Those two
 * measurements are what this fixture pins, and they are enough for #548 to know a bisect-and-retry
 * cannot assume it is re-inserting into an empty slot.</p>
 *
 * <p><b>What is NOT settled, and is deliberately not encoded anywhere: which servers are at risk.</b>
 * #700 tried twice to turn this into a startup check. The first compared the configured batch
 * against {@code max_insert_block_size} and refused startup on servers that commit nothing; the
 * second compared against {@code min_insert_block_size_rows} and was wrong in both directions —
 * silent on a server that partial-writes when {@code min_insert_block_size_bytes} is small, and
 * warning on a server that does not when the parse size is large. Two independent measurements of
 * the squash settings then produced contradictory rules, one concluding the predicate is OR and the
 * other AND, each falsified by the other's data. A further control deepens it: a two-million-row
 * insert against a completely untouched server committed nothing on refusal, where any row-count
 * model predicts about a million. So riptide states no boundary, and this fixture claims none.</p>
 *
 * <p>Rather than build a million-row batch, both containers lower the boundary to meet the batch: a
 * six-row batch with the poison row fifth, so blocks one and two are clean and are offered to the
 * server before it ever sees the offending row. That is a stand-in for exceeding a stock boundary,
 * and the control above is the reason it should be read as one rather than as the same thing.</p>
 *
 * <p><b>What this measures and what it does not.</b> It measures what the pinned server does to
 * blocks it already accepted when a later block is refused, on the direct insert path riptide uses.
 * It does not measure the 10,000-row batches {@code BatchingFlowRepository} actually flushes at
 * stock thresholds — see the control above, which is the closest anyone got and points the other
 * way. Request compression is not the cause: rerunning the hazard case with {@code compress-requests}
 * off gives the identical result.</p>
 *
 * <p><b>What this fixture does not release.</b> {@code ClickhouseRepository} does not override
 * {@code FlowRepository.stop()}, so the {@code stop()} calls below are the fixture's side of a
 * lifecycle the repository does not yet implement: each test leaks the repository's HTTP connection
 * pool for the life of the JVM. Same known leak as {@code PoisonBatchProbeIT}, left alone for the
 * same reason.</p>
 */
@Testcontainers
@Timeout(value = 3, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
public class MultiBlockPoisonProbeIT {

    /** Two rows per block, so six rows span three and the poison row is not in the first. */
    private static final int BLOCK_ROWS = 2;

    /**
     * A server that splits at {@link #BLOCK_ROWS} and squashes back only to that same size.
     *
     * <p>Named for what it does, not for "squashing off": it sets the row threshold TO the parse
     * size, and the setting it actually disables is the byte one. All three are load-bearing —
     * with only {@code max_insert_block_size} lowered, the same refused batch commits nothing. The
     * settings live in the server's own default profile rather than being applied with {@code ALTER
     * USER}, because setting {@code CLICKHOUSE_USER} makes the image's {@code default} user require
     * a password, so no admin connection is available to issue that statement.</p>
     */
    private static final String SQUASH_AT_PARSE_SIZE_PROFILE = """
            <clickhouse>
                <profiles>
                    <default>
                        <max_insert_block_size>%d</max_insert_block_size>
                        <min_insert_block_size_rows>%d</min_insert_block_size_rows>
                        <min_insert_block_size_bytes>0</min_insert_block_size_bytes>
                    </default>
                </profiles>
            </clickhouse>
            """.formatted(BLOCK_ROWS, BLOCK_ROWS);

    /**
     * The control: the same parse size, with squashing left at the server's stock value.
     *
     * <p>This is the configuration the first version of #700's check refused to start against, and
     * against which it should not have: the server merges the parsed blocks back together, so a
     * refused batch is atomic and nothing is at risk.</p>
     */
    private static final String PARSE_ONLY_PROFILE = """
            <clickhouse>
                <profiles>
                    <default>
                        <max_insert_block_size>%d</max_insert_block_size>
                    </default>
                </profiles>
            </clickhouse>
            """.formatted(BLOCK_ROWS);

    @Container
    private static final GenericContainer<?> SQUASH_AT_PARSE_SIZE = clickhouse(SQUASH_AT_PARSE_SIZE_PROFILE);

    @Container
    private static final GenericContainer<?> PARSE_ONLY = clickhouse(PARSE_ONLY_PROFILE);

    private static GenericContainer<?> clickhouse(final String profile) {
        return new GenericContainer<>(ContainerImages.clickhouse())
                .withEnv("CLICKHOUSE_DB", "riptide")
                .withEnv("CLICKHOUSE_USER", "riptide")
                .withEnv("CLICKHOUSE_PASSWORD", "riptide")
                .withCopyToContainer(Transferable.of(profile),
                        "/etc/clickhouse-server/users.d/zz-block-size.xml")
                .withExposedPorts(8123)
                .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));
    }

    private static final SecretResolvers RESOLVERS = SecretResolvers.defaults();
    private static final String DB = "multi_block_probe";
    private static final String GOOD_TENANT = "ok";
    private static final String BAD_TENANT = "rejected";

    private static final int BATCH_SIZE = 6;
    private static final int POISON_POSITION = 5;

    private static final long QUERY_TIMEOUT_SECONDS = 30;

    private static <T> T awaiting(final String statement, final CompletableFuture<T> future) throws Exception {
        try {
            return future.get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (final TimeoutException e) {
            throw new AssertionError("no answer within " + QUERY_TIMEOUT_SECONDS + "s to: " + statement, e);
        }
    }

    private static String endpoint(final GenericContainer<?> container) {
        return "http://" + container.getHost() + ":" + container.getMappedPort(8123);
    }

    private static ClickhouseConfig configFor(final GenericContainer<?> container, final int maxRows) {
        final var config = new ClickhouseConfig();
        config.setEndpoint(endpoint(container));
        config.setUsername(SecretRef.of("riptide"));
        config.setPassword(SecretRef.of("riptide"));
        config.setDatabase(DB);
        config.setManageSchema(true);
        // The direct path, as PoisonBatchProbeIT argues: the coalesced one is documented atomic, so
        // the direct one is where a partial write could appear at all.
        config.setAsyncInserts(false);
        config.getBatch().setMaxRows(maxRows);
        return config;
    }

    private static Client probeClient(final GenericContainer<?> container) {
        return new Client.Builder().addEndpoint(endpoint(container))
                .setUsername("riptide").setPassword("riptide").setDefaultDatabase(DB)
                .useAsyncRequests(true).build();
    }

    /**
     * How much has landed in the base table and in every rollup behind it.
     *
     * <p>Both guards are carried over from {@code PoisonBatchProbeIT.poisonRowMeasures}, where their
     * javadoc explains why they are load-bearing: the name check so a rollup dropped from the schema
     * cannot silently drop out of "nothing landed anywhere", and the per-query presence check so an
     * empty result fails as an assertion instead of as an NPE in the caller's unboxing. An earlier
     * version of this fixture copied the method without either and then unboxed, which is exactly
     * the path the second guard exists to prevent.</p>
     */
    private static Map<String, Long> measures(final Client client) throws Exception {
        Assertions.assertThat(FlowsSchema.rollupTableNames())
                .as("every rollup must be measured, or 'nothing landed' is a statement about one"
                        + " table while the others go unchecked")
                .containsExactlyInAnyOrder(FlowsSchema.ROLLUP_BY_APPLICATION,
                        FlowsSchema.ROLLUP_BY_CONVERSATION, FlowsSchema.ROLLUP_BY_EXPORTER_IFACE,
                        FlowsSchema.ROLLUP_BY_GEO_ASN);
        final Map<String, String> queries = new LinkedHashMap<>();
        queries.put(FlowsSchema.qualifiedFlows(DB), "SELECT count() AS v FROM " + FlowsSchema.qualifiedFlows(DB));
        FlowsSchema.rollupTableNames().forEach(rollup -> {
            final String table = FlowsSchema.qualifiedRollup(DB, rollup);
            queries.put(table, "SELECT sum(flowCount) AS v FROM " + table);
        });
        awaiting("SYSTEM FLUSH ASYNC INSERT QUEUE", client.execute("SYSTEM FLUSH ASYNC INSERT QUEUE"));
        final Map<String, Long> out = new LinkedHashMap<>();
        for (final Map.Entry<String, String> query : queries.entrySet()) {
            try (var rows = awaiting(query.getValue(), client.queryRecords(query.getValue()))) {
                for (final var row : rows) {
                    out.put(query.getKey(), row.getLong("v"));
                }
            }
            Assertions.assertThat(out)
                    .as("%s returned no record, so this snapshot measured nothing", query.getValue())
                    .containsKey(query.getKey());
        }
        return out;
    }

    private static void constrain(final Client client) throws Exception {
        final String alter = "ALTER TABLE " + FlowsSchema.qualifiedFlows(DB)
                + " ADD CONSTRAINT IF NOT EXISTS probe_tenant CHECK tenant = '" + GOOD_TENANT + "'";
        awaiting(alter, client.execute(alter));
    }

    /** Read back what the mounted profile actually produced, so a snapshot cannot pass vacuously. */
    private static long effective(final Client client, final String setting) throws Exception {
        final String query = "SELECT getSetting('" + setting + "') AS v";
        try (var rows = awaiting(query, client.queryRecords(query))) {
            for (final var row : rows) {
                return Long.parseLong(row.getString("v"));
            }
        }
        throw new AssertionError(query + " returned no rows, so the profile could not be verified");
    }

    private static List<EnrichedFlow> poisonedBatch(final int portBase) throws Exception {
        final List<EnrichedFlow> batch = new ArrayList<>();
        for (int i = 1; i <= BATCH_SIZE; i++) {
            batch.add(flow(i == POISON_POSITION ? BAD_TENANT : GOOD_TENANT, "org", portBase + i));
        }
        return batch;
    }

    /**
     * The hazard: with squashing off, a refused batch leaves its earlier blocks committed.
     *
     * <p>The method name says what the assertions pin. An earlier version was named for the answer
     * PQ-5 hoped for rather than the one it got, so a reader grepping the name met the opposite of
     * the finding the whole issue rests on.</p>
     */
    @Test
    void aRefusedMultiBlockBatchLeavesEarlierBlocksCommitted() throws Exception {
        final var repository = new ClickhouseRepository(
                new ClickhouseRepository$FlowMapperImpl(), configFor(SQUASH_AT_PARSE_SIZE, 1), RESOLVERS);
        try (var probe = probeClient(SQUASH_AT_PARSE_SIZE)) {
            repository.start();
            constrain(probe);
            Assertions.assertThat(effective(probe, "max_insert_block_size"))
                    .as("the lowered parse size must be in force, or this measures a stock server")
                    .isEqualTo(BLOCK_ROWS);
            Assertions.assertThat(effective(probe, "min_insert_block_size_rows"))
                    .as("and squashing must be off, which is the setting that actually decides this")
                    .isEqualTo(BLOCK_ROWS);

            // A healthy row first, and every rollup asserted to move. PoisonBatchProbeIT documents
            // why this is load-bearing rather than decoration: four absent, ungranted or mis-shaped
            // materialized views read 0 in both snapshots, and every assertion below would then
            // pass over four dead tables. An earlier version of this fixture copied that file's
            // measurement guards but not this control.
            final Map<String, Long> empty = measures(probe);
            repository.persist(List.of(flow(GOOD_TENANT, "org", 39999)));
            final Map<String, Long> before = measures(probe);
            Assertions.assertThat(before.get(FlowsSchema.qualifiedFlows(DB)))
                    .as("a healthy row must land, or this fixture rejects everything")
                    .isEqualTo(empty.get(FlowsSchema.qualifiedFlows(DB)) + 1);
            for (final String rollup : FlowsSchema.rollupTableNames()) {
                final String table = FlowsSchema.qualifiedRollup(DB, rollup);
                Assertions.assertThat(before.get(table))
                        .as("%s must aggregate the healthy row, or the rollup assertions below"
                                + " compare dead tables", table)
                        .isEqualTo(empty.get(table) + 1);
            }

            final Throwable refusal = catchThrowable(() -> repository.persist(poisonedBatch(40000)));
            final Map<String, Long> after = measures(probe);

            // The refusal must be the CHECK, not merely some throwable: a dropped connection or a
            // mapper fault would also be non-null, and then "rows survived" would read as a partial
            // write when it was something else entirely.
            Assertions.assertThat(refusal)
                    .as("the poisoned batch must be refused by the constraint, or this measures"
                            + " nothing about partial writes")
                    .isNotNull()
                    .hasStackTraceContaining(ClickhouseServerErrors.VIOLATED_CONSTRAINT_MESSAGE_PREFIX)
                    .hasStackTraceContaining("probe_tenant");

            // Asserted as "some rows survived" and not as "exactly two": the count is whatever the
            // block size makes it, and pinning 2 would fail on a server that splits differently
            // while saying nothing new. What #548 needs is the sign, not the magnitude.
            Assertions.assertThat(after.get(FlowsSchema.qualifiedFlows(DB)))
                    .as("PQ-5 [#548]: a refused batch spanning %d committed blocks leaves the"
                            + " earlier ones behind, which is why a bisect-and-retry would"
                            + " double-count them", BATCH_SIZE / BLOCK_ROWS)
                    .isGreaterThan(before.get(FlowsSchema.qualifiedFlows(DB)));

            // And at least one rollup disagrees with the BASE TABLE, which is the claim the issue
            // and the docs actually make. An earlier version asserted the rollups differ from each
            // OTHER, which is a different property and a non-deterministic one: the same data
            // records which rollups receive rows as varying between runs, and a server that refused
            // the block before any view ran would leave all four equal to each other and still
            // short against the base — the same hazard, failing that assertion.
            Assertions.assertThat(FlowsSchema.rollupTableNames())
                    .as("a partial write leaves the rollups inconsistent with the base table: %s", after)
                    .anySatisfy(rollup -> Assertions.assertThat(after.get(FlowsSchema.qualifiedRollup(DB, rollup)))
                            .isNotEqualTo(after.get(FlowsSchema.qualifiedFlows(DB))));
        } finally {
            repository.stop();
        }
    }

    /**
     * The control, and the regression test for #700's first, wrong model.
     *
     * <p>Same parse size, same batch, same poison row — only the squash setting differs, and the
     * refusal is atomic. This is the configuration the first version of the advisory treated as
     * dangerous, refusing startup on a server where nothing can go wrong.</p>
     */
    @Test
    void aRefusedBatchIsAtomicWhenTheServerSquashesTheBlocksBackTogether() throws Exception {
        final var repository = new ClickhouseRepository(
                new ClickhouseRepository$FlowMapperImpl(), configFor(PARSE_ONLY, 1), RESOLVERS);
        try (var probe = probeClient(PARSE_ONLY)) {
            repository.start();
            constrain(probe);
            Assertions.assertThat(effective(probe, "max_insert_block_size"))
                    .as("the parse size is lowered here too, so it is not what differs")
                    .isEqualTo(BLOCK_ROWS);
            Assertions.assertThat(effective(probe, "min_insert_block_size_rows"))
                    .as("what differs is squashing, left at the server's stock value")
                    .isGreaterThan(BATCH_SIZE);

            final Map<String, Long> before = measures(probe);
            final Throwable refusal = catchThrowable(() -> repository.persist(poisonedBatch(50000)));
            final Map<String, Long> after = measures(probe);

            Assertions.assertThat(refusal)
                    .as("the batch is still refused, by the same constraint as the hazard test")
                    .isNotNull()
                    .hasStackTraceContaining(ClickhouseServerErrors.VIOLATED_CONSTRAINT_MESSAGE_PREFIX)
                    .hasStackTraceContaining("probe_tenant");
            Assertions.assertThat(after)
                    .as("but nothing is committed, because the server squashed the parsed blocks"
                            + " back into one — so the parse size alone is NOT the boundary")
                    .isEqualTo(before);
        } finally {
            repository.stop();
        }
    }

}
