/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.session;

import org.riptide.flows.parser.exceptions.MissingTemplateException;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.state.ExporterState;
import org.riptide.flows.parser.state.OptionState;
import org.riptide.flows.parser.state.ParserState;
import org.riptide.flows.parser.state.TemplateState;
import org.riptide.pipeline.ExporterIdentity;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class UdpSessionManager {
    /**
     * Templates indexed by exporter (session + observation domain) first, template id second.
     *
     * <p>The nesting is load-bearing, not cosmetic. This used to be one flat
     * {@code Map<TemplateKey, ...>} for the whole collector, and {@code lookupOptions} found an
     * exporter's templates by scanning every entry and filtering on the session key — an
     * O(total exporters) walk with an {@code InetSocketAddress.equals} per entry, executed on the
     * Netty event-loop thread for every data record. Profiling a 1,500-exporter fleet put that
     * single thread at ~90% of one core with ~52% of its samples in
     * {@code InetSocketAddress.equals} and ~17% in {@code ConcurrentHashMap} iteration, while the
     * parser pool sat at ~4% per thread — the collector was producer-bound on a linear scan whose
     * cost grew with the size of the deployment. Keying by {@link DomainKey} makes both
     * {@code lookupOptions} and {@code removeAllTemplate} touch only the templates of the exporter
     * in hand.
     *
     * <p>Concurrency contract: the inner maps must never be published to a writer while the outer
     * mapping can be dropped underneath it, or a template insert can be silently lost. Every
     * insert therefore mutates the inner map inside an outer {@link ConcurrentMap#compute}, and
     * empty inner maps are reaped only via {@link ConcurrentMap#computeIfPresent} — both hold the
     * bin lock for the key, so an insert and a reap of the same exporter cannot interleave.
     */
    private final ConcurrentMap<DomainKey, ConcurrentMap<Integer, TimeWrapper<TemplateOptions>>> templates =
            new ConcurrentHashMap<>();

    private final Map<TrackerKey, TrackedSequence> sequenceNumbers = new ConcurrentHashMap<>();

    private final Duration timeout;

    private final Supplier<SequenceNumberTracker> sequenceNumberTracker;

    private final OptionListener optionListener;

    public UdpSessionManager(final Duration timeout, final Supplier<SequenceNumberTracker> sequenceNumberTracker) {
        this(timeout, sequenceNumberTracker, OptionListener.NONE);
    }

    public UdpSessionManager(final Duration timeout,
                             final Supplier<SequenceNumberTracker> sequenceNumberTracker,
                             final OptionListener optionListener) {
        this.timeout = timeout;
        this.sequenceNumberTracker = Objects.requireNonNull(sequenceNumberTracker);
        this.optionListener = Objects.requireNonNull(optionListener);
    }

    public void doHousekeeping() {
        final Instant timeout = Instant.now().minus(this.timeout);
        this.templates.forEach((domain, byId) -> byId.entrySet().removeIf(e -> e.getValue().time.isBefore(timeout)));
        this.reapEmptyDomains();
        // sequence trackers have their own lifecycle: templates are re-announced and
        // re-inserted, trackers are touched on every datagram — evict idle ones so
        // churning sources (NAT, roaming agents) don't accumulate trackers forever
        this.sequenceNumbers.entrySet().removeIf(e -> e.getValue().lastSeen.isBefore(timeout));
    }

    /**
     * Drop exporters whose last template just expired. Without this, a churning source (NAT,
     * roaming agents) leaves one empty inner map per address forever — the same unbounded-growth
     * problem the sequence-tracker eviction above exists to avoid.
     *
     * <p>{@code computeIfPresent} rather than {@code entrySet().removeIf}: the latter evaluates the
     * predicate and then removes by key, so a template inserted in between would be dropped with
     * the mapping. {@code computeIfPresent} re-checks emptiness while holding the key's bin lock.
     */
    private void reapEmptyDomains() {
        for (final DomainKey domain : this.templates.keySet()) {
            this.templates.computeIfPresent(domain, (k, byId) -> byId.isEmpty() ? null : byId);
        }
    }

    public Session getSession(final SessionKey sessionKey) {
        return new UdpSession(sessionKey);
    }

    /**
     * Total templates held across all exporters.
     *
     * <p>O(exporters) since the per-exporter indexing. Before that it was {@code templates.size()}
     * on a flat map — a {@link java.util.concurrent.ConcurrentHashMap} counter-cell sum rather than a
     * plain field read, but constant in the number of exporters. It now iterates the outer table and
     * adds one {@link Map#size()} per exporter, and it backs the {@code parsers.<name>.templateCount}
     * gauge, so a metrics scrape walks every exporter. No cost figure is quoted here because none has
     * been measured; the point is only that it is no longer constant-time, so do not call it on the
     * packet path.
     *
     * <p>Not a snapshot: concurrent with {@link #doHousekeeping()} — which expires templates in one
     * pass and reaps the emptied exporters in a second — the sum can include an inner map already
     * detached from the outer one. Same eventual consistency {@link #domainCount()} documents.
     */
    public int count() {
        return this.templates.values().stream().mapToInt(Map::size).sum();
    }

    int sequenceTrackerCount() {
        return this.sequenceNumbers.size();
    }

    /**
     * Exporter (session + observation domain) mappings held. Distinct from {@link #count()}, which
     * counts templates: this is what must return to zero once an exporter's templates expire, or
     * the per-exporter index leaks a mapping per address ever seen.
     *
     * <p>Granularity is one entry per {@code (session, observation domain)} pair, i.e. per exporting
     * process — a single source address announcing two observation domains counts twice.
     *
     * <p>Only eventually consistent with "has at least one template": {@link #doHousekeeping()}
     * expires templates in one pass and reaps the emptied exporters in a second, so a caller can
     * observe an exporter with no templates between the two.
     */
    public int domainCount() {
        return this.templates.size();
    }

    public Object dumpInternalState() {
        final ParserState.Builder parser = ParserState.builder();

        this.templates.forEach((domain, byId) -> {
            final String key = String.format("%s#%s",
                    domain.sessionKey.getDescription(),
                    domain.observationDomainId);

            final ExporterState.Builder exporter = ExporterState.builder(key);

            byId.forEach((templateId, wrapper) -> {
                exporter.withTemplate(TemplateState.builder(templateId).withInsertionTime(wrapper.time));
                wrapper.wrapped.options.forEach((selectors, values) ->
                        exporter.withOptions(OptionState.builder(templateId)
                                .withInsertionTime(values.time)
                                .withSelectors(selectors)
                                .withValues(values.wrapped)));
            });

            parser.withExporter(exporter);
        });

        return parser.build();
    }

    /**
     * Flattened snapshot of every template, keyed as before the per-exporter indexing. Builds a
     * copy on each call, so it is a diagnostic/test accessor — never call it on the packet path.
     */
    public Map<TemplateKey, TimeWrapper<TemplateOptions>> getTemplates() {
        final Map<TemplateKey, TimeWrapper<TemplateOptions>> flat = new LinkedHashMap<>();
        this.templates.forEach((domain, byId) -> byId.forEach((templateId, wrapper) ->
                flat.put(new TemplateKey(domain.sessionKey, domain.observationDomainId, templateId), wrapper)));
        return Collections.unmodifiableMap(flat);
    }

    public interface SessionKey {
        String getDescription();

        InetAddress getRemoteAddress();
    }

    // Package-private (not private) because the public TemplateKey exposes it in a field.
    static final class DomainKey {
        public final SessionKey sessionKey;
        public final long observationDomainId;

        private DomainKey(final SessionKey sessionKey,
                          final long observationDomainId) {
            this.sessionKey = Objects.requireNonNull(sessionKey);
            this.observationDomainId = observationDomainId;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof DomainKey that)) {
                return false;
            }

            return Objects.equals(this.observationDomainId, that.observationDomainId)
                    && Objects.equals(this.sessionKey, that.sessionKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.sessionKey, this.observationDomainId);
        }
    }

    public static final class TemplateKey {
        public final DomainKey observationDomainId;
        public final int templateId;

        public TemplateKey(final SessionKey sessionKey,
                    final long observationDomainId,
                    final int templateId) {
            this.observationDomainId = new DomainKey(sessionKey, observationDomainId);
            this.templateId = templateId;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TemplateKey that)) {
                return false;
            }

            return Objects.equals(this.observationDomainId, that.observationDomainId)
                    && Objects.equals(this.templateId, that.templateId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.observationDomainId, this.templateId);
        }
    }

    public static final class TimeWrapper<T> {
        public final Instant time;
        public final T wrapped;

        private TimeWrapper(final T wrapped) {
            this.time = Instant.now();
            this.wrapped = wrapped;
        }
    }

    private record TrackerKey(SessionKey sessionKey, ExporterIdentity scope) {
    }

    private static final class TrackedSequence {
        private final SequenceNumberTracker tracker;
        private volatile Instant lastSeen = Instant.now();

        private TrackedSequence(final SequenceNumberTracker tracker) {
            this.tracker = tracker;
        }
    }

    public static final class TemplateOptions {
        public final Template template;
        public final Map<Set<Value<?>>, TimeWrapper<List<Value<?>>>> options;

        private TemplateOptions(final Template template) {
            this(template, new ConcurrentHashMap<>());
        }

        private TemplateOptions(final Template template, Map<Set<Value<?>>, TimeWrapper<List<Value<?>>>> options) {
            this.template = Objects.requireNonNull(template);
            this.options = Objects.requireNonNull(options);
        }
    }

    private final class UdpSession implements Session {
        private final SessionKey sessionKey;

        private UdpSession(final SessionKey sessionKey) {
            this.sessionKey = Objects.requireNonNull(sessionKey);
        }

        private DomainKey domain(final long observationDomainId) {
            return new DomainKey(this.sessionKey, observationDomainId);
        }

        @Override
        public void addTemplate(final long observationDomainId, final Template template) {
            // The inner mutation happens inside the outer compute on purpose: it keeps the insert
            // atomic against reapEmptyDomains(), which would otherwise be free to drop a
            // freshly-created (still empty) inner map before this thread populates it.
            UdpSessionManager.this.templates.compute(domain(observationDomainId), (d, byId) -> {
                final ConcurrentMap<Integer, TimeWrapper<TemplateOptions>> target =
                        byId != null ? byId : new ConcurrentHashMap<>();
                target.compute(template.id, (id, wrapper) -> {
                    // preserve the old option values across a template re-announcement
                    final TemplateOptions options = wrapper == null
                            ? new TemplateOptions(template)
                            : new TemplateOptions(template, wrapper.wrapped.options);
                    return new TimeWrapper<>(options);
                });
                return target;
            });
        }

        @Override
        public void removeTemplate(final long observationDomainId, final int templateId) {
            UdpSessionManager.this.templates.computeIfPresent(domain(observationDomainId), (d, byId) -> {
                byId.remove(templateId);
                return byId.isEmpty() ? null : byId;
            });
        }

        @Override
        public void removeAllTemplate(final long observationDomainId, final Template.Type type) {
            UdpSessionManager.this.templates.computeIfPresent(domain(observationDomainId), (d, byId) -> {
                byId.entrySet().removeIf(e -> e.getValue().wrapped.template.type == type);
                return byId.isEmpty() ? null : byId;
            });
        }

        @Override
        public void addOptions(final long observationDomainId,
                               final int templateId,
                               final Collection<Value<?>> scopes,
                               final List<Value<?>> values) {
            // Unchanged failure semantics: a missing template still throws NullPointerException
            // rather than silently dropping the option record.
            UdpSessionManager.this.templates.get(domain(observationDomainId))
                    .get(templateId).wrapped.options.put(new HashSet<>(scopes), new TimeWrapper<>(values));

            UdpSessionManager.this.optionListener.accept(
                    new ExporterIdentity.NetflowIpfix(this.sessionKey.getRemoteAddress(), observationDomainId),
                    scopes, values);
        }

        @Override
        public Session.Resolver getResolver(final long observationDomainId) {
            return new DomainResolver(observationDomainId);
        }

        @Override
        public InetAddress getRemoteAddress() {
            return this.sessionKey.getRemoteAddress();
        }

        @Override
        public boolean verifySequenceNumber(final ExporterIdentity scope, final long sequenceNumber, final int sequenceIncrement) {
            final TrackerKey key = new TrackerKey(this.sessionKey, scope);
            final TrackedSequence tracked = UdpSessionManager.this.sequenceNumbers.computeIfAbsent(key,
                    (k) -> new TrackedSequence(UdpSessionManager.this.sequenceNumberTracker.get()));
            tracked.lastSeen = Instant.now();
            return tracked.tracker.verify(sequenceNumber, sequenceIncrement);
        }

        private final class DomainResolver implements Session.Resolver {
            /**
             * Built once, not per lookup. A resolver is bound to one exporter for its lifetime while
             * {@code lookupTemplate}/{@code lookupOptions} run per data record, so rebuilding the key
             * each time cost a DomainKey plus two varargs arrays and a boxed long per record — on the
             * very thread this indexing exists to unload.
             */
            private final DomainKey domain;

            private DomainResolver(final long observationDomainId) {
                this.domain = domain(observationDomainId);
            }

            /** This exporter's templates, or an empty map before the first template arrives. */
            private Map<Integer, TimeWrapper<TemplateOptions>> ownTemplates() {
                final var byId = UdpSessionManager.this.templates.get(this.domain);
                return byId != null ? byId : Map.of();
            }

            @Override
            public Template lookupTemplate(final int templateId) throws MissingTemplateException {
                final TimeWrapper<TemplateOptions> templateOptions = ownTemplates().get(templateId);
                if (templateOptions != null) {
                    return templateOptions.wrapped.template;
                } else {
                    throw new MissingTemplateException(templateId);
                }
            }

            @Override
            public List<Value<?>> lookupOptions(final List<Value<?>> values) {
                final LinkedHashMap<String, Value<?>> options = new LinkedHashMap<>();

                final Set<String> scoped = values.stream().map(Value::getName).collect(Collectors.toSet());

                // Only this exporter's templates, so the cost is independent of how many other
                // exporters the collector is fronting. Data templates have no scope names, so
                // containsAll() admits them and their (empty) option map contributes nothing —
                // the same outcome as the previous filter, without the collector-wide walk.
                for (final var wrapper : ownTemplates().values()) {
                    // Nothing recorded against this template means nothing can be found, so skip
                    // before building scopeValues. This is what elides the data templates — they
                    // never receive options, since addOptions is only reached under
                    // type == OPTIONS_TEMPLATE (see the ipfix/netflow9 Packet data-set loops).
                    // Filtering on the type instead would be subtly different: a zero-scope options
                    // template keys its options under the empty set, so a template re-announced as
                    // TEMPLATE could still legitimately match. Emptiness is exact either way.
                    if (wrapper.wrapped.options.isEmpty()) {
                        continue;
                    }

                    final Template template = wrapper.wrapped.template;

                    if (scoped.containsAll(template.scopeNames)) {
                        // Found option template where scoped fields is subset of actual data fields
                        final Set<Value<?>> scopeValues = values.stream()
                                .filter(s -> template.scopeNames.contains(s.getName()))
                                .collect(Collectors.toSet());

                        final TimeWrapper<List<Value<?>>> optionValues = wrapper.wrapped.options.get(scopeValues);
                        if (optionValues != null) {
                            for (final Value<?> value : optionValues.wrapped) {
                                options.put(value.getName(), value);
                            }
                        }
                    }
                }

                return new ArrayList<>(options.values());
            }
        }
    }
}
