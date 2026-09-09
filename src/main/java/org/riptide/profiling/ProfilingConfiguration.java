/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.profiling;

import io.pyroscope.javaagent.PyroscopeAgent;
import io.pyroscope.javaagent.config.Config;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.riptide.config.DaemonConfig;
import org.riptide.pipeline.Identity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The consumer of {@code riptide.profiling.enabled}, and the only thing that starts the profiler.
 *
 * <p><b>A library, not a {@code -javaagent}.</b> Dependabot tracks Maven coordinates and nothing else here
 * tracks a jar fetched by URL, so "kept current" means "declared in the pom". {@code spring-boot-maven-plugin}
 * then repackages it into {@code BOOT-INF/lib}, and a nested jar cannot be a javaagent. Starting it in-process
 * is what remains, and it also touches no packaging path: the container, the deb/rpm, the Nix wrapper and a
 * bare {@code java -jar} all behave identically.
 *
 * <p>The cost is that nothing before the Spring context is profiled. That is acceptable for what this is
 * wanted for — the classification tree build that blocks classification at startup happens after context
 * init — and it is not acceptable for anything earlier, which this cannot see.
 */
@Slf4j
@Configuration
public class ProfilingConfiguration {

    /**
     * A stable name when the operator has not chosen one.
     *
     * <p>Without this the agent generates {@code javaspy.<random>} per process — verified: two
     * {@code Config.build()} calls in one JVM already differ. Every restart would create a fresh unfindable
     * service, with the identity labels below attached to a name nobody can search for.
     */
    private static final String DEFAULT_APPLICATION_NAME = "riptide";

    /**
     * Starts the profiler, or explains once why it did not.
     *
     * <p>Returns a marker rather than {@code void} so the decision is a bean with a value a test can read.
     * A {@code void} {@code @PostConstruct} would leave "did it start" answerable only by asking the agent's
     * global state, which is process-wide and survives between tests.
     *
     * <p><b>Nothing here may abort the context.</b> Profiling is optional; flow ingest is not. The agent
     * loads a native library and parses the {@code PYROSCOPE_*} environment, and either can throw — an
     * unreadable or {@code noexec} temp directory, a malformed {@code PYROSCOPE_UPLOAD_INTERVAL}, a native
     * library that will not load on this libc. Pyroscope's own {@code try/catch} inside
     * {@code PyroscopeAgent.start} does not cover the construction that precedes it, so without the guard
     * below a collector would refuse to start because an optional profiler could not.
     */
    @Bean
    ProfilingStatus profilingStatus(final RiptideProfilingProperties properties, final DaemonConfig daemon) {
        if (!properties.isEnabled()) {
            // Deliberately not logged. Off is the default and the overwhelmingly common state; a line here
            // would appear in every boot of every deployment to report that nothing happened.
            return ProfilingStatus.disabled();
        }

        try {
            return start(daemon.resolveIdentity());
        } catch (final Throwable t) {
            // Throwable, not Exception: a native library that will not load raises UnsatisfiedLinkError.
            log.error("Continuous profiling was enabled but could not start; the collector continues without"
                    + " it. Flow ingest is unaffected and no profile will be produced.", t);
            return ProfilingStatus.disabled();
        }
    }

    /**
     * The configuration riptide hands the agent, separated from starting it so the wiring can be asserted.
     *
     * <p>{@code Config.build()} and {@code Config.Builder.build()} are pure: they read the environment and
     * system properties and construct a value. Neither opens a connection nor loads the native library, which
     * happens inside {@code PyroscopeAgent.start} by way of {@code ProfilerDelegate.create}. So this is safe
     * to call in a test, and the three overrides are pinned rather than trusted. Review found that deleting
     * {@code setLabels} survived the whole suite while this was inline.
     *
     * <p>Riptide overrides three things and no more: it enables the agent, because reaching here is the
     * decision to enable; it merges in the identity labels, which it alone knows; and it supplies a stable
     * application name if the operator did not choose one.
     *
     * <p>Note that {@code setAgentEnabled(true)} overrides {@code PYROSCOPE_AGENT_ENABLED} unconditionally.
     * It is the one variable in that vocabulary riptide does not defer to, because {@code
     * riptide.profiling.enabled} is the switch this project documents.
     */
    static Config buildConfig(final Identity identity) {
        final Config base = Config.build();
        return base.newBuilder()
                .setAgentEnabled(true)
                .setApplicationName(applicationName(base.applicationName))
                .setLabels(mergedLabels(base.labels, identity))
                .build();
    }

    private ProfilingStatus start(final Identity identity) {
        final Config config = buildConfig(identity);
        final Map<String, String> labels = config.labels;

        PyroscopeAgent.start(config);

        // start() swallows its own failures: its handler spans the scheduler start, which is the call that
        // actually starts async-profiler, and on failure it writes to System.err through its own logger,
        // resets its options and returns normally. Without this check riptide would log "started" at INFO
        // while nothing sampled, and the operator would read green with no profile ever arriving.
        //
        // The two failures this was written for have since been measured and neither occurred: the native
        // library loads and profiles correctly on musl (801 samples, 99.88% attributed, against 99.75% on
        // glibc), and both itimer and cpu start under the shipped hardened unit at perf_event_paranoid=4.
        // The guard stays because it costs one call and the failure it catches is silent -- an unreadable
        // or noexec temp directory, a malformed interval, a libc nobody has tried yet.
        if (!PyroscopeAgent.isStarted()) {
            log.error("Continuous profiling was enabled but the agent did not start; the collector continues"
                    + " without it and no profile will be produced. The agent reports its own reason on"
                    + " standard error rather than through this log. application={} event={}",
                    config.applicationName, config.profilingEvent);
            return ProfilingStatus.disabled();
        }

        // profilingEvent, not profilerType, is the sampling mode: profilerType is ASYNC vs JFR and says
        // nothing about what is being sampled. The default event is ITIMER, which measures CPU time via
        // setitimer(ITIMER_PROF) and needs no perf_event_open. Wall clock is the separate `wall` event.
        //
        // An earlier version of this comment said only `cpu` reaches perf_event_open and only that case can
        // be refused by a hardened unit file. The refusal half was never measured and does not hold: on a
        // real deployment under the shipped unit at perf_event_paranoid=4, cpu started and produced
        // correctly attributed samples. What remains true, and is why the log line hedges below, is that
        // the agent exposes no reading of the mode it actually obtained -- a successful perf_event_open and
        // a silent internal degrade are indistinguishable from here.
        log.info("Continuous profiling started: application={} event={} profiler={} server={} labels={}."
                        + " The event named here is the one configured; the agent exposes no reading of what"
                        + " the process actually obtained.",
                config.applicationName, config.profilingEvent, config.profilerType,
                config.serverAddress, labels);

        return ProfilingStatus.started(config.applicationName, labels);
    }

    /**
     * A stable application name, unless the operator chose one.
     *
     * <p>The agent's fallback is {@code javaspy.<random>} regenerated per call, so the test is whether the
     * resolved name is that fallback rather than whether an environment variable happens to be set — which
     * also covers the system-property form riptide does not read.
     */
    static String applicationName(final String resolved) {
        return isGenerated(resolved) ? DEFAULT_APPLICATION_NAME : resolved;
    }

    /**
     * The agent's fallback shape, matched precisely rather than by prefix.
     *
     * <p>{@code generateApplicationName()} produces {@code "javaspy."} followed by an unpadded base64url
     * UUID, always 22 characters. A bare prefix test would also rename an operator who deliberately set
     * {@code PYROSCOPE_APPLICATION_NAME=javaspy.staging}, contradicting both the documentation and
     * {@code aConfiguredApplicationNameIsKept}.
     */
    private static boolean isGenerated(final String resolved) {
        if (resolved == null) {
            return true;
        }
        final String prefix = Config.DEFAULT_SPY_NAME + ".";
        if (!resolved.startsWith(prefix)) {
            return false;
        }
        final String tail = resolved.substring(prefix.length());
        return tail.length() == 22 && tail.chars().allMatch(ProfilingConfiguration::isBase64Url);
    }

    private static boolean isBase64Url(final int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9') || c == '-' || c == '_';
    }

    /**
     * The deployment identity, merged over whatever the operator configured.
     *
     * <p><b>Merged, not replaced.</b> {@code Config.build()} populates {@code labels} from
     * {@code PYROSCOPE_LABELS}, and {@code setLabels} overwrites the map wholesale — so an operator who
     * followed Pyroscope's documentation would have had their labels silently vanish, with nothing logged.
     *
     * <p>Riptide's four win on a key collision, deliberately: they are resolved from {@code riptide.identity.*}
     * and describe what this collector actually is, which a hand-set label cannot override into being true.
     */
    static Map<String, String> mergedLabels(final Map<String, String> configured, final Identity identity) {
        final Map<String, String> labels = new LinkedHashMap<>();
        if (configured != null) {
            labels.putAll(configured);
        }
        labels.put("tenant", identity.tenant());
        labels.put("organisation", identity.organisation());
        labels.put("zone", identity.zone());
        labels.put("system", identity.system());
        return labels;
    }

    /**
     * Stops the profiler on an orderly shutdown.
     *
     * <p>Both agent threads are daemons, so nothing hangs without this. What it buys is that the queued
     * exporter is not killed mid-flight, so the final upload interval is not simply discarded.
     */
    @PreDestroy
    void stopProfiler() {
        if (PyroscopeAgent.isStarted()) {
            PyroscopeAgent.stop();
        }
    }

    /** What the decision was, as a value a test can read rather than global agent state. */
    public record ProfilingStatus(boolean enabled, String applicationName, Map<String, String> labels) {

        public ProfilingStatus {
            // The one copy that matters. Without it a caller could edit a published status behind the
            // reader's back; theStatusHoldsACopyOfItsLabels reds if it is removed.
            //
            // Do not reshape any of this to appease CodeQL's java/internal-representation-exposure.
            // Nothing you can write here will work, and that is measured rather than argued. On PR #791
            // the copy was moved onto the field itself, by spelling out the canonical constructor
            // instead of this compact one -- the exact shape the rule's syntactic half looks for. It
            // was still flagged, as alert 169, on that constructor. De-recording the type on the same
            // branch, changing nothing else, took the scan from one result to zero.
            //
            // So the trigger is the record: extraction contributes the implicit component assignment
            // `this.labels = labels`, a bare parameter access, whatever the constructor does. The rule's
            // other half then finds a caller that mutates its argument afterwards, which is
            // theStatusHoldsACopyOfItsLabels doing precisely what proves this copy holds. Only ceasing
            // to be a record clears it, and ~30 lines of accessors, equals and hashCode is a bad trade
            // for a rule that is wrong about code the test already pins.
            //
            // Expect to re-dismiss it. The alert renumbers on any nearby edit, and the dismissal does
            // not follow: 167 was dismissed, the next commit moved the line, and the same scan closed
            // 167 and opened 168 undismissed. That is 164, 165, 166, 167, 168 and 169 for one finding.
            labels = labels != null ? Map.copyOf(labels) : Map.of();
        }

        static ProfilingStatus disabled() {
            return new ProfilingStatus(false, null, Map.of());
        }

        static ProfilingStatus started(final String applicationName, final Map<String, String> labels) {
            return new ProfilingStatus(true, applicationName, labels);
        }
    }
}
