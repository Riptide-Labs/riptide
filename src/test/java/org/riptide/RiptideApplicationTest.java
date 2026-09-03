/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dispatch every context-free subcommand goes through, driven the way {@code main} drives it.
 *
 * <p>{@code CliLoggingTest} pins that the routing helper works. This pins that something calls it:
 * deleting the one call from {@link RiptideApplication#dispatchContextFree} restores the whole of
 * #727 — a log record inside the operator's generated configuration — and a suite that only tested
 * the helper stayed green through exactly that deletion.</p>
 *
 * <p>Two properties beyond "a record was routed", because a weaker test survives the same edit.
 * The routing has to happen <em>before</em> the subcommand runs, which is asserted by where the
 * conversion's own warning lands relative to the summary that conversion writes afterwards; and it
 * has to cover the provisioning dispatch, not only {@code convert}, because sharing one call site
 * is the thing that keeps a future split from reintroducing half a fix.</p>
 */
class RiptideApplicationTest {

    /** One node, and a cadence whose snapshots expire faster than the walk refreshes them. */
    private static final String LEGACY = """
            riptide:
              snmp:
                poll:
                  refresh-interval-ms: 900000
                  snapshot-expiry-ms: 300000
              nodes:
                core-router:
                  subnet-address: 10.20.30.7
                  snmp:
                    snmp-version: v3
                    security-name: monitoring
            """;

    private ch.qos.logback.classic.Logger root;
    private List<Appender<ILoggingEvent>> attached;
    private Level level;

    @BeforeEach
    void captureLoggingSetup() {
        this.root = ((LoggerContext) LoggerFactory.getILoggerFactory())
                .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        this.attached = new ArrayList<>();
        this.root.iteratorForAppenders().forEachRemaining(this.attached::add);
        this.level = this.root.getLevel();
    }

    @AfterEach
    void restoreLoggingSetup() {
        // detached, not stopped: these are the appenders the rest of the suite logs through, and
        // stopping one is not undone by attaching it again
        final List<Appender<ILoggingEvent>> current = new ArrayList<>();
        this.root.iteratorForAppenders().forEachRemaining(current::add);
        current.forEach(this.root::detachAppender);
        this.attached.forEach(this.root::addAppender);
        this.root.setLevel(this.level);
    }

    /**
     * The defect, at the seam: {@code riptide convert nodes.yaml} redirected into a file must put
     * the generated documents on stdout and every log record somewhere else.
     *
     * <p>The ordering is what the last assertion is for. A conversion emits its warning while it
     * converts and its summary after, both to the diagnostic stream, so a routing installed after
     * the dispatch would leave the warning out of this buffer entirely — and a routing that
     * happened at some unspecified point cannot put the record ahead of the summary.</p>
     */
    @Test
    void theConvertDispatchRoutesBeforeItConverts(@TempDir final Path dir) throws IOException {
        final Path legacy = Files.writeString(dir.resolve("legacy.yaml"), LEGACY);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();

        final OptionalInt exit = RiptideApplication.dispatchContextFree(
                new String[] {"convert", legacy.toString()},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(diagnostics, true, StandardCharsets.UTF_8));

        assertThat(exit).hasValue(0);
        assertThat(out.toString(StandardCharsets.UTF_8))
                .as("the operator's redirected file holds the two documents and nothing else")
                .contains("snapshot-expiry: PT5M")
                .doesNotContain("WARN");
        final String reported = diagnostics.toString(StandardCharsets.UTF_8);
        assertThat(reported)
                .as("the record the conversion emitted went to the diagnostic stream")
                .contains("expires snapshots (PT5M) faster than it refreshes them (PT15M)");
        assertThat(reported.indexOf("expires snapshots"))
                .as("routed before the conversion ran, not after it finished")
                .isLessThan(reported.indexOf("Converted 1 node(s)"));
    }

    /**
     * The provisioning dispatch is routed by the same call. It is asserted separately because the
     * one call site is the whole point: a split that routed only {@code convert} would leave
     * {@code riptide onboard} writing log records into the config stanza it prints on stdout.
     *
     * <p>{@code onboard} with no admin URL fails before it opens a connection, which is as far as
     * this can go without a ClickHouse — so the ordering property is the convert case's to carry,
     * and what is pinned here is that this dispatch installs the routing at all.</p>
     */
    @Test
    void theProvisioningDispatchIsRoutedByTheSameCall() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();

        final OptionalInt exit = RiptideApplication.dispatchContextFree(
                new String[] {"onboard"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(diagnostics, true, StandardCharsets.UTF_8));

        assertThat(exit).as("the provisioning subcommand ran through this dispatch").hasValue(2);
        assertThat(diagnostics.toString(StandardCharsets.UTF_8)).contains("missing required --admin-url");

        LoggerFactory.getLogger(RiptideApplicationTest.class).warn("a record from the onboard run");
        assertThat(diagnostics.toString(StandardCharsets.UTF_8))
                .as("logging was routed on this path too, not only on convert's")
                .contains("a record from the onboard run");
        assertThat(out.toString(StandardCharsets.UTF_8))
                .as("stdout is where the config stanza goes")
                .isEmpty();
    }

    /**
     * Anything else is the collector, and its logging is untouched: the routing is a CLI fix, and
     * reconfiguring Logback for a Spring run would change collector startup logging as a side
     * effect — which is the same reason a root {@code logback.xml} was not the answer.
     */
    @Test
    void thePathThatStartsTheCollectorIsNotRouted() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();

        final OptionalInt exit = RiptideApplication.dispatchContextFree(
                new String[] {"--server.port=0"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(diagnostics, true, StandardCharsets.UTF_8));

        assertThat(exit).as("no subcommand matched, so the collector starts").isEmpty();
        LoggerFactory.getLogger(RiptideApplicationTest.class).warn("a record on the collector path");
        assertThat(diagnostics.toString(StandardCharsets.UTF_8)).isEmpty();
    }
}
