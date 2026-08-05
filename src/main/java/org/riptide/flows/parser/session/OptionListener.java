/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.session;

import org.riptide.flows.parser.ie.Value;
import org.riptide.pipeline.ExporterIdentity;

import java.util.Collection;
import java.util.List;

/**
 * Observes option data records at arrival, with raw scopes and fields intact. This is
 * the only place `(exporter identity, ifIndex → interface name)` mappings exist in a
 * consumable shape: the per-record option merge can never carry interface options
 * (v9 scope names don't appear as data-record fields by construction; IPFIX matches
 * the ingress side at most), so consumers tap here instead.
 */
public interface OptionListener {

    OptionListener NONE = (identity, scopes, values) -> {
    };

    void accept(ExporterIdentity identity, Collection<Value<?>> scopes, List<Value<?>> values);

    /**
     * Fans one tap out to several consumers. Option records are a shared stream — one table reads
     * interface names out of it, another sampling rates — and each consumer already ignores the
     * records it does not recognize, so the fan-out needs no filtering of its own.
     */
    static OptionListener of(final OptionListener... listeners) {
        final List<OptionListener> targets = List.of(listeners);
        return (identity, scopes, values) -> targets.forEach(l -> l.accept(identity, scopes, values));
    }
}
