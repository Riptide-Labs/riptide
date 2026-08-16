/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.snmp;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.codahale.metrics.MetricRegistry;
import inet.ipaddr.IPAddressString;
import org.junit.jupiter.api.Test;
import org.riptide.secrets.SecretRef;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterfaceSnapshotPollerTest {

    private static final long MS = 1_000_000L;

    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final MetricRegistry metrics = new MetricRegistry();

    /** The published inventory the poller answers to; empty until a test serves one. */
    private final org.riptide.inventory.Inventory serving = new org.riptide.inventory.Inventory(
            new org.riptide.inventory.SnmpProfilesConfig(Map.of(), Map.of()),
            new org.riptide.inventory.InventoryConfig());

    /** Agent ranges served so far, so adding one does not drop the others. */
    private final java.util.LinkedHashMap<String, String> ranges = new java.util.LinkedHashMap<>();

    /** Counts walks and records which endpoints were walked; never touches a network. */
    private static class FakeSnmp implements SnmpService {
        final AtomicInteger walks = new AtomicInteger();
        private final Set<String> walked = ConcurrentHashMap.newKeySet();
        private final Map<String, AtomicInteger> walksPerEndpoint = new ConcurrentHashMap<>();
        final java.util.List<SnmpEndpoint> walkedEndpoints = new java.util.concurrent.CopyOnWriteArrayList<>();

        int walksFor(final SnmpEndpoint endpoint) {
            final AtomicInteger count = this.walksPerEndpoint.get(endpoint.getInetSocketAddress().toString());
            return count == null ? 0 : count.get();
        }
        private volatile boolean timeout;
        private volatile CountDownLatch entered;
        private volatile CountDownLatch release;

        @Override
        public Optional<IfInfo> getIfInfo(final SnmpEndpoint endpoint, final int ifIndex) {
            return Optional.empty();
        }

        @Override
        public InterfaceTable walkInterfaces(final SnmpEndpoint endpoint) {
            this.walks.incrementAndGet();
            this.walked.add(endpoint.getInetSocketAddress().toString());
            this.walksPerEndpoint.computeIfAbsent(endpoint.getInetSocketAddress().toString(),
                    key -> new AtomicInteger()).incrementAndGet();
            this.walkedEndpoints.add(endpoint);
            if (this.entered != null) {
                this.entered.countDown();
                try {
                    this.release.await(10, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (this.timeout) {
                return new InterfaceTable(Map.of(), true);
            }
            return new InterfaceTable(Map.of(1, new IfInfo("eth0", "uplink", 1000L)), false);
        }
    }

    private SnmpPollConfig config() {
        final var config = new SnmpPollConfig();
        config.setRefreshIntervalMs(600_000);
        config.setSnapshotExpiryMs(1_800_000);
        config.setPoolWidth(4);
        config.setDeregisterAfter(3);
        config.setDeadEndpointBaseMs(60_000);
        config.setDeadEndpointCeilingMs(1_800_000);
        return config;
    }

    private InterfaceSnapshotPoller poller(final SnmpService snmp, final SnmpPollConfig config) {
        return new InterfaceSnapshotPoller(snmp, config, this.metrics, this.serving, this.clock::get, false);
    }

    /**
     * The cadence a polling profile asks for must be what the poller uses. Written before
     * the plumbing existed: profiles were validated, resolved onto every range, and read
     * by nothing, so an operator could set a one-hour refresh, watch it validate, and be
     * walked every ten minutes with a green suite.
     */
    @Test
    void eachEndpointIsWalkedOnItsOwnProfileCadence() throws Exception {
        final var snmp = new FakeSnmp();
        final var config = config();
        // the fleet defaults must differ from BOTH profiles, or a fallback to them is
        // indistinguishable from the profile being honoured. The first version of this
        // test set the fleet refresh to ten minutes and called it "deliberately far",
        // while the sedate profile was also ten minutes
        config.setRefreshIntervalMs(3_600_000);
        config.setSnapshotExpiryMs(7_200_000);
        final var poller = poller(snmp, config);

        final var brisk = endpoint("10.7.0.1", "polling: brisk");
        final var sedate = endpoint("10.7.0.2", "polling: sedate");
        // an hour of fleet cadence would give zero walks in this window, so a sedate count
        // of one or two can only come from its own ten-minute profile

        poller.trackAndResolve(brisk, 1);
        poller.trackAndResolve(sedate, 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 2);

        // ticked finer than either interval on purpose: a tick period equal to the
        // interval aliases against the spreading phase and undercounts
        for (int step = 0; step < 60; step++) {
            advanceMs(10_000);
            // both exporters keep sending flows: silence is measured in the
            // registration's OWN refresh intervals, so the one-minute profile would
            // otherwise be deregistered after three minutes while the ten-minute one lives
            poller.trackAndResolve(brisk, 1);
            poller.trackAndResolve(sedate, 1);
            poller.tick(this.clock.get());
            Thread.sleep(10);
        }

        // exact counts are not the contract: walks are spread across the interval by an
        // address-derived phase plus jitter, so a tick landing exactly on the boundary
        // sometimes misses and catches up on the next one. The cadence is what is pinned
        assertThat(snmp.walksFor(brisk))
                .as("one-minute profile over ten minutes")
                .isGreaterThanOrEqualTo(8);
        assertThat(snmp.walksFor(sedate))
                .as("ten-minute profile over ten minutes")
                .isLessThanOrEqualTo(2);
        // and the whole point: the same fleet config, two cadences
        assertThat(snmp.walksFor(brisk)).isGreaterThan(snmp.walksFor(sedate) * 2);
    }

    /**
     * The endpoint-capture bug: a registration kept the endpoint it was built from and
     * discarded every later one, so a credential rotation or a repointed range reached
     * an already-polled agent only after it went silent long enough to be deregistered,
     * which for an active exporter is never.
     */
    @Test
    void aRepointedRangeReachesAnAlreadyRegisteredAgent() throws Exception {
        final var snmp = new FakeSnmp();
        final var poller = poller(snmp, config());
        poller.trackAndResolve(endpoint("10.8.0.1", "credentials: old-community"), 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);

        // the reload lands, and the next batch of flows resolves the exporter against it
        // and hands the poller a different endpoint for the same address. The refresh
        // sweep is deliberately not called: the flow path getting there first is the case
        // the endpoint-capture bug lived in
        poller.trackAndResolve(endpoint("10.8.0.1", "credentials: new-community"), 1);
        assertThat(this.metrics.meter("snmp.poller.reresolved").getCount())
                .as("re-resolution fired").isEqualTo(1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 2);

        assertThat(snmp.walkedEndpoints).hasSize(2);
        assertThat(snmp.walkedEndpoints.get(0).getSnmpDefinition().getCommunity())
                .isEqualTo(SecretRef.of("old-community"));
        // no restart, and no waiting out the refresh interval: repointing is usually an
        // operator fixing something, so the next tick walks
        assertThat(snmp.walkedEndpoints.get(1).getSnmpDefinition().getCommunity())
                .isEqualTo(SecretRef.of("new-community"));
    }

    @Test
    void anUnchangedEndpointDoesNotDisturbTheSchedule() throws Exception {
        final var snmp = new FakeSnmp();
        final var poller = poller(snmp, config());
        final var stable = endpoint("10.9.0.1");

        poller.trackAndResolve(stable, 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);

        // every batch re-resolves and hands over an equal endpoint: that must not reset
        // the schedule, or a busy exporter would be walked on every tick
        for (int batch = 0; batch < 5; batch++) {
            poller.trackAndResolve(endpoint("10.9.0.1"), 1);
            poller.tick(this.clock.get());
        }
        Thread.sleep(50);

        assertThat(snmp.walks.get()).isEqualTo(1);
    }

    /**
     * Credential sets named after the community they carry, so a test that cares which
     * credential reached the agent can name one and then assert on what was walked.
     */
    private static org.riptide.inventory.CredentialSet community(final String value) {
        return org.riptide.inventory.CredentialSet.community(
                org.riptide.inventory.CredentialVersion.V2C, SecretRef.of(value));
    }

    private static final org.riptide.inventory.SnmpProfilesConfig PROFILES =
            new org.riptide.inventory.SnmpProfilesConfig(
                    Map.of("corp-v3", org.riptide.inventory.CredentialSet.usm("riptide"),
                            "public", community("public"),
                            "old-community", community("old-community"),
                            "new-community", community("new-community"),
                            "rotated", community("rotated")),
                    Map.of("slow", new org.riptide.inventory.PollingProfile(
                                    java.time.Duration.ofMinutes(30), java.time.Duration.ofMinutes(90), 500, 1),
                            "brisk", new org.riptide.inventory.PollingProfile(
                                    java.time.Duration.ofMinutes(1), java.time.Duration.ofMinutes(30), 500, 1),
                            "sedate", new org.riptide.inventory.PollingProfile(
                                    java.time.Duration.ofMinutes(10), java.time.Duration.ofMinutes(30), 500, 1)));

    private static org.riptide.inventory.InventorySnapshot inventory(final String agentsBlock) {
        return org.riptide.inventory.InventoryLoader.parse(PROFILES, """
                riptide:
                  snmp:
                    agents:
                %s""".formatted(agentsBlock), "poller.yaml");
    }

    /**
     * Publishes {@code snapshot} as the serving inventory and returns it.
     *
     * <p>The poller reads the inventory rather than being handed one, so a test that wants
     * a reload to have happened has to publish it here. That is the production sequence:
     * the reloader commits the swap and only then asks for a refresh.
     */
    private org.riptide.inventory.InventorySnapshot serve(
            final org.riptide.inventory.InventorySnapshot snapshot) {
        this.serving.swap(snapshot);
        return snapshot;
    }

    /**
     * A carve-out has to take effect on reload. Without the refresh half of
     * swap-then-refresh an operator can disable a range, watch the reload succeed, and
     * still be polling the segment until it happens to go silent.
     */
    @Test
    void aCarvedOutRangeStopsBeingPolledOnReload() throws Exception {
        final var snmp = new FakeSnmp();
        final var poller = poller(snmp, config());
        final var live = serve(inventory("""
                      "10.30.0.7":
                        credentials: corp-v3
                """));

        poller.trackAndResolve(resolveOnly(live, "10.30.0.7"), 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);

        // the operator carves the address out and the reloader publishes it
        serve(inventory("""
                      "10.30.0.7":
                        enabled: false
                """));
        poller.refreshRegistrations();

        advanceMs(600_000);
        poller.tick(this.clock.get());
        Thread.sleep(50);

        // no restart, no waiting out the deregistration deadline
        assertThat(snmp.walks.get()).isEqualTo(1);
    }

    @Test
    void aReloadThatRepointsARangeReachesTheRegistration() throws Exception {
        final var snmp = new FakeSnmp();
        final var poller = poller(snmp, config());
        final var before = serve(inventory("""
                      "10.31.0.7":
                        credentials: corp-v3
                        port: 161
                """));
        poller.trackAndResolve(resolveOnly(before, "10.31.0.7"), 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);

        // same address, slower profile: the registration must adopt it rather than keep
        // walking on the cadence it was first built with
        serve(inventory("""
                      "10.31.0.7":
                        credentials: corp-v3
                        port: 161
                        polling: slow
                """));
        poller.refreshRegistrations();

        assertThat(this.metrics.meter("snmp.poller.reresolved").getCount()).isEqualTo(1);
        // and an identical inventory is not churn: re-publishing the same thing must not
        // reset schedules across the whole fleet
        serve(inventory("""
                      "10.31.0.7":
                        credentials: corp-v3
                        port: 161
                        polling: slow
                """));
        poller.refreshRegistrations();
        assertThat(this.metrics.meter("snmp.poller.reresolved").getCount()).isEqualTo(1);
    }

    /**
     * Resolving a registration by address alone is exact because agent ranges carry no
     * observation-domain pin. This is the assumption {@code refreshRegistrations} rests
     * on, and the widening it implies: a device is polled whatever domain its flows carry.
     */
    @Test
    void agentRangesResolveRegardlessOfObservationDomain() throws Exception {
        final var snapshot = inventory("""
                      "10.32.0.0/24":
                        credentials: corp-v3
                """);
        final var address = InetAddress.getByName("10.32.0.7");

        for (final long domain : new long[]{0L, 42L, 4_294_967_295L}) {
            assertThat(snapshot.agentView().match(new org.riptide.pipeline.ExporterIdentity.NetflowIpfix(address, domain)))
                    .as("observation domain %d", domain)
                    .isPresent();
        }
    }

    /**
     * The push cannot see a batch that was already resolving when it ran. Without the
     * lazy check that registration keeps its pre-carve-out endpoint until it goes silent,
     * which for an active exporter is never.
     */
    @Test
    void aRegistrationThatRacedTheSweepIsCaughtOnTheNextTick() throws Exception {
        final var snmp = new FakeSnmp();
        final var poller = poller(snmp, config());
        final var live = inventory("""
                      "10.40.0.7":
                        credentials: corp-v3
                """);
        final var carvedOut = inventory("""
                      "10.40.0.7":
                        enabled: false
                """);

        // the reload happens first, with nothing registered for it to sweep
        serve(carvedOut);
        poller.refreshRegistrations();
        // and only then does the in-flight batch, holding the old snapshot, register
        poller.trackAndResolve(resolveOnly(live, "10.40.0.7"), 1);

        poller.tick(this.clock.get());
        Thread.sleep(50);
        // the tick noticed it was resolved against an older inventory and stopped it
        poller.tick(this.clock.get());
        Thread.sleep(50);

        assertThat(snmp.walks.get()).isZero();
    }

    /**
     * The poller used to keep its own copy of "the current inventory", assigned by
     * whichever reloader called the refresh last. The two reloaders serialise their swaps
     * inside {@code Inventory} but not their refreshes, so the one that lost the swap can
     * sweep afterwards and leave that copy holding a snapshot that is no longer serving.
     * Registrations stamped by the losing sweep then compare equal to the copy forever, so
     * the winning snapshot's carve-out never reaches a polled agent and both reloaders log
     * success.
     *
     * <p>This pins the invariant that removes the race rather than the interleaving that
     * exposed it: what is serving decides, and a refresh call only makes it immediate. No
     * refresh is made here at all, which is what a losing reloader's push amounts to.</p>
     */
    @Test
    void aCarveOutTakesEffectEvenIfNoRefreshEverArrives() throws Exception {
        final var snmp = new FakeSnmp();
        final var poller = poller(snmp, config());
        final var live = serve(inventory("""
                      "10.42.0.7":
                        credentials: corp-v3
                """));

        poller.trackAndResolve(resolveOnly(live, "10.42.0.7"), 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);

        // the swap that wins. Its refresh is never seen by this poller
        serve(inventory("""
                      "10.42.0.7":
                        enabled: false
                """));

        advanceMs(600_000);
        poller.tick(this.clock.get());
        Thread.sleep(50);
        poller.tick(this.clock.get());
        Thread.sleep(50);

        assertThat(snmp.walks.get()).as("no walk after the carve-out was published").isEqualTo(1);
        assertThat(this.metrics.meter("snmp.poller.deregistered").getCount()).isEqualTo(1);
    }

    /**
     * A carve-out whose walk is in flight cannot be removed on the spot without letting a
     * re-registration start a second concurrent walk, so it is marked and finished by the
     * next tick rather than left polling until it goes silent.
     */
    @Test
    void aCarveOutDuringAnInFlightWalkIsCompletedByTheNextTick() throws Exception {
        final var snmp = new FakeSnmp();
        final var poller = poller(snmp, config());
        final var live = serve(inventory("""
                      "10.41.0.7":
                        credentials: corp-v3
                """));
        snmp.entered = new CountDownLatch(1);
        snmp.release = new CountDownLatch(1);

        poller.trackAndResolve(resolveOnly(live, "10.41.0.7"), 1);
        poller.tick(this.clock.get());
        assertThat(snmp.entered.await(5, TimeUnit.SECONDS)).isTrue();

        // carved out while the walk is parked in the agent
        serve(inventory("""
                      "10.41.0.7":
                        enabled: false
                """));
        poller.refreshRegistrations();
        snmp.release.countDown();
        awaitWalks(poller, snmp, 1);

        advanceMs(600_000);
        poller.tick(this.clock.get());
        Thread.sleep(50);
        poller.tick(this.clock.get());
        Thread.sleep(50);

        // the in-flight walk finished, and no further walk was ever issued
        assertThat(snmp.walks.get()).isEqualTo(1);
    }

    /** The endpoint an inventory resolves for one address, for tests that then poll it. */
    private static SnmpEndpoint resolveOnly(final org.riptide.inventory.InventorySnapshot snapshot,
                                            final String ip) throws Exception {
        final var identity = new org.riptide.pipeline.ExporterIdentity.NetflowIpfix(InetAddress.getByName(ip), 0L);
        return AgentEndpointFactory.endpointFor(snapshot.agentView().match(identity).orElseThrow(),
                new IPAddressString(ip)).orElseThrow();
    }

    /**
     * Adds an agent range for {@code ip} to the served inventory and returns the endpoint
     * that inventory resolves for it.
     *
     * <p>Returning the inventory's own endpoint rather than a synthesized one is what keeps
     * these tests honest. The poller re-resolves any registration not known to agree with
     * the serving inventory, so a registration built from an endpoint no inventory serves
     * is a state production cannot reach: the enricher only ever hands over endpoints it
     * resolved from the same inventory.</p>
     */
    private SnmpEndpoint endpoint(final String ip, final String... extraKeys) throws Exception {
        // keyed, not appended: naming a credential set has to replace the default rather
        // than add a second "credentials:" line, which is not valid YAML
        final var keys = new java.util.LinkedHashMap<String, String>();
        keys.put("credentials", "public");
        for (final String extra : extraKeys) {
            final int colon = extra.indexOf(':');
            keys.put(extra.substring(0, colon).trim(), extra.substring(colon + 1).trim());
        }
        final StringBuilder body = new StringBuilder();
        keys.forEach((key, value) -> body.append(body.isEmpty() ? "" : "\n        ")
                .append(key).append(": ").append(value));
        this.ranges.put(ip, body.toString());
        final StringBuilder block = new StringBuilder();
        this.ranges.forEach((address, rangeBody) ->
                block.append("      \"").append(address).append("\":\n        ").append(rangeBody).append('\n'));
        return resolveOnly(serve(inventory(block.toString())), ip);
    }

    private void advanceMs(final long millis) {
        this.clock.addAndGet(millis * MS);
    }

    /**
     * Walks run on the pool, so tests wait for the effect rather than assuming it landed. Waiting
     * on the issued-walk count alone is not enough: the poller sets the next walk time only after
     * the walk returns, so a test that raced that would then see a stale schedule.
     */
    private static void awaitWalks(final InterfaceSnapshotPoller poller, final FakeSnmp snmp,
                                   final int expected) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 5_000;
        while ((snmp.walks.get() < expected || poller.anyWalkInFlight())
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertThat(snmp.walks.get()).isEqualTo(expected);
        assertThat(poller.anyWalkInFlight()).isFalse();
    }

    /** Waits for pool work to settle without asserting a count, for loops that only step time. */
    private static void awaitQuiet(final InterfaceSnapshotPoller poller) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 5_000;
        while (poller.anyWalkInFlight() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
    }

    /**
     * Without the catch in {@code walk()} the schedule is never advanced for an endpoint whose
     * walk throws something the SNMP layer does not degrade, and the 1 Hz scheduler re-submits it
     * every second forever. The symptom is an unbounded retry loop plus a stack trace per second,
     * so this pins that a throwing walk backs off exactly like a failing one.
     */
    @Test
    void aWalkThatThrowsBacksOffInsteadOfRetryingEverySecond() throws Exception {
        final var thrower = new FakeSnmp() {
            @Override
            public InterfaceTable walkInterfaces(final SnmpEndpoint endpoint) {
                this.walks.incrementAndGet();
                throw new IllegalStateException("snmp4j target construction blew up");
            }
        };
        final var config = config();
        config.setDeadEndpointBaseMs(1_000);
        final var poller = poller(thrower, config);
        final var endpoint = endpoint("10.5.0.1");

        poller.trackAndResolve(endpoint, 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, thrower, 1);

        // an unguarded throw would leave nextWalkNanos in the past, so every tick re-walks
        poller.tick(this.clock.get());
        poller.tick(this.clock.get());
        Thread.sleep(50);
        assertThat(thrower.walks.get()).isEqualTo(1);

        advanceMs(1_100);
        poller.tick(this.clock.get());
        awaitWalks(poller, thrower, 2);
    }

    /**
     * Hot-reload used to clear the whole registration map. Under demand-fill that was free because
     * the next flow refilled synchronously; against a poller it blanks interface names fleet-wide
     * until every exporter is re-walked, for any config change at all.
     */
    @Test
    void hotReloadRePollsWithoutBlankingEnrichmentInTheMeantime() throws Exception {
        final var snmp = new FakeSnmp();
        final var poller = poller(snmp, config());
        final var endpoint = endpoint("10.5.0.2");

        poller.trackAndResolve(endpoint, 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);

        // a reload that repoints this range: re-resolution schedules an immediate re-walk
        final var repointed = endpoint("10.5.0.2", "credentials: rotated");
        poller.trackAndResolve(repointed, 1);

        // the existing snapshot is still served while the re-walk happens underneath
        assertThat(poller.trackAndResolve(repointed, 1)).contains(new IfInfo("eth0", "uplink", 1000L));
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 2);
    }

    /**
     * The in-flight flag lives on the Registration, so removing one mid-walk lets a
     * re-registration mint a fresh flag and start a second concurrent walk against an agent whose
     * first walk is still parked in its timeout — breaking the one-walk-per-endpoint guarantee.
     */
    @Test
    void anExporterIsNotDeregisteredWhileItsWalkIsStillRunning() throws Exception {
        final var snmp = new FakeSnmp();
        snmp.entered = new CountDownLatch(1);
        snmp.release = new CountDownLatch(1);
        final var poller = poller(snmp, config());
        final var endpoint = endpoint("10.5.0.3");

        poller.trackAndResolve(endpoint, 1);
        poller.tick(this.clock.get());
        assertThat(snmp.entered.await(5, TimeUnit.SECONDS)).isTrue();

        // long past the deregistration threshold, but the walk is still parked
        advanceMs(600_000L * 5);
        poller.tick(this.clock.get());
        poller.trackAndResolve(endpoint, 1);
        poller.tick(this.clock.get());
        assertThat(snmp.walks.get()).isEqualTo(1);

        snmp.release.countDown();
    }

    @Test
    void nonPositiveConfigurationFailsFastAndNamesTheProperty() {
        final var config = config();
        config.setPoolWidth(0);

        assertThatThrownBy(() -> poller(new FakeSnmp(), config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("riptide.snmp.poll.pool-width");
    }

    /**
     * The old design discovered a miss by walking, so warning per lookup warned per walk. Against
     * a snapshot the absence is already known, so an unguarded warning would fire on every flow
     * referencing that interface and scale with traffic while saying nothing new.
     */
    @Test
    void anAbsentIfIndexIsDiagnosedOncePerSnapshotNotOncePerFlow() throws Exception {
        final var snmp = new FakeSnmp();
        final var poller = poller(snmp, config());
        final var endpoint = endpoint("10.6.0.1");

        final var logger = (Logger) LoggerFactory.getLogger(InterfaceSnapshotPoller.class);
        final var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            poller.trackAndResolve(endpoint, 1);
            poller.tick(this.clock.get());
            awaitWalks(poller, snmp, 1);

            // ifIndex 99 is absent from the walked table; 500 flows must not mean 500 warnings
            for (int i = 0; i < 500; i++) {
                assertThat(poller.trackAndResolve(endpoint, 99)).isEmpty();
            }
            assertThat(missWarnings(appender)).isEqualTo(1);

            // a fresh snapshot re-arms the diagnosis, so a persistent gap stays visible
            advanceMs(700_000);
            poller.tick(this.clock.get());
            awaitWalks(poller, snmp, 2);
            poller.trackAndResolve(endpoint, 99);
            assertThat(missWarnings(appender)).isEqualTo(2);
        } finally {
            logger.detachAppender(appender);
        }
    }

    /** The cap exists because the ifIndex comes straight off the wire. */
    @Test
    void missDiagnosticsAreBoundedSoASprayCannotGrowTheSet() throws Exception {
        final var snmp = new FakeSnmp();
        final var poller = poller(snmp, config());
        final var endpoint = endpoint("10.6.0.2");

        final var logger = (Logger) LoggerFactory.getLogger(InterfaceSnapshotPoller.class);
        final var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            poller.trackAndResolve(endpoint, 1);
            poller.tick(this.clock.get());
            awaitWalks(poller, snmp, 1);

            for (int ifIndex = 1000; ifIndex < 6000; ifIndex++) {
                poller.trackAndResolve(endpoint, ifIndex);
            }
            assertThat(missWarnings(appender)).isLessThanOrEqualTo(64);
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static long missWarnings(final ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("not in the polled interface table"))
                .count();
    }

    @Test
    void anExporterThatSendsNoFlowsIsNeverPolled() {
        final var snmp = new FakeSnmp();
        final var poller = poller(snmp, config());

        // no trackAndResolve() call means no registration, however long the scheduler runs
        poller.tick(this.clock.get());
        advanceMs(600_000);
        poller.tick(this.clock.get());

        assertThat(snmp.walks.get()).isZero();
    }

    @Test
    void theFirstFlowRegistersAndTheFirstWalkRunsImmediately() throws Exception {
        final var snmp = new FakeSnmp();
        final var poller = poller(snmp, config());
        final var endpoint = endpoint("10.0.0.1");

        // warmup: registered, but nothing walked yet, so the ladder's SNMP rung is empty
        assertThat(poller.trackAndResolve(endpoint, 1)).isEmpty();

        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);

        assertThat(poller.trackAndResolve(endpoint, 1)).contains(new IfInfo("eth0", "uplink", 1000L));
    }

    @Test
    void theFlowPathNeverWalksHoweverManyIfIndexesItReferences() throws Exception {
        final var snmp = new FakeSnmp();
        final var poller = poller(snmp, config());
        final var endpoint = endpoint("10.0.0.2");

        for (int ifIndex = 1; ifIndex <= 50; ifIndex++) {
            poller.trackAndResolve(endpoint, ifIndex);
        }
        assertThat(snmp.walks.get()).isZero();

        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);

        // and after a snapshot exists, 50 more lookups still cost nothing
        for (int ifIndex = 1; ifIndex <= 50; ifIndex++) {
            poller.trackAndResolve(endpoint, ifIndex);
        }
        assertThat(snmp.walks.get()).isEqualTo(1);
    }

    @Test
    void anIfIndexAbsentFromTheSnapshotResolvesEmptyWithoutWalking() throws Exception {
        final var snmp = new FakeSnmp();
        final var poller = poller(snmp, config());
        final var endpoint = endpoint("10.0.0.3");

        poller.trackAndResolve(endpoint, 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);

        // 99 is not in the walked table: a known absence, not an unknown
        assertThat(poller.trackAndResolve(endpoint, 99)).isEmpty();
        assertThat(snmp.walks.get()).isEqualTo(1);
    }

    @Test
    void aStaleButUnexpiredSnapshotIsStillServedAndAnExpiredOneIsNot() throws Exception {
        final var snmp = new FakeSnmp();
        final var poller = poller(snmp, config());
        final var endpoint = endpoint("10.0.0.4");

        poller.trackAndResolve(endpoint, 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);

        // past the refresh interval but inside the expiry backstop: a name from the previous
        // cycle beats no name, which is the whole reason these are two settings
        advanceMs(700_000);
        assertThat(poller.trackAndResolve(endpoint, 1)).isPresent();

        advanceMs(1_200_000); // now beyond snapshotExpiryMs
        assertThat(poller.trackAndResolve(endpoint, 1)).isEmpty();
    }

    @Test
    void oneWalkPerEndpointPerRefreshIntervalRegardlessOfLookups() throws Exception {
        final var snmp = new FakeSnmp();
        final var poller = poller(snmp, config());
        final var endpoint = endpoint("10.0.0.5");

        poller.trackAndResolve(endpoint, 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);

        // ticks before the interval elapses must not re-walk
        advanceMs(300_000);
        poller.trackAndResolve(endpoint, 1);
        poller.tick(this.clock.get());
        Thread.sleep(50);
        assertThat(snmp.walks.get()).isEqualTo(1);

        advanceMs(400_000); // past refresh + jitter
        poller.trackAndResolve(endpoint, 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 2);
    }

    @Test
    void noSecondWalkIsIssuedWhileOneIsStillRunning() throws Exception {
        final var snmp = new FakeSnmp();
        snmp.entered = new CountDownLatch(1);
        snmp.release = new CountDownLatch(1);
        final var poller = poller(snmp, config());
        final var endpoint = endpoint("10.0.0.6");

        poller.trackAndResolve(endpoint, 1);
        poller.tick(this.clock.get());
        assertThat(snmp.entered.await(5, TimeUnit.SECONDS)).isTrue();

        // the walk is parked inside the fake; further ticks must not start another
        advanceMs(900_000);
        poller.tick(this.clock.get());
        poller.tick(this.clock.get());
        assertThat(snmp.walks.get()).isEqualTo(1);

        snmp.release.countDown();
    }

    @Test
    void poolWidthBoundsWalksInFlightAcrossTheFleet() throws Exception {
        final var snmp = new FakeSnmp();
        snmp.entered = new CountDownLatch(2);
        snmp.release = new CountDownLatch(1);
        final var config = config();
        config.setPoolWidth(2);
        final var poller = poller(snmp, config);

        for (int i = 1; i <= 20; i++) {
            poller.trackAndResolve(endpoint("10.1.0." + i), 1);
        }
        poller.tick(this.clock.get());

        assertThat(snmp.entered.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);
        // 20 exporters are due, but only pool width may be walking at once
        assertThat(snmp.walks.get()).isEqualTo(2);

        snmp.release.countDown();
    }

    @Test
    void backOffLengthensOnRepeatedFailureAndCapsAtTheCeiling() throws Exception {
        final var snmp = new FakeSnmp();
        snmp.timeout = true;
        final var config = config();
        config.setDeadEndpointBaseMs(1_000);
        config.setDeadEndpointCeilingMs(8_000);
        final var poller = poller(snmp, config);
        final var endpoint = endpoint("10.0.0.7");

        poller.trackAndResolve(endpoint, 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);

        // first retry at the base delay, and not before it
        advanceMs(999);
        poller.tick(this.clock.get());
        Thread.sleep(50);
        assertThat(snmp.walks.get()).isEqualTo(1);

        advanceMs(2);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 2);

        // second failure doubles it: 1s must no longer be enough
        advanceMs(1_001);
        poller.tick(this.clock.get());
        Thread.sleep(50);
        assertThat(snmp.walks.get()).isEqualTo(2);

        advanceMs(1_100);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 3);

        // keep failing until the delay would exceed the ceiling, then confirm it stops growing
        for (int i = 0; i < 6; i++) {
            advanceMs(9_000);
            poller.tick(this.clock.get());
            awaitWalks(poller, snmp, 4 + i);
        }
        advanceMs(8_001);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 10);
    }

    @Test
    void aSuccessfulWalkResetsTheBackOff() throws Exception {
        final var snmp = new FakeSnmp();
        snmp.timeout = true;
        final var config = config();
        config.setDeadEndpointBaseMs(1_000);
        final var poller = poller(snmp, config);
        final var endpoint = endpoint("10.0.0.8");

        poller.trackAndResolve(endpoint, 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);

        advanceMs(1_100);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 2);

        snmp.timeout = false;
        advanceMs(2_100);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 3);

        // recovered: the next walk is a normal refresh, so a short advance must not trigger one
        advanceMs(5_000);
        poller.tick(this.clock.get());
        Thread.sleep(50);
        assertThat(snmp.walks.get()).isEqualTo(3);
        assertThat(poller.trackAndResolve(endpoint, 1)).isPresent();
    }

    @Test
    void hotReloadClearsBothTheSnapshotAndTheAccumulatedBackOff() throws Exception {
        final var snmp = new FakeSnmp();
        snmp.timeout = true;
        final var config = config();
        config.setDeadEndpointBaseMs(600_000);
        final var poller = poller(snmp, config);
        final var endpoint = endpoint("10.0.0.9");

        poller.trackAndResolve(endpoint, 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);

        // without the re-resolution this endpoint would not be retried for the whole base
        // delay: an operator fixing a credential should not wait out the back-off
        poller.trackAndResolve(endpoint("10.0.0.9", "credentials: rotated"), 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 2);
    }

    @Test
    void aQuietExporterIsDeregisteredAndStopsBeingPolled() throws Exception {
        final var snmp = new FakeSnmp();
        final var poller = poller(snmp, config());
        final var endpoint = endpoint("10.0.0.10");

        poller.trackAndResolve(endpoint, 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);

        // silent for deregisterAfter (3) refresh intervals, with no further trackAndResolve() calls
        advanceMs(600_000L * 3 + 1_000);
        poller.tick(this.clock.get());
        Thread.sleep(50);

        advanceMs(600_000);
        poller.tick(this.clock.get());
        Thread.sleep(50);
        assertThat(snmp.walks.get()).isEqualTo(1);
    }

    @Test
    void theSnapshotStoreIsBoundedByExporterCount() throws Exception {
        final var snmp = new FakeSnmp();
        final var config = config();
        config.setMaxExporters(5);
        final var poller = poller(snmp, config);

        // registration follows flow arrival, so a spoofed source population must not grow the heap
        for (int i = 1; i <= 50; i++) {
            poller.trackAndResolve(endpoint("10.2.0." + i), 1);
        }

        poller.tick(this.clock.get());
        // the meter counts refused lookups, not distinct exporters: each address resolves once
        // here, so the numbers coincide, but under real traffic it is a pressure signal
        assertThat(this.metrics.meter(MetricRegistry.name("snmp", "poller", "rejectedLookups")).getCount())
                .isEqualTo(45L);
    }

    /**
     * The point of the whole change. Every exporter's first walk runs immediately, so a restart
     * registers the fleet in lockstep; if the schedule were simply {@code now + interval} they
     * would re-walk together forever, reproducing the synchronized herd the demand-filled design
     * produced when all its cache entries expired at once.
     */
    @Test
    void reWalksSpreadAcrossTheIntervalInsteadOfArrivingAsOneHerd() throws Exception {
        final var snmp = new FakeSnmp();
        final var config = config();
        config.setPoolWidth(8);
        final var poller = poller(snmp, config);

        for (int i = 1; i <= 40; i++) {
            poller.trackAndResolve(endpoint("10.4.0." + i), 1);
        }
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 40); // the cold-start burst, drained by the pool

        // step through one refresh interval in twentieths and record when re-walks land
        int busiestSlice = 0;
        int slicesWithWork = 0;
        for (int slice = 0; slice < 20; slice++) {
            final int before = snmp.walks.get();
            advanceMs(600_000 / 20);
            poller.tick(this.clock.get());
            final long deadline = System.currentTimeMillis() + 2_000;
            while (poller.anyWalkInFlight() && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
            final int inSlice = snmp.walks.get() - before;
            busiestSlice = Math.max(busiestSlice, inSlice);
            if (inSlice > 0) {
                slicesWithWork++;
            }
        }

        assertThat(snmp.walks.get()).isEqualTo(80); // each exporter re-walked exactly once
        // the herd is gone: work lands in many slices, and no single slice carries the fleet
        assertThat(slicesWithWork).isGreaterThan(3);
        assertThat(busiestSlice).isLessThan(40);
    }

    /**
     * The phase is derived from the endpoint rather than stored, so a restart must not reshuffle
     * the fleet. Both pollers are driven from the same clock values so the comparison is of the
     * schedule itself, not of when each happened to start.
     */
    @Test
    void walkPhasesAreStableAcrossRestarts() throws Exception {
        final var config = config();
        final var endpoint = endpoint("10.3.0.1");

        final var first = new FakeSnmp();
        final var pollerA = poller(first, config);
        final var second = new FakeSnmp();
        final var pollerB = new InterfaceSnapshotPoller(second, config, new MetricRegistry(), this.serving, this.clock::get, false);

        pollerA.trackAndResolve(endpoint, 1);
        pollerB.trackAndResolve(endpoint, 1);
        pollerA.tick(this.clock.get());
        pollerB.tick(this.clock.get());
        awaitWalks(pollerA, first, 1);
        awaitWalks(pollerB, second, 1);

        // step through an interval in twentieths; both must come due in the same slice
        Integer dueSliceA = null;
        Integer dueSliceB = null;
        for (int slice = 0; slice < 21; slice++) {
            advanceMs(600_000 / 20);
            pollerA.tick(this.clock.get());
            pollerB.tick(this.clock.get());
            awaitQuiet(pollerA);
            awaitQuiet(pollerB);
            if (dueSliceA == null && first.walks.get() > 1) {
                dueSliceA = slice;
            }
            if (dueSliceB == null && second.walks.get() > 1) {
                dueSliceB = slice;
            }
        }

        assertThat(dueSliceA).isNotNull();
        assertThat(dueSliceB).isEqualTo(dueSliceA);
    }
}
