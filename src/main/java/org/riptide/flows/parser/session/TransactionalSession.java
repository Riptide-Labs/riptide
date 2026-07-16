/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.session;

import org.riptide.flows.parser.exceptions.MissingTemplateException;
import org.riptide.flows.parser.ie.Value;
import org.riptide.pipeline.ExporterIdentity;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * A {@link Session} view that can undo the template mutations of one packet. Packets install
 * templates set-by-set while they parse, so a malformed packet may have committed templates
 * (possibly garbage read out of a mis-framed region) before a later set throws. RFC 7011 §10.3
 * requires discarding the malformed message — including what it taught us: retaining such a
 * template would silently mis-decode every subsequent data set referencing its ID, while
 * {@link #rollback()} restores the pre-packet state so the worst case is a loud
 * {@link MissingTemplateException}.
 *
 * <p>Only template add/remove operations are rolled back. {@code removeAllTemplate} and
 * {@code addOptions} are not undoable through the {@link Session} API — both degrade safely
 * (missing templates fail loudly; options are soft enrichment refreshed by the exporter).
 */
public final class TransactionalSession implements Session {

    private final Session delegate;
    private final Deque<Runnable> undo = new ArrayDeque<>();

    public TransactionalSession(final Session delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    /** Undo this packet's template mutations, most recent first. */
    public void rollback() {
        while (!this.undo.isEmpty()) {
            this.undo.pop().run();
        }
    }

    @Override
    public void addTemplate(final long observationDomainId, final Template template) {
        final Template previous = lookup(observationDomainId, template.id);
        this.undo.push(previous != null
                ? () -> this.delegate.addTemplate(observationDomainId, previous)
                : () -> this.delegate.removeTemplate(observationDomainId, template.id));
        this.delegate.addTemplate(observationDomainId, template);
    }

    @Override
    public void removeTemplate(final long observationDomainId, final int templateId) {
        final Template previous = lookup(observationDomainId, templateId);
        if (previous != null) {
            this.undo.push(() -> this.delegate.addTemplate(observationDomainId, previous));
        }
        this.delegate.removeTemplate(observationDomainId, templateId);
    }

    @Override
    public void removeAllTemplate(final long observationDomainId, final Template.Type type) {
        // Not undoable through the Session API (no enumeration); a rollback leaves the templates
        // absent, which fails loudly on the next data set — the safe direction.
        this.delegate.removeAllTemplate(observationDomainId, type);
    }

    @Override
    public void addOptions(final long observationDomainId, final int templateId,
                           final Collection<Value<?>> scopes, final List<Value<?>> values) {
        this.delegate.addOptions(observationDomainId, templateId, scopes, values);
    }

    @Override
    public Resolver getResolver(final long observationDomainId) {
        return this.delegate.getResolver(observationDomainId);
    }

    @Override
    public InetAddress getRemoteAddress() {
        return this.delegate.getRemoteAddress();
    }

    @Override
    public boolean verifySequenceNumber(final ExporterIdentity scope, final long sequenceNumber,
                                        final int sequenceIncrement) {
        return this.delegate.verifySequenceNumber(scope, sequenceNumber, sequenceIncrement);
    }

    private Template lookup(final long observationDomainId, final int templateId) {
        try {
            return this.delegate.getResolver(observationDomainId).lookupTemplate(templateId);
        } catch (final MissingTemplateException e) {
            return null;
        }
    }
}
