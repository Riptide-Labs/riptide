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
import org.riptide.node.NodeDefinition;
import org.riptide.node.LegacyNodesInertCheck;
import org.riptide.node.NodeRegistry;
import org.riptide.inventory.Inventory;
import org.riptide.inventory.InventoryConfig;
import org.riptide.inventory.InventorySnapshot;
import org.riptide.inventory.InventoryMisplacementCheck;
import org.riptide.inventory.PollKeyMigrationCheck;
import org.riptide.inventory.SnmpProfilesConfig;
import org.riptide.node.NodesConfigMigrationCheck;
import org.riptide.routing.RoutingConfig;
import org.riptide.secrets.SopsSecretResolver;
import org.riptide.snmp.InterfaceSnapshotPoller;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

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
    private final NodeRegistry nodeRegistry;
    private final Inventory inventory;
    private final InventoryConfig inventoryConfig;
    private final RoutingConfig routingConfig;
    private final InterfaceSnapshotPoller interfacePoller;
    private final SopsSecretResolver sopsSecretResolver;

    private final Counter reloadSuccesses;
    private final Counter reloadFailures;
    private volatile boolean stale = false;

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> polling;
    private Path location;
    private byte[] lastAttemptedHash = new byte[0];
    private byte[] lastCommittedHash = new byte[0];
    private boolean warnedMissing = false;

    public ConfigFileReloader(final ConfigurableEnvironment environment,
                              final ConfigReloadProperties properties,
                              final NodeRegistry nodeRegistry,
                              final RoutingConfig routingConfig,
                              final InterfaceSnapshotPoller interfacePoller,
                              final SopsSecretResolver sopsSecretResolver,
                              final Inventory inventory,
                              final InventoryConfig inventoryConfig,
                              final MetricRegistry metrics) {
        this.environment = Objects.requireNonNull(environment);
        this.properties = Objects.requireNonNull(properties);
        this.nodeRegistry = Objects.requireNonNull(nodeRegistry);
        this.routingConfig = Objects.requireNonNull(routingConfig);
        this.interfacePoller = Objects.requireNonNull(interfacePoller);
        this.sopsSecretResolver = Objects.requireNonNull(sopsSecretResolver);
        this.inventory = Objects.requireNonNull(inventory);
        this.inventoryConfig = Objects.requireNonNull(inventoryConfig);

        this.reloadSuccesses = metrics.counter(MetricRegistry.name("config", "reload", "successes"));
        this.reloadFailures = metrics.counter(MetricRegistry.name("config", "reload", "failures"));
        metrics.register(MetricRegistry.name("config", "reload", "stale"), (Gauge<Integer>) () -> this.stale ? 1 : 0);
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
        // hot-reload dead for the process lifetime — which is the failure this return value exists
        // to make visible.
        this.polling = this.executor.scheduleWithFixedDelay(this::poll, millis, millis, TimeUnit.MILLISECONDS);
        log.info("Config hot-reload enabled: watching {} every {}", this.location, this.properties.getReloadInterval());
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

    /** The imported config file, or {@code null} when there is none to watch. */
    private Path resolveLocation() {
        // spring.config.import (not additional-location, which Spring ignores when set
        // inside the packaged application.properties) of the documented shape:
        // optional:file:/etc/riptide/config.yaml
        final String raw = this.environment.getProperty("spring.config.import", "");
        final String stripped = raw.replace("optional:", "").replace("file:", "").trim();
        if (stripped.isEmpty() || stripped.contains(",")) {
            return null;
        }
        return Path.of(stripped);
    }

    // visible for the scheduled task and tests; never throws (a throwing scheduled
    // task would silently cancel the schedule)
    void poll() {
        try {
            if (!Files.isRegularFile(this.location)) {
                if (!this.warnedMissing) {
                    log.warn("Config file {} is missing — skipping reload cycles until it reappears "
                            + "(deletion and atomic replacement are indistinguishable; keeping the running config)", this.location);
                    this.warnedMissing = true;
                }
                return;
            }
            this.warnedMissing = false;

            final byte[] content;
            try {
                content = Files.readAllBytes(this.location);
            } catch (final NoSuchFileException e) {
                // vanished between the check and the read: an atomic rm+mv replacement
                // or a symlink swap, the healthy deploy this class expects
                return;
            }
            if (content.length == 0) {
                // a shell '>' redirect truncates before writing — indistinguishable
                // from an intentionally emptied file; never commit on empty
                log.warn("Config file {} is empty — skipping reload cycle (truncate-write race or intentional; keeping the running config)", this.location);
                return;
            }
            final byte[] hash = MessageDigest.getInstance("SHA-256").digest(content);
            if (MessageDigest.isEqual(hash, this.lastAttemptedHash)) {
                // unchanged, or the same bad content we already warned about; staleness
                // reflects whether the file matches what is running (a transient read
                // failure must not latch the gauge)
                this.stale = !MessageDigest.isEqual(hash, this.lastCommittedHash);
                return;
            }
            this.lastAttemptedHash = hash;

            reload(content);
        } catch (final Exception e) {
            this.reloadFailures.inc();
            this.stale = true;
            log.warn("Config reload failed — keeping the running configuration: {}", e.getMessage(), e);
        }
    }

    private void reload(final byte[] content) throws Exception {
        final List<PropertySource<?>> fresh = new YamlPropertySourceLoader()
                .load(this.location.toString(), new ByteArrayResource(content, this.location.toString()));
        if (fresh.isEmpty()) {
            throw new IllegalStateException("file parsed to no property sources — keeping the running config");
        }
        final List<PropertySource<?>> applicable = fresh.stream()
                .filter(this::withoutProfileActivation)
                .toList();
        if (applicable.isEmpty()) {
            throw new IllegalStateException("all documents are profile-gated — profile activation is boot-only");
        }

        // legacy indexed keys and retired poll keys fail the candidate like they
        // fail boot — scanned over the applicable documents only, because
        // profile-gated documents are never installed on reload
        NodesConfigMigrationCheck.failOnLegacyIndexedNodes(applicable);
        PollKeyMigrationCheck.failOnRetiredPollKeys(applicable);
        // startup rejects an inventory tree in the main config; accepting it here and
        // silently ignoring it would break this class's own stated contract
        InventoryMisplacementCheck.failOnMisplacedInventoryTrees(applicable);

        // fidelity by construction: the candidate stack is the live stack with exactly
        // the file layer swapped — env overrides keep their boot-time precedence.
        // Boot lets LATER YAML documents override earlier ones; property-source order
        // is highest-first, so the documents install reversed.
        final List<PropertySource<?>> ordered = applicable.reversed();
        final MutablePropertySources candidate = substituted(this.environment.getPropertySources(), ordered);

        final Binder binder = new Binder(
                ConfigurationPropertySources.from(candidate),
                new PropertySourcesPlaceholdersResolver(candidate),
                ApplicationConversionService.getSharedInstance());
        final Map<String, NodeDefinition> nodes = binder
                .bind("riptide.nodes", Bindable.mapOf(String.class, NodeDefinition.class))
                .orElseGet(Map::of);
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
        // best effort, and never destructive: this reload exists to carry a credential
        // rotation into the inventory, so failing to read the file (an atomic rm+mv
        // landing here) or reading one that parses to nothing must leave the running
        // inventory alone rather than take the config edit down with it or blank the fleet
        final Map<String, NodeDefinition> validatedNodes = NodeRegistry.validated(nodes);
        final RoutingConfig.Parsed parsedRouting = RoutingConfig.parse(routing.getPrefixes(), routing.getAsNames());

        // commit: live environment stays truthful, snapshots swap atomically, caches refresh
        substitute(this.environment.getPropertySources(), ordered);
        this.lastCommittedHash = this.lastAttemptedHash;
        this.nodeRegistry.swap(validatedNodes);
        // the tree still binds and still validates, so a successful reload of a grown one
        // would otherwise read as "my configuration is live"
        LegacyNodesInertCheck.warnIfPopulated(validatedNodes.size());
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
        try {
            final InventorySnapshot published =
                    this.inventory.rebuildAndSwap(candidateProfiles, this.inventoryConfig.getFile());
            if (published == null) {
                log.warn("Config reloaded, but the inventory was left alone: rebuilding it from {} produced "
                        + "no entries while a populated one is serving. The credential and profile changes "
                        + "in this edit are NOT serving", this.inventoryConfig.getFile());
            } else {
                this.interfacePoller.refreshRegistrations(published);
            }
        } catch (final RuntimeException e) {
            log.warn("Config reloaded, but the inventory could not be rebuilt from {} ({}). The credential "
                    + "and profile changes in this edit are NOT serving: fix the file and edit the config "
                    + "again, or restart", this.inventoryConfig.getFile(), e.getMessage());
        }
        // no invalidateAll() here any more: it reset the whole fleet's next-walk time, so
        // an unrelated edit (a routing prefix, a receiver port) walked every exporter at
        // once, against the very spreading the poller exists to do. The refresh above
        // touches exactly the registrations whose endpoint actually changed
        this.reloadSuccesses.inc();
        this.stale = false;
        log.info("Config reloaded from {}: {} nodes", this.location, validatedNodes.size());
    }

    /** Profile-gated documents are a boot-only ConfigData feature; reload skips them loudly. */
    private boolean withoutProfileActivation(final PropertySource<?> document) {
        if (document instanceof org.springframework.core.env.EnumerablePropertySource<?> enumerable) {
            for (final String name : enumerable.getPropertyNames()) {
                if (name.startsWith("spring.config.activate.")) {
                    log.warn("Config reload skips profile-gated document '{}' — profile activation applies at boot only", document.getName());
                    return false;
                }
            }
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
