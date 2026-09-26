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
import org.riptide.config.DaemonConfig;
import org.riptide.metrics.MetricSink;
import org.riptide.metrics.Sample;
import org.riptide.secrets.SecretRef;
import org.riptide.testsupport.LogCapture;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterfaceSnapshotPollerTest {

    private static final long MS = 1_000_000L;

    /** Wall-clock milliseconds stamped on samples; fixed so a test can assert the timestamp. */
    private static final long WALL_CLOCK_MS = 1_790_000_000_000L;

    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final MetricRegistry metrics = new MetricRegistry();

    /** The published inventory the poller answers to; empty until a test serves one. */
    private final org.riptide.inventory.Inventory serving = new org.riptide.inventory.Inventory(
            new org.riptide.inventory.SnmpProfilesConfig(Map.of(), Map.of()),
            new org.riptide.inventory.FileInventoryDocument(new org.riptide.inventory.InventoryConfig()));

    /** Agent ranges served so far, so adding one does not drop the others. */
    private final java.util.LinkedHashMap<String, String> ranges = new java.util.LinkedHashMap<>();

    /** Counts walks and records which endpoints were walked; never touches a network. */
    private static class FakeSnmp implements SnmpService {
        final AtomicInteger walks = new AtomicInteger();
        final AtomicInteger collects = new AtomicInteger();
        /** When set, collected rows carry no ifName: a device answering ifTable but not ifXTable. */
        private volatile boolean noIfXTable;
        private volatile boolean throwOnCollect;
        private final Set<String> walked = ConcurrentHashMap.newKeySet();
        private final Map<String, AtomicInteger> walksPerEndpoint = new ConcurrentHashMap<>();
        final java.util.List<SnmpEndpoint> walkedEndpoints = new java.util.concurrent.CopyOnWriteArrayList<>();

        int walksFor(final SnmpEndpoint endpoint) {
            final AtomicInteger count = this.walksPerEndpoint.get(endpoint.getInetSocketAddress().toString());
            return count == null ? 0 : count.get();
        }
        private volatile boolean timeout;
        /** Endpoints whose walks and collects answer with a failed table. */
        final Set<SnmpEndpoint> failing = ConcurrentHashMap.newKeySet();
        /** When set, each walk counts down {@code entered} and then waits on {@code release}. */
        private volatile boolean block;
        private volatile CountDownLatch entered;
        private volatile CountDownLatch release;
        /**
         * Runs the blocking walks off the caller's thread, the way snmp4j completes a real walk
         * on its own. An unblocked walk completes inline, so a tick's permits come back before
         * it examines the next registration.
         */
        private final java.util.concurrent.ExecutorService completer = java.util.concurrent.Executors.newCachedThreadPool(
                runnable -> {
                    final Thread thread = new Thread(runnable, "fake-snmp-completer");
                    thread.setDaemon(true);
                    return thread;
                });

        /** The thread each walk or collect was started on. */
        final List<String> startThreads = new CopyOnWriteArrayList<>();

        @Override
        public java.util.concurrent.CompletableFuture<InterfaceTable> walkInterfacesAsync(final SnmpEndpoint endpoint) {
            this.startThreads.add(Thread.currentThread().getName());
            if (this.block) {
                return java.util.concurrent.CompletableFuture.supplyAsync(() -> walkInterfaces(endpoint), this.completer);
            }
            return java.util.concurrent.CompletableFuture.completedFuture(walkInterfaces(endpoint));
        }

        @Override
        public java.util.concurrent.CompletableFuture<org.riptide.snmp.collect.CollectedTable> collectAsync(
                final SnmpEndpoint endpoint, final org.riptide.snmp.collect.CollectionDefinition definition,
                final Duration budget) {
            this.startThreads.add(Thread.currentThread().getName());
            if (this.block) {
                return java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> collect(endpoint, definition, budget), this.completer);
            }
            return java.util.concurrent.CompletableFuture.completedFuture(collect(endpoint, definition, budget));
        }

        private void gate() {
            if (this.block) {
                this.entered.countDown();
                try {
                    this.release.await(10, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

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
            gate();
            if (this.timeout || this.failing.contains(endpoint)) {
                return new InterfaceTable(Map.of(), true);
            }
            return new InterfaceTable(Map.of(1, new IfInfo("eth0", "uplink", 1000L)), false);
        }

        @Override
        public org.riptide.snmp.collect.CollectedTable collect(final SnmpEndpoint endpoint,
                final org.riptide.snmp.collect.CollectionDefinition definition, final Duration budget) {
            final int collected = this.collects.incrementAndGet();
            if (this.throwOnCollect) {
                throw new IllegalStateException("snmp4j target construction blew up");
            }
            gate();
            if (this.timeout || this.failing.contains(endpoint)) {
                return new org.riptide.snmp.collect.CollectedTable(Map.of(), true);
            }
            final Map<String, String> info = this.noIfXTable
                    ? Map.of()
                    : Map.of("ifName", "eth0", "ifAlias", "uplink", "ifHighSpeed", "1000");
            final Map<String, Long> values = this.noIfXTable
                    ? Map.of("ifInErrors", 0L)
                    : Map.of("ifHCInOctets", 100L * collected);
            return new org.riptide.snmp.collect.CollectedTable(
                    Map.of(1, new org.riptide.snmp.collect.CollectedTable.CollectedRow(info, values)), false);
        }
    }

    /** Keeps every batch the poller hands over, in order. */
    private static final class RecordingSink implements MetricSink {
        final List<Sample> samples = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        /** The thread each accept ran on. */
        final List<String> threads = new CopyOnWriteArrayList<>();

        @Override
        public void accept(final List<Sample> batch) {
            this.calls.incrementAndGet();
            this.threads.add(Thread.currentThread().getName());
            this.samples.addAll(batch);
        }
    }

    private static DaemonConfig identity() {
        final var daemon = new DaemonConfig();
        daemon.getIdentity().setTenant("t1");
        daemon.getIdentity().setOrganisation("o1");
        daemon.getIdentity().setZone("z1");
        return daemon;
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
        return poller(snmp, config, new RecordingSink());
    }

    private InterfaceSnapshotPoller poller(final SnmpService snmp, final SnmpPollConfig config,
                                           final MetricSink sink) {
        return new InterfaceSnapshotPoller(snmp, config, this.metrics, this.serving, sink, identity(),
                this.clock::get, false, () -> WALL_CLOCK_MS);
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
                                    java.time.Duration.ofMinutes(30), java.time.Duration.ofMinutes(90), 500, 1,
                                    java.util.List.of()),
                            "brisk", new org.riptide.inventory.PollingProfile(
                                    java.time.Duration.ofMinutes(1), java.time.Duration.ofMinutes(30), 500, 1,
                                    java.util.List.of()),
                            "sedate", new org.riptide.inventory.PollingProfile(
                                    java.time.Duration.ofMinutes(10), java.time.Duration.ofMinutes(30), 500, 1,
                                    java.util.List.of()),
                            "counters", new org.riptide.inventory.PollingProfile(
                                    java.time.Duration.ofMinutes(1), java.time.Duration.ofMinutes(30), 500, 1,
                                    java.util.List.of(org.riptide.inventory.CollectionName.IF_MIB_INTERFACES))));

    // corp-v3 on the /24 because the loader refuses a v2c community on a range wider than one address
    private static final String ALWAYS_INVENTORY = """
            riptide:
              snmp:
                agents:
                  10.0.0.0/24: { credentials: corp-v3, polling: counters }
              exporters:
                silent-switch: { address: 10.0.0.20, poll: always }
            """;

    private static org.riptide.inventory.InventorySnapshot parse(final String yaml) {
        return org.riptide.inventory.InventoryLoader.parse(PROFILES, yaml, "poller.yaml");
    }

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

        // immediacy is the whole point of the refresh half, and the only thing that
        // distinguishes it from the tick's verification pass. Without this the sweep could
        // be gutted to an empty method and the test would still pass one second later, on
        // the tick, having proved nothing about the reload path
        assertThat(this.metrics.meter("snmp.poller.deregistered").getCount())
                .as("stopped by the refresh itself, before any tick")
                .isEqualTo(1);

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
        snmp.block = true;
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

    /**
     * AC 5's second half. The cadence test above pins the refresh interval; without this
     * the expiry could keep falling back to the fleet default and every profile that
     * widens or narrows the serving window would be silently ignored.
     *
     * <p>The fleet expiry is set a day out, so a snapshot that expires here can only be
     * expiring on its profile's schedule, and the two profiles differ from each other so
     * one serving while the other blanks cannot be a coincidence of timing.</p>
     */
    @Test
    void snapshotExpiryFollowsTheProfileRatherThanTheFleetDefault() throws Exception {
        final var snmp = new FakeSnmp();
        final var config = config();
        config.setRefreshIntervalMs(3_600_000);
        config.setSnapshotExpiryMs(86_400_000);
        final var poller = poller(snmp, config);

        // brisk expires after 30 minutes, slow after 90
        final var brisk = endpoint("10.43.0.1", "polling: brisk");
        final var slow = endpoint("10.43.0.2", "polling: slow");

        poller.trackAndResolve(brisk, 1);
        poller.trackAndResolve(slow, 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 2);
        assertThat(poller.trackAndResolve(brisk, 1)).as("served straight after the walk").isPresent();

        // 45 minutes on, with no tick so neither snapshot is refreshed underneath us
        advanceMs(2_700_000);

        assertThat(poller.trackAndResolve(brisk, 1))
                .as("past its own 30-minute expiry, and a day short of the fleet's")
                .isEmpty();
        assertThat(poller.trackAndResolve(slow, 1))
                .as("inside its own 90-minute expiry")
                .isPresent();
    }

    /**
     * AC 7. The endpoint carries {@link SecretRef}s rather than resolved values, and the
     * value is read when the walk builds its target, so rotating the secret behind a
     * reference reaches a polled agent with no configuration change and no reload.
     *
     * <p>The fake resolves at walk time because that is where the real service resolves,
     * which is also this test's limit: it pins that the poller keeps handing over a
     * reference rather than a value, not the walk path's own resolution.</p>
     *
     * <p>Scoped to references that are read on every resolve, which is what {@code file://}
     * and {@code env://} are. It is deliberately not a claim about every scheme:
     * {@code sops://} caches decrypted content for the process lifetime and is only
     * invalidated by a main-config reload, so rotating a value behind a sops reference
     * alone does <em>not</em> reach a polled agent. That asymmetry is real and belongs in
     * the operator documentation, not hidden behind a passing test.</p>
     */
    @Test
    void aRotatedSecretValueReachesAPolledAgentWithoutAnyReload() throws Exception {
        final var secret = java.nio.file.Files.createTempDirectory("riptide-rotation").resolve("community");
        secret.toFile().deleteOnExit();
        java.nio.file.Files.writeString(secret, "before-rotation");

        final var resolvers = org.riptide.secrets.SecretResolvers.defaults();
        final var walkedValues = new java.util.concurrent.CopyOnWriteArrayList<String>();
        final var snmp = new FakeSnmp() {
            @Override
            public InterfaceTable walkInterfaces(final SnmpEndpoint endpoint) {
                walkedValues.add(resolvers.resolve(endpoint.getSnmpDefinition().getCommunity()));
                return super.walkInterfaces(endpoint);
            }
        };
        final var poller = poller(snmp, config());

        final var profiles = new org.riptide.inventory.SnmpProfilesConfig(
                Map.of("rotating", org.riptide.inventory.CredentialSet.community(
                        org.riptide.inventory.CredentialVersion.V2C, SecretRef.of("file://" + secret))),
                Map.of());
        final var snapshot = serve(org.riptide.inventory.InventoryLoader.parse(profiles, """
                riptide:
                  snmp:
                    agents:
                      "10.44.0.1":
                        credentials: rotating
                """, "rotation.yaml"));
        final var endpoint = resolveOnly(snapshot, "10.44.0.1");

        poller.trackAndResolve(endpoint, 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);

        // the operator rotates the value behind the reference. No file the inventory
        // watcher looks at changed, and no reload is triggered
        java.nio.file.Files.writeString(secret, "after-rotation");

        // still well inside the deregistration deadline, so the registration is the same one
        advanceMs(1_200_000);
        poller.trackAndResolve(endpoint, 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 2);

        assertThat(walkedValues).containsExactly("before-rotation", "after-rotation");
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
     * Walks complete after the tick returns, so tests wait for the effect rather than assuming it landed. Waiting
     * on the issued-walk count alone is not enough: the poller sets the next walk time only after
     * the walk returns, so a test that raced that would then see a stale schedule.
     */
    private static void awaitWalks(final InterfaceSnapshotPoller poller, final FakeSnmp snmp,
                                   final int expected) throws InterruptedException {
        // walks and collects both count: a collecting profile goes through collect instead
        final long deadline = System.currentTimeMillis() + 5_000;
        while ((snmp.walks.get() + snmp.collects.get() < expected || poller.anyWalkInFlight())
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertThat(snmp.walks.get() + snmp.collects.get()).isEqualTo(expected);
        assertThat(poller.anyWalkInFlight()).isFalse();
    }

    /** Waits for walks to settle without asserting a count, for loops that only step time. */
    private static void awaitQuiet(final InterfaceSnapshotPoller poller) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 5_000;
        while (poller.anyWalkInFlight() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
    }

    /**
     * The bulkhead. An endpoint that has failed at least once draws from its own, smaller permit
     * budget, so a population of dead agents parked in their timeouts cannot take the permits a
     * healthy endpoint needs.
     */
    @Test
    void suspectEndpointsDrawFromTheirOwnBudgetSoHealthyOnesAreNeverStarved() throws Exception {
        final var snmp = new FakeSnmp();
        final var config = config();
        config.setPoolWidth(2);
        config.setSuspectPoolWidth(1);
        final var poller = poller(snmp, config, new RecordingSink());
        // three endpoints that fail, and one healthy endpoint
        for (final String ip : List.of("10.0.0.1", "10.0.0.2", "10.0.0.3")) {
            final var ep = endpoint(ip);
            snmp.failing.add(ep);
            poller.trackAndResolve(ep, 1);
        }
        final var healthy = endpoint("10.0.0.9");
        poller.trackAndResolve(healthy, 1);
        // every first walk runs from the healthy budget, nobody being a suspect yet. Two
        // permits for four due walks takes more than one tick, so tick until all four ran
        final long deadline = System.currentTimeMillis() + 5_000;
        while (snmp.walks.get() < 4 && System.currentTimeMillis() < deadline) {
            poller.tick(this.clock.get());
            awaitQuiet(poller);
        }
        assertThat(snmp.walks.get()).isEqualTo(4);

        // one full refresh interval later everybody is due: the three suspects after their
        // back-off, the healthy one at its next phase (at most one interval plus the jitter)
        advanceMs(config.getRefreshIntervalMs() + config.getRefreshIntervalMs() / 50 + 1);
        poller.trackAndResolve(healthy, 1);
        snmp.block = true;
        snmp.entered = new CountDownLatch(2);
        snmp.release = new CountDownLatch(1);
        final long deferredBefore = this.metrics.meter("snmp.poller.deferred").getCount();
        poller.tick(this.clock.get());
        assertThat(snmp.entered.await(2, TimeUnit.SECONDS)).as("the healthy walk and one suspect started").isTrue();

        assertThat(this.metrics.gauge("snmp.poller.inFlight").getValue())
                .as("the healthy endpoint got a healthy permit").isEqualTo(1);
        assertThat(this.metrics.gauge("snmp.poller.suspectInFlight").getValue()).isEqualTo(1);
        assertThat(this.metrics.meter("snmp.poller.deferred").getCount() - deferredBefore)
                .as("the other two suspects wait, and did not take the free healthy permit").isEqualTo(2);
        snmp.release.countDown();
    }

    /**
     * Neither the tick thread nor snmp4j's threads do riptide's side of a walk: the start (secret
     * resolution, session setup) and the sink hand-off both run on the walk executor.
     */
    @Test
    void walkStartsAndSinkHandOffsRunOnTheWalkExecutor() throws Exception {
        final var snmp = new FakeSnmp();
        // completes every walk on the fake's own thread, as snmp4j completes a real one on its
        // dispatcher; released up front, so nothing waits
        snmp.block = true;
        snmp.entered = new CountDownLatch(3);
        snmp.release = new CountDownLatch(0);
        final var sink = new RecordingSink();
        final var poller = poller(snmp, config(), sink);
        poller.trackAndResolve(endpoint("10.0.0.10", "polling: counters"), 1);
        poller.trackAndResolve(endpoint("10.0.0.11", "polling: counters"), 1);
        poller.trackAndResolve(endpoint("10.0.0.12"), 1);

        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 3);

        assertThat(snmp.startThreads).hasSize(3).allSatisfy(name -> assertThat(name).startsWith("snmp-walk-io"));
        assertThat(sink.threads).hasSize(2).allSatisfy(name -> assertThat(name).startsWith("snmp-walk-io"));
    }

    /**
     * A due walk that finds no permit waits in the due queue, and the permit that frees starts it
     * at once: no second tick is needed (#899). Before the queue, a tick could start at most
     * pool-width walks, so the fleet's ceiling was pool-width walks per second however fast the
     * agents answered.
     */
    @Test
    void aFreedPermitStartsTheNextDueWalkWithoutWaitingForATick() throws Exception {
        final var snmp = new FakeSnmp();
        snmp.block = true;
        snmp.entered = new CountDownLatch(1);
        snmp.release = new CountDownLatch(1);
        final var config = config();
        config.setPoolWidth(1);
        final var poller = poller(snmp, config, new RecordingSink());
        poller.trackAndResolve(endpoint("10.0.0.1"), 1);
        poller.trackAndResolve(endpoint("10.0.0.2"), 1);
        poller.tick(this.clock.get());
        assertThat(snmp.entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(this.metrics.gauge("snmp.poller.inFlight").getValue()).isEqualTo(1);
        assertThat(this.metrics.meter("snmp.poller.deferred").getCount()).as("one walk had to wait").isEqualTo(1);

        // free the permit: the queued walk must start with no tick in between
        snmp.entered = new CountDownLatch(1);
        snmp.release.countDown();
        assertThat(snmp.entered.await(2, TimeUnit.SECONDS)).as("second walk started on the freed permit").isTrue();
        awaitWalks(poller, snmp, 2);
    }

    /**
     * Fairness. With one permit and four due devices, one tick per interval walks every device
     * once before any device is walked twice. Before the due queue the same map-order winner took
     * the permit every tick and the rest were never polled.
     */
    @Test
    void everyDueDeviceIsWalkedOnceBeforeAnyIsWalkedTwice() throws Exception {
        final var snmp = new FakeSnmp();
        final var config = config();
        config.setPoolWidth(1);
        final var poller = poller(snmp, config, new RecordingSink());
        final var endpoints = List.of(endpoint("10.0.0.1"), endpoint("10.0.0.2"), endpoint("10.0.0.3"), endpoint("10.0.0.4"));
        for (final var ep : endpoints) {
            poller.trackAndResolve(ep, 1);
        }
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 4);
        for (final var ep : endpoints) {
            assertThat(snmp.walksFor(ep)).as("first round, %s", ep).isEqualTo(1);
        }

        // one interval plus the jitter later every device is due again; still one tick, one permit
        advanceMs(config.getRefreshIntervalMs() + config.getRefreshIntervalMs() / 50 + 1);
        for (final var ep : endpoints) {
            poller.trackAndResolve(ep, 1);
        }
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 8);
        for (final var ep : endpoints) {
            assertThat(snmp.walksFor(ep)).as("second round, %s", ep).isEqualTo(2);
        }
    }

    /**
     * A sweep registers a whole fleet at once, so the first walks are spread across the interval
     * the way re-walks are (#900). All due on the first tick, 5,000 devices produced their first
     * sweep in 40 seconds and overflowed the sink queue.
     */
    @Test
    void pollAlwaysEntriesSpreadTheirFirstWalkAcrossTheInterval() throws Exception {
        final var snmp = new FakeSnmp();
        final var config = config();
        config.setPoolWidth(64);
        final var poller = poller(snmp, config, new RecordingSink());
        final var yaml = new StringBuilder("""
                riptide:
                  snmp:
                    agents:
                      10.7.0.0/24: { credentials: corp-v3, polling: counters }
                  exporters:
                """);
        for (int i = 1; i <= 16; i++) {
            yaml.append("    sw-").append(i).append(": { address: 10.7.0.").append(i).append(", poll: always }\n");
        }
        serve(parse(yaml.toString()));
        poller.refreshRegistrations();
        assertThat(this.metrics.gauge("snmp.poller.inventoryRegistered").getValue()).isEqualTo(16);

        poller.tick(this.clock.get());
        awaitQuiet(poller);
        final int firstTick = snmp.collects.get();
        assertThat(firstTick).as("not the whole fleet on the first tick").isLessThan(16);

        // by the end of one interval (plus jitter) every device has had its first walk
        advanceMs(60_000L + 60_000L / 50 + 1);
        poller.tick(this.clock.get());
        awaitQuiet(poller);
        assertThat(snmp.collects.get()).isGreaterThanOrEqualTo(16);
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
        snmp.block = true;
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

    /** No suspect permit would leave every failed endpoint deferred forever, silently. */
    @Test
    void aNonPositiveSuspectPoolWidthFailsFastAndNamesTheProperty() {
        final var config = config();
        config.setSuspectPoolWidth(0);

        assertThatThrownBy(() -> poller(new FakeSnmp(), config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("riptide.snmp.poll.suspect-pool-width must be greater than 0");
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
        final var appender = LogCapture.startedAppender();
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
        final var appender = LogCapture.startedAppender();
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
        snmp.block = true;
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
        snmp.block = true;
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
        // a permit for every exporter: with fewer, a herd would be deferred across ticks and
        // spread over slices by the permits alone, and this test could no longer see it
        config.setPoolWidth(40);
        final var poller = poller(snmp, config);

        for (int i = 1; i <= 40; i++) {
            poller.trackAndResolve(endpoint("10.4.0." + i), 1);
        }
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 40); // the cold-start burst, one tick

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
        final var pollerB = new InterfaceSnapshotPoller(second, config, new MetricRegistry(), this.serving,
                new RecordingSink(), identity(), this.clock::get, false, () -> WALL_CLOCK_MS);

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

    @Test
    void aCollectingProfileWalksThroughCollectAndEmitsSamplesWithTheIdentityLabels() throws Exception {
        final var snmp = new FakeSnmp();
        final var sink = new RecordingSink();
        final var poller = poller(snmp, config(), sink);
        final var endpoint = endpoint("10.0.0.10", "polling: counters");

        poller.trackAndResolve(endpoint, 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);

        assertThat(snmp.collects.get()).isEqualTo(1);
        assertThat(snmp.walks.get()).as("no second walk for enrichment").isEqualTo(0);
        assertThat(sink.calls.get()).as("one sink call per walk").isEqualTo(1);
        assertThat(sink.samples).extracting(Sample::name).contains("ifHCInOctets", "riptide_interface_info");
        final Sample octets = sink.samples.stream().filter(s -> s.name().equals("ifHCInOctets")).findFirst().orElseThrow();
        assertThat(octets.labels()).containsEntry("tenant", "t1").containsEntry("organisation", "o1")
                .containsEntry("zone", "z1").containsEntry("exporter_address", "10.0.0.10")
                .containsEntry("exporter", "10.0.0.10")
                .containsEntry("ifIndex", "1").containsEntry("ifName", "eth0");
        assertThat(octets.value()).isEqualTo(100d);
        assertThat(octets.timestampMs()).isEqualTo(WALL_CLOCK_MS);
        assertThat(this.metrics.meter("snmp.poller.samplesEmitted").getCount()).isEqualTo(sink.samples.size());
        // and the enrichment snapshot was refreshed from the same rows
        assertThat(poller.trackAndResolve(endpoint, 1)).contains(new IfInfo("eth0", "uplink", 1000L));
    }

    @Test
    void theExporterLabelIsTheInventoryNameWhenAnEntryCoversTheAddress() throws Exception {
        final var snmp = new FakeSnmp();
        final var sink = new RecordingSink();
        final var poller = poller(snmp, config(), sink);
        final var snapshot = serve(parse("""
                riptide:
                  snmp:
                    agents:
                      10.0.0.0/24: { credentials: corp-v3, polling: counters }
                  exporters:
                    edge-01: { address: 10.0.0.10 }
                """));
        final var endpoint = resolveOnly(snapshot, "10.0.0.10");
        poller.trackAndResolve(endpoint, 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);
        assertThat(sink.samples).isNotEmpty();
        assertThat(sink.samples.get(0).labels()).containsEntry("exporter", "edge-01");
    }

    @Test
    void aFailedCollectEmitsNothingAndBacksOff() throws Exception {
        final var snmp = new FakeSnmp();
        snmp.timeout = true;
        final var sink = new RecordingSink();
        final var poller = poller(snmp, config(), sink);
        final var endpoint = endpoint("10.0.0.10", "polling: counters");
        poller.trackAndResolve(endpoint, 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);
        assertThat(sink.samples).isEmpty();
        assertThat(sink.calls.get()).isEqualTo(0);
        assertThat(this.metrics.meter("snmp.poller.collectsFailed").getCount()).isEqualTo(1);

        // backed off: the next tick does not collect again
        poller.tick(this.clock.get());
        awaitQuiet(poller);
        assertThat(snmp.collects.get()).isEqualTo(1);
    }

    @Test
    void aDeviceWithoutIfXTableIsWarnedOnceNotPerCollect() throws Exception {
        final var snmp = new FakeSnmp();
        snmp.noIfXTable = true;
        final var poller = poller(snmp, config());
        final var endpoint = endpoint("10.0.0.11", "polling: counters");

        final var logger = (Logger) LoggerFactory.getLogger(InterfaceSnapshotPoller.class);
        final var appender = LogCapture.startedAppender();
        logger.addAppender(appender);
        try {
            poller.trackAndResolve(endpoint, 1);
            poller.tick(this.clock.get());
            awaitWalks(poller, snmp, 1);
            advanceMs(60_000L * 2);
            poller.trackAndResolve(endpoint, 1);
            poller.tick(this.clock.get());
            awaitWalks(poller, snmp, 2);
            assertThat(ifXTableWarnings(appender)).isEqualTo(1);

            // a later collect that carries ifName re-arms it
            snmp.noIfXTable = false;
            advanceMs(60_000L * 2);
            poller.trackAndResolve(endpoint, 1);
            poller.tick(this.clock.get());
            awaitWalks(poller, snmp, 3);
            snmp.noIfXTable = true;
            advanceMs(60_000L * 2);
            poller.trackAndResolve(endpoint, 1);
            poller.tick(this.clock.get());
            awaitWalks(poller, snmp, 4);
            assertThat(ifXTableWarnings(appender)).isEqualTo(2);
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static long ifXTableWarnings(final ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("not ifXTable"))
                .count();
    }

    @Test
    void aPollAlwaysEntryIsRegisteredFromTheInventoryAndSurvivesSilence() throws Exception {
        final var snmp = new FakeSnmp();
        final var sink = new RecordingSink();
        final var poller = poller(snmp, config(), sink);
        serve(parse(ALWAYS_INVENTORY));
        poller.refreshRegistrations();
        // the first walk of an inventory registration is spread across the interval (#900)
        advanceMs(60_000L + 60_000L / 50 + 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);

        assertThat(this.metrics.gauge("snmp.poller.inventoryRegistered").getValue()).isEqualTo(1);
        // silent for far longer than deregisterAfter intervals: still polled
        advanceMs(60_000L * 10);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 2);
        assertThat(sink.samples).extracting(s -> s.labels().get("exporter")).contains("silent-switch");
    }

    @Test
    void anAlwaysEntryWithoutACoveringAgentRangeIsWarnedAndSkipped() throws Exception {
        final var snmp = new FakeSnmp();
        final var poller = poller(snmp, config(), new RecordingSink());
        serve(parse("""
                riptide:
                  snmp:
                    agents: {}
                  exporters:
                    orphan: { address: 10.9.9.9, poll: always }
                """));
        final var logger = (Logger) LoggerFactory.getLogger(InterfaceSnapshotPoller.class);
        final var appender = LogCapture.startedAppender();
        logger.addAppender(appender);
        try {
            poller.refreshRegistrations();
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(m -> assertThat(m).contains("orphan").contains("no agent range"));
        } finally {
            logger.detachAppender(appender);
        }
        poller.tick(this.clock.get());
        awaitQuiet(poller);
        assertThat(snmp.collects.get() + snmp.walks.get()).isEqualTo(0);
        assertThat(this.metrics.gauge("snmp.poller.inventoryRegistered").getValue()).isEqualTo(0);
    }

    @Test
    void anAlwaysEntryRemovedFromTheInventoryFallsBackToFlowLifecycle() throws Exception {
        final var snmp = new FakeSnmp();
        final var poller = poller(snmp, config(), new RecordingSink());
        serve(parse(ALWAYS_INVENTORY));
        poller.refreshRegistrations();
        // the first walk of an inventory registration is spread across the interval (#900)
        advanceMs(60_000L + 60_000L / 50 + 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);

        // silent long enough that a flow registration would already be gone
        advanceMs(60_000L * 10);
        serve(parse(ALWAYS_INVENTORY.replace(", poll: always", "")));
        poller.refreshRegistrations();
        // silence counts from the downgrade, not from registration: still here one tick later
        poller.tick(this.clock.get());
        awaitQuiet(poller);
        assertThat(this.metrics.getGauges().get("snmp.poller.exporters").getValue()).isEqualTo(1);

        // no flows and silent for deregisterAfter intervals: now it goes
        advanceMs(60_000L * 3 + 1_000);
        poller.tick(this.clock.get());
        awaitQuiet(poller);
        assertThat(this.metrics.getGauges().get("snmp.poller.exporters").getValue()).isEqualTo(0);
        assertThat(this.metrics.gauge("snmp.poller.inventoryRegistered").getValue()).isEqualTo(0);
    }

    @Test
    void moreAlwaysEntriesThanMaxExportersRefusesTheWholeSet() throws Exception {
        final var snmp = new FakeSnmp();
        final var config = config();
        config.setMaxExporters(2);
        final var poller = poller(snmp, config, new RecordingSink());
        serve(parse("""
                riptide:
                  snmp:
                    agents:
                      10.0.0.0/24: { credentials: corp-v3, polling: counters }
                  exporters:
                    a: { address: 10.0.0.1, poll: always }
                    b: { address: 10.0.0.2, poll: always }
                    c: { address: 10.0.0.3, poll: always }
                """));
        final var logger = (Logger) LoggerFactory.getLogger(InterfaceSnapshotPoller.class);
        final var appender = LogCapture.startedAppender();
        logger.addAppender(appender);
        try {
            poller.refreshRegistrations();
            assertThat(appender.list)
                    .filteredOn(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(m -> assertThat(m).contains("3 entries").contains("riptide.snmp.poll.max-exporters"));
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(this.metrics.gauge("snmp.poller.inventoryRefused").getValue()).isEqualTo(3);
        assertThat(this.metrics.gauge("snmp.poller.inventoryRegistered").getValue()).isEqualTo(0);
        assertThat(this.metrics.getGauges().get("snmp.poller.exporters").getValue()).isEqualTo(0);
    }

    /**
     * The set fits under the cap, but flow registrations already fill it. The entry that finds no
     * room must show on the gauge and in the log, not only on rejectedLookups.
     */
    @Test
    void anAlwaysEntryRefusedBecauseFlowsFillTheCapIsCountedAndWarned() throws Exception {
        final var snmp = new FakeSnmp();
        final var config = config();
        config.setMaxExporters(2);
        final var poller = poller(snmp, config, new RecordingSink());
        final var live = serve(parse("""
                riptide:
                  snmp:
                    agents:
                      10.0.0.0/24: { credentials: corp-v3, polling: counters }
                  exporters:
                    c: { address: 10.0.0.3, poll: always }
                """));
        poller.trackAndResolve(resolveOnly(live, "10.0.0.1"), 1);
        poller.trackAndResolve(resolveOnly(live, "10.0.0.2"), 1);
        assertThat(this.metrics.getGauges().get("snmp.poller.exporters").getValue()).isEqualTo(2);

        final var logger = (Logger) LoggerFactory.getLogger(InterfaceSnapshotPoller.class);
        final var appender = LogCapture.startedAppender();
        logger.addAppender(appender);
        try {
            poller.refreshRegistrations();
            assertThat(appender.list)
                    .filteredOn(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(m -> assertThat(m).startsWith("1 of 1 poll: always entries are not polled")
                            .contains("riptide.snmp.poll.max-exporters (2)"));
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(this.metrics.gauge("snmp.poller.inventoryRefused").getValue()).isEqualTo(1);
        assertThat(this.metrics.gauge("snmp.poller.inventoryRegistered").getValue()).isEqualTo(0);
        assertThat(this.metrics.getGauges().get("snmp.poller.exporters").getValue()).isEqualTo(2);
    }

    @Test
    void pollAlwaysEntriesAreRegisteredAtBootWithoutARefresh() throws Exception {
        final var snmp = new FakeSnmp();
        serve(parse(ALWAYS_INVENTORY));
        // the constructor's boot path only runs with the scheduler started
        final var poller = new InterfaceSnapshotPoller(snmp, config(), this.metrics, this.serving,
                new RecordingSink(), identity(), this.clock::get, true, () -> WALL_CLOCK_MS);
        try {
            assertThat(this.metrics.gauge("snmp.poller.inventoryRegistered").getValue()).isEqualTo(1);
            assertThat(this.metrics.getGauges().get("snmp.poller.exporters").getValue()).isEqualTo(1);
        } finally {
            poller.stop();
        }
    }

    @Test
    void aCollectThatThrowsCountsAsAFailedCollectAndBacksOff() throws Exception {
        final var snmp = new FakeSnmp();
        snmp.throwOnCollect = true;
        final var sink = new RecordingSink();
        final var poller = poller(snmp, config(), sink);
        final var endpoint = endpoint("10.0.0.12", "polling: counters");
        poller.trackAndResolve(endpoint, 1);
        poller.tick(this.clock.get());
        awaitWalks(poller, snmp, 1);

        assertThat(this.metrics.meter("snmp.poller.collectsFailed").getCount()).isEqualTo(1);
        assertThat(sink.calls.get()).isEqualTo(0);
        poller.tick(this.clock.get());
        awaitQuiet(poller);
        assertThat(snmp.collects.get()).as("backed off").isEqualTo(1);
    }

    @Test
    void aSinkThatThrowsIsNotChargedToTheAgent() throws Exception {
        final var snmp = new FakeSnmp();
        final var config = config();
        // a back-off far longer than the one-minute profile, so a backed-off agent is visible
        config.setDeadEndpointBaseMs(1_800_000);
        final MetricSink throwing = samples -> {
            throw new IllegalStateException("queue exploded");
        };
        final var poller = poller(snmp, config, throwing);
        final var endpoint = endpoint("10.0.0.13", "polling: counters");

        final var logger = (Logger) LoggerFactory.getLogger(InterfaceSnapshotPoller.class);
        final var appender = LogCapture.startedAppender();
        logger.addAppender(appender);
        try {
            poller.trackAndResolve(endpoint, 1);
            poller.tick(this.clock.get());
            awaitWalks(poller, snmp, 1);

            // not backed off: the next cycle collects on the profile's own cadence
            advanceMs(60_000L * 2);
            poller.trackAndResolve(endpoint, 1);
            poller.tick(this.clock.get());
            awaitWalks(poller, snmp, 2);

            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(m -> assertThat(m).contains("Metric sink").contains("failed to accept"))
                    .noneSatisfy(m -> assertThat(m).contains("failed unexpectedly"))
                    .noneSatisfy(m -> assertThat(m).contains("did not produce a usable interface table"));
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(poller.trackAndResolve(endpoint, 1)).contains(new IfInfo("eth0", "uplink", 1000L));
    }
}
