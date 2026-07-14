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
import org.riptide.node.NodeRegistry;
import org.riptide.node.NodesConfigMigrationCheck;
import org.riptide.routing.RoutingConfig;
import org.riptide.secrets.SopsSecretResolver;
import org.riptide.snmp.CachingSnmpService;
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
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in hot-reload of the external config file ({@code spring.config.additional-location}):
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
 * additional-location slot: above classpath defaults, below environment variables.</p>
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
    private final RoutingConfig routingConfig;
    private final CachingSnmpService snmpCache;
    private final SopsSecretResolver sopsSecretResolver;

    private final Counter reloadSuccesses;
    private final Counter reloadFailures;
    private volatile boolean stale = false;

    private ScheduledExecutorService executor;
    private Path location;
    private byte[] lastAttemptedHash = new byte[0];
    private byte[] lastCommittedHash = new byte[0];
    private boolean warnedMissing = false;

    public ConfigFileReloader(final ConfigurableEnvironment environment,
                              final ConfigReloadProperties properties,
                              final NodeRegistry nodeRegistry,
                              final RoutingConfig routingConfig,
                              final CachingSnmpService snmpCache,
                              final SopsSecretResolver sopsSecretResolver,
                              final MetricRegistry metrics) {
        this.environment = Objects.requireNonNull(environment);
        this.properties = Objects.requireNonNull(properties);
        this.nodeRegistry = Objects.requireNonNull(nodeRegistry);
        this.routingConfig = Objects.requireNonNull(routingConfig);
        this.snmpCache = Objects.requireNonNull(snmpCache);
        this.sopsSecretResolver = Objects.requireNonNull(sopsSecretResolver);

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
            log.warn("Config hot-reload requested but spring.config.additional-location is not a single file: location — disabled");
            return;
        }
        final long millis = this.properties.getReloadInterval().toMillis();
        this.executor = Executors.newSingleThreadScheduledExecutor(
                runnable -> new Thread(runnable, "ConfigFileReloader"));
        this.executor.scheduleWithFixedDelay(this::poll, millis, millis, TimeUnit.MILLISECONDS);
        log.info("Config hot-reload enabled: watching {} every {}", this.location, this.properties.getReloadInterval());
    }

    @PreDestroy
    void stop() {
        if (this.executor != null) {
            this.executor.shutdownNow();
        }
    }

    /** The additional-location file, or {@code null} when there is none to watch. */
    private Path resolveLocation() {
        final String raw = this.environment.getProperty("spring.config.additional-location", "");
        // single location of the documented shape: optional:file:/etc/riptide/config.yaml
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

            final byte[] content = Files.readAllBytes(this.location);
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
            log.warn("Config reload failed — keeping the running configuration: {}", e.getMessage());
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

        // legacy indexed keys in the fresh file fail the candidate like they fail boot
        NodesConfigMigrationCheck.failOnLegacyIndexedNodes(fresh);

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

        // validate the candidate with startup's rules; throws → keep-old in poll()
        final Map<String, NodeDefinition> validatedNodes = NodeRegistry.validated(nodes);
        final RoutingConfig.Parsed parsedRouting = RoutingConfig.parse(routing.getPrefixes(), routing.getAsNames());

        // commit: live environment stays truthful, snapshots swap atomically, caches refresh
        substitute(this.environment.getPropertySources(), ordered);
        this.lastCommittedHash = this.lastAttemptedHash;
        this.nodeRegistry.swap(validatedNodes);
        this.routingConfig.swap(parsedRouting);
        this.snmpCache.invalidateAll();
        this.sopsSecretResolver.invalidateCache();

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

    /** Swaps the file layer in place, or inserts it at additional-location precedence. */
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
            // file was absent at boot (optional:): insert where additional-location
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
