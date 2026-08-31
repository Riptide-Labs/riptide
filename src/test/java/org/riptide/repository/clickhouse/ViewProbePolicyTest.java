/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import com.clickhouse.client.api.ServerException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.riptide.schema.FlowsSchema;
import org.riptide.schema.RollupShapeCheck;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which views get probed, and what each server code means (#587).
 *
 * <p>Both halves are policy the shape check depends on and neither is reachable from an
 * integration test: a deployment cannot be put in the dropped-database state without hand-built
 * DDL, and "no query was issued" is not observable from a verdict at all.</p>
 */
@Timeout(30)
class ViewProbePolicyTest {

    /** Every view visible: the case every healthy deployment is in, and it must cost nothing. */
    @Test
    void aDeploymentWhoseViewsAreAllVisibleIsNotProbedAtAll() {
        final Map<String, String> selects = new LinkedHashMap<>();
        FlowsSchema.rollupTableNames().forEach(rollup -> selects.put(FlowsSchema.rollupViewName(rollup), "SELECT 1"));

        assertThat(selects.keySet())
                .as("the fixture must name every rollup's view by the convention the code under test"
                        + " uses. Asserting its size against the same list it was built from could"
                        + " only fail on duplicate rollup names, which is not the risk here: the"
                        + " risk is the _mv suffix drifting apart between fixture and production")
                .containsExactlyInAnyOrderElementsOf(FlowsSchema.rollupTableNames().stream()
                        .map(FlowsSchema::rollupViewName).toList());
        assertThat(ClickhouseRepository.rollupsNeedingProbe(selects, allTargets()))
                .as("a healthy deployment must issue no probe: this change is only free for"
                        + " everyone not in the broken state if nothing is asked when nothing is"
                        + " missing")
                .isEmpty();
    }

    /** Only the invisible view is asked about, not the whole set. */
    @Test
    void onlyTheRollupWhoseViewIsInvisibleIsProbed() {
        final String invisible = FlowsSchema.rollupTableNames().iterator().next();
        final Map<String, String> selects = new LinkedHashMap<>();
        FlowsSchema.rollupTableNames().stream()
                .filter(rollup -> !rollup.equals(invisible))
                .forEach(rollup -> selects.put(FlowsSchema.rollupViewName(rollup), "SELECT 1"));

        assertThat(ClickhouseRepository.rollupsNeedingProbe(selects, allTargets()))
                .as("probing every rollup would charge a healthy deployment for one broken one")
                .containsExactly(invisible);
    }

    /**
     * An unread catalog probes nothing, because no verdict would consult the answer.
     *
     * <p>A rollup whose target is unreadable answers {@code UNREACHABLE} before the view branch is
     * reached, so a probe there buys nothing and costs a round trip. That is the start least able
     * to spare one.</p>
     */
    @Test
    void anUnreadCatalogProbesNothingBecauseNoVerdictWouldUseIt() {
        assertThat(ClickhouseRepository.rollupsNeedingProbe(Map.of(), Set.of()))
                .as("every target is unreadable here, so every verdict is UNREACHABLE and every"
                        + " probe outcome would be discarded")
                .isEmpty();
    }

    /**
     * Among rollups with no visible view, only those whose target is readable are probed.
     *
     * <p>A rollup whose target is unreadable answers UNREACHABLE before the view branch, so its
     * probe outcome is discarded. This mixes both kinds in one fixture, which the all-unreadable
     * case cannot: there, an empty result is also what a broken filter would return.</p>
     */
    @Test
    void amongInvisibleViewsOnlyTheReadableTargetsAreProbed() {
        final List<String> rollups = FlowsSchema.rollupTableNames();
        final String readable = rollups.getFirst();
        final String unreadable = rollups.get(1);

        assertThat(ClickhouseRepository.rollupsNeedingProbe(Map.of(), Set.of(readable)))
                .as("the readable target is asked about and the unreadable one (%s) is not",
                        unreadable)
                .containsExactly(readable);
    }

    /**
     * The probe map is keyed by rollup target, and the query is issued against the qualified view.
     *
     * <p>The one place this feature can go silently inert. {@code RollupShapeCheck.compare} looks
     * the map up by rollup target name; the query needs the qualified view name; the two differ.
     * Key it by the view and every lookup misses, every rollup reads INCONCLUSIVE, nothing is
     * declined, and no verdict assertion notices because they all hand-build this map.</p>
     */
    @Test
    void theProbeMapIsKeyedByRollupWhileTheQueryUsesTheQualifiedView() throws Exception {
        final String rollup = FlowsSchema.rollupTableNames().getFirst();
        final List<String> asked = new java.util.ArrayList<>();

        final Map<String, RollupShapeCheck.ViewProbe> probes = ClickhouseRepository.probeViews(
                Map.of(), Set.of(rollup), "acme",
                view -> {
                    asked.add(view);
                    return RollupShapeCheck.ViewProbe.ABSENT;
                });

        assertThat(probes)
                .as("compare() looks this up by rollup target name; keyed by view name every lookup"
                        + " misses and the feature does nothing")
                .containsExactly(java.util.Map.entry(rollup, RollupShapeCheck.ViewProbe.ABSENT));
        assertThat(asked)
                .as("but the query must name the qualified VIEW, not the target")
                .containsExactly(FlowsSchema.qualifiedRollupView("acme", rollup));
    }

    /**
     * The probe map honours the filter, so a healthy deployment issues no query.
     *
     * <p>Asserted here rather than only on the helper: replacing the filtered list with every
     * rollup leaves the helper's own tests green, and no verdict assertion can see the extra
     * queries either.</p>
     */
    @Test
    void aHealthyDeploymentIssuesNoQueryAtAll() throws Exception {
        final Map<String, String> selects = new LinkedHashMap<>();
        FlowsSchema.rollupTableNames()
                .forEach(rollup -> selects.put(FlowsSchema.rollupViewName(rollup), "SELECT 1"));
        final List<String> asked = new java.util.ArrayList<>();

        final var probes = ClickhouseRepository.probeViews(selects, allTargets(), "acme", view -> {
            asked.add(view);
            return RollupShapeCheck.ViewProbe.ABSENT;
        });

        assertThat(asked).as("not one round trip may be spent when nothing is missing").isEmpty();
        assertThat(probes).isEmpty();
    }

    /**
     * The probe's wait is bounded, so a server that never answers cannot stop the collector.
     *
     * <p>The class {@code @Timeout} matters here specifically: with the bound removed this test
     * does not fail, it <em>hangs</em> on a future that never completes. Unbounded, a regression
     * would burn the whole CI job rather than reporting in seconds — which is the same failure the
     * production bound exists to prevent, reproduced in the suite.
     *
     * <p>Pinned against a future that never completes. Without this, changing the bounded
     * {@code get(timeout)} back to a bare {@code get()} passes every test: the ITs run against a
     * container that answers immediately.</p>
     */
    @Test
    void aProbeWaitIsBoundedRatherThanIndefinite() {
        final var never = new java.util.concurrent.CompletableFuture<AutoCloseable>();

        assertThatThrownBy(() -> ClickhouseRepository.awaitBounded(never, Duration.ofMillis(50), "v"))
                .as("an unbounded get() here blocks startup forever, once per invisible rollup")
                .isInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(never.isCancelled())
                .as("and the abandoned query must be cancelled, or it leaks on every start")
                .isTrue();
    }

    /** Every rollup target, as a fully readable catalog. */
    private static Set<String> allTargets() {
        return Set.copyOf(FlowsSchema.rollupTableNames());
    }

    /**
     * A probe that returns without error decides nothing.
     *
     * <p>It contradicts the catalog read that triggered it — the view was invisible there and
     * readable here — and nothing measured says what that combination means.</p>
     */
    @Test
    void aProbeThatSucceedsDecidesNothing() throws Exception {
        assertThat(ClickhouseRepository.outcomeOfProbe("v", () -> { }))
                .isEqualTo(RollupShapeCheck.ViewProbe.INCONCLUSIVE);
    }

    /**
     * A probe that times out decides nothing, and does not hang.
     *
     * <p>The timeout exists because {@code get()} without one waits forever on a server that accepts
     * the connection and never answers, once per invisible rollup, and ingestion never begins.</p>
     */
    @Test
    void aProbeThatTimesOutDecidesNothing() throws Exception {
        assertThat(ClickhouseRepository.outcomeOfProbe("v", () -> {
            throw new java.util.concurrent.TimeoutException("no answer");
        })).isEqualTo(RollupShapeCheck.ViewProbe.INCONCLUSIVE);
    }

    /**
     * An interrupt propagates instead of becoming a verdict.
     *
     * <p>{@code verifyRollupShapes} has an arm that refuses to judge anything on a half-read catalog
     * during teardown. Swallowing the interrupt here walks past it: every remaining probe fails
     * instantly and silently, and verdicts get recorded from a catalog nobody finished reading.</p>
     */
    @Test
    void anInterruptedProbePropagatesRatherThanDeciding() {
        assertThatThrownBy(() -> ClickhouseRepository.outcomeOfProbe("v", () -> {
            throw new InterruptedException("teardown");
        })).isInstanceOf(InterruptedException.class);
    }

    /**
     * Each measured code maps to the outcome production acts on.
     *
     * <p>{@code UNKNOWN_DATABASE} maps to INCONCLUSIVE deliberately, not by omission: a database
     * with no readable tables is answered by {@code UNREACHABLE} one branch earlier, so an arm here
     * would be unreachable through the only caller. {@code RollupShapeCheck.compareOne} carries the
     * full reasoning, including the ordering assumption it rests on.</p>
     */
    @Test
    void eachMeasuredCodeMapsToItsOutcome() {
        assertThat(ClickhouseRepository.outcomeOf(ServerException.ErrorCodes.TABLE_NOT_FOUND.getCode()))
                .isEqualTo(RollupShapeCheck.ViewProbe.ABSENT);
        assertThat(ClickhouseRepository.outcomeOf(ServerException.ErrorCodes.DATABASE_NOT_FOUND.getCode()))
                .as("a vanished database has no arm on purpose: compareOne answers UNREACHABLE one"
                        + " branch earlier, so an outcome here would be dead code")
                .isEqualTo(RollupShapeCheck.ViewProbe.INCONCLUSIVE);
        assertThat(ClickhouseRepository.outcomeOf(497))
                .isEqualTo(RollupShapeCheck.ViewProbe.UNGRANTED);
    }

    /**
     * A failure carrying no server error decides nothing.
     *
     * <p>A transport problem is not an answer about the view. Reporting one as "absent" would drop
     * a healthy rollup out of the query path on a dropped connection.</p>
     */
    @Test
    void aFailureWithNoServerExceptionIsInconclusive() {
        assertThat(ClickhouseRepository.outcomeOf(new java.io.IOException("connection reset")))
                .isEqualTo(RollupShapeCheck.ViewProbe.INCONCLUSIVE);
    }

    /**
     * The server's error is found through the cause chain, not at the top level.
     *
     * <p>The client wraps a {@code ServerException} in an {@code ExecutionException}. A check on
     * the thrown type alone finds nothing, reports every state inconclusive, and would still pass a
     * test that made the same mistake — so this wraps it the way the client does.</p>
     */
    @Test
    void aWrappedServerErrorIsFoundThroughTheCauseChain() {
        final var wrapped = new java.util.concurrent.ExecutionException(
                new ServerException(ServerException.ErrorCodes.TABLE_NOT_FOUND.getCode(),
                        "no such table", 0, ""));

        assertThat(ClickhouseRepository.outcomeOf(wrapped))
                .as("inspecting the thrown type instead of the chain finds nothing and never fires")
                .isEqualTo(RollupShapeCheck.ViewProbe.ABSENT);
    }

    /**
     * A wrapped interrupt propagates and leaves the flag set.
     *
     * <p>The arm this covers is the one the client actually exercises: {@code get()} throws
     * {@link InterruptedException} directly only when this thread is interrupted, while a client
     * interrupting its own worker surfaces it inside an {@code ExecutionException}. The bare case
     * is caught one arm earlier, so a test that throws it unwrapped never reaches this code — and
     * without this, deleting the unwrapping leaves the suite green while teardown goes back to
     * recording verdicts from a half-read catalog.</p>
     */
    @Test
    void aWrappedInterruptPropagatesAndKeepsTheFlagSet() {
        assertThatThrownBy(() -> ClickhouseRepository.outcomeOfProbe("v", () -> {
            throw new java.util.concurrent.ExecutionException(new InterruptedException("teardown"));
        })).isInstanceOf(InterruptedException.class);

        assertThat(Thread.interrupted())
                .as("the flag must be restored, or the teardown arm in verifyRollupShapes never"
                        + " fires and this unwrapping buys nothing")
                .isTrue();
    }

    /**
     * An unrecognised code decides nothing.
     *
     * <p>The codes are pinned against a real server only under {@code make e2e}. A server version
     * that renumbered or added one must not be able to push a rollup out of the query path on an
     * outcome nobody has measured, so anything unknown falls back to the answer this version gave
     * before the probe existed.</p>
     */
    @Test
    void anUnrecognisedCodeIsInconclusiveRatherThanADecision() {
        assertThat(ClickhouseRepository.outcomeOf(241))
                .isEqualTo(RollupShapeCheck.ViewProbe.INCONCLUSIVE);
        assertThat(ClickhouseRepository.outcomeOf(0))
                .isEqualTo(RollupShapeCheck.ViewProbe.INCONCLUSIVE);
    }
}
