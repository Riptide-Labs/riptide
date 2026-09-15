/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.riptide.discovery.ComposedInventoryDocument;
import org.riptide.inventory.Inventory;
import org.riptide.inventory.InventoryConfig;
import org.riptide.inventory.InventoryLoader;
import org.riptide.inventory.InventorySnapshot;
import org.riptide.inventory.SnmpProfilesConfig;
import org.riptide.snmp.InterfaceSnapshotPoller;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Hot-reload of the dedicated inventory file ({@code riptide.inventory.file}):
 * agent-range and exporter changes apply without a restart. A sibling of
 * {@link ConfigFileReloader}, sharing its trigger by owning a second
 * {@link FileWatchTrigger} rather than by being the same bean: the main reloader's
 * commit substitutes property sources, which never applies here: the inventory file is
 * direct-parsed by design and is never a property source. The watcher only watches and
 * triggers (AD-4); {@link InventoryLoader} parses and validates, {@link Inventory} serves.
 *
 * <p><b>Trigger</b>: {@link FileWatchTrigger}'s mtime-independent content-hash poll,
 * the one copy of the loop the main reloader also runs: path re-resolved every cycle,
 * missing file skips (atomic
 * {@code rm}+{@code mv} replacement is indistinguishable from deletion), empty or
 * blank file skips (a shell {@code >} redirect truncates before writing), unchanged
 * or already-attempted content short-circuits. The skips defuse truncate-style
 * rewrite races only: a non-atomic in-place writer can still expose a parseable
 * prefix for one interval, which the next cycle heals. Atomic rename replacement
 * remains the recommended write pattern.</p>
 *
 * <p><b>Failure semantics</b>: the candidate runs through the same loader as boot;
 * a failing reload keeps the last good snapshot serving, warns with the loader's
 * entry-naming message, and counts the failure with a staleness gauge.</p>
 *
 * <p><b>Where profiles come from</b>: the candidate is parsed against the profiles the
 * {@link Inventory} currently holds, not a copy captured at boot, because a main-config
 * reload republishes both the profiles and an inventory rebuilt from them. That is what
 * lets a credential rotation reach a range edited afterwards. Changing
 * {@code riptide.inventory.file} still needs a restart before this watcher follows it,
 * for a different reason in each mode. With discovery off the location is captured at
 * start. With it on, the composed document reads {@code InventoryConfig.getFile()} on
 * every fetch, but nothing rebinds that bean at runtime: {@code ConfigFileReloader} warns
 * about a changed path and keeps the boot one.</p>
 *
 * <p><b>With discovery on</b> ({@code riptide.discovery.url} holding a non-blank value; blank
 * counts as unset, so an exported-but-empty variable leaves discovery off) the watched source is the
 * {@link ComposedInventoryDocument}, not the file: discovery owns the {@code exporters} tree,
 * so a cycle that re-read the file alone would offer the loader a document with no exporters,
 * the regression guard below would refuse it forever, and the staleness gauge would pin at 1
 * while an operator's agent-range edit never applied. The pace then comes from
 * {@code riptide.discovery.interval} rather than {@code riptide.config.reload-interval}, so
 * enabling discovery does not also require enabling config hot-reload, and an unset
 * {@code riptide.inventory.file} is valid because the composed document supplies the whole
 * thing. The trigger, the regression pre-check, the deferral, the counters and the poller
 * refresh behave exactly as they do for the file, because they all still see one document.
 * The sentences differ: they name the composed document rather than a file, and the
 * refusal's remediation drops the {@code exporters: {}} advice, which discovery makes
 * impossible to follow. So does staleness on an absent source: a 404 latches
 * {@code inventory.reload.stale} where a missing file does not, because an operator who
 * deleted a file knows they did and sees it return, while an endpoint that starts answering
 * 404 under a running collector is invisible without the gauge. It latches in this class's
 * idle hook, never in the shared trigger, whose {@code Absent} case two other features
 * depend on. The failure counter still does not move: absence is not failure.</p>
 *
 * <p><b>A boot that could not reach the endpoint</b> does not fail startup: boot reads
 * {@code bootText()}, which then serves the file's trees with no exporters. This watcher is
 * what heals it. It starts with {@code inventory.reload.stale} latched at 1 and with no seeded
 * hashes, so the first cycle that fetches the endpoint publishes the discovered exporters and
 * clears the gauge. Every cycle reads the strict {@code fetch()}, so a reload never publishes a
 * degraded document.</p>
 */
@Slf4j
@Component
public class InventoryFileReloader {

    private final ConfigReloadProperties properties;
    private final InventoryConfig inventoryConfig;
    private final Inventory inventory;
    private final InterfaceSnapshotPoller interfacePoller;
    /**
     * The composed document when discovery is on, {@code null} when it is off. Typed to the
     * composed document rather than to {@code FileWatchTrigger.Source}: the classification rule
     * reloader publishes a {@code Source} bean too, so a by-interface lookup would find that one
     * with discovery off and this watcher would silently poll the classification ruleset.
     */
    private final ComposedInventoryDocument discovery;

    private final MetricRegistry metrics;
    private final Counter reloadSuccesses;
    private final Counter reloadFailures;

    /** The shared poll loop: schedule, hashes, skips, failure counting, gauges. */
    private FileWatchTrigger trigger;
    private Path location;
    /**
     * What the watched source is called in this reloader's own sentences. The file's path when
     * discovery is off, so every message below is spelled exactly as it always was. The
     * composed document's description when it is on, where {@link #location} can be null.
     *
     * <p>Taken from {@link Inventory#documentName()} rather than derived here a second time: that
     * is the one spelling, and it is the same one the sibling {@code ConfigFileReloader} and the
     * loader's own boot errors use, so a startup error, a reload error and a rebuild failure all
     * name the same thing.</p>
     */
    private String watched;

    public InventoryFileReloader(final ConfigReloadProperties properties,
                                 final InventoryConfig inventoryConfig,
                                 final Inventory inventory,
                                 final InterfaceSnapshotPoller interfacePoller,
                                 final MetricRegistry metrics,
                                 final Optional<ComposedInventoryDocument> discovery) {
        this.properties = Objects.requireNonNull(properties);
        this.inventoryConfig = Objects.requireNonNull(inventoryConfig);
        this.inventory = Objects.requireNonNull(inventory);
        this.interfacePoller = Objects.requireNonNull(interfacePoller);
        this.discovery = discovery.orElse(null);

        // counters stay here: a zero counter is true when reloading is disabled. The
        // gauges register from start(), after the disabled early-returns (#539): a gauge
        // registered here published a constant 0 with reloading disabled, which reads as
        // "the file matches what is serving" for a file that is never read again
        this.metrics = metrics;
        this.reloadSuccesses = metrics.counter(MetricRegistry.name("inventory", "reload", "successes"));
        this.reloadFailures = metrics.counter(MetricRegistry.name("inventory", "reload", "failures"));
    }

    @PostConstruct
    void start() {
        // discovery paces itself: riptide.discovery.interval, never riptide.config.reload-interval,
        // so turning discovery on does not also turn config hot-reload on
        final Duration interval = this.discovery != null
                ? this.discovery.interval()
                : this.properties.getReloadInterval();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            // named, not "no interval configured": the operator has to know which key to set
            log.debug("Inventory hot-reload disabled (no {})",
                    this.discovery != null ? "riptide.discovery.interval" : "riptide.config.reload-interval");
            return;
        }
        this.location = this.inventoryConfig.getFile();
        if (this.location == null && this.discovery == null) {
            log.debug("Inventory hot-reload disabled (no riptide.inventory.file)");
            return;
        }
        // the inventory's own name for its document: the file's path with discovery off, spelled
        // exactly as it always was, and "<file> + <endpoint>" with it on. Not derived from
        // this.location or this.discovery a second time — one spelling, shared with
        // ConfigFileReloader's rebuild failures and with the loader's boot errors
        this.watched = this.inventory.documentName();
        // hashes seeded from the content boot just served, so the first cycle does not
        // spuriously recommit unchanged content (a set-but-missing file fails startup, and so does
        // an endpoint that answers with content that cannot be composed. The seed is a second
        // fetch, not boot's bytes, so an endpoint that changed in between costs one re-parse,
        // which the trigger's seed already accepts as safe)
        if (this.discovery != null) {
            // the composed document, NOT the file: discovery owns the exporters tree, so a cycle
            // reading the file alone would offer a document with no exporters, the regression
            // pre-check below would refuse it every cycle forever, and an agent-range edit would
            // never apply. Vanished never comes back from it either: a remote source cannot tell
            // an atomic replacement from a deletion
            //
            // an endpoint that could not be reached at boot does NOT fail startup: boot served the
            // file's trees alone. Read here, after that boot: Inventory is a constructor dependency,
            // so its @PostConstruct load() has run. Two consequences. No seed, because a seed
            // fetch that succeeds now would record the discovered document as committed while the
            // file-only one serves, and the first cycle would skip it as unchanged forever. And
            // stale latches before the gauge exists, because what serves is not what the source
            // says, and a 404 skips cycles without ever recomputing it
            final boolean degraded = this.discovery.degradedAtBoot();
            this.trigger = new FileWatchTrigger(log, this.discovery, interval,
                    "InventoryFileReloader", messages(this.discovery), this.metrics, "inventory",
                    this.reloadFailures, !degraded, cycle());
            this.trigger.setStale(degraded);
        } else {
            this.trigger = new FileWatchTrigger(log, this.location, interval,
                    "InventoryFileReloader", messages(this.location), this.metrics, "inventory",
                    this.reloadFailures, true, cycle());
        }
        this.trigger.start(() -> this.trigger.isStale() ? 1 : 0);
        log.info("Inventory hot-reload enabled: watching {} every {}", this.watched, interval);
    }

    /** One copy of the commit hooks, so the two sources above cannot drift apart. */
    private FileWatchTrigger.Cycle cycle() {
        return new FileWatchTrigger.Cycle() {
            @Override
            public void onContent(final byte[] content) throws Exception {
                reload(content);
            }

            @Override
            public void onIdle() {
                // nothing is ever left pending here: the inventory commit either
                // publishes, defers to the next cycle, or is refused outright.
                //
                // Staleness is the one thing an idle cycle can still owe. With discovery on, a 404
                // is absence: the trigger warns once, skips, and touches neither the failure
                // counter nor its gauge, so after a healthy publish an endpoint that starts 404ing
                // left inventory.reload.stale reading 0 forever while every later discovery change
                // went unserved. Latched here, in discovery's own owner, and NOT in the trigger:
                // that loop is shared with the classification rule reloader and the plain file
                // reloader, where Absent means a deleted file and must keep meaning exactly that.
                // Only the gauge moves — a 404 is absence, not failure, and
                // inventory.reload.failures must not count a cycle that read nothing (#539)
                if (InventoryFileReloader.this.discovery != null
                        && InventoryFileReloader.this.discovery.endpointAbsent()) {
                    InventoryFileReloader.this.trigger.setStale(true);
                }
            }

            @Override
            public void onFailure(final Exception e) {
                // one WARN, and since #630 the loader's message is a multi-line
                // report listing every bad entry: it renders as a multi-line WARN
                // here, deliberately. The alternative — one line per problem —
                // interleaves with other threads' logging and stops being one
                // readable failure, which is the whole point of collecting them
                log.warn("Inventory reload failed, keeping the last good inventory: {}", e.getMessage(), e);
            }
        };
    }

    /** The skip and shutdown sentences, spelled the way this reloader has always spelled them. */
    private static FileWatchTrigger.Messages messages(final Path location) {
        return messages(
                ("Inventory file %s is missing: skipping reload cycles until it reappears "
                        + "(deletion and atomic replacement are indistinguishable; keeping the running inventory)")
                        .formatted(location),
                // "empty or whitespace-only", not "empty": the skip has always covered
                // whitespace here, and an operator told "is empty" about an 8-byte file
                // goes looking for a second problem that does not exist
                ("Inventory file %s is empty or whitespace-only: skipping reload cycle "
                        + "(truncate-write race or intentional; keeping the running inventory)").formatted(location));
    }

    /**
     * The same two sentences for the composed document, which has two halves that can be absent:
     * the endpoint and the inventory file. Neither file sentence is true of it as written: "until
     * it reappears" would tell an operator to restore a file when the endpoint is what answered
     * 404, and "truncate-write race" names a local writer that has nothing to do with a fetched
     * document. So the absent sentence names both halves, because
     * {@code ComposedInventoryDocument.fetch()} turns exactly two answers into {@code Absent} — a
     * 404 from the endpoint, and a {@code NoSuchFileException} from the file, which is the absence
     * the discovery-off path has always skipped. Every other failure is a counted throw with its
     * own WARN, a permission denial on the file included. {@code source.describe()} names the file
     * and the endpoint, so the operator can tell which half to look at.
     *
     * <p>The blank sentence is required by {@code Messages} but no composed answer reaches it
     * today, and that is a loss as well as a fact: the merge always emits an exporters tree and the
     * renderer refuses one with no entries, so a composed document is never blank — which means the
     * <em>file's</em> own blank protection went with it. With discovery off, a truncated or
     * whitespace-only inventory file is a silent benign skip. With discovery on it parses to an
     * empty root, composes into a well-formed document carrying the discovered exporters, and
     * reaches the commit path, where the regression guard in {@link #reload} catches the same
     * truncate-write race one layer later — as a "would drop a whole tree" WARN with
     * {@code inventory.reload.stale} latched, not as a silent skip. A future reader must not
     * conclude the blank guard still applies here; it cannot fire.</p>
     */
    private static FileWatchTrigger.Messages messages(final ComposedInventoryDocument source) {
        return messages(
                ("Inventory source %s is absent: either the endpoint answered 404 or the inventory file "
                        + "is missing (deletion and atomic replacement are indistinguishable). Skipping "
                        + "reload cycles until it can be read again (keeping the running inventory)")
                        .formatted(source.describe()),
                ("Inventory source %s composed an empty or whitespace-only document: skipping reload "
                        + "cycle (keeping the running inventory)").formatted(source.describe()));
    }

    /**
     * The three sentences that do not depend on where the document comes from. One copy, because
     * the alternative is two that drift. That drift is the defect {@code FileWatchTrigger} itself
     * was extracted to end.
     */
    private static FileWatchTrigger.Messages messages(final String missingSource, final String blankSource) {
        return new FileWatchTrigger.Messages(
                "Inventory reload poll skipped: thread interrupted (shutdown)",
                "Inventory reload poll interrupted mid-cycle (shutdown): {}",
                missingSource,
                blankSource,
                "Inventory reload housekeeping failed unexpectedly; the reload schedule keeps running: {}");
    }

    @PreDestroy
    void stop() {
        if (this.trigger != null) {
            this.trigger.stop();
        }
    }

    // visible for the scheduled task and tests; never throws (a throwing scheduled
    // task would silently cancel the schedule). The loop itself lives in the trigger,
    // which calls back into reload()
    void poll() {
        if (this.trigger != null) {
            this.trigger.poll();
        }
    }

    /** The commit path: parse the candidate, refuse or defer it, or publish it. */
    private void reload(final byte[] content) throws Exception {
        // the exact pure function boot uses: parse + validate + resolve (AD-4);
        // throws with entry-and-file-naming messages -> keep-old in the trigger's catch.
        // The decode is strict like boot's Files.readString: malformed bytes must
        // fail the reload here, not the next restart
        // the profiles as they are now, not as they were at boot: a main-config reload
        // can have rotated a credential since
        // captured, then republished with the candidate: parsing against one set of
        // profiles and committing while another is live would pair a snapshot with
        // profiles it was not built from, and the config reloader can commit between
        // these two lines
        final SnmpProfilesConfig parsedWith = this.inventory.profiles();
        // parseWithWarnings, not parse: the walk's warnings describe the candidate
        // as if it were live, so they flush only after the swap below commits — a
        // refused or deferred candidate logs nothing from the walk (#539)
        // named by what was watched, not by this.location: with discovery on the bytes are the
        // composed document and the location can be null. The composed name is also the one boot
        // gave the loader (Inventory.load() names the document by name()), so a reload error and
        // a startup error name the same thing
        final InventoryLoader.ParseResult parsed = InventoryLoader.parseWithWarnings(parsedWith,
                strictUtf8(content, subject()), this.watched);
        final InventorySnapshot candidate = parsed.snapshot();

        final InventorySnapshot serving = this.inventory.snapshot();
        if (candidate.isRegressiveOver(serving)) {
            // per tree, not whole-file: a non-atomic writer flushes the trees in file
            // order, so a mid-write read has one tree populated and one empty, and
            // publishing it would deregister the whole polled fleet or drop every
            // exporter name. Deleting the file already keeps the old inventory
            // serving, so refusing this is the same rule, not a new one. This is a
            // pre-check for the message; the monitor-held guard in Inventory decides
            // pre-formatted, not SLF4J placeholders: the message teaches the literal
            // "agents: {}" idiom, and {} in an SLF4J format string IS a placeholder —
            // the first version consumed its own arguments and printed shifted counts
            log.warn(("%s would drop a whole tree (%d -> %d agent range(s), %d -> %d "
                    + "enrichment entry/entries): keeping the running inventory (a partially written "
                    + "file reads this way; write atomically via mv). %s; "
                    + "to stop polling while keeping entries, set enabled: false on a covering "
                    + "range").formatted(subject(),
                    serving.agentCount(), candidate.agentCount(),
                    serving.exporterCount(), candidate.exporterCount(), emptyTreeAdvice()));
            // latch immediately, like the failure path: the watched content (the file, or
            // the composed document with discovery on) does not match what is serving.
            // Without this the gauge read 0 until the next cycle's
            // unchanged-content recompute flipped it — a one-interval blink the docs'
            // "a rejected file raises inventory.reload.stale" never had
            this.trigger.setStale(true);
            return;
        }

        if (!this.inventory.swapIfProfilesUnchanged(parsedWith, candidate)) {
            // a main-config reload republished the profiles while this candidate was
            // being parsed; committing would undo it. Leave the attempted hash unset so
            // the next cycle re-parses against what is now serving
            this.trigger.rollbackAttempt();
            log.info("Inventory reload deferred: the credential and polling profiles changed while {} "
                    + "was being parsed, so it is re-read on the next cycle", this.watched);
            return;
        }
        // Marked here, immediately after the swap above — #718's rule is "mark once what is
        // serving has changed", and swapIfProfilesUnchanged is that point. Everything below is
        // bookkeeping over a snapshot that is already live: flushWarnings() only logs. Moving the
        // mark past it was tried and reverted, because a throw from logging would then leave the
        // inventory serving and matching the file while stale latched at 1 with no self-heal until
        // the content changed — a permanent false alarm, worse than the transient one.
        this.trigger.markCommitted();
        this.reloadSuccesses.inc();
        this.trigger.setStale(false);
        parsed.flushWarnings();

        // swap, then refresh (AD-6): registrations built from the previous inventory
        // are re-resolved against this one, so a carve-out reaches an agent that is
        // already being polled instead of waiting out its deregistration deadline.
        // Guarded separately and after the commit bookkeeping: the snapshot IS serving
        // by now, so a failure here must not report the reload as failed, latch
        // staleness against content that is actually live, and then never retry
        // because the hash already matches
        try {
            this.interfacePoller.refreshRegistrations();
        } catch (final Exception e) {
            log.warn("Inventory reloaded, but refreshing polled endpoints failed: registrations keep "
                    + "their previous endpoints until their next flow or deregistration", e);
        }
        log.info("Inventory reloaded from {}: {} agent ranges, {} enrichment entries",
                this.watched, candidate.agentCount(), candidate.exporterCount());
    }

    /**
     * How this reloader's own sentences open: {@code Inventory file <path>} when discovery is off,
     * spelled exactly as it always was, and {@code Inventory document <file> + <endpoint>} when it
     * is on. "File" is false there: the bytes that failed are the composed document, and an
     * operator told "Inventory file" goes looking at a file that may not even be configured.
     */
    private String subject() {
        return (this.discovery == null ? "Inventory file " : "Inventory document ") + this.watched;
    }

    /**
     * The remediation clause of the regression refusal. With discovery on, "exporters: {}" is not
     * something an operator can write: the file may not carry an exporters tree at all (the
     * composed document refuses one), and the renderer refuses an empty result, so the only tree
     * a composed candidate can drop is the file's agent ranges.
     */
    private String emptyTreeAdvice() {
        return this.discovery == null
                ? "To deliberately empty a tree, write it as an explicit empty mapping (agents: {} / exporters: {})"
                : "To deliberately empty the agent ranges, write them as an explicit empty mapping (agents: {}); "
                        + "the exporters tree belongs to discovery, which never renders it empty";
    }

    private static String strictUtf8(final byte[] content, final String subject) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (final CharacterCodingException e) {
            // named like every loader error: two reloaders share this log. Unreachable from the
            // composed document, whose bytes are String.getBytes(UTF_8) and so always well formed;
            // the decode stays because a Source hands over bytes, and the file source's can be anything
            throw new IllegalStateException(
                    "%s is not valid UTF-8 (%s)".formatted(subject, e), e);
        }
    }
}
