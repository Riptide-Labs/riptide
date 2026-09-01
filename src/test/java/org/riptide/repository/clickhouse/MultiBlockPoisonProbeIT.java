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
 * PQ-5 where it can actually fail: a refused insert whose batch spans more than one block (#548).
 *
 * <p>{@link PoisonBatchProbeIT} answers PQ-5 for a six-row batch, which is the regime where a
 * partial write is impossible by construction. {@code ClickhouseRepository} states the risk as: a
 * refused insert is atomic on the buffered path but "can leave whole blocks committed on a direct
 * one <em>once a batch exceeds {@code max_insert_block_size}</em>". The server default is 1,048,576,
 * so no test in this repository has ever crossed that boundary — and #548 has to choose between
 * bisecting a poisoned batch and dead-lettering it on exactly this fact. A bisect re-inserts a half;
 * if the original left rows behind, the retry double-counts them.</p>
 *
 * <p>Rather than build a million-row batch, this lowers the boundary to meet the batch:
 * {@code max_insert_block_size = 2} on the writing user, so six rows span three blocks and the
 * poison row sits in the third. Blocks one and two are clean and are offered to the server before it
 * ever sees the offending row.</p>
 *
 * <p><b>What this measures and what it does not.</b> It measures what the pinned server does to
 * blocks it already accepted when a later block is refused, on the direct insert path riptide uses.
 * It does not measure the 10,000-row batches {@code BatchingFlowRepository} actually flushes at the
 * real block size; lowering the setting is a stand-in for exceeding it, and the two are the same
 * question only if the server's behaviour depends on block count rather than on row count. That is
 * the assumption this fixture rests on, and it is stated rather than hidden.</p>
 */
@Testcontainers
@Timeout(value = 2, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
public class MultiBlockPoisonProbeIT {

    /** Two rows per block, so six rows span three and the poison row is not in the first. */
    private static final int BLOCK_ROWS = 2;

    /**
     * The block size is lowered in the server's own default profile rather than with
     * {@code ALTER USER}: setting {@code CLICKHOUSE_USER} makes the image's {@code default} user
     * require a password, so no admin connection is available to issue that statement, and
     * {@code riptide} has no access management of its own.
     */
    private static final String BLOCK_SIZE_PROFILE = """
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

    @Container
    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>(ContainerImages.clickhouse())
            .withEnv("CLICKHOUSE_DB", "riptide")
            .withEnv("CLICKHOUSE_USER", "riptide")
            .withEnv("CLICKHOUSE_PASSWORD", "riptide")
            .withCopyToContainer(Transferable.of(BLOCK_SIZE_PROFILE),
                    "/etc/clickhouse-server/users.d/zz-block-size.xml")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

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

    private static String endpoint() {
        return "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123);
    }

    private static ClickhouseConfig configFor(final String database) {
        final var config = new ClickhouseConfig();
        config.setEndpoint(endpoint());
        config.setUsername(SecretRef.of("riptide"));
        config.setPassword(SecretRef.of("riptide"));
        config.setDatabase(database);
        config.setManageSchema(true);
        // The direct path, as PoisonBatchProbeIT argues: the coalesced one is documented atomic, so
        // the direct one is where a partial write could appear at all.
        config.setAsyncInserts(false);
        // Below BLOCK_ROWS, or #700's startup guard refuses this very configuration — the guard
        // this fixture's other test exists to prove. The value governs nothing here: the probe
        // hands persist() its six-row list directly, bypassing BatchingFlowRepository, which is
        // what lets a batch span blocks while the configured batch size stays legal.
        config.getBatch().setMaxRows(1);
        return config;
    }

    private static Map<String, Long> measures(final Client client) throws Exception {
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
        }
        return out;
    }

    @Test
    void aRefusedMultiBlockBatchLeavesNothingBehind() throws Exception {
        final var config = configFor(DB);
        final var repository = new ClickhouseRepository(
                new ClickhouseRepository$FlowMapperImpl(), config, RESOLVERS);
        repository.start();

        try (var probe = new Client.Builder().addEndpoint(endpoint())
                     .setUsername("riptide").setPassword("riptide").setDefaultDatabase(DB)
                     .useAsyncRequests(true).build()) {

            final String constrain = "ALTER TABLE " + FlowsSchema.qualifiedFlows(DB)
                    + " ADD CONSTRAINT IF NOT EXISTS probe_tenant CHECK tenant = '" + GOOD_TENANT + "'";
            awaiting(constrain, probe.execute(constrain));

            // Prove the lowered setting actually reached the writing user, or a "nothing landed"
            // result below would read as atomicity when it is really a profile that never applied.
            try (var rows = awaiting("readback", probe.queryRecords(
                    "SELECT getSetting('max_insert_block_size') AS v"))) {
                for (final var row : rows) {
                    Assertions.assertThat(row.getString("v"))
                            .as("the lowered block size must be in force for the writing user, or this"
                                    + " probe measures the single-block regime again")
                            .isEqualTo(String.valueOf(BLOCK_ROWS));
                }
            }

            final Map<String, Long> before = measures(probe);

            final List<EnrichedFlow> poisoned = new ArrayList<>();
            for (int i = 1; i <= BATCH_SIZE; i++) {
                poisoned.add(flow(i == POISON_POSITION ? BAD_TENANT : GOOD_TENANT, "org", 40000 + i));
            }
            final Throwable refusal = catchThrowable(() -> repository.persist(poisoned));

            final Map<String, Long> after = measures(probe);

            System.out.println("=== MULTI-BLOCK PQ-5 ===");
            System.out.println("block rows       : " + BLOCK_ROWS);
            System.out.println("batch size       : " + BATCH_SIZE + " (poison at " + POISON_POSITION + ")");
            System.out.println("refusal          : " + refusal);
            System.out.println("before           : " + before);
            System.out.println("after            : " + after);
            System.out.println("=== END ===");

            Assertions.assertThat(refusal)
                    .as("the poisoned batch must still be refused, or this measures nothing")
                    .isNotNull();

            // The measured answer, pinned as it was found rather than as PQ-5 hoped. Asserted as
            // "some rows survived" and not as "exactly two": the row count is what the block size
            // makes it, and pinning 2 would fail on a server that splits differently while saying
            // nothing new. What matters to #548 is the sign, not the magnitude.
            Assertions.assertThat(after.get(FlowsSchema.qualifiedFlows(DB)))
                    .as("PQ-5 [#548] multi-block on a batch spanning %d blocks: the refused insert"
                            + " leaves earlier blocks COMMITTED. This is the opposite of the"
                            + " single-block answer, and it is why a bisect-and-retry would"
                            + " double-count the rows that already landed.",
                            BATCH_SIZE / BLOCK_ROWS)
                    .isGreaterThan(before.get(FlowsSchema.qualifiedFlows(DB)));
        } finally {
            repository.stop();
        }
    }

    /**
     * The guard #700 adds, against the same server that makes it necessary.
     *
     * <p>The test above is the hazard; this is the fix refusing to enter it. Both run on one
     * container with {@code max_insert_block_size = 2}, so the boundary the guard reads is the same
     * boundary the partial write was measured at — a guard checked against a mocked or assumed
     * block size would prove only that a comparison compiles.</p>
     *
     * <p>{@code maxRows} is left at the shipped default of 10,000, which is what an operator would
     * be running: the configuration is legal today and legal against a default server, and it is
     * only this server's lowered block size that makes it dangerous. That is exactly the case a
     * constant ceiling in riptide would have missed.</p>
     */
    @Test
    void aBatchLargerThanOneInsertBlockIsRefusedAtStartup() {
        final var config = configFor(DB);
        config.getBatch().setMaxRows(10_000);

        final var repository = new ClickhouseRepository(
                new ClickhouseRepository$FlowMapperImpl(), config, RESOLVERS);

        Assertions.assertThatThrownBy(repository::start)
                .as("a batch that cannot fit one insert block must be refused before any row is"
                        + " written, or the partial write the test above measures is reachable from"
                        + " configuration alone")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("riptide.clickhouse.batch.max-rows is 10000")
                .hasMessageContaining("max_insert_block_size of " + BLOCK_ROWS)
                // The operator has to be told what to do, not merely that something is wrong: the
                // two settings interact in a way nobody tuning throughput has reason to know.
                .hasMessageContaining("Lower max-rows below " + BLOCK_ROWS);
    }
}
