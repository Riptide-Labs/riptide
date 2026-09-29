/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.session;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ipfix.IpfixUdpParser;
import org.riptide.flows.parser.netflow9.Netflow9UdpParser;
import org.riptide.pipeline.ExporterIdentity;
import org.riptide.testsupport.LogCapture;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
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
            public Object getExporterHost() {
                return this;
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

        admission.admit(attacker, scope("10.0.0.1", 1), e -> evicted.add(e.scope()));
        admission.admit(attacker, scope("10.0.0.1", 2), e -> evicted.add(e.scope()));
        admission.admit(attacker, scope("10.0.0.1", 3), e -> evicted.add(e.scope()));

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

        admission.admit(victim, scope("10.0.0.2", 1), e -> evicted.add(e.scope()));
        admission.admit(victim, scope("10.0.0.2", 2), e -> evicted.add(e.scope()));

        for (long domain = 0; domain < 500; domain++) {
            admission.admit(attacker, scope("10.0.0.1", domain), e -> evicted.add(e.scope()));
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
        assertThat(admission.indexedSourceCount()).as("reclaimed sources leave the host index too").isZero();
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

    /**
     * The shipped defaults must fit real hardware. A per-linecard chassis legitimately exports
     * several observation domains from one address, and a cap below that would churn a healthy
     * device's templates — the design's stated main operational risk, and the failure mode an
     * operator would misread as an exporter problem.
     */
    @Test
    void aMultiScopeChassisFitsWithinTheShippedDefaults() {
        // Shipped defaults, deliberately: this test exists to check them, not a tuned value.
        final SessionAdmission admission =
                new SessionAdmission(new SessionAdmissionConfig(), this.metrics, this.clock::get);
        final var chassis = source("10.0.0.1");
        final List<ExporterIdentity> evicted = new ArrayList<>();

        // Eight linecards, each its own observation domain, each re-announcing templates.
        for (int round = 0; round < 20; round++) {
            for (long linecard = 0; linecard < 8; linecard++) {
                admission.admit(chassis, scope("10.0.0.1", linecard), e -> evicted.add(e.scope()));
            }
        }

        assertThat(evicted)
                .as("a real 8-domain chassis must never have a scope displaced at the defaults")
                .isEmpty();
        assertThat(meter("rejectedScopes")).isZero();
        assertThat(admission.scopeCount()).isEqualTo(8);
    }

    @Test
    void anAdmittedScopeIsNotDisplacedByItsOwnRepeatedTraffic() {
        final SessionAdmission admission = admission(config(8, 2));
        final var exporter = source("10.0.0.1");
        final List<ExporterIdentity> evicted = new ArrayList<>();

        admission.admit(exporter, scope("10.0.0.1", 1), e -> evicted.add(e.scope()));
        admission.admit(exporter, scope("10.0.0.1", 2), e -> evicted.add(e.scope()));
        for (int i = 0; i < 100; i++) {
            admission.admit(exporter, scope("10.0.0.1", 1), e -> evicted.add(e.scope()));
            admission.admit(exporter, scope("10.0.0.1", 2), e -> evicted.add(e.scope()));
        }

        assertThat(evicted)
                .as("a legitimate multi-domain chassis within its budget must never be churned")
                .isEmpty();
        assertThat(meter("rejectedScopes")).isZero();
    }

    /** The collector's listening socket; one receiver, so every key below shares it. */
    private static final InetSocketAddress LOCAL = new InetSocketAddress(address("10.10.10.10"), 4739);

    private static UdpSessionManager.SessionKey ipfix(final String host, final int port) {
        return new IpfixUdpParser.SocketSessionKey(new InetSocketAddress(address(host), port), LOCAL);
    }

    /**
     * #946: an IPFIX exporter that restarts comes back on a new source port, and IPFIX keys its
     * session on the full socket. At a full source table that new socket used to be refused for the
     * whole idle timeout while the old, silent socket kept its slot. It now takes the slot of its own
     * host's least-recently-seen socket, and every scope of that socket is handed back to be dropped.
     */
    @Test
    void anIpfixExporterReturningOnANewPortReplacesItsOwnStaleSocket() {
        final SessionAdmission admission = admission(config(3, 16));
        final List<SessionAdmission.AdmittedScope> dropped = new ArrayList<>();

        for (int i = 1; i <= 3; i++) {
            admission.admit(ipfix("10.0.0." + i, 50_000), scope("10.0.0." + i, 0), dropped::add);
            admission.admit(ipfix("10.0.0." + i, 50_000), scope("10.0.0." + i, 1), dropped::add);
        }
        this.clock.addAndGet(TimeUnit.MINUTES.toNanos(1));

        // Every exporter restarts and comes back on a new port, with the source table full.
        for (int i = 1; i <= 3; i++) {
            assertThat(admission.admit(ipfix("10.0.0." + i, 60_000), scope("10.0.0." + i, 0), dropped::add))
                    .as("10.0.0.%d already holds a slot; its new socket must not be refused", i)
                    .isTrue();
        }

        assertThat(admission.sourceCount()).isEqualTo(3);
        assertThat(admission.indexedSourceCount()).as("replaced sources leave the host index").isEqualTo(3);
        assertThat(meter("rejectedSources")).as("nothing was refused").isZero();
        assertThat(meter("replacedSources")).as("but every replacement is visible").isEqualTo(3);
        assertThat(dropped)
                .as("every scope of each replaced socket, named by that socket's own key")
                .containsExactlyInAnyOrder(
                        new SessionAdmission.AdmittedScope(ipfix("10.0.0.1", 50_000), scope("10.0.0.1", 0)),
                        new SessionAdmission.AdmittedScope(ipfix("10.0.0.1", 50_000), scope("10.0.0.1", 1)),
                        new SessionAdmission.AdmittedScope(ipfix("10.0.0.2", 50_000), scope("10.0.0.2", 0)),
                        new SessionAdmission.AdmittedScope(ipfix("10.0.0.2", 50_000), scope("10.0.0.2", 1)),
                        new SessionAdmission.AdmittedScope(ipfix("10.0.0.3", 50_000), scope("10.0.0.3", 0)),
                        new SessionAdmission.AdmittedScope(ipfix("10.0.0.3", 50_000), scope("10.0.0.3", 1)));
    }

    /**
     * Replacement is confined to the host that caused it, the same confinement the scope LRU relies
     * on. The oldest socket in the whole table belongs to another host here, and must survive.
     */
    @Test
    void replacementPicksTheLeastRecentlySeenSocketOfTheSameHostOnly() {
        final SessionAdmission admission = admission(config(3, 16));
        final List<SessionAdmission.AdmittedScope> dropped = new ArrayList<>();

        admission.admit(ipfix("10.0.0.1", 50_000), scope("10.0.0.1", 0), dropped::add);
        this.clock.addAndGet(TimeUnit.MINUTES.toNanos(1));
        admission.admit(ipfix("10.0.0.2", 50_000), scope("10.0.0.2", 0), dropped::add);
        this.clock.addAndGet(TimeUnit.MINUTES.toNanos(1));
        admission.admit(ipfix("10.0.0.2", 50_001), scope("10.0.0.2", 0), dropped::add);
        this.clock.addAndGet(TimeUnit.MINUTES.toNanos(1));

        assertThat(admission.admit(ipfix("10.0.0.2", 50_002), scope("10.0.0.2", 0), dropped::add)).isTrue();

        assertThat(dropped)
                .as("10.0.0.2's older socket, not its newer one and not 10.0.0.1's, which is older still")
                .containsExactly(new SessionAdmission.AdmittedScope(ipfix("10.0.0.2", 50_000), scope("10.0.0.2", 0)));

        assertThat(admission.admit(ipfix("10.0.0.3", 50_000), scope("10.0.0.3", 0), dropped::add))
                .as("a host holding no slot is refused as before")
                .isFalse();
        assertThat(meter("rejectedSources")).isEqualTo(1);
        assertThat(dropped).hasSize(1);
    }

    /**
     * Without the quiet period, one spoofed packet from a live exporter's address on a new port would
     * replace that exporter's session at a full table. On main such a packet was simply refused.
     */
    @Test
    void aLiveSocketIsNotReplacedEvenByItsOwnHost() {
        final SessionAdmission admission = admission(config(1, 16));
        final List<SessionAdmission.AdmittedScope> dropped = new ArrayList<>();

        admission.admit(ipfix("10.0.0.1", 50_000), scope("10.0.0.1", 0), dropped::add);
        this.clock.addAndGet(SessionAdmission.REPLACEABLE_AFTER_NANOS - 1);

        assertThat(admission.admit(ipfix("10.0.0.1", 60_000), scope("10.0.0.1", 0), dropped::add))
                .as("the incumbent was heard from within the quiet period")
                .isFalse();
        assertThat(dropped).isEmpty();
        assertThat(meter("replacedSources")).isZero();

        this.clock.addAndGet(1);
        assertThat(admission.admit(ipfix("10.0.0.1", 60_000), scope("10.0.0.1", 0), dropped::add))
                .as("and once it has been quiet for the full period, it is replaceable")
                .isTrue();
    }

    /**
     * One budget per host was tried for #946 and withdrawn: a host running several export processes,
     * each on its own port, thrashed once they held more than one budget's worth of scopes between
     * them. Each socket keeps its own budget.
     */
    @Test
    void aHostWithSeveralLiveSocketsKeepsABudgetPerSocket() {
        final SessionAdmission admission = admission(config(16, 16));
        final List<SessionAdmission.AdmittedScope> dropped = new ArrayList<>();

        for (int round = 0; round < 10; round++) {
            for (int port = 50_000; port < 50_004; port++) {
                for (long domain = 0; domain < 8; domain++) {
                    admission.admit(ipfix("10.0.0.1", port), scope("10.0.0.1", domain), dropped::add);
                }
            }
        }

        assertThat(dropped).as("4 live sockets x 8 domains, and no scope is displaced").isEmpty();
        assertThat(admission.scopeCount()).isEqualTo(32);
        assertThat(meter("rejectedScopes")).isZero();
    }

    /**
     * NetFlow v9 and IPFIX run separate session managers behind this one oracle, and a replaced
     * session's state is handed to the manager that asked. Replacing across parsers would hand one
     * manager another's state to drop, and the real state would outlive its slot.
     */
    @Test
    void parsersDoNotReplaceEachOthersSources() {
        final SessionAdmission admission = admission(config(2, 16));
        final List<SessionAdmission.AdmittedScope> dropped = new ArrayList<>();

        admission.admit(new Netflow9UdpParser.HostSessionKey(address("10.0.0.1"), LOCAL), scope("10.0.0.1", 0),
                dropped::add);
        admission.admit(ipfix("10.0.0.2", 50_000), scope("10.0.0.2", 0), dropped::add);
        // Past the quiet period, so only the parser boundary can stop a replacement here.
        this.clock.addAndGet(TimeUnit.MINUTES.toNanos(2));

        assertThat(admission.admit(ipfix("10.0.0.1", 50_000), scope("10.0.0.1", 0), dropped::add))
                .as("10.0.0.1 holds a NetFlow v9 slot, not an IPFIX one")
                .isFalse();
        assertThat(dropped).isEmpty();
        assertThat(meter("replacedSources")).isZero();
    }

    /** An operator reading the refusal must still be able to tell which IPFIX socket was refused. */
    @Test
    void theSourceRefusalNamesTheRefusedSocket() {
        final Logger logger = (Logger) LoggerFactory.getLogger(SessionAdmission.class);
        final ListAppender<ILoggingEvent> appender = LogCapture.startedAppender();
        logger.addAppender(appender);
        try {
            final SessionAdmission admission = admission(config(1, 16));
            admission.admit(ipfix("10.0.0.1", 50_000), scope("10.0.0.1", 0), e -> { });

            assertThat(admission.admit(ipfix("10.0.0.2", 50_001), scope("10.0.0.2", 0), e -> { })).isFalse();

            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains("Session source bound (1) reached")
                            .contains("Last refused: 10.0.0.2:50001."));
        } finally {
            logger.detachAppender(appender);
        }
    }
}
