/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.session;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.riptide.pipeline.ExporterIdentity;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The bound that makes GHSA-rggj-c47j-46v9 unexploitable.
 *
 * <p>Every table these tests stand in for is keyed on an identity the sender chooses, so the
 * property under test is not "the cap is applied somewhere" but the three specific ones an attacker
 * would otherwise turn into heap exhaustion: a spray is bounded, one source cannot reach across and
 * evict another's state, and the bound recovers once the flood stops.
 */
class SessionAdmissionTest {

    private final MetricRegistry metrics = new MetricRegistry();
    private final AtomicLong clock = new AtomicLong();

    private SessionAdmission admission(final SessionAdmissionConfig config) {
        return new SessionAdmission(config, this.metrics, this.clock::get);
    }

    private static SessionAdmissionConfig config(final int maxSources, final int maxScopesPerSource) {
        final SessionAdmissionConfig config = new SessionAdmissionConfig();
        config.setMaxSources(maxSources);
        config.setMaxScopesPerSource(maxScopesPerSource);
        return config;
    }

    /** A distinct UDP source. Only identity matters here, so the local address is fixed. */
    private static UdpSessionManager.SessionKey source(final String host) {
        return new UdpSessionManager.SessionKey() {
            @Override
            public String getDescription() {
                return host;
            }

            @Override
            public InetAddress getRemoteAddress() {
                return address(host);
            }

            @Override
            public boolean equals(final Object o) {
                return o instanceof UdpSessionManager.SessionKey key && host.equals(key.getDescription());
            }

            @Override
            public int hashCode() {
                return host.hashCode();
            }
        };
    }

    private static InetAddress address(final String host) {
        try {
            return InetAddress.getByName(host);
        } catch (final UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ExporterIdentity scope(final String host, final long domain) {
        return new ExporterIdentity.NetflowIpfix(address(host), domain);
    }

    private long meter(final String name) {
        return this.metrics.meter(MetricRegistry.name("flows", "session", name)).getCount();
    }

    @Test
    void aSingleSourceSprayingObservationDomainsIsBounded() {
        final SessionAdmission admission = admission(config(16, 4));
        final var attacker = source("10.0.0.1");

        // The attack: one un-spoofed source, one header field varied 1000 times.
        for (long domain = 0; domain < 1_000; domain++) {
            assertThat(admission.admit(attacker, scope("10.0.0.1", domain), evicted -> { }))
                    .as("the source itself stays admitted; it is the scope budget that binds")
                    .isTrue();
        }

        assertThat(admission.scopeCount())
                .as("1000 minted identities must not become 1000 retained scopes")
                .isEqualTo(4);
        assertThat(meter("rejectedScopes"))
                .as("every displacement past the budget is counted, so an operator can see it")
                .isEqualTo(1_000 - 4);
    }

    @Test
    void everyDisplacedScopeIsHandedBackSoItsStateCanBeDropped() {
        final SessionAdmission admission = admission(config(16, 2));
        final var attacker = source("10.0.0.1");
        final List<ExporterIdentity> evicted = new ArrayList<>();

        admission.admit(attacker, scope("10.0.0.1", 1), evicted::add);
        admission.admit(attacker, scope("10.0.0.1", 2), evicted::add);
        admission.admit(attacker, scope("10.0.0.1", 3), evicted::add);

        // Without this the budget would shrink while the tables it governs kept growing — the
        // bound would be bookkeeping rather than a bound.
        assertThat(evicted)
                .as("the least-recently-used scope is surrendered, not silently forgotten")
                .containsExactly(scope("10.0.0.1", 1));
    }

    @Test
    void totalSourcesAreBounded() {
        final SessionAdmission admission = admission(config(3, 8));

        for (int i = 1; i <= 3; i++) {
            assertThat(admission.admit(source("10.0.0." + i), scope("10.0.0." + i, 1), e -> { })).isTrue();
        }

        assertThat(admission.admit(source("10.0.0.99"), scope("10.0.0.99", 1), e -> { }))
                .as("a source arriving at a full table allocates nothing at all")
                .isFalse();
        assertThat(admission.sourceCount()).isEqualTo(3);
        assertThat(meter("rejectedSources")).isEqualTo(1);
    }

    /**
     * The reason the budget is LRU <em>within</em> a source and reject-new across sources. Global
     * LRU would let whoever sprays hardest choose which real exporters stop being monitored.
     */
    @Test
    void oneSourceCannotEvictAnotherSourcesState() {
        final SessionAdmission admission = admission(config(16, 2));
        final var victim = source("10.0.0.2");
        final var attacker = source("10.0.0.1");
        final List<ExporterIdentity> evicted = new ArrayList<>();

        admission.admit(victim, scope("10.0.0.2", 1), evicted::add);
        admission.admit(victim, scope("10.0.0.2", 2), evicted::add);

        for (long domain = 0; domain < 500; domain++) {
            admission.admit(attacker, scope("10.0.0.1", domain), evicted::add);
        }

        assertThat(evicted)
                .as("the victim's scopes are never surrendered, however hard the attacker sprays")
                .allSatisfy(identity -> assertThat(identity.deviceAddress()).isEqualTo(address("10.0.0.1")));
        assertThat(admission.scopeCount())
                .as("victim keeps both of its scopes; attacker is held at its own budget")
                .isEqualTo(4);
    }

    @Test
    void idleSourcesAreReclaimedSoTheBoundRecoversAfterAFlood() {
        final SessionAdmissionConfig config = config(2, 4);
        config.setSourceIdleTimeout(java.time.Duration.ofMinutes(30));
        final SessionAdmission admission = admission(config);

        admission.admit(source("10.0.0.1"), scope("10.0.0.1", 1), e -> { });
        admission.admit(source("10.0.0.2"), scope("10.0.0.2", 1), e -> { });
        assertThat(admission.admit(source("10.0.0.3"), scope("10.0.0.3", 1), e -> { }))
                .as("full while the flood is live")
                .isFalse();

        // The flood stops and the idle timeout passes.
        this.clock.addAndGet(TimeUnit.MINUTES.toNanos(31));
        admission.reclaimIdle();

        assertThat(admission.sourceCount()).isZero();
        assertThat(admission.admit(source("10.0.0.3"), scope("10.0.0.3", 1), e -> { }))
                .as("a real exporter appearing after the flood must not stay locked out")
                .isTrue();
    }

    @Test
    void aLiveSourceIsNotReclaimed() {
        final SessionAdmissionConfig config = config(8, 4);
        config.setSourceIdleTimeout(java.time.Duration.ofMinutes(30));
        final SessionAdmission admission = admission(config);
        final var live = source("10.0.0.1");

        admission.admit(live, scope("10.0.0.1", 1), e -> { });
        this.clock.addAndGet(TimeUnit.MINUTES.toNanos(29));
        admission.admit(live, scope("10.0.0.1", 1), e -> { }); // still talking
        this.clock.addAndGet(TimeUnit.MINUTES.toNanos(5));

        admission.reclaimIdle();

        assertThat(admission.sourceCount())
                .as("34 minutes since first contact, but only 5 since the last packet")
                .isEqualTo(1);
    }

    /**
     * sFlow scope identity is payload-borne — {@code agent_address} is independent of the UDP
     * source — so one sender can mint identities across the whole agent-address space. The budget
     * has to count those against the source they arrived on, not against the agent address.
     */
    @Test
    void sflowAgentAddressesAreCountedAgainstTheSourceTheyArriveOn() {
        final SessionAdmission admission = admission(config(16, 4));
        final var oneSender = source("10.0.0.1");

        for (int agent = 0; agent < 200; agent++) {
            admission.admit(oneSender,
                    new ExporterIdentity.Sflow(address("192.0.2." + (agent % 256)), agent), e -> { });
        }

        assertThat(admission.scopeCount())
                .as("a forged agent address must not buy a fresh budget")
                .isEqualTo(4);
    }

    /**
     * A bound set to zero must be rejected, not honoured as "no bound". Before this check, a
     * mistyped {@code max-scopes-per-source} restored the unbounded growth the class exists to
     * stop, silently and with no way to tell from the outside.
     */
    @Test
    void aNonPositiveBoundIsRefusedAtStartupRatherThanDisablingTheBound() {
        assertThatThrownBy(() -> admission(config(4096, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("riptide.flows.session.max-scopes-per-source")
                .hasMessageContaining("disables the bound");

        assertThatThrownBy(() -> admission(config(0, 16)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("riptide.flows.session.max-sources");

        final SessionAdmissionConfig negativeIfIndexes = config(16, 4);
        negativeIfIndexes.setMaxIfIndexesPerScope(-1);
        assertThatThrownBy(() -> admission(negativeIfIndexes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("riptide.flows.session.max-ifindexes-per-scope");

        final SessionAdmissionConfig zeroTimeout = config(16, 4);
        zeroTimeout.setSourceIdleTimeout(java.time.Duration.ZERO);
        assertThatThrownBy(() -> admission(zeroTimeout))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("riptide.flows.session.source-idle-timeout");
    }

    /**
     * The source bound is the more serious of the two conditions — new exporters stop being
     * retained entirely — so a continuous scope-budget flood must not be able to suppress it.
     */
    @Test
    void aScopeFloodDoesNotSuppressTheSourceBoundWarning() {
        final SessionAdmission admission = admission(config(1, 1));
        final var admitted = source("10.0.0.1");

        // Fill the single source slot, then spray scopes so the scope-budget warning fires.
        admission.admit(admitted, scope("10.0.0.1", 0), e -> { });
        for (long domain = 1; domain < 50; domain++) {
            admission.admit(admitted, scope("10.0.0.1", domain), e -> { });
        }
        assertThat(meter("rejectedScopes")).isPositive();

        // A different source now arrives at a full table. Its warning uses its own limiter, so the
        // scope flood above cannot have consumed the interval.
        assertThat(admission.admit(source("10.0.0.2"), scope("10.0.0.2", 1), e -> { })).isFalse();
        assertThat(meter("rejectedSources")).isEqualTo(1);
    }

    @Test
    void anAdmittedScopeIsNotDisplacedByItsOwnRepeatedTraffic() {
        final SessionAdmission admission = admission(config(8, 2));
        final var exporter = source("10.0.0.1");
        final List<ExporterIdentity> evicted = new ArrayList<>();

        admission.admit(exporter, scope("10.0.0.1", 1), evicted::add);
        admission.admit(exporter, scope("10.0.0.1", 2), evicted::add);
        for (int i = 0; i < 100; i++) {
            admission.admit(exporter, scope("10.0.0.1", 1), evicted::add);
            admission.admit(exporter, scope("10.0.0.1", 2), evicted::add);
        }

        assertThat(evicted)
                .as("a legitimate multi-domain chassis within its budget must never be churned")
                .isEmpty();
        assertThat(meter("rejectedScopes")).isZero();
    }
}
