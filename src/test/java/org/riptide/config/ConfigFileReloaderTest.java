/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ie.values.StringValue;
import org.riptide.flows.parser.ie.values.UnsignedValue;
import org.riptide.pipeline.ExporterIdentity;
import org.riptide.snmp.ExporterInterfaceTable;
import org.riptide.snmp.IfInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration: the reloader's whole contract against a real Spring context. The
 * config file does not exist at boot (test property sources are applied after
 * ConfigData), so every test also exercises the file-created-after-boot insertion at
 * imported-file precedence. Polls are driven manually — the scheduled interval
 * is far in the future.
 */
@SpringBootTest
public class ConfigFileReloaderTest {

    private static final Path CONFIG = createTempConfigPath();

    /** The blank-skip sentence, which since #561 says "empty or whitespace-only". */
    private static final String BLANK_SKIP = "is empty or whitespace-only — skipping reload cycle";

    /**
     * Declared at boot, like a real deployment: the inventory watcher captures its path at
     * start, so the config reloader deliberately will not follow a path that only appears
     * in a reloaded file.
     */
    private static final Path INVENTORY = createTempInventoryPath();

    private static Path createTempInventoryPath() {
        try {
            final Path file = Files.createTempFile("riptide-config-reload-inventory", ".yaml");
            file.toFile().deleteOnExit();
            // empty at boot: the config file is not part of the boot environment (only the
            // reloader reads it), so a credential reference here could not resolve yet
            Files.writeString(file, "riptide: {}\n");
            return file;
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    private ConfigFileReloader reloader;


    @Autowired
    private ExporterInterfaceTable exporterInterfaceTable;

    @Autowired
    private MetricRegistry metrics;

    @Autowired
    private org.riptide.inventory.Inventory inventory;

    private static Path createTempConfigPath() {
        try {
            final Path file = Files.createTempDirectory("riptide-reload-test").resolve("config.yaml");
            return file;
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void reloadProperties(final DynamicPropertyRegistry registry) {
        registry.add("spring.config.import", () -> "optional:file:" + CONFIG);
        registry.add("riptide.inventory.file", () -> INVENTORY.toString());
        registry.add("riptide.config.reload-interval", () -> "1h");
        // stands in for the environment-variable layer: test properties outrank files
        registry.add("riptide.snmp.credentials.envcred.version", () -> "v3");
        registry.add("riptide.snmp.credentials.envcred.security-name", () -> "from-the-override");
    }

    @BeforeEach
    void cleanSlate() throws IOException {
        Files.deleteIfExists(CONFIG);
        // the inventory file is shared and one test rewrites it to reference a credential set
        // its own config defines. Left in place, every later test whose config omits that set
        // makes the inventory rebuild throw, which the reloader swallows into a warning: the
        // profiles then stay stale and any assertion on them fails, depending only on method
        // order. Reset to something non-empty (an empty inventory over a populated one is
        // refused) that references nothing
        // agents declared explicitly empty: a test can leave agent ranges serving, and an
        // ABSENT tree over a populated one is refused by the torn-write guard (#535). The
        // marker is the sanctioned spelling of "deliberately none"
        Files.writeString(INVENTORY, """
                riptide:
                  snmp:
                    agents: {}
                  exporters:
                    neutral:
                      address: 198.51.100.1
                """);
        // fixture-critical and asserted as such: an empty inventory here would be refused by
        // rebuildAndSwap, the reloader would log a warning and still count a success, and the
        // profiles would silently keep the previous test's values — which is exactly the
        // order-dependence this reset exists to remove, returning with nothing pointing at it
        assertThat(Files.readString(INVENTORY)).contains("neutral");
    }

    /**
     * #533. The reload binder used the shared ApplicationConversionService, which lacks the
     * context's SecretRefConverter and falls back to SecretRef.of via reflection. The two
     * agree on every input except blank: boot reads "no secret", the reload threw — so a
     * blank optional secret booted fine and then permanently failed every reload.
     */
    @Test
    public void aBlankOptionalSecretCommitsOnReloadJustAsItBootsCleanly() throws Exception {
        final long successesBefore = metrics.counter("config.reload.successes").getCount();
        write("""
                riptide:
                  snmp:
                    credentials:
                      opt:
                        version: v3
                        security-name: monitoring
                        priv-passphrase: ""
                """);
        reloader.poll();

        assertThat(metrics.counter("config.reload.successes").getCount()).isEqualTo(successesBefore + 1);
        // serving, with the blank read as "no secret", exactly as boot reads it
        final var opt = inventory.profiles().credentials().get("opt");
        assertThat(opt).isNotNull();
        assertThat(opt.privPassphrase()).isNull();
        assertThat((Integer) metrics.getGauges().get("config.reload.stale").getValue()).isZero();
    }

    /**
     * The other half of the #533 agreement: a blank PAIRED secret must be rejected with the
     * same shape error boot gives (blank binds to null, and null fails the auth pairing),
     * not with the reflective path's "must not be blank" throw.
     */
    @Test
    public void aBlankPairedSecretIsRejectedWithBootsOwnError() throws Exception {
        final var appender = captureReloaderLog();
        try {
            final long failuresBefore = metrics.counter("config.reload.failures").getCount();
            write("""
                    riptide:
                      snmp:
                        credentials:
                          half:
                            version: v3
                            security-name: monitoring
                            auth-protocol: hmac192sha256
                            auth-passphrase: ""
                    """);
            reloader.poll();

            assertThat(metrics.counter("config.reload.failures").getCount()).isEqualTo(failuresBefore + 1);
            // the pairing error is the CAUSE of the bind failure, so it lives in the logged
            // exception chain rather than the message text. Under the old shared-instance
            // binder this chain carried "must not be blank" instead — the wrong error, from
            // the reflective fallback rather than from boot's own validation
            assertThat(appender.list).anySatisfy(event -> {
                final StringBuilder chain = new StringBuilder(event.getFormattedMessage());
                for (var proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                    chain.append('\n').append(proxy.getMessage());
                }
                assertThat(chain.toString())
                        .contains("pairs auth-protocol and auth-passphrase incompletely")
                        .doesNotContain("must not be blank");
            });
        } finally {
            releaseReloaderLog(appender);
        }
    }

    /**
     * #534, the full contract. A reload whose inventory rebuild does not publish used to
     * count a success and clear the stale gauge — and the first fix latched a boolean the
     * very next unchanged-content poll recomputed away, so the gauge showed 1 for one
     * interval and then lied again. The contract: stale reads 1 for as long as the edit is
     * not fully serving, and the pending half retries each poll until it heals.
     */
    @Test
    public void aPartialReloadStaysVisibleUntilItHeals() throws Exception {
        // seed: a committed reload against a publishable inventory, so the "populated
        // inventory refuses an empty candidate" precondition holds by construction rather
        // than by sibling-test ordering (this class has been order-dependent before)
        write("""
                riptide:
                  snmp:
                    credentials:
                      seed:
                        version: v3
                        security-name: monitoring
                """);
        reloader.poll();
        assertThat(inventory.profiles().credentials()).containsKey("seed");
        assertThat(inventory.snapshot().exporterCount()).isPositive();

        // the inventory file turns unpublishable (parses to no entries over a populated
        // inventory), and the operator rotates a credential in the main config
        Files.writeString(INVENTORY, "---\n");
        final long successesBefore = metrics.counter("config.reload.successes").getCount();
        final long partialBefore = metrics.counter("config.reload.partial").getCount();
        write("""
                riptide:
                  snmp:
                    credentials:
                      rotated:
                        version: v3
                        security-name: monitoring
                """);
        reloader.poll();

        // the config half committed and says so; the edit is not fully serving and the
        // gauge must not claim it is
        assertThat(metrics.counter("config.reload.successes").getCount()).isEqualTo(successesBefore + 1);
        assertThat(metrics.counter("config.reload.partial").getCount()).isEqualTo(partialBefore + 1);
        assertThat((Integer) metrics.getGauges().get("config.reload.stale").getValue()).isEqualTo(1);
        assertThat(inventory.profiles().credentials()).doesNotContainKey("rotated");

        // the next poll sees unchanged content. The first fix cleared the gauge here
        reloader.poll();
        assertThat((Integer) metrics.getGauges().get("config.reload.stale").getValue())
                .as("stale must hold while the edit is not fully serving")
                .isEqualTo(1);
        // counted per edit, not per retry
        assertThat(metrics.counter("config.reload.partial").getCount()).isEqualTo(partialBefore + 1);

        // the inventory file is fixed; the next unchanged-content poll retries the pending
        // rebuild and the rotation finally serves, with no further config edit
        Files.writeString(INVENTORY, """
                riptide:
                  snmp:
                    agents: {}
                  exporters:
                    neutral:
                      address: 198.51.100.1
                """);
        reloader.poll();
        assertThat(inventory.profiles().credentials()).containsKey("rotated");
        assertThat((Integer) metrics.getGauges().get("config.reload.stale").getValue()).isZero();
    }

    /**
     * The "retried every poll" promise holds in the degraded states too. A pending
     * partial heals from the INVENTORY file, which the missing-config branch says nothing
     * about — but the first version returned from the missing-file and empty-file
     * branches before the only retryPendingRebuild() call, so deleting or truncating the
     * CONFIG file suspended a stranded rotation exactly when the deployment was already
     * having a bad day.
     */
    @Test
    public void aPendingPartialHealsWhileTheConfigFileIsMissing() throws Exception {
        latchPendingPartial("rotmiss");

        Files.delete(CONFIG);
        healInventory();
        reloader.poll();

        assertThat(inventory.profiles().credentials()).containsKey("rotmiss");
        assertThat((Integer) metrics.getGauges().get("config.reload.stale").getValue()).isZero();
    }

    /** The truncated-file sibling of the missing-file case above. */
    @Test
    public void aPendingPartialHealsWhileTheConfigFileIsEmpty() throws Exception {
        latchPendingPartial("rotempty");

        Files.writeString(CONFIG, "");
        healInventory();
        reloader.poll();

        assertThat(inventory.profiles().credentials()).containsKey("rotempty");
        assertThat((Integer) metrics.getGauges().get("config.reload.stale").getValue()).isZero();
    }

    /**
     * #561, the divergence the shared trigger settled. A whitespace-only file is the same
     * truncate-write race a zero-byte one is, and the inventory watcher has always skipped
     * it — but this reloader tested {@code content.length == 0}, so two spaces reached
     * {@code reload()}, failed there with "parsed to no property sources", counted a
     * {@code config.reload.failures} and latched the stale gauge. The operations doc calls
     * this shape a skip, and it now is one on both reloaders.
     *
     * <p>The pending-partial setup is not decoration: the skip has to keep running the idle
     * hook, so this also pins that the healing retry survives a truncated config file that
     * is whitespace rather than zero bytes.</p>
     */
    @Test
    public void aWhitespaceOnlyConfigFileIsABenignSkipThatStillRunsTheRetry() throws Exception {
        latchPendingPartial("rotblank");
        healInventory();
        final long failuresBefore = metrics.counter("config.reload.failures").getCount();
        final long successesBefore = metrics.counter("config.reload.successes").getCount();

        Files.writeString(CONFIG, "  \n\t\n   ");
        reloader.poll();

        assertThat(metrics.counter("config.reload.failures").getCount())
                .as("a truncate-write race is a skip, not a failure").isEqualTo(failuresBefore);
        assertThat(metrics.counter("config.reload.successes").getCount())
                .as("and it commits nothing either").isEqualTo(successesBefore);
        // the idle hook ran during this very skip: the stranded rotation healed off the
        // inventory file, which is also the proof that the committed config is serving
        assertThat(inventory.profiles().credentials()).containsKey("rotblank");
        assertThat((Integer) metrics.getGauges().get("config.reload.stale").getValue()).isZero();
    }

    /**
     * #561, the other half: this reloader had no {@code warnedEmpty} latch, so a truncated
     * file warned on every single poll — forever, at whatever the reload interval is. The
     * inventory watcher's latch (and its re-arming) is now the shared behaviour.
     */
    @Test
    public void aTruncatedConfigFileWarnsOnceAndTheLatchReArms() throws Exception {
        // the latch lives on the trigger inside the cached Spring context, and cleanSlate()
        // resets the files, not the latches: a sibling test ending on a blank-file skip
        // leaves it armed. A non-blank read clears it, so this test starts from a known
        // state instead of from whatever ran before it
        write("""
                riptide:
                  snmp:
                    credentials:
                      before-truncation:
                        version: v3
                        security-name: monitoring
                """);
        reloader.poll();

        final var captured = captureReloaderLog();
        try {
            Files.writeString(CONFIG, "");
            for (int poll = 0; poll < 10; poll++) {
                reloader.poll();
            }
            assertThat(warnCount(captured, BLANK_SKIP)).as("ten polls, one warning").isEqualTo(1);

            write("""
                    riptide:
                      snmp:
                        credentials:
                          after-truncation:
                            version: v3
                            security-name: monitoring
                    """);
            reloader.poll();
            assertThat(inventory.profiles().credentials()).containsKey("after-truncation");

            // truncated again — and blank rather than empty, the shape that used to reach
            // reload() instead of this branch: the latch re-arms and warns once more
            Files.writeString(CONFIG, "   \n");
            reloader.poll();
            assertThat(warnCount(captured, BLANK_SKIP))
                    .as("the latch re-arms when content returns").isEqualTo(2);
        } finally {
            releaseReloaderLog(captured);
        }
    }

    /**
     * "Retried every poll" includes polls whose CHANGED content fails validation: a
     * rejected candidate neither supersedes the pending edit nor — in the first version —
     * retried it, so a continuously churning broken config file starved a stranded
     * rotation indefinitely while both WARNs promised otherwise.
     */
    @Test
    public void aPendingPartialHealsEvenWhileTheConfigFileChurnsInvalid() throws Exception {
        latchPendingPartial("rotchurn");
        healInventory();

        // changed content that fails validation BEFORE the commit/supersede (the blank
        // paired secret, the same shape aBlankPairedSecretIsRejectedWithBootsOwnError
        // proves is rejected), so the pending edit survives — and must still be retried
        // this very poll
        Files.writeString(CONFIG, """
                riptide:
                  snmp:
                    credentials:
                      churnbad:
                        version: v3
                        security-name: monitoring
                        auth-protocol: hmac192sha256
                        auth-passphrase: ""
                """);
        reloader.poll();

        assertThat(inventory.profiles().credentials()).containsKey("rotchurn");
    }

    /**
     * The last-failure memory dies with its pending episode. Episode A's rebuild throws
     * cause M and remembers it; a torn-file edit then latches a NEW episode via the
     * null path (which names no cause); the inventory starts throwing M again. To this
     * episode M is new information and must WARN — the first version kept the memory
     * across the supersede and DEBUG'd M as a repetition the operator never saw.
     */
    @Test
    public void aRetryCauseRecurringAcrossEpisodesStillWarns() throws Exception {
        // episode A: the rebuild throws (dangling credential reference), memory = M
        Files.writeString(INVENTORY, """
                riptide:
                  snmp:
                    agents:
                      "10.88.0.2":
                        credentials: not-defined-anywhere
                  exporters:
                    neutral:
                      address: 198.51.100.1
                """);
        write("""
                riptide:
                  snmp:
                    credentials:
                      epia:
                        version: v3
                        security-name: monitoring
                """);
        reloader.poll();

        // a new edit supersedes episode A but goes partial itself, via the torn-file
        // NULL path — no cause is named for this episode
        Files.writeString(INVENTORY, "---\n");
        write("""
                riptide:
                  snmp:
                    credentials:
                      epib:
                        version: v3
                        security-name: monitoring
                """);
        reloader.poll();

        // the inventory starts throwing M again: WARN, not a DEBUG'd "repetition"
        Files.writeString(INVENTORY, """
                riptide:
                  snmp:
                    agents:
                      "10.88.0.2":
                        credentials: not-defined-anywhere
                  exporters:
                    neutral:
                      address: 198.51.100.1
                """);
        final var appender = captureReloaderLog();
        try {
            reloader.poll();
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).hasToString("WARN");
                assertThat(event.getFormattedMessage())
                        .contains("still failing, now with")
                        .contains("not-defined-anywhere");
            });
        } finally {
            releaseReloaderLog(appender);
        }
    }

    /**
     * The throw path lands in the same latched-and-retried state as the refusal path, so
     * its WARN must prescribe the same remediation. The first version said "fix the file
     * and edit the config again, or restart" — steering the operator to a needless
     * restart (or a needless second edit) for a state the next poll heals once the
     * inventory file alone is fixed. The healing is asserted, not just the wording.
     */
    @Test
    public void aRebuildThrowNamesTheRetryNotARestart() throws Exception {
        // the inventory references a credential set the candidate config does not
        // define, so rebuildAndSwap THROWS (the refusal path returns null instead)
        Files.writeString(INVENTORY, """
                riptide:
                  snmp:
                    agents:
                      "10.88.0.1":
                        credentials: not-defined-anywhere
                  exporters:
                    neutral:
                      address: 198.51.100.1
                """);
        final var appender = captureReloaderLog();
        try {
            write("""
                    riptide:
                      snmp:
                        credentials:
                          rotthrow:
                            version: v3
                            security-name: monitoring
                    """);
            reloader.poll();

            assertThat((Integer) metrics.getGauges().get("config.reload.stale").getValue()).isEqualTo(1);
            assertThat(appender.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("could not be rebuilt")
                    .contains("retried every poll")
                    .doesNotContain("restart"));
        } finally {
            releaseReloaderLog(appender);
        }

        // the promise is kept: fixing the inventory file alone heals it, no second edit
        healInventory();
        reloader.poll();
        assertThat(inventory.profiles().credentials()).containsKey("rotthrow");
        assertThat((Integer) metrics.getGauges().get("config.reload.stale").getValue()).isZero();
    }

    /**
     * #630 here: the loader's failure is now a multi-line report, and this is the only
     * site that interpolates a rebuild failure into the MIDDLE of a sentence.
     *
     * <p>Every other fixture in this class carries exactly one bad inventory entry, so a
     * substring assertion passes identically whether the interpolated block is one line
     * or twenty-two. With a real report inside the parenthesis, "they are retried every
     * poll" landed below the last bullet — orphaned from the sentence it completes, at
     * the one message that explains a credential rotation is not serving yet.</p>
     */
    @Test
    public void aMultiProblemRebuildKeepsItsRemediationAheadOfTheReport() throws Exception {
        Files.writeString(INVENTORY, """
                riptide:
                  snmp:
                    agents:
                      "10.88.0.1":
                        credentials: not-defined-anywhere
                      "10.88.0.2":
                        polling: no-such-profile
                  exporters:
                    neutral:
                      address: 198.51.100.1
                """);
        final var appender = captureReloaderLog();
        try {
            write("""
                    riptide:
                      snmp:
                        credentials:
                          rotthrow:
                            version: v3
                            security-name: monitoring
                    """);
            reloader.poll();

            assertThat(appender.list).anySatisfy(event -> {
                final String message = event.getFormattedMessage();
                assertThat(message)
                        .contains("carries problems in 2 entries")
                        .contains("not-defined-anywhere")
                        .contains("no-such-profile");
                assertThat(message.indexOf("retried every poll"))
                        .as("the remediation finishes its sentence before the report starts")
                        .isLessThan(message.indexOf("\n  - "));
            });
        } finally {
            releaseReloaderLog(appender);
        }

        // the promise still holds with several problems: fixing the file alone heals it
        healInventory();
        reloader.poll();
        assertThat(inventory.profiles().credentials()).containsKey("rotthrow");
    }

    /**
     * #539, config half: a poll that begins interrupted is shutdown, not a reload
     * failure — it must not read, count, or commit anything. (The mid-read
     * ClosedByInterruptException belt in the catch is deliberately untested: a PRE-SET
     * flag does not fault the read on this JDK.) The same test pins the dead-schedule
     * gauge's healthy reading, registered from start().
     */
    @Test
    public void aPollBeginningInterruptedConsumesAndCountsNothing() throws Exception {
        final long failuresBefore = metrics.counter("config.reload.failures").getCount();
        final long successesBefore = metrics.counter("config.reload.successes").getCount();
        write("""
                riptide:
                  snmp:
                    credentials:
                      interrupted:
                        version: v3
                        security-name: monitoring
                """);
        final var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(ConfigFileReloader.class);
        final var previousLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        final var captured = captureReloaderLog();
        Thread.currentThread().interrupt();
        try {
            reloader.poll();
        } finally {
            // clear the flag or it poisons the next test on this thread
            Thread.interrupted();
            releaseReloaderLog(captured);
            logger.setLevel(previousLevel);
        }

        // the before-poll sentence, not the mid-cycle one: the two are adjacent String
        // arguments, both DEBUG, and only the mid-cycle one carries a placeholder — so a
        // swap would read plausibly while silently dropping an exception message
        assertThat(captured.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .isEqualTo("Config reload poll skipped: thread interrupted (shutdown)"));
        // and the poll thread carries this reloader's name: threadName and metricPrefix
        // are adjacent String arguments to the trigger, and only the gauges pin the other
        assertThat(Thread.getAllStackTraces().keySet())
                .anyMatch(thread -> "ConfigFileReloader".equals(thread.getName()));

        assertThat(metrics.counter("config.reload.successes").getCount())
                .as("an interrupted poll commits nothing").isEqualTo(successesBefore);
        assertThat(metrics.counter("config.reload.failures").getCount())
                .as("shutdown is not a failure").isEqualTo(failuresBefore);
        assertThat((Integer) ((com.codahale.metrics.Gauge<?>)
                metrics.getGauges().get("config.reload.dead")).getValue())
                .as("the schedule is alive").isZero();

        // the content was never consumed, so the next clean poll serves it normally
        reloader.poll();
        assertThat(metrics.counter("config.reload.successes").getCount()).isEqualTo(successesBefore + 1);
        assertThat(inventory.profiles().credentials()).containsKey("interrupted");
    }

    /** Seeds a committed reload, then latches a partial edit carrying {@code credential}. */
    private void latchPendingPartial(final String credential) throws Exception {
        // seed against a publishable inventory, so the "populated inventory refuses an
        // empty candidate" precondition holds by construction (same reasoning as
        // aPartialReloadStaysVisibleUntilItHeals)
        write("""
                riptide:
                  snmp:
                    credentials:
                      seed-%s:
                        version: v3
                        security-name: monitoring
                """.formatted(credential));
        reloader.poll();
        assertThat(inventory.snapshot().exporterCount()).isPositive();

        Files.writeString(INVENTORY, "---\n");
        write("""
                riptide:
                  snmp:
                    credentials:
                      %s:
                        version: v3
                        security-name: monitoring
                """.formatted(credential));
        reloader.poll();
        assertThat(inventory.profiles().credentials())
                .as("precondition: the edit latched as a partial")
                .doesNotContainKey(credential);
    }

    /** The sanctioned neutral inventory: declared-empty agents, one exporter. */
    private void healInventory() throws IOException {
        Files.writeString(INVENTORY, """
                riptide:
                  snmp:
                    agents: {}
                  exporters:
                    neutral:
                      address: 198.51.100.1
                """);
    }

    /**
     * #537, the landmine half. A profile-gated document carrying a fatal startup key
     * commits nothing and fails nothing (pinned posture), but activating that profile
     * later converts a working deployment into a boot that refuses to come up — and the
     * reload is the only moment anything reads the document before then. It must say so.
     */
    @Test
    public void aGatedDocumentCarryingAFatalKeyWarnsButStillCommits() throws Exception {
        final var captured = captureReloaderLog();
        try {
            final long successesBefore = metrics.counter("config.reload.successes").getCount();
            write("""
                    riptide:
                      snmp:
                        credentials:
                          plain:
                            version: v3
                            security-name: monitoring
                    ---
                    spring:
                      config:
                        activate:
                          on-profile: some-day
                    riptide:
                      nodes:
                        dormant:
                          subnet-address: 10.0.0.1
                    ---
                    spring:
                      config:
                        activate:
                          on-profile: [alpha, beta]
                    riptide:
                      snmp:
                        poll:
                          refresh-interval-ms: 60000
                    ---
                    spring:
                      config:
                        activate:
                          on-cloud-platform: kubernetes
                    riptide:
                      snmp:
                        agents:
                          "10.0.0.0/24":
                            credentials: plain
                    """);
            reloader.poll();

            // the pinned posture holds: dormant configuration commits the active half
            assertThat(metrics.counter("config.reload.successes").getCount()).isEqualTo(successesBefore + 1);
            assertThat(inventory.profiles().credentials()).containsKey("plain");
            // the landmine is named: gate and key, neither of which boilerplate contains
            assertThat(captured.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("some-day")
                    .contains("riptide.nodes.dormant")
                    .contains("only signal before that boot"));
            // a multi-profile gate flattens to on-profile[0]/[1]; the first version of the
            // warn read the exact scalar key and printed "profile 'null'" for exactly the
            // shapes the gate accepts — both names must appear, and 'null' must not
            assertThat(captured.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("alpha").contains("beta")
                    .contains("refresh-interval-ms")
                    .doesNotContain("null"));
            // and a cloud-platform gate is not a profile; the retired-key and misplaced-tree
            // probes cover the other two fatal checks through the same path
            assertThat(captured.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("kubernetes")
                    .contains("riptide.snmp.agents"));
        } finally {
            releaseReloaderLog(captured);
        }
    }

    /**
     * A file staged entirely for a future profile is the shape most likely to be all
     * landmine, and the first version rejected it before the landmine scan ran — the
     * warning's whole purpose defeated on the file that needed it most. The rejection
     * stands (nothing is applicable); the warning now precedes it.
     */
    @Test
    public void anAllGatedFileStillGetsItsLandmineWarningBeforeRejection() throws Exception {
        final var captured = captureReloaderLog();
        try {
            final long failuresBefore = metrics.counter("config.reload.failures").getCount();
            write("""
                    spring:
                      config:
                        activate:
                          on-profile: some-day
                    riptide:
                      nodes:
                        dormant:
                          subnet-address: 10.0.0.1
                    """);
            reloader.poll();

            assertThat(metrics.counter("config.reload.failures").getCount()).isEqualTo(failuresBefore + 1);
            assertThat(captured.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("some-day").contains("riptide.nodes.dormant"));
        } finally {
            releaseReloaderLog(captured);
        }
    }

    /**
     * #537, the half-view half. A nested spring.config.import is boot-only: this reload
     * substitutes only the watched file's own documents, so whatever is behind the import
     * is invisible until the next restart — for any key, not just the fatal ones.
     */
    @Test
    public void aNestedImportWarnsOncePerContentVersionNamingEveryImport() throws Exception {
        final var captured = captureReloaderLog();
        try {
            write("""
                    spring:
                      config:
                        import: [optional:file:/etc/riptide/more.yaml, optional:file:/etc/riptide/extra.yaml]
                    riptide:
                      snmp:
                        credentials:
                          plain:
                            version: v3
                            security-name: monitoring
                    """);
            reloader.poll();

            assertThat(inventory.profiles().credentials()).containsKey("plain");
            // every import named, not just the first: a warning naming one file while
            // omitting another would read as complete
            assertThat(captured.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("more.yaml").contains("extra.yaml")
                    .contains("only a restart reads them"));

            // unchanged content: the hash latch means no reload at all, so no repetition
            final long importWarns = warnCount(captured, "nested spring.config.import");
            reloader.poll();
            assertThat(warnCount(captured, "nested spring.config.import")).isEqualTo(importWarns);

            // a NEW content version still carrying the import warns again: once per content
            // version is the documented semantics — the reminder working, not a defect
            write("""
                    spring:
                      config:
                        import: [optional:file:/etc/riptide/more.yaml, optional:file:/etc/riptide/extra.yaml]
                    riptide:
                      snmp:
                        credentials:
                          plain:
                            version: v3
                            security-name: monitoring
                          second:
                            version: v3
                            security-name: monitoring
                    """);
            reloader.poll();
            assertThat(warnCount(captured, "nested spring.config.import")).isEqualTo(importWarns + 1);
        } finally {
            releaseReloaderLog(captured);
        }
    }

    /** Warnings whose rendered message contains {@code needle}. */
    private static long warnCount(
            final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captured,
            final String needle) {
        return captured.list.stream()
                .filter(event -> event.getFormattedMessage().contains(needle))
                .count();
    }

    private static ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captureReloaderLog() {
        final var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(ConfigFileReloader.class);
        final var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void releaseReloaderLog(
            final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender) {
        final var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(ConfigFileReloader.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void malformedCredentialEditIsRejectedOnReloadNotAtTheNextBoot() throws Exception {
        // 2.3's bind-time shape validation must gate reload candidates too: a
        // half-configured USM pair committing cleanly here would fail the NEXT boot
        final long failuresBefore = this.metrics.counter("config.reload.failures").getCount();
        write("""
                riptide:
                  snmp:
                    credentials:
                      half:
                        version: v3
                        security-name: riptide
                        auth-passphrase: env://X
                """);
        this.reloader.poll();

        assertThat(this.metrics.counter("config.reload.failures").getCount()).isEqualTo(failuresBefore + 1);
    }

    private void write(final String yaml) throws IOException {
        Files.writeString(CONFIG, yaml);
    }

    private ExporterIdentity identity(final String host, final long domain) throws Exception {
        return new ExporterIdentity.NetflowIpfix(InetAddress.getByName(host), domain);
    }

    @Test
    public void fileCreatedAfterBootAppliesBeneathOverridesAndBindsFully(
            @Autowired final org.springframework.core.env.ConfigurableEnvironment environment) throws Exception {
        write("""
                riptide:
                  snmp:
                    credentials:
                      envcred:
                        version: v3
                        security-name: from-the-file
                      filecred:
                        version: v2c
                        community: env://RELOAD_TEST_COMMUNITY
                  routing:
                    prefixes:
                      "[203.0.113.0/24]": { asn: 64500, org: "Reloaded Org" }
                """);
        reloader.poll();

        // bound, not just present: the SecretRef constructor binding is the part most likely
        // to break silently, and asserting the raw property string would not exercise it
        final var credentials = inventory.profiles().credentials();
        assertThat(credentials.get("filecred").community().getScheme()).isEqualTo("env");
        assertThat(environment.getProperty("riptide.routing.prefixes[203.0.113.0/24].org"))
                .isEqualTo("Reloaded Org");

        // the override layer still wins: the property source outranks the file it sits above
        assertThat(credentials.get("envcred").securityName()).isEqualTo("from-the-override");

        assertThat(metrics.counter("config.reload.successes").getCount()).isPositive();
    }

    @Test
    public void invalidCandidateKeepsServingTheOldConfig() throws Exception {
        write("""
                riptide:
                  snmp:
                    credentials:
                      good:
                        version: v3
                        security-name: monitoring
                """);
        reloader.poll();
        assertThat(inventory.profiles().credentials()).containsKey("good");
        final long failuresBefore = metrics.counter("config.reload.failures").getCount();

        write("""
                riptide:
                  snmp:
                    credentials:
                      broken:
                        version: v3
                """);
        reloader.poll();

        // old config keeps serving; failure counted; staleness visible
        assertThat(inventory.profiles().credentials()).containsKey("good").doesNotContainKey("broken");
        assertThat(metrics.counter("config.reload.failures").getCount()).isEqualTo(failuresBefore + 1);
        assertThat((Integer) metrics.getGauges().get("config.reload.stale").getValue()).isEqualTo(1);
    }

    @Test
    public void invalidRoutingAlsoRejectsTheCandidate() throws Exception {
        final long failuresBefore = metrics.counter("config.reload.failures").getCount();
        write("""
                riptide:
                  routing:
                    prefixes:
                      "[10.0.0.0/24]": { asn: 1 }
                      "[10.0.0.5/24]": { asn: 2 }
                """);
        reloader.poll();

        assertThat(metrics.counter("config.reload.failures").getCount()).isEqualTo(failuresBefore + 1);
    }

    @Test
    public void anyLegacyNodesTreeRejectsTheCandidate() throws Exception {
        final var logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(ConfigFileReloader.class);
        final var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        final var captured = appender.list;
        try {
        write("""
                riptide:
                  snmp:
                    credentials:
                      still-serving:
                        version: v3
                        security-name: monitoring
                """);
        reloader.poll();
        assertThat(inventory.profiles().credentials()).containsKey("still-serving");

        // both spellings: the indexed list this check originally guarded, and the name-keyed
        // map that 0.9 removed outright. A running collector must not accept a reload that
        // adds either, now that nothing reads them
        for (final String legacy : new String[] {
                "riptide:\n  nodes:\n    - subnet-address: 10.10.0.0/16\n",
                "riptide:\n  nodes:\n    core:\n      subnet-address: 10.10.0.0/16\n"}) {
            final long failuresBefore = metrics.counter("config.reload.failures").getCount();
            write(legacy);
            reloader.poll();
            assertThat(metrics.counter("config.reload.failures").getCount())
                    .as("rejected: %s", legacy)
                    .isEqualTo(failuresBefore + 1);
            // rejected AND still serving: a check that took the collector down with it would
            // satisfy the counter and fail the requirement
            assertThat(inventory.profiles().credentials()).containsKey("still-serving");
        }
        // and rejected by THIS check: without naming the cause, an unrelated validation
        // failure would keep the counter moving and the test green with the check deleted.
        // The category label AND its own remediation, not merely that a rejection happened —
        // the collected message names every category, so "a failure occurred" is now satisfied
        // by any of them
        assertThat(captured).anySatisfy(event ->
                assertThat(event.getFormattedMessage())
                        .contains("riptide.nodes tree")
                        .contains("riptide.nodes was removed in 0.9"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    public void rotatingACredentialReachesTheServingInventory() throws Exception {
        // the regression this closes: riptide.nodes used to carry credentials and
        // propagated them on reload. It is inert now, so riptide.snmp.credentials is the
        // only credential surface, and it used to be bound once at boot and never again:
        // rotating a compromised community would have needed a restart, silently
        // the range arrives with the credential that defines it, both through the reload
        // both trees populated: replacing the fixture's exporters-only inventory with an
        // agents-only one is indistinguishable from a torn write (one tree flushed, the
        // other truncated) and is now refused by the per-tree guard (#535)
        Files.writeString(INVENTORY, """
                riptide:
                  snmp:
                    agents:
                      "10.77.0.1":
                        credentials: corp
                  exporters:
                    neutral:
                      address: 198.51.100.1
                """);
        write("""
                riptide:
                  snmp:
                    credentials:
                      corp:
                        version: v2c
                        community: old-community
                """);
        reloader.poll();

        assertThat(resolvedCommunity()).isEqualTo("old-community");

        write("""
                riptide:
                  snmp:
                    credentials:
                      corp:
                        version: v2c
                        community: new-community
                """);
        reloader.poll();

        // no restart: the profiles and the inventory built from them move together
        assertThat(resolvedCommunity()).isEqualTo("new-community");
    }

    private String resolvedCommunity() throws Exception {
        final var identity = new org.riptide.pipeline.ExporterIdentity.NetflowIpfix(
                java.net.InetAddress.getByName("10.77.0.1"), 0L);
        return this.inventory.snapshot().agentView().match(identity).orElseThrow()
                .credentials().community().getValue();
    }

    @Test
    public void anInventoryTreeInTheMainConfigRejectsTheCandidate() throws Exception {
        // boot refuses it, so a reload must too: accepting it would silently ignore the
        // tree until the next restart failed
        final long failuresBefore = metrics.counter("config.reload.failures").getCount();
        write("""
                riptide:
                  exporters:
                    core-router:
                      address: 10.0.0.1
                """);
        reloader.poll();

        assertThat(metrics.counter("config.reload.failures").getCount()).isEqualTo(failuresBefore + 1);
    }

    @Test
    public void retiredPollKeysRejectTheCandidate() throws Exception {
        // a reload-accepted retired key would otherwise kill the NEXT boot
        final long failuresBefore = metrics.counter("config.reload.failures").getCount();
        write("""
                riptide:
                  snmp:
                    poll:
                      refresh-interval-ms: 60000
                """);
        reloader.poll();

        assertThat(metrics.counter("config.reload.failures").getCount()).isEqualTo(failuresBefore + 1);
    }

    @Test
    public void exporterOptionDataSurvivesReload() throws Exception {
        final var exporter = identity("172.16.0.1", 0);
        exporterInterfaceTable.accept(exporter,
                List.of(new UnsignedValue("SCOPE:INTERFACE", 7)),
                List.of(new StringValue("IF_NAME", "persistent")));

        write("""
                riptide:
                  snmp:
                    credentials:
                      another:
                        version: v3
                        security-name: monitoring
                """);
        reloader.poll();

        // the reload has to have committed, or an untouched interface table proves nothing:
        // a rejected candidate leaves the table alone too, and the test would pass either way
        assertThat(inventory.profiles().credentials()).containsKey("another");
        assertThat(exporterInterfaceTable.lookup(exporter, 7)).contains(new IfInfo("persistent", null, null));
    }

    @Test
    public void missingAndEmptyFilesSkipWithoutFailing() throws Exception {
        final long failuresBefore = metrics.counter("config.reload.failures").getCount();
        final long successesBefore = metrics.counter("config.reload.successes").getCount();

        reloader.poll(); // file deleted by cleanSlate

        write("");
        reloader.poll(); // truncate-write race shape

        assertThat(metrics.counter("config.reload.failures").getCount()).isEqualTo(failuresBefore);
        assertThat(metrics.counter("config.reload.successes").getCount()).isEqualTo(successesBefore);
    }

    @Test
    public void unchangedContentDoesNotRecommit() throws Exception {
        write("""
                riptide:
                  snmp:
                    credentials:
                      steady:
                        version: v3
                        security-name: monitoring
                """);
        reloader.poll();
        // the reload must actually have committed, or "does not recommit" is trivially true
        assertThat(inventory.profiles().credentials()).containsKey("steady");
        final long successesAfterFirst = metrics.counter("config.reload.successes").getCount();

        reloader.poll();

        assertThat(metrics.counter("config.reload.successes").getCount()).isEqualTo(successesAfterFirst);
    }

    @Test
    public void laterYamlDocumentsOverrideEarlierOnesLikeBoot() throws Exception {
        write("""
                riptide:
                  snmp:
                    credentials:
                      multidoc:
                        version: v3
                        security-name: first
                ---
                riptide:
                  snmp:
                    credentials:
                      multidoc:
                        version: v3
                        security-name: second
                """);
        reloader.poll();

        // boot semantics: the LAST document wins
        assertThat(inventory.profiles().credentials().get("multidoc").securityName())
                .isEqualTo("second");
    }

    @Test
    public void profileGatedDocumentsAreSkippedNotApplied() throws Exception {
        write("""
                riptide:
                  snmp:
                    credentials:
                      plain:
                        version: v3
                        security-name: monitoring
                ---
                spring:
                  config:
                    activate:
                      on-profile: never-active
                riptide:
                  snmp:
                    credentials:
                      gated:
                        version: v3
                        security-name: monitoring
                """);
        reloader.poll();

        assertThat(inventory.profiles().credentials())
                .containsKey("plain").doesNotContainKey("gated");
    }

    @Test
    public void retiredKeysInsideSkippedGatedDocumentsDoNotFailTheReload() throws Exception {
        // gated documents are never installed on reload, so a retired key in one
        // must not reject the candidate (it does not fail boot either — inactive
        // gated documents are never loaded there)
        final long failuresBefore = metrics.counter("config.reload.failures").getCount();
        write("""
                riptide:
                  snmp:
                    credentials:
                      plain:
                        version: v3
                        security-name: monitoring
                ---
                spring:
                  config:
                    activate:
                      on-profile: never-active
                riptide:
                  snmp:
                    poll:
                      refresh-interval-ms: 60000
                """);
        reloader.poll();

        assertThat(metrics.counter("config.reload.failures").getCount()).isEqualTo(failuresBefore);
        assertThat(inventory.profiles().credentials()).containsKey("plain");
    }

    @Test
    public void fileInsertsAboveClasspathDefaults(@Autowired final org.springframework.core.env.ConfigurableEnvironment environment) throws Exception {
        // classpath application.properties sets riptide.snmp.cache.retentionMs=600000;
        // the reloaded file must outrank it (imported-file precedence)
        write("""
                riptide:
                  snmp:
                    cache:
                      retentionMs: 123
                    credentials:
                      precedence:
                        version: v3
                        security-name: monitoring
                """);
        reloader.poll();

        assertThat(environment.getProperty("riptide.snmp.cache.retentionMs")).isEqualTo("123");
    }
}
