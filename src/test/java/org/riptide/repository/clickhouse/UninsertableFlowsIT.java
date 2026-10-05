/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.metadata.TableSchema;
import com.codahale.metrics.MetricRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.riptide.config.ClickhouseConfig;
import org.riptide.e2e.ContainerImages;
import org.riptide.flows.parser.data.Flow;
import org.riptide.flows.parser.ie.values.ValueConversionService;
import org.riptide.flows.parser.ie.values.visitor.BooleanVisitor;
import org.riptide.flows.parser.ie.values.visitor.DoubleVisitor;
import org.riptide.flows.parser.ie.values.visitor.DurationVisitor;
import org.riptide.flows.parser.ie.values.visitor.InetAddressVisitor;
import org.riptide.flows.parser.ie.values.visitor.InstantVisitor;
import org.riptide.flows.parser.ie.values.visitor.IntegerVisitor;
import org.riptide.flows.parser.ie.values.visitor.LongVisitor;
import org.riptide.flows.parser.ie.values.visitor.StringVisitor;
import org.riptide.flows.parser.ie.values.visitor.UnsignedLongVisitor;
import org.riptide.flows.parser.netflow5.Netflow5FlowBuilder;
import org.riptide.flows.parser.netflow9.Netflow9FlowBuilder;
import org.riptide.flows.parser.netflow9.Netflow9RawFlow;
import org.riptide.flows.parser.session.SequenceNumberTracker;
import org.riptide.flows.parser.session.TcpSession;
import org.riptide.pipeline.EnrichedFlow;
import org.riptide.pipeline.EnrichedFlow$FlowMapperImpl;
import org.riptide.pipeline.FlowException;
import org.riptide.pipeline.Source;
import org.riptide.repository.UninsertableFlowsException;
import org.riptide.schema.FlowsSchema;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.riptide.flows.utils.BufferUtils.slice;
import static org.riptide.repository.clickhouse.ClickhouseItFlows.flow;

/**
 * A flow riptide cannot insert costs that flow, not its batch (#985), against a real server.
 *
 * <p>{@code DeadLetterIT} covers the batch a reachable server refuses, which is still charged and
 * kept in full. This covers the row the client would refuse before anything is sent: a null in a
 * column the {@code flows} table declares non-nullable.
 *
 * <p>The flows here come out of the real flow builders and {@code EnrichedFlow.FlowMapper}, from the
 * captures the issue names. No enricher runs, so what is asserted is what the storage path does with
 * a flow as the parser hands it over.
 */
@Testcontainers
@Timeout(value = 3, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
public class UninsertableFlowsIT {

    @Container
    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>(ContainerImages.clickhouse())
            .withEnv("CLICKHOUSE_DB", "riptide")
            .withEnv("CLICKHOUSE_USER", "riptide")
            .withEnv("CLICKHOUSE_PASSWORD", "riptide")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    private static final SecretResolvers RESOLVERS = SecretResolvers.defaults();

    private static final String DATABASE = "uninsertable";

    /** Carries a {@code CHECK} that admits {@link #GOOD_TENANT} only. */
    private static final String BARRIER_DB = "uninsertable_barrier";

    /** Has {@code flows} and no {@code flows_dead_letter}. */
    private static final String UNMIGRATED_DB = "uninsertable_unmigrated";

    /** {@code zone} has a {@code DEFAULT} and {@code exporterAddr} is {@code Nullable}. */
    private static final String OPERATOR_DB = "uninsertable_operator";

    private static final String GOOD_TENANT = "ok";

    /** The exporter whose template has no address field, and the one whose flows are complete. */
    private static final String WLC = "127.0.1.1";
    private static final String MX80 = "127.0.1.2";

    private static Client admin;

    @BeforeAll
    static void provision() throws Exception {
        admin = new Client.Builder().addEndpoint(endpoint())
                .setUsername("riptide").setPassword("riptide").build();
        for (final String database : List.of(DATABASE, BARRIER_DB)) {
            final var managed = repositoryOn(database, true);
            managed.start();
            managed.stop();
        }
        // The synthetic barrier DeadLetterIT uses, for the same reason: a row the SERVER refuses.
        admin.execute("ALTER TABLE " + FlowsSchema.qualifiedFlows(BARRIER_DB)
                + " ADD CONSTRAINT IF NOT EXISTS probe_tenant CHECK tenant = '" + GOOD_TENANT + "'").get();

        // An operator's own table: one column given a default, one relaxed to Nullable. No shipped
        // column has either, so without this nothing measures those two arms of the rule.
        admin.execute(FlowsSchema.createDatabase(OPERATOR_DB)).get();
        admin.execute(FlowsSchema.createFlowsTable(OPERATOR_DB)).get();
        admin.execute("ALTER TABLE " + FlowsSchema.qualifiedFlows(OPERATOR_DB)
                + " MODIFY COLUMN zone String DEFAULT 'operator-default'").get();
        admin.execute("ALTER TABLE " + FlowsSchema.qualifiedFlows(OPERATOR_DB)
                + " MODIFY COLUMN exporterAddr Nullable(String)").get();

        // A deployment provisioned before the dead-letter table existed: flows alone.
        admin.execute(FlowsSchema.createDatabase(UNMIGRATED_DB)).get();
        admin.execute(FlowsSchema.createFlowsTable(UNMIGRATED_DB)).get();
    }

    /** The reproduction from #985: three datagrams, two exporters, one batch. */
    @Test
    void theWlcFlowsOfIssue985DoNotTakeTheNetflow5FlowsWithThem() throws Exception {
        final List<EnrichedFlow> wlc = enriched(WLC, netflow9(
                "/flows/netflow9_test_cisco_wlc_tpl.dat", "/flows/netflow9_test_cisco_wlc_data261.dat"));
        final List<EnrichedFlow> mx80 = enriched(MX80, netflow5("/flows/netflow5_test_juniper_mx80.dat"));
        Assertions.assertThat(wlc).as("the capture the issue counted").hasSize(19)
                .allSatisfy(flow -> Assertions.assertThat(flow.getSrcAddr()).isNull());
        Assertions.assertThat(mx80).hasSize(29);

        final List<EnrichedFlow> batch = new ArrayList<>(wlc);
        batch.addAll(mx80);
        final var registry = new MetricRegistry();
        try (var flusher = startedFlusher(DATABASE, registry)) {
            flusher.offer(batch, () -> counter(registry, "deadLetteredRows") >= wlc.size());
        }

        Assertions.assertThat(countByExporter(FlowsSchema.qualifiedFlows(DATABASE), "exporterAddr", MX80))
                .as("the valid flows of the batch are stored")
                .isEqualTo(29);
        Assertions.assertThat(countByExporter(FlowsSchema.qualifiedFlows(DATABASE), "exporterAddr", WLC))
                .isZero();
        Assertions.assertThat(countByExporter(FlowsSchema.qualifiedDeadLetter(DATABASE),
                "JSONExtractString(payload, 'exporterAddr')", WLC))
                .as("only the flows that could not be inserted are kept as dead letters")
                .isEqualTo(19);
        Assertions.assertThat(countByExporter(FlowsSchema.qualifiedDeadLetter(DATABASE),
                "JSONExtractString(payload, 'exporterAddr')", MX80))
                .isZero();
        Assertions.assertThat(counter(registry, "failedRows")).isEqualTo(19);
        Assertions.assertThat(counter(registry, "deadLetteredRows")).isEqualTo(19);
        Assertions.assertThat(counter(registry, "droppedRows")).isZero();
        Assertions.assertThat(deadLetterErrors(DATABASE, "JSONExtractString(payload, 'exporterAddr') = '" + WLC + "'"))
                .as("the record names both columns, so nobody has to ask whether dstAddr is affected too")
                .hasSize(19)
                .allSatisfy(error -> Assertions.assertThat(error)
                        .contains("null in non-nullable column(s) srcAddr, dstAddr"));
    }

    /** {@code dstAddr} alone is enough, and the error then names it alone. */
    @Test
    void aFlowWithoutADestinationAddressIsLeftOutAndTheErrorNamesThatColumn() throws Exception {
        final EnrichedFlow noDestination = flow("t", "org", 41001);
        noDestination.setDstAddr(null);
        final var registry = new MetricRegistry();

        try (var flusher = startedFlusher(DATABASE, registry)) {
            flusher.offer(List.of(flow("t", "org", 41002), noDestination, flow("t", "org", 41003)),
                    () -> counter(registry, "deadLetteredRows") >= 1);
        }

        Assertions.assertThat(count(FlowsSchema.qualifiedFlows(DATABASE) + " WHERE srcPort BETWEEN 41001 AND 41003"))
                .isEqualTo(2);
        Assertions.assertThat(deadLetterErrors(DATABASE, "JSONExtractInt(payload, 'srcPort') BETWEEN 41001 AND 41003"))
                .singleElement()
                .satisfies(error -> Assertions.assertThat(error)
                        .contains("null in non-nullable column(s) dstAddr")
                        .doesNotContain("srcAddr"));
        Assertions.assertThat(counter(registry, "failedRows")).isEqualTo(1);
    }

    /**
     * The check and the client agree, in the direction that loses data: no null the check lets
     * through is refused.
     *
     * <p>Every reference-typed field of the row is nulled in turn, past the mapper, on an otherwise
     * complete row. The check flags it or the insert succeeds. A third outcome is the bug this
     * change exists for, one column further on.
     */
    @Test
    void everyNullIsEitherFlaggedByTheCheckOrAcceptedByTheServer() throws Exception {
        final List<String> flagged = new ArrayList<>();
        final List<String> accepted = new ArrayList<>();
        for (final Field field : nullableFields()) {
            final var repository = repositoryOn(DATABASE, false, row -> setNull(row, field));
            repository.start();
            try {
                repository.persist(List.of(flow("t", "org", 42001)));
                accepted.add(field.getName());
            } catch (final UninsertableFlowsException e) {
                Assertions.assertThat(e.getMessage()).as("the reason names the column").contains(field.getName());
                flagged.add(field.getName());
            } catch (final FlowException e) {
                Assertions.fail("a null in '%s' passed the check and the insert was refused"
                        .formatted(field.getName()), e);
            } finally {
                repository.stop();
            }
        }

        Assertions.assertThat(flagged)
                .as("examined nothing, or the check flags nothing: either way this measured nothing")
                .contains("srcAddr", "dstAddr", "timestamp");
        Assertions.assertThat(accepted)
                .as("and a check that flags everything would pass the loop above just as well")
                .contains("nextHop", "srcAsOrg");
    }

    /** And in the other direction: a row with a null everywhere the check allows one is taken. */
    @Test
    void aRowWithEveryUnflaggedFieldNullIsStored() throws Exception {
        final var probe = repositoryOn(DATABASE, false);
        final List<String> required = RequiredColumns.of(probe.checkSchema().getColumns()).names();
        probe.stop();
        final List<Field> unflagged = nullableFields().stream()
                .filter(field -> !required.contains(field.getName()))
                .toList();
        Assertions.assertThat(unflagged).as("nothing to null, so nothing measured").isNotEmpty();

        final var repository = repositoryOn(DATABASE, false, row -> unflagged.forEach(field -> setNull(row, field)));
        repository.start();
        try {
            repository.persist(List.of(flow("t", "org", 43001)));
        } finally {
            repository.stop();
        }

        Assertions.assertThat(count(FlowsSchema.qualifiedFlows(DATABASE) + " WHERE srcPort = 43001")).isEqualTo(1);
    }

    /**
     * The reverse of the two above: every column the check flags is one the client refuses a null
     * in.
     *
     * <p>A check that flagged too much would leave storable flows out, and neither test above would
     * see it, because a flagged row is never sent there. So the check is bypassed here: a bare
     * client, registered with the schema the repository registers, is handed each flagged null.
     */
    @Test
    void everyColumnTheCheckFlagsIsOneTheClientRefusesANullIn() throws Exception {
        final var probe = repositoryOn(DATABASE, false);
        final TableSchema schema = probe.checkSchema();
        probe.stop();
        final List<String> flagged = RequiredColumns.of(schema.getColumns()).names();
        Assertions.assertThat(flagged).as("nothing flagged, so nothing measured").contains("srcAddr", "dstAddr");
        final var mapper = new ClickhouseRepository$FlowMapperImpl();

        try (Client bare = new Client.Builder().addEndpoint(endpoint())
                .setUsername("riptide").setPassword("riptide").setDefaultDatabase(DATABASE).build()) {
            bare.register(ClickhouseFlow.class, schema);
            // The control: this client and this row do insert, so a refusal below is the null's.
            bare.insert("flows", List.of(mapper.flow(flow("t", "org", 48000)))).get();

            for (final String column : flagged) {
                final ClickhouseFlow row = mapper.flow(flow("t", "org", 48001));
                setNull(row, ClickhouseFlow.class.getDeclaredField(column));
                Assertions.assertThatThrownBy(() -> bare.insert("flows", List.of(row)).get())
                        .as("the check flags '%s', so the client must refuse a null in it", column)
                        .hasStackTraceContaining("not nullable column '" + column + "'");
            }
        }

        Assertions.assertThat(count(FlowsSchema.qualifiedFlows(DATABASE) + " WHERE srcPort = 48000")).isEqualTo(1);
        Assertions.assertThat(count(FlowsSchema.qualifiedFlows(DATABASE) + " WHERE srcPort = 48001")).isZero();
    }

    /**
     * The check follows the live table: a column an operator gave a default or made
     * {@code Nullable} takes a null, and the server agrees.
     */
    @Test
    void aColumnTheOperatorGaveADefaultOrMadeNullableTakesANull() throws Exception {
        final var repository = repositoryOn(OPERATOR_DB, false, row -> {
            row.setZone(null);
            row.setExporterAddr(null);
        });
        repository.start();
        try {
            repository.persist(List.of(flow("t", "org", 47001)));
        } finally {
            repository.stop();
        }

        Assertions.assertThat(count(FlowsSchema.qualifiedFlows(OPERATOR_DB)
                + " WHERE srcPort = 47001 AND zone = 'operator-default' AND exporterAddr IS NULL"))
                .as("stored, with the default applied and the null kept")
                .isEqualTo(1);
    }

    /**
     * When the server refuses what is left, the batch is handled as a refused batch always was: all
     * of it charged, all of it kept, the left-out flows included.
     */
    @Test
    void aServerRefusalOfTheRemainingRowsStillCostsTheWholeBatch() throws Exception {
        final EnrichedFlow noSource = flow(GOOD_TENANT, "org", 44001);
        noSource.setSrcAddr(null);
        final List<EnrichedFlow> batch = List.of(flow(GOOD_TENANT, "org", 44002), flow("rejected", "org", 44003), noSource);
        final var registry = new MetricRegistry();

        try (var flusher = startedFlusher(BARRIER_DB, registry)) {
            flusher.offer(batch, () -> counter(registry, "deadLetteredRows") >= batch.size());
        }

        Assertions.assertThat(count(FlowsSchema.qualifiedFlows(BARRIER_DB))).isZero();
        Assertions.assertThat(counter(registry, "failedRows")).isEqualTo(batch.size());
        Assertions.assertThat(deadLetterErrors(BARRIER_DB, "1"))
                .hasSize(batch.size())
                .allSatisfy(error -> Assertions.assertThat(error).contains("VIOLATED_CONSTRAINT"));
    }

    /** Without a dead-letter table the left-out flows are counted as not kept, and the rest is stored. */
    @Test
    void withoutADeadLetterTableTheRestOfTheBatchIsStillStored() throws Exception {
        final EnrichedFlow noSource = flow("t", "org", 45001);
        noSource.setSrcAddr(null);
        final var registry = new MetricRegistry();

        try (var flusher = startedFlusher(UNMIGRATED_DB, registry)) {
            flusher.offer(List.of(flow("t", "org", 45002), noSource, flow("t", "org", 45003)),
                    () -> counter(registry, "deadLetterFailedRows") >= 1);
        }

        Assertions.assertThat(count(FlowsSchema.qualifiedFlows(UNMIGRATED_DB))).isEqualTo(2);
        Assertions.assertThat(counter(registry, "failedRows")).isEqualTo(1);
        Assertions.assertThat(counter(registry, "deadLetterFailedRows")).isEqualTo(1);
        Assertions.assertThat(counter(registry, "deadLetteredRows")).isZero();
    }

    /**
     * The unbatched path, where there is no flusher: the repository stores the rest of the call and
     * reports what it left out, which {@code Daemon} charges to {@code pipeline.dispatchErrors}
     * ({@code DaemonDispatcherTest.uninsertableFlowsAreChargedAloneNotThePacket}).
     */
    @Test
    void theUnbatchedPathStoresTheRestOfTheCallAndReportsWhatItLeftOut() throws Exception {
        final EnrichedFlow noSource = flow("t", "org", 46001);
        noSource.setSrcAddr(null);
        final var repository = repositoryOn(DATABASE, false);
        repository.start();
        try {
            Assertions.assertThatThrownBy(() -> repository.persist(
                            List.of(flow("t", "org", 46002), noSource, flow("t", "org", 46003))))
                    .isInstanceOfSatisfying(UninsertableFlowsException.class, e -> {
                        Assertions.assertThat(e.count()).isEqualTo(1);
                        Assertions.assertThat(e.rejected().values()).containsExactly(List.of(noSource));
                    });
        } finally {
            repository.stop();
        }

        Assertions.assertThat(count(FlowsSchema.qualifiedFlows(DATABASE) + " WHERE srcPort BETWEEN 46001 AND 46003"))
                .isEqualTo(2);
    }

    /** The fields of the row a null can be written to at all. */
    private static List<Field> nullableFields() {
        return Arrays.stream(ClickhouseFlow.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()) && !field.isSynthetic())
                .filter(field -> !field.getType().isPrimitive())
                .toList();
    }

    private static void setNull(final ClickhouseFlow row, final Field field) {
        try {
            field.setAccessible(true);
            field.set(row, null);
        } catch (final IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    /** The {@code error} of every dead letter matching a condition. */
    private static List<String> deadLetterErrors(final String database, final String where) throws Exception {
        final List<String> errors = new ArrayList<>();
        try (var rows = admin.queryRecords("SELECT error FROM " + FlowsSchema.qualifiedDeadLetter(database)
                + " WHERE " + where).get()) {
            rows.forEach(row -> errors.add(row.getString("error")));
        }
        return errors;
    }

    private static final ValueConversionService NETFLOW9_VALUES = new ValueConversionService(Netflow9RawFlow.class,
            List.of(new StringVisitor(), new BooleanVisitor(), new DoubleVisitor(), new DurationVisitor(),
                    new InetAddressVisitor(), new InstantVisitor(), new IntegerVisitor(), new LongVisitor(),
                    new UnsignedLongVisitor()));

    /** The flows of a NetFlow v9 exporter's datagrams, template first, in one session. */
    private static List<Flow> netflow9(final String... captures) throws Exception {
        final var session = new TcpSession(InetAddress.getLoopbackAddress(), () -> new SequenceNumberTracker(32));
        final var builder = new Netflow9FlowBuilder(NETFLOW9_VALUES);
        final List<Flow> flows = new ArrayList<>();
        for (final String capture : captures) {
            final ByteBuf buffer = Unpooled.wrappedBuffer(payload(capture));
            final var header = new org.riptide.flows.parser.netflow9.proto.Header(
                    slice(buffer, org.riptide.flows.parser.netflow9.proto.Header.SIZE));
            final var packet = new org.riptide.flows.parser.netflow9.proto.Packet(session, header, buffer);
            flows.addAll(builder.buildFlows(Instant.now(), packet).toList());
        }
        return flows;
    }

    private static List<Flow> netflow5(final String capture) throws Exception {
        final ByteBuf buffer = Unpooled.wrappedBuffer(payload(capture));
        final var header = new org.riptide.flows.parser.netflow5.proto.Header(
                slice(buffer, org.riptide.flows.parser.netflow5.proto.Header.SIZE));
        final var packet = new org.riptide.flows.parser.netflow5.proto.Packet(header, buffer);
        return new Netflow5FlowBuilder("test", new MetricRegistry()).buildFlows(Instant.now(), packet).toList();
    }

    private static byte[] payload(final String capture) throws Exception {
        return Files.readAllBytes(Paths.get(UninsertableFlowsIT.class.getResource(capture).toURI()));
    }

    /**
     * What the pipeline hands the persister before any enricher has run, moved to the present.
     *
     * <p>The move is not optional. The captures are years old and {@code flows} has a TTL on
     * {@code timestamp}, so an unmoved row is inserted and then removed by the next merge
     * (measured: {@code system.part_log} shows the 29-row part and a merge to 0 rows). A count of
     * {@code flows} would then read a stored row as a lost one.
     *
     * <p>Done by hand and not by {@code ClockCorrectionEnricher}, which would also set
     * {@code clockCorrection}: the client refuses that {@code Duration} for the column's
     * {@code Int64} ("Cannot convert PT… to Long", measured here), which is a different refusal
     * from the one under test and would take the batch for its own reason.
     */
    private static List<EnrichedFlow> enriched(final String exporter, final List<Flow> flows) throws Exception {
        final var source = new Source("default", InetAddress.getByName(exporter));
        final var mapper = new EnrichedFlow$FlowMapperImpl();
        final List<EnrichedFlow> enriched = flows.stream().map(flow -> mapper.enrichedFlow(source, flow)).toList();
        for (final EnrichedFlow flow : enriched) {
            final Duration age = Duration.between(flow.getTimestamp(), flow.getReceivedAt());
            flow.setTimestamp(flow.getTimestamp().plus(age));
            flow.setFirstSwitched(flow.getFirstSwitched().plus(age));
            flow.setDeltaSwitched(flow.getDeltaSwitched().plus(age));
            flow.setLastSwitched(flow.getLastSwitched().plus(age));
        }
        return enriched;
    }

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
            admin.execute("SYSTEM FLUSH ASYNC INSERT QUEUE").get();
        }

        @Override
        public void close() {
            this.repository.stop();
        }
    }

    private static ClickhouseRepository repositoryOn(final String database, final boolean manage) {
        return repositoryOn(database, manage, row -> { });
    }

    /**
     * @param afterMapping applied to every mapped row, which is the only way to hand the insert a
     *                     row the mapper itself would never produce
     */
    private static ClickhouseRepository repositoryOn(final String database, final boolean manage,
            final Consumer<ClickhouseFlow> afterMapping) {
        final var config = new ClickhouseConfig();
        config.setEndpoint(endpoint());
        config.setUsername(SecretRef.of("riptide"));
        config.setPassword(SecretRef.of("riptide"));
        config.setDatabase(database);
        config.setManageSchema(manage);
        config.setAsyncInserts(false);
        final var mapper = new ClickhouseRepository$FlowMapperImpl() {
            @Override
            public ClickhouseFlow flow(final EnrichedFlow flow) {
                final ClickhouseFlow row = super.flow(flow);
                afterMapping.accept(row);
                return row;
            }
        };
        return new ClickhouseRepository(mapper, config, RESOLVERS);
    }

    private static long countByExporter(final String table, final String expression, final String exporter)
            throws Exception {
        return count(table + " WHERE " + expression + " = '" + exporter + "'");
    }

    private static long count(final String from) throws Exception {
        try (var rows = admin.queryRecords("SELECT count() AS v FROM " + from).get()) {
            for (final var row : rows) {
                return row.getLong("v");
            }
        }
        throw new AssertionError("count() returned no record for " + from);
    }

    private static long counter(final MetricRegistry registry, final String name) {
        return registry.counter(MetricRegistry.name("persister", "batch", name)).getCount();
    }

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

    private static final Duration AWAIT_BUDGET = Duration.ofSeconds(30);

    private static String endpoint() {
        return "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123);
    }
}
