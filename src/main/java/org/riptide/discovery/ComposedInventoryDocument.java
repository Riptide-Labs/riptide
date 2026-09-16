/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import lombok.extern.slf4j.Slf4j;
import org.riptide.config.FileWatchTrigger;
import org.riptide.inventory.InventoryDocument;
import org.riptide.inventory.InventoryLoader;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The inventory document when discovery is on: agent ranges from the file, exporters from the
 * endpoint, merged into one document the existing loader validates as a whole.
 *
 * <p><b>Why compose rather than publish.</b> {@code Inventory} holds one immutable snapshot with
 * both trees behind a single volatile write. A discovery publisher of its own would publish an
 * empty agents tree, which the regression guard would either refuse forever or, once declared,
 * apply by deregistering the entire polled fleet. Composing keeps one document, one hash and one
 * commit path, and every guard downstream keeps working unchanged.</p>
 *
 * <p><b>The merge is structural.</b> The file is parsed, the rendered {@code riptide.exporters}
 * tree is added to it, and the result is dumped for the loader. A file that already declares an
 * exporters tree is refused, never overridden. Textual splicing is rejected because it would have
 * to find a subtree by position in a file an operator hand-writes.</p>
 *
 * <p><b>The file is parsed by the loader's rules, not a copy of them.</b>
 * {@link InventoryLoader#readTopLevels} is the loader's own parse of the top two levels. So a
 * duplicate key anywhere in the file, malformed YAML, a root or {@code riptide} tree that is not a
 * mapping, and a non-string key at either of those two levels all fail exactly as they fail with
 * discovery off, naming the file. Everything below the {@code riptide} level is carried into the
 * composed text as parsed and validated there by the loader. Round-tripping loses the file's
 * comments, which nothing downstream reads.</p>
 *
 * <p><b>It is also the watched source.</b> {@code InventoryFileReloader} polls this as its
 * {@link FileWatchTrigger.Source} whenever discovery is on and {@link #interval()} is positive, so
 * the bytes the watcher hashes and re-parses are the composed document rather than the file alone.
 * Watching the file while discovery owns the exporters tree would hand the loader a document with
 * no exporters on every cycle, which the regression guard refuses forever.</p>
 *
 * <p><b>Two reads, one of which degrades.</b> {@link #bootText()} is boot's, and serves the file's
 * trees alone when the endpoint cannot be fetched. {@link #text()} and {@link #fetch()} are every
 * later reader's, and throw instead. The distinction is a method, not a flag, so no later reader
 * can reach the degraded answer by calling in an unexpected order.</p>
 */
@Slf4j
public class ComposedInventoryDocument implements InventoryDocument, FileWatchTrigger.Source {

    /** What a discovery fetch answers with; an interface so tests need no HTTP server. */
    @FunctionalInterface
    public interface Fetcher {
        byte[] fetch() throws IOException;
    }

    private final InventoryDocument file;
    private final Fetcher fetcher;
    private final Supplier<String> describe;
    private final DiscoveryConfig config;
    private final AtomicInteger targets = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private volatile boolean degradedAtBoot;
    private volatile boolean endpointAbsent;

    public ComposedInventoryDocument(final InventoryDocument file,
                                     final Fetcher fetcher,
                                     final Supplier<String> describe,
                                     final DiscoveryConfig config,
                                     final MetricRegistry metrics) {
        this.file = Objects.requireNonNull(file);
        this.fetcher = Objects.requireNonNull(fetcher);
        this.describe = Objects.requireNonNull(describe);
        this.config = Objects.requireNonNull(config);
        Objects.requireNonNull(metrics, "metrics");
        // remove-then-register, not Dropwizard's get-or-create: a restarted bean would otherwise
        // be handed the old bean's lambda, permanently reading dead fields
        register(metrics, "discovery.targets", this.targets);
        register(metrics, "discovery.skipped", this.skipped);
    }

    private static void register(final MetricRegistry metrics, final String name, final AtomicInteger value) {
        metrics.remove(name);
        metrics.register(name, (Gauge<Integer>) value::get);
    }

    /**
     * The composed document, strictly: any failure to fetch or compose throws. This is what the
     * watcher and both credential-rotation rebuilds read, so none of them can ever publish a
     * document without the discovered exporters.
     */
    @Override
    public String text() {
        final byte[] json;
        try {
            json = this.fetcher.fetch();
        } catch (final IOException e) {
            throw new IllegalStateException(
                    "%s could not be read: %s".formatted(this.describe.get(), e.getMessage()), e);
        }
        return compose(json);
    }

    /**
     * Boot's read, which degrades on exactly one failure: the fetch itself. A refused connection,
     * a timeout, a non-200 and a 404 all mean the endpoint could not be reached, and boot then serves
     * the file's trees with no exporters tree instead of refusing to start. A flow collector that
     * will not start while NetBox is down is worse than one that starts without device names.
     *
     * <p>Everything else still fails boot: an answer that is not a service discovery document, a
     * name collision, an empty answer, and an exporters tree in the file. Those are configuration an
     * operator has to see, not an endpoint that is down.</p>
     *
     * <p>The degraded document has no {@code exporters} key at all rather than an empty one. An empty
     * mapping is how an operator declares a tree deliberately empty, which the regression guard
     * honours; a missing tree over a populated one is refused. So if a degraded document ever reached
     * a reload, the guard would refuse it rather than drop every exporter name. One file shape escapes
     * this: a bare {@code riptide: {}}, which declares both trees empty and is carried as written.</p>
     */
    @Override
    public String bootText() {
        final byte[] json;
        try {
            json = this.fetcher.fetch();
        } catch (final IOException e) {
            // composed first, warned after: the merge can still fail boot with the endpoint down
            // (an exporters tree in the file is refused either way, and so is an unparseable
            // file), and warning first printed "serving the inventory file's trees" immediately
            // above a startup failure that served nothing at all
            final String degraded = merge(null);
            this.degradedAtBoot = true;
            // the retry clause is chosen, not asserted: with a non-positive interval the watcher
            // never starts, so it registers no inventory.reload.stale gauge at all, and the
            // sentence promising one reads as "wait for it" about something that never arrives
            log.warn("Boot could not reach {}: {}. Serving the inventory file's trees with no discovered "
                    + "exporters. {}", this.describe.get(), reason(e), retryClause());
            return degraded;
        }
        this.degradedAtBoot = false;
        return compose(json);
    }

    /**
     * A fetch failure with the endpoint's name taken out of it, because the sentence quoting this
     * has already said it. Every message {@link org.riptide.config.BoundedHttpRead} raises opens
     * with exactly this description ("&lt;endpoint&gt; answered 404"), so quoting it whole read
     * "Boot could not reach X (X answered 404)". A JDK-raised message ("Connection refused") names
     * nothing and is quoted as it is.
     */
    private String reason(final IOException e) {
        final String message = String.valueOf(e.getMessage());
        final String endpoint = this.describe.get();
        return message.startsWith(endpoint) ? message.substring(endpoint.length()).trim() : message;
    }

    /**
     * What a degraded boot can honestly promise an operator, which depends on whether a watcher
     * will exist. {@code InventoryFileReloader} starts only on a positive interval, and it is the
     * bean that registers {@code inventory.reload.stale}, so with a non-positive one there is no
     * retry and no gauge to watch — only a restart, once the endpoint is back.
     */
    private String retryClause() {
        final Duration configured = this.config.getInterval();
        if (configured == null || configured.isZero() || configured.isNegative()) {
            return "No reload is scheduled and no inventory.reload.stale gauge is registered, so the "
                    + "exporters stay missing until a restart: set riptide.discovery.interval to a "
                    + "positive duration to have the endpoint retried.";
        }
        return "A reload retries every %s and publishes them when it succeeds; inventory.reload.stale "
                .formatted(configured) + "reads 1 until then.";
    }

    /**
     * Whether the last {@link #bootText()} served the file without the endpoint. The inventory
     * watcher reads it at start to latch its staleness gauge and to skip seeding its hashes, which
     * would otherwise record a later successful fetch as already committed and never publish it.
     */
    public boolean degradedAtBoot() {
        return this.degradedAtBoot;
    }

    private String compose(final byte[] json) {
        final List<TargetGroup> groups = ServiceDiscoveryParser.parse(json, this.describe.get());
        final RenderedExporters rendered =
                ExporterRenderer.render(groups, this.config.getAddressLabels(), this.describe.get());
        this.targets.set(rendered.byName().size());
        this.skipped.set(rendered.skipped());
        return merge(rendered);
    }

    @Override
    public String name() {
        return "%s + %s".formatted(this.file.name(), this.describe.get());
    }

    /**
     * How often the watcher re-composes this document, which is {@code riptide.discovery.interval}
     * and never {@code riptide.config.reload-interval}: enabling discovery must not also require
     * enabling config hot-reload. Read from here rather than from a second injection of
     * {@link DiscoveryConfig} into the watcher, so the interval and the source it paces cannot come
     * from two different places.
     */
    public Duration interval() {
        return this.config.getInterval();
    }

    /**
     * One fetch for the watch loop, with two absences and one failure mode.
     *
     * <p><b>A 404 from the endpoint</b> is absence: the last good inventory keeps serving, the
     * trigger warns once, and {@link #endpointAbsent()} latches staleness in discovery's own owner.
     * Every other endpoint failure — a 500, a timeout, a refused connection, a body past the
     * ceiling, an answer that will not compose — is a throw the trigger counts.</p>
     *
     * <p><b>A missing inventory file</b> is absence too, which is exactly what it already is with
     * discovery off: the file reloader's trigger skips the cycle, warns once and keeps serving.
     * {@code FileInventoryDocument.text()} wraps the read's failure in an
     * {@link IllegalStateException}, so the cause decides, and it decides the same way
     * {@code ClassificationRulesSource.fetch()} does — a file that is <em>not there</em> is absence,
     * a file that is <em>there and unreadable</em> is a failure. That is a type distinction here:
     * {@link NoSuchFileException} is the first, and {@code AccessDeniedException} (a permission
     * denial) is the second, even though both are a {@code FileSystemException}. Matching
     * {@code FileNotFoundException} alone left the missing file rethrown, counted in
     * {@code inventory.reload.failures} and logged with a stack trace on every poll forever — and an
     * operator hits it both by deleting the file and through the {@code rm}+{@code mv} replacement
     * this project's own messages recommend, which has a real window where the read sees the gap.</p>
     *
     * <p>Neither absence is ever {@code Vanished}: no remote source can tell an atomic replacement
     * from a deletion, and the file half is read through the same composed document.</p>
     */
    @Override
    public FileWatchTrigger.Fetch fetch() throws IOException {
        try {
            final FileWatchTrigger.Fetch.Present present =
                    new FileWatchTrigger.Fetch.Present(text().getBytes(StandardCharsets.UTF_8));
            this.endpointAbsent = false;
            return present;
        } catch (final IllegalStateException e) {
            if (e.getCause() instanceof FileNotFoundException) {
                // a 404: the endpoint is not there, which is not the same as a broken one. Never
                // Vanished: no remote source can tell an atomic replacement from a deletion
                this.endpointAbsent = true;
                return new FileWatchTrigger.Fetch.Absent();
            }
            if (e.getCause() instanceof NoSuchFileException) {
                // the inventory file is gone, or is mid-replacement. endpointAbsent is NOT set: it
                // is the endpoint's own flag, and latching inventory.reload.stale for a missing file
                // is precisely what the discovery-off path does not do — an operator who deleted a
                // file knows they did
                return new FileWatchTrigger.Fetch.Absent();
            }
            throw e;
        }
    }

    /**
     * Whether the last {@link #fetch()} answered 404. Absence is not failure, so the trigger skips
     * the cycle without counting it and without touching its gauges — which left
     * {@code inventory.reload.stale} reading 0 while discovery had silently stopped, for example
     * after a NetBox plugin path change. {@code InventoryFileReloader} reads this in its own idle
     * hook and latches its staleness there: the trigger is shared with the classification rule
     * reloader and the plain file reloader, and changing what {@code Absent} means inside it would
     * change two features that have nothing to do with discovery.
     *
     * <p>Cleared by the next fetch that produces a document, which is what lets the gauge return to
     * 0 when the endpoint recovers with content that has not changed: the trigger recomputes
     * staleness from the hashes on that cycle, and this no longer overrides it. A counted failure
     * needs no flag here, because the trigger latches staleness for those itself.</p>
     */
    public boolean endpointAbsent() {
        return this.endpointAbsent;
    }

    @Override
    public String describe() {
        return name();
    }

    /**
     * The file's trees plus the rendered {@code riptide.exporters} tree, or the file's trees alone
     * when {@code rendered} is null (boot could not reach the endpoint).
     */
    private String merge(final RenderedExporters rendered) {
        final String fileText = this.file.text();
        // null is an unset file, the valid empty inventory; blank content the loader reads as
        // an empty root itself, so it needs no case of its own here
        final InventoryLoader.TopLevels levels = fileText == null
                ? new InventoryLoader.TopLevels(Map.of(), Map.of())
                : InventoryLoader.readTopLevels(fileText, this.file.name());
        final Map<String, Object> root = new LinkedHashMap<>(levels.root());
        final Map<String, Object> riptide = new LinkedHashMap<>(levels.riptide());
        if (riptide.containsKey("exporters")) {
            throw new IllegalStateException(
                    ("%s declares an 'exporters' tree while riptide.discovery.url is set. Discovery owns "
                            + "the exporters tree and the inventory file owns snmp.agents, so an entry can "
                            + "never have two possible sources. Remove the exporters tree from the file, or "
                            + "unset riptide.discovery.url.").formatted(this.file.name()));
        }
        // `riptide: {}` declares BOTH trees deliberately empty, which is what lets the guard pass a
        // decommission instead of refusing it as a half-written file. Inserting exporters below
        // makes that map non-empty, so the declaration would be gone before the loader ever read
        // it, and an operator emptying a twelve-range fleet would get every poll refused. Measured
        // here, before the insert, because afterwards the evidence is destroyed. The test is
        // ComposedInventoryDocumentTest.aBroadlyDeclaredEmptyTreeStaysDeclaredThroughComposition.
        //
        // Present-as-a-mapping, not merely present: a file's bare `riptide:` is null, which is not
        // a declaration today and must not become one.
        final boolean fileDeclaredBothTreesEmpty =
                root.get("riptide") instanceof Map<?, ?> declared && declared.isEmpty();
        if (rendered != null) {
            if (fileDeclaredBothTreesEmpty) {
                // the same statement in the narrow spelling, which survives composition because
                // nothing here looks inside `snmp`. A translation between two forms the loader
                // already treats as equivalent, so the rule about what counts as a declaration
                // stays in InventoryLoader alone and discovery-off keeps deciding it the one way.
                // The exporters half of the file's declaration is necessarily dropped: with
                // discovery on the file does not own that tree, and what it still owns is agents
                riptide.put("snmp", new LinkedHashMap<>(Map.of("agents", new LinkedHashMap<>())));
            }
            final Map<String, Object> exporters = new LinkedHashMap<>();
            rendered.byName().forEach((name, address) -> exporters.put(name, Map.of("address", address)));
            riptide.put("exporters", exporters);
        }
        // a riptide tree the file did not write is added only to carry exporters: an empty one
        // would read as `riptide: {}`, which declares both trees deliberately empty. A file's
        // bare `riptide:` stays null for the same reason
        if (!riptide.isEmpty() || root.get("riptide") != null) {
            root.put("riptide", riptide);
        }
        return new Yaml(dumperOptions()).dump(root);
    }

    /** Block style so the dumped document reads like one an operator wrote, and parses the same. */
    private static DumperOptions dumperOptions() {
        final DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return options;
    }
}
