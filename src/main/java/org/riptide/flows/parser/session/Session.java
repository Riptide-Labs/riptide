/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.session;

import org.riptide.flows.parser.exceptions.MissingTemplateException;
import org.riptide.flows.parser.ie.Value;
import org.riptide.pipeline.ExporterIdentity;

import java.net.InetAddress;
import java.util.Collection;
import java.util.List;

public interface Session {

    interface Resolver {
        Template lookupTemplate(int templateId) throws MissingTemplateException;
        List<Value<?>> lookupOptions(List<Value<?>> values);
    }

    void addTemplate(long observationDomainId, Template template);

    void removeTemplate(long observationDomainId, int templateId);

    void removeAllTemplate(long observationDomainId, Template.Type type);

    void addOptions(long observationDomainId, int templateId, Collection<Value<?>> scopes, List<Value<?>> values);

    Resolver getResolver(long observationDomainId);

    InetAddress getRemoteAddress();

    /**
     * Sequence streams are scoped by the full exporter identity, not just the
     * observation domain: sFlow agents with distinct payload agent addresses may share
     * one UDP source, and their independent streams must not interleave in one tracker.
     */
    boolean verifySequenceNumber(ExporterIdentity scope, long sequenceNumber);
}
