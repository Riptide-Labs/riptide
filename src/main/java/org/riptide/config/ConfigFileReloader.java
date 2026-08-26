/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.riptide.inventory.Inventory;
import org.riptide.inventory.InventoryConfig;
import org.riptide.inventory.InventorySnapshot;
import org.riptide.inventory.InventoryMisplacementCheck;
import org.riptide.inventory.PollKeyMigrationCheck;
import org.riptide.inventory.SnmpProfilesConfig;
import org.riptide.node.LegacyNodesFlagDayCheck;
import org.riptide.routing.RoutingConfig;
import org.riptide.secrets.SopsSecretResolver;
import org.riptide.snmp.InterfaceSnapshotPoller;
import org.riptide.utils.PropertyNames;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.GenericConverter;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in hot-reload of the external config file ({@code spring.config.import}):
 * node and routing changes apply without a restart.
 *
 * <p><b>Trigger</b>: an mtime-independent content-hash poll — the path is re-resolved
 * every cycle, so docker bind mounts and Kubernetes ConfigMap symlink swaps are seen
 * where a {@code WatchService} would miss them. A missing file skips the cycle (an
 * atomic {@code rm}+{@code mv} replacement is indistinguishable from deletion; never
 * commit on absence).</p>
 *
 * <p><b>Layering fidelity by construction</b>: candidates are bound from a copy of the
 * live property-source stack with exactly the file layer swapped — environment-variable
 * overrides keep their boot-time precedence because the candidate bind runs the same
 * binder over the same sources. A file created after boot is inserted at the
 * imported-file slot: above classpath defaults, below environment variables.</p>
 *
 * <p><b>Failure semantics</b>: startup's validation rules run against the candidate;
 * a failing reload keeps serving the old config, warns naming the problem, and counts
 * the failure with a staleness gauge. Commits are atomic snapshot swaps and also
 * refresh the SNMP interface cache and the SOPS decrypted-file cache. The exporter
 * option table is deliberately untouched — exporter facts, not node config.</p>
 */
@Slf4j
@Component
public class ConfigFileReloader {

    // matches Boot's "Config resource 'class path resource [application.properties]' …"
    static final String CLASSPATH_CONFIG_SOURCE_MARKER = "application.properties";

    private final ConfigurableEnvironment environment;
    private final ConfigReloadProperties properties;
    private final Inventory inventory;
    private final InventoryConfig inventoryConfig;
    private final RoutingConfig routingConfig;
    private final InterfaceSnapshotPoller interfacePoller;
    private final SopsSecretResolver sopsSecretResolver;

    /** The gate prefix, not an exact key: multi-profile lists flatten to on-profile[0]/[1]
     * and on-cloud-platform is not a profile at all (#537). */
    private static final String ACTIVATE_PREFIX = "spring.config.activate.";
    /** Bare key or indexed, since a multi-import list flattens to import[0]/[1]. */
    private static final String IMPORT_KEY = "spring.config.import";

    private final MetricRegistry metrics;
    private final Counter reloadSuccesses;
    private final Counter reloadFailures;
    /** Config committed but the inventory rebuild did not publish: the edit is only partly serving. */
    private final Counter reloadPartial;

    /**
     * The profiles a committed config edit could not carry into the inventory, retried on
     * every poll until they publish. Non-null is the partial state: the config half is
     * serving, the inventory half is not, and the stale gauge must say so for as long as
     * that holds — the first version of this fix latched a boolean that the very next
     * unchanged-content poll recomputed away, so the gauge showed 1 for at most one
     * interval while the rotation stayed stranded.
     */
    private volatile SnmpProfilesConfig pendingProfiles;
    /** Last pending-retry failure message; retries repeat quietly, a changed cause WARNs. */
    private String lastPendingRetryFailure;
    private volatile boolean stale = false;

    /** Boot's binding conversion, reproduced: defaults plus the context's binding converters. */
    private final ConversionService conversionService;

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> polling;
    private Path location;
    private byte[] lastAttemptedHash = new byte[0];
    private byte[] lastCommittedHash = new byte[0];
    private boolean warnedMissing = false;

    public ConfigFileReloader(final ConfigurableEnvironment environment,
                              final ConfigReloadProperties properties,
                              final RoutingConfig routingConfig,
                              final InterfaceSnapshotPoller interfacePoller,
                              final SopsSecretResolver sopsSecretResolver,
                              final Inventory inventory,
                              final InventoryConfig inventoryConfig,
                              final MetricRegistry metrics,
                              @Qualifier(ConfigurationPropertiesBinding.VALUE) final List<Converter<?, ?>> bindingConverters,
                              @Qualifier(ConfigurationPropertiesBinding.VALUE) final List<GenericConverter> bindingGenericConverters) {
        this.environment = Objects.requireNonNull(environment);
        this.properties = Objects.requireNonNull(properties);
        this.routingConfig = Objects.requireNonNull(routingConfig);
        this.interfacePoller = Objects.requireNonNull(interfacePoller);
        this.sopsSecretResolver = Objects.requireNonNull(sopsSecretResolver);
        this.inventory = Objects.requireNonNull(inventory);
        this.inventoryConfig = Objects.requireNonNull(inventoryConfig);

        // the converters boot binding applies, injected by their qualifier (the annotation
        // itself does not target parameters, so the parameters name its qualifier VALUE),
        // so a future Converter or GenericConverter joins this path automatically.
        // Formatters carrying the qualifier would not — none exist, and adding one means
        // widening this. A fresh ApplicationConversionService, never the shared instance:
        // the shared one is a JVM-global singleton, and without the context's converters it
        // binds SecretRef through the reflective of(String) fallback, which agrees with
        // SecretRefConverter on every input except blank — where boot reads "no secret" and
        // this reloader threw, permanently failing every reload after a boot that
        // succeeded (#533)
        final ApplicationConversionService reloadConversion = new ApplicationConversionService();
        bindingConverters.forEach(reloadConversion::addConverter);
        bindingGenericConverters.forEach(reloadConversion::addConverter);
        this.conversionService = reloadConversion;

        // counters stay here: a zero counter is true when reloading is disabled. The
        // gauges register from start(), after the disabled early-returns (#539): a gauge
        // registered here published a constant 0 with reloading disabled, which reads as
        // "the file matches what is serving" for a file that is never read again
        this.metrics = metrics;
        this.reloadSuccesses = metrics.counter(MetricRegistry.name("config", "reload", "successes"));
        this.reloadFailures = metrics.counter(MetricRegistry.name("config", "reload", "failures"));
        this.reloadPartial = metrics.counter(MetricRegistry.name("config", "reload", "partial"));
    }

    @PostConstruct
    void start() {
        if (this.properties.getReloadInterval() == null || this.properties.getReloadInterval().isZero()
                || this.properties.getReloadInterval().isNegative()) {
            log.debug("Config hot-reload disabled (no riptide.config.reload-interval)");
            return;
        }
        this.location = resolveLocation();
        if (this.location == null) {
            log.warn("Config hot-reload requested but spring.config.import is not a single file: location — disabled");
            return;
        }
        final long millis = this.properties.getReloadInterval().toMillis();
        this.executor = Executors.newSingleThreadScheduledExecutor(
                runnable -> new Thread(runnable, "ConfigFileReloader"));
        // The handle is kept and cancelled explicitly rather than discarded. poll() swallows every
        // Exception itself, so a bad reload cycle cannot silently cancel the schedule and leave
        // hot-reload dead for the process lifetime — and the dead gauge below is what finally
        // makes that visible: an Error (the realistic one: OOM on an oversized file mid-read)
        // still propagates and cancels the task, deliberately — catching Throwable and marching
        // on would hide a process in real trouble. Fail-visible, not resilience theater
        this.polling = this.executor.scheduleWithFixedDelay(this::poll, millis, millis, TimeUnit.MILLISECONDS);
        registerGauge(MetricRegistry.name("config", "reload", "stale"),
                () -> this.stale || this.pendingProfiles != null ? 1 : 0);
        registerGauge(MetricRegistry.name("config", "reload", "dead"),
                () -> this.polling.isDone() ? 1 : 0);
        log.info("Config hot-reload enabled: watching {} every {}", this.location, this.properties.getReloadInterval());
    }

    /**
     * Shutdown recognition for the rebuild-path catches, where the poll-level belt cannot
     * reach: {@code InventoryLoader.load} wraps an interrupted read's
     * {@link ClosedByInterruptException} into its "not readable" IllegalStateException,
     * so both the flag and the cause chain must be consulted. Untestable
     * deterministically for the same reason as the poll-level belt (the interrupt must
     * land mid-cycle, past the top-of-poll check); verified by inspection.
     */
    private static boolean interruptedShutdown(final Exception e) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ClosedByInterruptException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Remove-then-register, NOT Dropwizard's get-or-create {@code gauge(name, supplier)}:
     * get-or-create would hand a restarted bean (devtools, cached test contexts) the OLD
     * bean's gauge lambda, permanently reading dead fields. Plain register threw instead.
     */
    private void registerGauge(final String name, final Gauge<Integer> gauge) {
        this.metrics.remove(name);
        this.metrics.register(name, gauge);
    }

    @PreDestroy
    void stop() {
        if (this.polling != null) {
            this.polling.cancel(true);
        }
        if (this.executor != null) {
            this.executor.shutdownNow();
        }
    }

    /**
     * Retries carrying a partially applied edit's profiles into the inventory, once per
     * poll while the state persists. This is what heals a rotation whose inventory file
     * was unreadable or mid-write at commit time: the inventory watcher cannot do it,
     * because it re-parses against the SERVING profiles, which a partial edit never
     * updated. Success is logged and clears the partial state; a repeated failure stays
     * quiet (the WARN at commit time named the cause and the gauge holds it visible), a
     * changed cause WARNs once.
     */
    private void retryPendingRebuild() {
        final SnmpProfilesConfig pending = this.pendingProfiles;
        if (pending == null) {
            return;
        }
        final InventorySnapshot published;
        try {
            published = this.inventory.rebuildAndSwap(pending, this.inventoryConfig.getFile());
        } catch (final RuntimeException e) {
            if (interruptedShutdown(e)) {
                // a shutdown artifact must not be remembered as a failure cause: the
                // wrapped message would mismatch the real cause and WARN "now with"
                Thread.currentThread().interrupt();
                log.debug("Pending inventory rebuild interrupted (shutdown)");
                return;
            }
            // quiet on repetition (the commit-time WARN named the cause, the gauge holds
            // it visible), but a CHANGED cause is new information the operator otherwise
            // never sees: the file was edited and now fails differently
            if (!Objects.equals(e.getMessage(), this.lastPendingRetryFailure)) {
                this.lastPendingRetryFailure = e.getMessage();
                log.warn("Pending inventory rebuild still failing, now with: {}", e.getMessage());
            } else {
                log.debug("Pending inventory rebuild still failing: {}", e.getMessage());
            }
            return;
        }
        if (published == null) {
            // still torn or regressive: stays latched, retried next poll, quietly
            return;
        }
        this.lastPendingRetryFailure = null;
        this.pendingProfiles = null;
        // outside the state-clearing block above: the rebuild SUCCEEDED and the gauge just
        // dropped, so a refresh failure must not be logged as "still failing" (the first
        // version caught it that way, at DEBUG, and swallowed the recovery INFO with it)
        try {
            this.interfacePoller.refreshRegistrations();
        } catch (final Exception e) {
            log.warn("Recovered inventory published, but refreshing polled endpoints failed: registrations "
                    + "keep their previous endpoints until their next flow or deregistration", e);
        }
        log.info("Recovered: the credential and profile changes from the last config edit are "
                + "now fully serving ({} credential set(s), {} polling profile(s))",
                pending.credentials().size(), pending.polling().size());
    }

    /** The imported config file, or {@code null} when there is none to watch. */
    private Path resolveLocation() {
        // spring.config.import (not additional-location, which Spring ignores when set
        // inside the packaged application.properties) of the documented shape:
        // optional:file:/etc/riptide/config.yaml
        final String raw = this.environment.getProperty(IMPORT_KEY, "");
        final String stripped = raw.replace("optional:", "").replace("file:", "").trim();
        if (stripped.isEmpty() || stripped.contains(",")) {
            return null;
        }
        return Path.of(stripped);
    }

    // visible for the scheduled task and tests; never throws (a throwing scheduled
    // task would silently cancel the schedule)
    void poll() {
        if (Thread.currentThread().isInterrupted()) {
            // orderly shutdown: polling.cancel(true) interrupts this thread, and a cycle
            // that begins interrupted must not read, count, or latch anything — counting
            // it corrupted the failure counter's meaning for alerting (#539)
            log.debug("Config reload poll skipped: thread interrupted (shutdown)");
            return;
        }
        try {
            if (!Files.isRegularFile(this.location)) {
                if (!this.warnedMissing) {
                    log.warn("Config file {} is missing — skipping reload cycles until it reappears "
                            + "(deletion and atomic replacement are indistinguishable; keeping the running config)", this.location);
                    this.warnedMissing = true;
                }
                // a pending partial heals from the INVENTORY file, which this branch says
                // nothing about — suspending the retry here would falsify the commit-time
                // WARN's "retried every poll" exactly in the degraded states
                retryPendingRebuild();
                return;
            }
            this.warnedMissing = false;

            final byte[] content;
            try {
                content = Files.readAllBytes(this.location);
            } catch (final NoSuchFileException e) {
                // vanished between the check and the read: an atomic rm+mv replacement
                // or a symlink swap, the healthy deploy this class expects
                retryPendingRebuild();
                return;
            }
            if (content.length == 0) {
                // a shell '>' redirect truncates before writing — indistinguishable
                // from an intentionally emptied file; never commit on empty
                log.warn("Config file {} is empty — skipping reload cycle (truncate-write race or intentional; keeping the running config)", this.location);
                retryPendingRebuild();
                return;
            }
            final byte[] hash = MessageDigest.getInstance("SHA-256").digest(content);
            if (MessageDigest.isEqual(hash, this.lastAttemptedHash)) {
                // unchanged, or the same bad content we already warned about; staleness
                // reflects whether the file matches what is running (a transient read
                // failure must not latch the gauge)
                this.stale = !MessageDigest.isEqual(hash, this.lastCommittedHash);
                retryPendingRebuild();
                return;
            }
            this.lastAttemptedHash = hash;

            reload(content);
        } catch (final Exception e) {
            if (e instanceof ClosedByInterruptException || Thread.currentThread().isInterrupted()) {
                // the belt for an interrupt DELIVERED mid-read (the check above catches
                // one already pending): same shutdown, same silence. Untestable
                // deterministically — a pre-set flag does not fault the read on this
                // JDK — kept for the delivered-interrupt race and verified by inspection
                Thread.currentThread().interrupt();
                log.debug("Config reload poll interrupted mid-cycle (shutdown): {}", e.getMessage());
                return;
            }
            this.reloadFailures.inc();
            this.stale = true;
            log.warn("Config reload failed — keeping the running configuration: {}", e.getMessage(), e);
            // a rejected CANDIDATE neither supersedes nor retries the pending edit (the
            // supersede sits after validation), so without this a continuously churning
            // broken config file would starve the retry while both WARNs promise it
            retryPendingRebuild();
        }
    }

    private void reload(final byte[] content) throws Exception {
        final List<PropertySource<?>> fresh = new YamlPropertySourceLoader()
                .load(this.location.toString(), new ByteArrayResource(content, this.location.toString()));
        if (fresh.isEmpty()) {
            throw new IllegalStateException("file parsed to no property sources — keeping the running config");
        }
        // partitioned in one pass by identity, not name-equality: PropertySource.equals is
        // name-based, and the gated/active split must not depend on Spring's internal
        // document-naming scheme staying collision-free
        final List<PropertySource<?>> applicable = new java.util.ArrayList<>();
        final List<PropertySource<?>> gated = new java.util.ArrayList<>();
        for (final PropertySource<?> document : fresh) {
            (withoutProfileActivation(document) ? applicable : gated).add(document);
        }

        // gated documents commit nothing and fail nothing (pinned posture: dormant
        // configuration is not wrong, it is dormant) — but dormant-and-fatal deserves a
        // voice, because activating that gate later converts a working deployment into
        // a startup failure, and this reload is the only moment anything reads the
        // document before that boot (#537). BEFORE the all-gated rejection below: a file
        // staged entirely for a future profile is exactly the shape most likely to be
        // all landmine, and rejecting it silently would defeat the warning's purpose
        warnAboutGatedLandmines(gated);
        // and where the reload knowingly cannot check — imports are boot-only — it says so
        warnAboutNestedImports(fresh);

        if (applicable.isEmpty()) {
            throw new IllegalStateException("all documents are profile-gated — profile activation is boot-only");
        }

        // keys this release does not read fail the candidate like they fail boot — scanned over the
        // applicable documents only, because profile-gated documents are never installed on reload.
        // One call rather than three: a candidate carrying keys in several categories reported one
        // and stopped, so the operator edited and resubmitted once per category (#562)
        ObsoleteKeys.failOnObsoleteKeys(applicable);

        // fidelity by construction: the candidate stack is the live stack with exactly
        // the file layer swapped — env overrides keep their boot-time precedence.
        // Boot lets LATER YAML documents override earlier ones; property-source order
        // is highest-first, so the documents install reversed.
        final List<PropertySource<?>> ordered = applicable.reversed();
        final MutablePropertySources candidate = substituted(this.environment.getPropertySources(), ordered);

        final Binder binder = new Binder(
                ConfigurationPropertySources.from(candidate),
                new PropertySourcesPlaceholdersResolver(candidate),
                this.conversionService);
        final RoutingConfig routing = binder
                .bind("riptide.routing", Bindable.of(RoutingConfig.class))
                .orElseGet(RoutingConfig::new);

        // validate the candidate with startup's rules; throws → keep-old in poll().
        // Credential and polling profiles live here, and agent ranges resolve them into
        // objects when the inventory is built (AD-5), so a rotated community only reaches
        // a walk if the inventory is rebuilt against the new profiles. Both are built as
        // candidates before anything is committed, so a rotation that breaks a reference
        // fails THIS reload instead of half-applying.
        final SnmpProfilesConfig candidateProfiles = binder
                .bind("riptide.snmp", Bindable.of(SnmpProfilesConfig.class))
                .orElseGet(() -> new SnmpProfilesConfig(Map.of(), Map.of()));
        // the file the candidate config names, not the one bound at boot: an operator who
        // adds riptide.inventory.file in the same edit would otherwise see it ignored
        final InventoryConfig candidateInventoryConfig = binder
                .bind("riptide.inventory", Bindable.of(InventoryConfig.class))
                .orElseGet(InventoryConfig::new);
        if (!Objects.equals(candidateInventoryConfig.getFile(), this.inventoryConfig.getFile())) {
            // the inventory watcher captured its path at start, so following a new one here
            // would leave the two reloaders publishing different files at each other
            log.warn("riptide.inventory.file changed from {} to {}: the running inventory keeps the old "
                    + "path until a restart", this.inventoryConfig.getFile(), candidateInventoryConfig.getFile());
        }
        final RoutingConfig.Parsed parsedRouting = RoutingConfig.parse(routing.getPrefixes(), routing.getAsNames());

        // commit: live environment stays truthful, snapshots swap atomically, caches refresh
        substitute(this.environment.getPropertySources(), ordered);
        this.lastCommittedHash = this.lastAttemptedHash;
        this.routingConfig.swap(parsedRouting);
        // swap, then refresh (AD-6): profiles and the inventory built from them move
        // together, and the poller re-resolves what it is already walking, which is what
        // makes a credential rotation reach an agent without a restart
        // secrets first: a refresh can make a walk due immediately, and it must not read
        // a value the rotation just replaced
        this.sopsSecretResolver.invalidateCache();
        // rebuilt and published in one monitor-held read, so nothing can change between
        // validating the candidate and committing it, and guarded because the config is
        // already serving by now: a throw here must not be reported as a failed reload
        boolean inventoryPublished = false;
        // a new edit supersedes whatever an earlier partial left pending — but the
        // supersede is assigned at the OUTCOME points below, not cleared here: clearing
        // before the rebuild outcome is known let a metrics scrape during the file read
        // see stale=0 with a rotation still unserved. Scrape-only: poll() is
        // single-threaded, so the retry can never interleave mid-reload. The last-failure
        // memory dies with its episode at the same points — left alone, a LATER pending
        // episode whose retry throws the same cause would be DEBUG'd as a repetition of a
        // message this episode never showed the operator
        try {
            final InventorySnapshot published =
                    this.inventory.rebuildAndSwap(candidateProfiles, this.inventoryConfig.getFile());
            if (published == null) {
                // a new episode with no named cause: the failure memory resets so the
                // FIRST throwing retry of this episode WARNs rather than matching a
                // long-dead episode's message
                this.lastPendingRetryFailure = null;
                // pre-formatted for the same reason as the inventory watcher's refusal WARN:
                // the taught idiom "agents: {}" is an SLF4J placeholder unless the whole
                // message bypasses SLF4J formatting
                log.warn(("Config reloaded, but the inventory was left alone: rebuilding it from %s would "
                        + "have dropped a whole tree that is currently serving (a partially written file "
                        + "reads this way; write atomically via mv, or declare a deliberate decommission "
                        + "as an explicit empty mapping, e.g. agents: {}). The credential and profile "
                        + "changes in this edit are NOT serving until the file is whole; they are retried "
                        + "every poll").formatted(this.inventoryConfig.getFile()));
            } else {
                inventoryPublished = true;
                // the supersede, at the earliest true point: the edit is fully published,
                // and a scrape during the refresh below must not read stale=1 off a
                // pending this publish just replaced
                this.pendingProfiles = null;
                this.lastPendingRetryFailure = null;
            }
        } catch (final RuntimeException e) {
            if (interruptedShutdown(e)) {
                // the poll-level belt structurally cannot see this one: the loader wraps
                // the interrupted read's ClosedByInterruptException into its "not
                // readable" IllegalStateException, so shutdown must be recognized HERE —
                // otherwise an orderly stop mid-rebuild WARNed "NOT serving", counted a
                // partial, and parked a pending edit off a shutdown artifact
                Thread.currentThread().interrupt();
                log.debug("Config reload interrupted during the inventory rebuild (shutdown): {}", e.getMessage());
                return;
            }
            // this lands in the same latched-and-retried state as the null return above,
            // so the two messages must prescribe the same remediation. The first version
            // said "edit the config again, or restart" here — a needless restart for a
            // state the next poll heals once the inventory file is fixed
            this.lastPendingRetryFailure = e.getMessage();
            log.warn("Config reloaded, but the inventory could not be rebuilt from {} ({}). The credential "
                    + "and profile changes in this edit are NOT serving until the inventory file is fixed; "
                    + "they are retried every poll", this.inventoryConfig.getFile(), e.getMessage());
        }
        if (inventoryPublished) {
            // outside the rebuild try: the snapshot IS serving by now, so a refresh failure
            // must not be reported as a failed rebuild ("NOT serving" would be false on
            // both clauses). Same guard and wording as the inventory watcher's
            try {
                this.interfacePoller.refreshRegistrations();
            } catch (final Exception e) {
                log.warn("Config reloaded and the inventory published, but refreshing polled endpoints "
                        + "failed: registrations keep their previous endpoints until their next flow or "
                        + "deregistration", e);
            }
        }
        // no invalidateAll() here any more: it reset the whole fleet's next-walk time, so
        // an unrelated edit (a routing prefix, a receiver port) walked every exporter at
        // once, against the very spreading the poller exists to do. The refresh above
        // touches exactly the registrations whose endpoint actually changed
        this.reloadSuccesses.inc();
        // the stale contract: 0 means "what is serving matches the files". A partial reload
        // (config committed, inventory rebuild unpublished) used to clear it anyway, so an
        // operator whose credential rotation never reached a walk saw a success counter, a
        // clean gauge and a reloaded log line (#534). Partial keeps it latched, counted on
        // its own meter; the remediation is the one the WARN above names
        if (inventoryPublished) {
            this.stale = false;
        } else {
            // counted once per edit, not once per retry; the pending profiles keep the
            // stale gauge at 1 until a retry or a newer edit publishes. Note partial is a
            // subset of successes: the config half committed and successes counts that
            this.reloadPartial.inc();
            this.pendingProfiles = candidateProfiles;
        }
        // the SERVING counts, read back from the inventory, not the candidate's. Both paths
        // above can leave the candidate profiles unpublished while this line still runs, and
        // quoting the candidate would report credential sets that never reached a walk right
        // underneath the warning saying they did not
        final SnmpProfilesConfig serving = this.inventory.profiles();
        log.info("Config reloaded from {}: {} credential set(s), {} polling profile(s) serving",
                this.location, serving.credentials().size(), serving.polling().size());
    }

    /**
     * Scans the documents the reload deliberately ignores for keys that fail a boot.
     * WARN, never reject: rejecting would punish an edit that changes nothing about what
     * is serving. Repetition is bounded by the content-hash latch — one warning per
     * content version. Scope is the three fatal startup checks only: a malformed gated
     * credential set fails its future boot with a good, named error, which is the system
     * working; these are the keys that refuse to boot at all.
     */
    private void warnAboutGatedLandmines(final List<PropertySource<?>> gated) {
        for (final PropertySource<?> document : gated) {
            final List<PropertySource<?>> one = List.of(document);
            LegacyNodesFlagDayCheck.findLegacyNodesKey(one)
                    .ifPresent(key -> warnLandmine(document, key));
            PollKeyMigrationCheck.findRetiredPollKey(one)
                    .ifPresent(key -> warnLandmine(document, key));
            InventoryMisplacementCheck.findMisplacedInventoryKey(one)
                    .ifPresent(key -> warnLandmine(document, key));
        }
    }

    private void warnLandmine(final PropertySource<?> document, final String key) {
        log.warn("A gated document in {} ({}) carries '{}', which fails any boot where that "
                + "activation matches. Reloads never install gated documents, so this warning is "
                + "the only signal before that boot refuses to come up",
                this.location, describeGate(document), key);
    }

    /**
     * Every activation condition the document carries, verbatim. The first version read the
     * single exact key {@code spring.config.activate.on-profile} — and printed
     * {@code profile 'null'} for exactly the shapes the gate accepts, because a
     * multi-profile list flattens to {@code on-profile[0]}/{@code [1]} and
     * {@code on-cloud-platform} is not a profile at all. The gate matches on the prefix, so
     * the description enumerates the same prefix.
     */
    private static String describeGate(final PropertySource<?> document) {
        // the document stays in scope: this reads values as well as names, so the
        // per-source overload is the right one — a flattened stack loses the owner
        final String gate = PropertyNames.in(document)
                .filter(name -> name.startsWith(ACTIVATE_PREFIX))
                .map(name -> name.substring(ACTIVATE_PREFIX.length()) + "=" + document.getProperty(name))
                .collect(java.util.stream.Collectors.joining(", "));
        // one fallback for both "cannot enumerate" and "enumerable but carries no gate
        // key": they always produced the same word, and collapsing them here keeps it
        return gate.isEmpty() ? "gated" : "gated on " + gate;
    }

    /**
     * A {@code spring.config.import} inside the watched file is a boot-only mechanism:
     * Spring's ConfigData follows imports-from-imports, this reloader substitutes only the
     * watched file's own documents. A reload that introduces one commits a half-view — for
     * any key behind the import, not just the fatal ones — so it is named, mirroring the
     * riptide.inventory.file-changed precedent above. Not rejected: import sources that
     * existed at boot remain in the live stack during substitution, so nothing regresses;
     * only the new import's contents are invisible until the next restart.
     */
    private void warnAboutNestedImports(final List<PropertySource<?>> fresh) {
        // all of them, not the first: a multi-import list flattens to import[0]/[1] and a
        // warning naming one file while omitting another would read as complete
        // per document, not over the flattened stack: the value belongs to the document
        // that declared it, and getProperty needs that owner
        final List<Object> imports = new java.util.ArrayList<>();
        for (final PropertySource<?> document : fresh) {
            PropertyNames.in(document)
                    .filter(name -> name.equals(IMPORT_KEY) || name.startsWith(IMPORT_KEY + "["))
                    .map(document::getProperty)
                    .forEach(imports::add);
        }
        if (!imports.isEmpty()) {
            // outcome-neutral wording: this fires before the candidate is accepted or
            // rejected, so it must be true either way. Fires once per content version —
            // including for an import present since boot — which is the reminder working,
            // not a defect: the guidance is to keep hot-reloaded config in the watched file
            log.warn("{} names nested spring.config.import(s) {}: imports are boot-only, so reloads "
                    + "never include those files' contents; only a restart reads them. Keep "
                    + "hot-reloaded configuration in the watched file itself",
                    this.location, imports);
        }
    }

    /** Profile-gated documents are a boot-only ConfigData feature; reload skips them loudly. */
    private boolean withoutProfileActivation(final PropertySource<?> document) {
        // anyMatch short-circuits like the old early return; the WARN stays here because
        // it names the document, which the shared walk knows nothing about
        if (PropertyNames.in(document).anyMatch(name -> name.startsWith(ACTIVATE_PREFIX))) {
            log.warn("Config reload skips profile-gated document '{}' — profile activation applies at boot only", document.getName());
            return false;
        }
        return true;
    }

    /** A copy of {@code live} with the file layer swapped (or inserted); {@code live} untouched. */
    private MutablePropertySources substituted(final MutablePropertySources live, final List<PropertySource<?>> fresh) {
        final MutablePropertySources copy = new MutablePropertySources();
        for (final PropertySource<?> source : live) {
            copy.addLast(source);
        }
        substitute(copy, fresh);
        return copy;
    }

    /** Swaps the file layer in place, or inserts it at the imported-file precedence. */
    private void substitute(final MutablePropertySources sources, final List<PropertySource<?>> fresh) {
        final String fileMarker = this.location.toString();
        String anchor = null;
        for (final PropertySource<?> source : sources) {
            if (source.getName().contains(fileMarker)) {
                anchor = source.getName();
                break;
            }
        }

        if (anchor != null) {
            sources.replace(anchor, fresh.getFirst());
            // drop any further boot-time documents of the same file (multi-doc YAML)
            // so stale layers can't linger behind the refreshed ones
            for (final String name : sources.stream().map(PropertySource::getName).toList()) {
                if (name.contains(fileMarker) && !name.equals(fresh.getFirst().getName())) {
                    sources.remove(name);
                }
            }
        } else {
            // file was absent at boot (optional:): insert where the imported file
            // sits — above classpath defaults, below environment variables
            String classpathSource = null;
            for (final PropertySource<?> source : sources) {
                if (source.getName().contains(CLASSPATH_CONFIG_SOURCE_MARKER)) {
                    classpathSource = source.getName();
                    break;
                }
            }
            if (classpathSource != null) {
                sources.addBefore(classpathSource, fresh.getFirst());
            } else {
                sources.addLast(fresh.getFirst());
            }
        }

        // multi-document YAML: keep the remaining documents in order behind the first
        PropertySource<?> previous = fresh.getFirst();
        for (final PropertySource<?> document : fresh.subList(1, fresh.size())) {
            sources.addAfter(previous.getName(), document);
            previous = document;
        }
    }
}
