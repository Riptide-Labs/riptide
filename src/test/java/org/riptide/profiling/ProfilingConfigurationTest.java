/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.profiling;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.riptide.config.DaemonConfig;
import org.riptide.pipeline.Identity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The consumer of {@code riptide.profiling.enabled}, pinned.
 *
 * <p>This project's rule is that an operator-settable key must name the consumer that reads it and the test
 * proving the read — a key nothing consumes is a key that silently does nothing, found by an operator rather
 * than by CI. {@link ProfilingConfiguration} is that consumer and this is that test.
 *
 * <p><b>What these rows deliberately do not do is start the profiler.</b> {@code PyroscopeAgent} keeps
 * process-wide state and opens an outbound connection, so a row that started it would leak into every test
 * that ran after it in the same JVM and would try to reach a server that is not there. The enabled path is
 * covered by its parts — the label mapping, and the default being off — rather than by running it.
 */
class ProfilingConfigurationTest {

    /**
     * Off unless asked for. The most important row here, and the cheapest to lose.
     *
     * <p>A profiler that starts unasked samples the process and ships what it finds somewhere. If a later
     * refactor flips this default, nothing else in the suite would notice: the collector would still ingest
     * flows, still classify, still answer its probes, and would additionally be making an outbound
     * connection nobody asked for.
     */
    @Test
    void profilingIsOffUnlessAskedFor() {
        assertThat(new RiptideProfilingProperties().isEnabled())
                .as("a profiler samples the process and ships what it finds to a server; both are an"
                        + " operator's choice, not something they should discover")
                .isFalse();
    }

    /**
     * The disabled path returns a status saying so, and does not touch the agent.
     *
     * <p>Reading the returned value rather than the agent's global state is the point: {@code
     * PyroscopeAgent.isStarted()} is process-wide, so a row asserting on it would pass or fail according to
     * what some other test did earlier in the same JVM.
     */
    @Test
    void aCollectorWithProfilingOffStartsNoProfiler() {
        final var properties = new RiptideProfilingProperties();
        final var status = new ProfilingConfiguration().profilingStatus(properties, new DaemonConfig());

        assertThat(status.enabled()).isFalse();
        assertThat(status.labels()).as("nothing was configured, so there is nothing to label").isEmpty();
    }

    /**
     * A failure to start must not take the collector down with it.
     *
     * <p>Review found this unguarded, and it is the finding that mattered most: profiling is optional and
     * flow ingest is not. The agent loads a native library and parses the {@code PYROSCOPE_*} environment,
     * and either can throw — an unreadable or {@code noexec} temp directory, a malformed interval, a native
     * library that will not load on this libc. Unguarded inside a {@code @Bean}, any of those becomes a
     * {@code BeanCreationException} and the collector refuses to start because a profiler could not.
     */
    @Test
    void aProfilerThatCannotStartLeavesTheCollectorRunning() {
        final var properties = new RiptideProfilingProperties();
        properties.setEnabled(true);
        // Identity resolution throwing stands in for any failure on the start path -- a native library
        // that will not load, an unreadable temp directory, a malformed interval. What is pinned is that
        // the bean method returns rather than propagating. DaemonConfig is final, hence the mock.
        final var exploding = Mockito.mock(DaemonConfig.class);
        Mockito.when(exploding.resolveIdentity())
                .thenThrow(new IllegalStateException("simulated failure on the profiler start path"));

        final var status = new ProfilingConfiguration().profilingStatus(properties, exploding);

        assertThat(status.enabled())
                .as("an optional profiler that cannot start must not abort the context; ingest is not"
                        + " optional and must survive it")
                .isFalse();
    }

    /**
     * Operator labels survive. {@code Config.build()} reads {@code PYROSCOPE_LABELS}, and {@code setLabels}
     * replaces the map wholesale — so an operator following Pyroscope's own documentation would have had
     * theirs silently discarded, with nothing logged to say so.
     */
    @Test
    void configuredLabelsAreMergedRatherThanReplaced() {
        final var configured = java.util.Map.of("region", "eu-west", "role", "edge");
        final var identity = new Identity("acme", "acme-net", "eu-west-1", "collector-7");

        final var labels = ProfilingConfiguration.mergedLabels(configured, identity);

        assertThat(labels).containsEntry("region", "eu-west").containsEntry("role", "edge");
        assertThat(labels).containsEntry("zone", "eu-west-1");
    }

    /** On a key collision the identity wins: it describes what this collector is, and a label cannot. */
    @Test
    void theIdentityWinsOverAConfiguredLabelOfTheSameName() {
        final var configured = java.util.Map.of("zone", "somewhere-the-operator-typed");
        final var identity = new Identity("acme", "acme-net", "eu-west-1", "collector-7");

        assertThat(ProfilingConfiguration.mergedLabels(configured, identity))
                .containsEntry("zone", "eu-west-1");
    }

    /**
     * A stable application name when the operator has not chosen one.
     *
     * <p>The agent generates {@code javaspy.<random>} per call — two {@code Config.build()} calls in one JVM
     * already differ — so every restart would create a fresh service in Pyroscope that nobody can search
     * for, with the identity labels attached to a name that never recurs.
     */
    @Test
    void aGeneratedApplicationNameIsReplacedWithAStableOne() {
        assertThat(ProfilingConfiguration.applicationName("javaspy.enmYhrRCTBOjNFXHcMnuHw"))
                .as("the agent's per-process fallback must not reach a server as a service name")
                .isEqualTo("riptide");
        assertThat(ProfilingConfiguration.applicationName(null)).isEqualTo("riptide");
    }

    /** An operator who chose a name keeps it. */
    @Test
    void aConfiguredApplicationNameIsKept() {
        assertThat(ProfilingConfiguration.applicationName("riptide-edge-01")).isEqualTo("riptide-edge-01");
    }

    /**
     * A chosen name that merely starts with the agent's prefix is still the operator's.
     *
     * <p>Review found a bare prefix test would rename {@code javaspy.staging} to {@code riptide}, which
     * contradicts both the docs and the row above. The generated shape is the prefix plus exactly 22
     * base64url characters, so the test matches that rather than the prefix alone.
     */
    @Test
    void aChosenNameThatLooksLikeThePrefixIsNotRenamed() {
        assertThat(ProfilingConfiguration.applicationName("javaspy.staging")).isEqualTo("javaspy.staging");
        assertThat(ProfilingConfiguration.applicationName("javaspy.this-tail-is-far-too-long-to-be-a-uuid"))
                .isEqualTo("javaspy.this-tail-is-far-too-long-to-be-a-uuid");
    }

    /**
     * The three overrides riptide makes actually reach the config the agent is handed.
     *
     * <p>Review found this unpinned: with the builder calls inline, deleting {@code setLabels} survived the
     * whole suite, so the read of {@code Config.labels} — an operator-settable input — had no test proving
     * it. {@code Config.build()} and the builder are pure, opening no connection and loading no native
     * library, so the wiring is assertable without the process-wide state that keeps the enabled path itself
     * out of these tests.
     */
    @Test
    void theConfigHandedToTheAgentCarriesRiptidesThreeOverrides() {
        final var identity = new Identity("acme", "acme-net", "eu-west-1", "collector-7");

        final var config = ProfilingConfiguration.buildConfig(identity);

        assertThat(config.agentEnabled).as("reaching here is the decision to enable").isTrue();
        assertThat(config.applicationName)
                .as("a generated per-process name must not reach a server")
                .isEqualTo("riptide");
        assertThat(config.labels)
                .as("the identity has to arrive on the config the agent is given, not merely be computed")
                .containsEntry("tenant", "acme")
                .containsEntry("organisation", "acme-net")
                .containsEntry("zone", "eu-west-1")
                .containsEntry("system", "collector-7");
    }

    /**
     * Every field of the identity reaches the labels, under the name a query would use.
     *
     * <p>This is the mapping that makes a profile filterable when more than one collector reports to the
     * same server, and it is the part a bare javaagent could not supply — the agent has no notion of a
     * tenant or a zone. Asserted field by field rather than by size, so renaming one silently to another's
     * value cannot pass.
     */
    @Test
    void everyIdentityFieldBecomesALabel() {
        final var identity = new Identity("acme", "acme-net", "eu-west", "collector-7");

        final var labels = ProfilingConfiguration.mergedLabels(java.util.Map.of(), identity);

        assertThat(labels)
                .as("a renamed or dropped field would leave a profile that cannot be filtered by the"
                        + " dimension an operator actually has")
                .containsExactly(
                        java.util.Map.entry("tenant", "acme"),
                        java.util.Map.entry("organisation", "acme-net"),
                        java.util.Map.entry("zone", "eu-west"),
                        java.util.Map.entry("system", "collector-7"));
    }

    /** Labels are a snapshot, not a live view: a caller mutating its map must not reshape a running profile. */
    @Test
    void theStatusHoldsACopyOfItsLabels() {
        final var mutable = new java.util.LinkedHashMap<String, String>();
        mutable.put("zone", "eu-west");

        final var status = new ProfilingConfiguration.ProfilingStatus(true, "riptide", mutable);
        mutable.put("zone", "somewhere-else");

        assertThat(status.labels()).containsExactly(java.util.Map.entry("zone", "eu-west"));
    }
}
