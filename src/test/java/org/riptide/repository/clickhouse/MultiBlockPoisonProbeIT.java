/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.clickhouse.client.api.Client;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.riptide.config.ClickhouseConfig;
import org.riptide.e2e.ContainerImages;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.schema.FlowsSchema;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;
import org.slf4j.LoggerFactory;
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
 * <p><b>The parse size is not the boundary, and this fixture exists to prove it both ways.</b>
 * {@code max_insert_block_size} decides only how the incoming stream is cut up. {@code
 * min_insert_block_size_rows} then squashes those pieces back together before they are committed,
 * and the squashed size is what decides atomicity. The two containers below differ in exactly that
 * one setting, and they answer PQ-5 differently — which is why {@code ClickhouseRepository}'s
 * advisory compares against the squash threshold. An earlier version compared against the parse
 * size and refused startup on servers where nothing could go wrong.</p>
 *
 * <p>Rather than build a million-row batch, both containers lower the boundary to meet the batch: a
 * six-row batch with the poison row fifth, so blocks one and two are clean and are offered to the
 * server before it ever sees the offending row.</p>
 *
 * <p><b>What this measures and what it does not.</b> It measures what the pinned server does to
 * blocks it already accepted when a later block is refused, on the direct insert path riptide uses.
 * It does not measure the 10,000-row batches {@code BatchingFlowRepository} actually flushes at the
 * real thresholds; lowering the settings is a stand-in for exceeding them, and the two are the same
 * question only if the behaviour depends on block count rather than on row count. That assumption is
 * stated rather than hidden. It also says nothing about {@code min_insert_block_size_bytes}, which
 * can merge blocks this fixture expects to stay separate.</p>
 *
 * <p><b>Two controls worth recording, because they bound what the measurement means.</b> Request
 * compression is not the cause: rerunning the hazard case with {@code compress-requests} off gives
 * the identical result. And the boundary has never actually been crossed through the real client at
 * stock settings — a two-million-row insert issued as raw TSV against an untouched server committed
 * nothing on refusal, while riptide's own client at a lowered threshold commits rows every time. So
 * the hazard is real on riptide's path, but every demonstration of it lowers the boundary to meet
 * the batch rather than raising the batch past a stock boundary.</p>
 *
 * <p><b>What this fixture does not release.</b> {@code ClickhouseRepository} does not override
 * {@code FlowRepository.stop()}, so the {@code stop()} calls below are the fixture's side of a
 * lifecycle the repository does not yet implement: each test leaks the repository's HTTP connection
 * pool for the life of the JVM. Same known leak as {@code PoisonBatchProbeIT}, left alone for the
 * same reason — closing it means changing production code that is not this issue's business.</p>
 */
@Testcontainers
@Timeout(value = 3, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
public class MultiBlockPoisonProbeIT {

    /** Two rows per block, so six rows span three and the poison row is not in the first. */
    private static final int BLOCK_ROWS = 2;

    /**
     * A server that both splits at {@link #BLOCK_ROWS} and does not squash the pieces back.
     *
     * <p>All three settings are load-bearing, and saying so is the correction this fixture carries:
     * with only {@code max_insert_block_size} lowered, the same refused batch commits nothing. The
     * settings live in the server's own default profile rather than being applied with {@code ALTER
     * USER}, because setting {@code CLICKHOUSE_USER} makes the image's {@code default} user require
     * a password, so no admin connection is available to issue that statement.</p>
     */
    private static final String SQUASHING_OFF_PROFILE = """
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
    private static final GenericContainer<?> SQUASHING_OFF = clickhouse(SQUASHING_OFF_PROFILE);

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

    /** What an operator actually runs, and what the advisory has to judge against each server. */
    private static final int SHIPPED_MAX_ROWS = 10_000;

    private static final long QUERY_TIMEOUT_SECONDS = 30;

    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void captureLogs() {
        this.logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ClickhouseRepository.class);
        this.appender = new ListAppender<>();
        this.appender.start();
        this.logger.addAppender(this.appender);
    }

    @AfterEach
    void releaseLogs() {
        this.logger.detachAppender(this.appender);
        this.appender.stop();
    }

    private List<String> warnings() {
        return this.appender.list.stream()
                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

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
                new ClickhouseRepository$FlowMapperImpl(), configFor(SQUASHING_OFF, 1), RESOLVERS);
        repository.start();
        try (var probe = probeClient(SQUASHING_OFF)) {
            constrain(probe);
            Assertions.assertThat(effective(probe, "max_insert_block_size"))
                    .as("the lowered parse size must be in force, or this measures a stock server")
                    .isEqualTo(BLOCK_ROWS);
            Assertions.assertThat(effective(probe, "min_insert_block_size_rows"))
                    .as("and squashing must be off, which is the setting that actually decides this")
                    .isEqualTo(BLOCK_ROWS);

            final Map<String, Long> before = measures(probe);
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

            // And the rollups disagree with the base table. Asserted rather than printed: this is
            // the half that says a partial write is not merely short but inconsistent, and an
            // earlier version of this fixture only logged it.
            Assertions.assertThat(FlowsSchema.rollupTableNames().stream()
                            .map(rollup -> after.get(FlowsSchema.qualifiedRollup(DB, rollup)))
                            .distinct())
                    .as("the rollups do not all agree with each other after a partial write: %s", after)
                    .hasSizeGreaterThan(1);
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
        repository.start();
        try (var probe = probeClient(PARSE_ONLY)) {
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

    /**
     * The advisory fires where the hazard is real, and names both settings.
     *
     * <p>{@code maxRows} is the shipped default: the configuration is legal today and legal against
     * a stock server, and only this server's settings make it worth a word. That is the case a
     * constant ceiling inside riptide would have missed.</p>
     */
    @Test
    void theAdvisoryWarnsWhenABatchCanSpanCommittedBlocks() {
        final var repository = new ClickhouseRepository(
                new ClickhouseRepository$FlowMapperImpl(),
                configFor(SQUASHING_OFF, SHIPPED_MAX_ROWS), RESOLVERS);
        try {
            repository.start();
            Assertions.assertThat(warnings())
                    .as("startup must warn, and must not fail: an advisory that refuses to boot"
                            + " trades a consistency risk for an outage")
                    .anySatisfy(message -> Assertions.assertThat(message)
                            .contains("riptide.clickhouse.batch.max-rows is " + SHIPPED_MAX_ROWS)
                            .contains("committed insert block of " + BLOCK_ROWS + " rows")
                            // Both settings named, because an operator who reads only the parse
                            // size will change the one that does not decide this.
                            .contains("max_insert_block_size=" + BLOCK_ROWS)
                            .contains("min_insert_block_size_rows=" + BLOCK_ROWS));
        } finally {
            repository.stop();
        }
    }

    /**
     * And it stays quiet where the hazard is not, which is the whole correction.
     *
     * <p>Parse size 2, batch size 10,000 — every input the first version of the advisory looked at
     * says "danger", and it refused to start. The squash threshold says otherwise, and the test
     * above proves the server agrees.</p>
     */
    @Test
    void theAdvisoryStaysQuietWhenTheServerSquashesTheBlocksBackTogether() {
        final var repository = new ClickhouseRepository(
                new ClickhouseRepository$FlowMapperImpl(),
                configFor(PARSE_ONLY, SHIPPED_MAX_ROWS), RESOLVERS);
        try {
            repository.start();
            Assertions.assertThat(warnings())
                    .as("no warning: max-rows is far above the parse size but far below the squash"
                            + " threshold, and it is the squash threshold that decides atomicity")
                    .noneSatisfy(message -> Assertions.assertThat(message)
                            .contains("riptide.clickhouse.batch.max-rows"));
        } finally {
            repository.stop();
        }
    }
}
