/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.clickhouse.client.api.Client;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.riptide.config.ClickhouseConfig;
import org.riptide.e2e.ContainerImages;
import org.riptide.mcp.service.QueryRouter;
import org.riptide.provisioning.ProvisioningDdl;
import org.riptide.schema.FlowsSchema;
import org.riptide.schema.RollupAvailability;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rollup shape drift against a real server, through a real scoped writer (#470).
 *
 * <p><b>Why the writer and not {@code default}.</b> The collector connects as the provisioned
 * writer, and that role sees a filtered {@code system.tables}: ClickHouse hides objects it holds no
 * grant on rather than refusing the query, so an ungranted materialized view reads as zero rows —
 * the same thing an absent view reads as. Running this through an all-privileges user would make
 * every assertion here pass while telling us nothing about the path operators run. That blindness
 * is exactly what hid the grant problem from this change's first draft.</p>
 */
@Testcontainers
public class RollupShapeDriftIT {

    private static final String DATABASE = "drift";
    private static final SecretResolvers RESOLVERS = SecretResolvers.defaults();

    @Container
    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>(ContainerImages.clickhouse())
            .withEnv("CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT", "1")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    private static String endpoint;
    private static Client admin;

    @BeforeAll
    static void provision() throws Exception {
        endpoint = "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123);
        admin = new Client.Builder()
                .addEndpoint(endpoint)
                .setUsername("default")
                .setPassword("")
                .setDefaultDatabase("default")
                .build();

        // Manage mode as the admin creates the schema; then the shared roles and a scoped writer,
        // exactly as `riptide onboard` would.
        new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(), config("default", "", true), RESOLVERS)
                .start();
        for (final String ddl : ProvisioningDdl.ensureShared(DATABASE, 1L)) {
            admin.execute(ddl).get();
        }
        admin.execute("CREATE USER IF NOT EXISTS writer IDENTIFIED WITH plaintext_password BY 'pw'").get();
        admin.execute("GRANT flow_writer TO writer").get();
    }

    private static ClickhouseConfig config(final String user, final String password, final boolean manage) {
        final var config = new ClickhouseConfig();
        config.setEndpoint(endpoint);
        config.setDatabase(DATABASE);
        config.setUsername(SecretRef.of(user));
        // An unset ref binds the empty password, which is what the container's `default` user has;
        // SecretRef.of("") is rejected outright.
        if (!password.isEmpty()) {
            config.setPassword(SecretRef.of(password));
        }
        config.setManageSchema(manage);
        config.setAsyncInserts(false);
        return config;
    }

    /** Start as the scoped writer in provisioned mode, capturing what the repository logs. */
    private static List<ILoggingEvent> startWriterAndCapture() {
        final Logger logger = (Logger) LoggerFactory.getLogger(ClickhouseRepository.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new ClickhouseRepository(new ClickhouseRepository$FlowMapperImpl(),
                    config("writer", "pw", false), RESOLVERS).start();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static List<String> messages(final List<ILoggingEvent> events) {
        return events.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * A deployment provisioned by this version is silent. Guards the normalisation: if the emitted
     * SELECT did not compare equal to what the server stores, this would warn about all four
     * rollups on every start — and an operator who learns to ignore the warning is worse off than
     * one who never had it.
     */
    @Test
    void aCurrentDeploymentReportsNothingAndKeepsItsRollups() {
        RollupAvailability.recordDrifted(List.of());

        final List<String> logged = messages(startWriterAndCapture());

        assertThat(logged).noneMatch(m -> m.contains("does not match this version's schema"));
        assertThat(logged).noneMatch(m -> m.contains("could not be verified"));
        for (final String rollup : FlowsSchema.rollupTableNames()) {
            assertThat(RollupAvailability.usable(FlowsSchema.qualifiedRollup(DATABASE, rollup))).isTrue();
        }
    }

    /**
     * The case a column comparison cannot see: the aggregate changed, every column name did not.
     * Also the routing consequence — detection that left the rollup reachable would only write the
     * wrong answer into a log while continuing to serve it.
     */
    @Test
    void aChangedViewExpressionIsReportedAndTheRollupIsDeclined() throws Exception {
        final String rollup = FlowsSchema.ROLLUP_BY_APPLICATION;
        final String mv = FlowsSchema.qualifiedRollupView(DATABASE, rollup);
        admin.execute("DROP VIEW IF EXISTS " + mv).get();
        admin.execute("CREATE MATERIALIZED VIEW " + mv + " TO " + FlowsSchema.qualifiedRollup(DATABASE, rollup)
                + " AS " + FlowsSchema.rollupSelects(DATABASE).get(rollup)
                        .replace("sumIf(f.packets, f.direction = 'EGRESS') AS packetsOut",
                                "sum(f.packets) AS packetsOut")).get();
        admin.execute("GRANT SELECT ON " + mv + " TO flow_writer").get();
        try {
            RollupAvailability.recordDrifted(List.of());

            final List<String> logged = messages(startWriterAndCapture());

            assertThat(logged).anyMatch(m -> m.contains(rollup) && m.contains("does not match"));
            assertThat(RollupAvailability.usable(FlowsSchema.qualifiedRollup(DATABASE, rollup))).isFalse();
            assertThat(QueryRouter.resolveTopTalkersTable(DATABASE, 120, "application"))
                    .isEqualTo(FlowsSchema.qualifiedFlows(DATABASE));
            // one stale rollup must not cost the other three
            assertThat(QueryRouter.resolveInterfaceTable(DATABASE, 120))
                    .isEqualTo(FlowsSchema.qualifiedRollup(DATABASE, FlowsSchema.ROLLUP_BY_EXPORTER_IFACE));
        } finally {
            admin.execute("DROP VIEW IF EXISTS " + mv).get();
            admin.execute(FlowsSchema.createRollupViews(DATABASE).stream()
                    .filter(ddl -> ddl.contains(rollup + "_mv")).findFirst().orElseThrow()).get();
            admin.execute("GRANT SELECT ON " + mv + " TO flow_writer").get();
            RollupAvailability.recordDrifted(List.of());
        }
    }

    /**
     * The grant problem, and the reason it needs its own outcome. Without the grant the view is
     * zero rows — indistinguishable from absent — so calling it stale would condemn every
     * deployment provisioned before that grant existed, and calling it fine would be silent on real
     * drift.
     */
    @Test
    void aViewTheWriterCannotSeeIsReportedAsUnverifiableAndStillUsed() throws Exception {
        final String rollup = FlowsSchema.ROLLUP_BY_CONVERSATION;
        final String mv = FlowsSchema.qualifiedRollupView(DATABASE, rollup);
        admin.execute("REVOKE SELECT ON " + mv + " FROM flow_writer").get();
        try {
            RollupAvailability.recordDrifted(List.of());

            final List<String> logged = messages(startWriterAndCapture());

            assertThat(logged)
                    .as("an unreadable view must name the grant, not accuse the rollup")
                    .anyMatch(m -> m.contains(rollup) && m.contains("could not be verified")
                            && m.contains("GRANT SELECT"));
            assertThat(logged).noneMatch(m -> m.contains(rollup) && m.contains("does not match"));
            assertThat(RollupAvailability.usable(FlowsSchema.qualifiedRollup(DATABASE, rollup)))
                    .as("a rollup that could not be checked is not thereby known to be wrong")
                    .isTrue();
        } finally {
            admin.execute("GRANT SELECT ON " + mv + " TO flow_writer").get();
            RollupAvailability.recordDrifted(List.of());
        }
    }

    /** A column the running version expects and the table never got. */
    @Test
    void aMissingTargetColumnIsReported() throws Exception {
        final String rollup = FlowsSchema.ROLLUP_BY_GEO_ASN;
        final String target = FlowsSchema.qualifiedRollup(DATABASE, rollup);
        final String mv = FlowsSchema.qualifiedRollupView(DATABASE, rollup);
        admin.execute("DROP VIEW IF EXISTS " + mv).get();
        admin.execute("ALTER TABLE " + target + " DROP COLUMN packetsOut").get();
        try {
            RollupAvailability.recordDrifted(List.of());

            final List<String> logged = messages(startWriterAndCapture());

            assertThat(logged).anyMatch(m -> m.contains(rollup) && m.contains("missing")
                    && m.contains("packetsOut"));
            assertThat(RollupAvailability.usable(target)).isFalse();
        } finally {
            admin.execute("ALTER TABLE " + target + " ADD COLUMN IF NOT EXISTS packetsOut UInt64").get();
            admin.execute(FlowsSchema.createRollupViews(DATABASE).stream()
                    .filter(ddl -> ddl.contains(rollup + "_mv")).findFirst().orElseThrow()).get();
            admin.execute("GRANT SELECT ON " + mv + " TO flow_writer").get();
            RollupAvailability.recordDrifted(List.of());
        }
    }
}
