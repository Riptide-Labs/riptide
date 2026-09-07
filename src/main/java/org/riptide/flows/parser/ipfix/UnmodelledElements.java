/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.ipfix;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import org.riptide.flows.parser.ipfix.proto.FieldSpecifier;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.ipfix.proto.OptionsTemplateRecord;
import org.riptide.flows.parser.ipfix.proto.OptionsTemplateSet;
import org.riptide.flows.parser.ipfix.proto.Packet;
import org.riptide.flows.parser.ipfix.proto.TemplateRecord;
import org.riptide.flows.parser.ipfix.proto.TemplateSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reports an exporter announcing an information element riptide has deliberately chosen not to model (#596).
 *
 * <p><b>Why this is not covered by the warning that already exists.</b> {@code FieldSpecifier} logs
 * {@code "Undeclared information element"} when the registry lookup misses. Every element here is
 * <em>declared</em> — the shipped {@code ipfix-information-elements.xml} carries all ten of IE 390-399 — so
 * they take the other branch: parsed cleanly, bound to nothing on {@link IpfixRawFlow}, discarded in silence.
 * A conformant flow-selection exporter could be sending templates to a collector today with no artefact
 * anywhere saying so, which is what left #596's deferral without an exit condition.
 *
 * <p><b>Why a watchlist rather than "anything unbound".</b> The registry declares 475 elements and the flow
 * record carries on the order of seventy fields, so a signal derived from that difference would fire on
 * nearly every template from nearly every exporter. A signal that is always on is not read — the failure
 * {@code static-analysis-gating} already forbids for compiler diagnostics in this project. The list here
 * holds only elements somebody decided not to model, so an entry arrives with the decision that made it.
 *
 * <p><b>What a zero does not mean.</b> Nothing observed means nothing has reached <em>this</em> collector
 * since it started. It is not evidence that no such exporter exists. That distinction is the reason this
 * class exists: #584 concluded from exporter source that the packet-selection family was unimplemented in
 * practice, and #598 then found softflowd 1.1.1 emitting IE 304/305/306 with its volume under-reported
 * hundredfold.
 */
final class UnmodelledElements {

    private static final Logger LOG = LoggerFactory.getLogger(UnmodelledElements.class);

    /**
     * The elements riptide sees, understands, and deliberately drops, each with the decision that chose so.
     *
     * <p>Keyed by element id because that is what arrives on the wire. The value is the sentence a reader
     * needs in order to judge whether the decision still holds, which is why it names the issue rather than
     * only the family: an entry whose reasoning nobody can retrieve is an entry nobody can retire.
     */
    private static final Map<Integer, String> WATCHLIST = watchlist();

    private static Map<Integer, String> watchlist() {
        final Map<Integer, String> entries = new LinkedHashMap<>();
        // #596: the IPFIX flow-selection family. Deferred rather than modelled because no exporter was found
        // writing it and an implementation written from the RFC alone could not be verified -- which is how
        // #584's four defects arrived. Kept visible because that survey's sibling claim was wrong (#598).
        put(entries, 390, "flowSelectorAlgorithm");
        put(entries, 391, "flowSelectedOctetDeltaCount");
        put(entries, 392, "flowSelectedPacketDeltaCount");
        put(entries, 393, "flowSelectedFlowDeltaCount");
        put(entries, 394, "selectorIDTotalFlowsObserved");
        put(entries, 395, "selectorIDTotalFlowsSelected");
        put(entries, 396, "samplingFlowInterval");
        put(entries, 397, "samplingFlowSpacing");
        put(entries, 398, "flowSamplingTimeInterval");
        put(entries, 399, "flowSamplingTimeSpacing");
        return Map.copyOf(entries);
    }

    private static void put(final Map<Integer, String> entries, final int id, final String name) {
        entries.put(id, "IE %d (%s), IPFIX flow selection, unmodelled per issue 596".formatted(id, name));
    }

    /**
     * A {@link Counter}, not a {@code Meter}, matching the event-count siblings in {@code ParserBase}
     * ({@code sequenceErrors}, {@code dispatchDrops}, {@code undecodableSets}). The only thing a meter adds
     * is a rate, and a rate is the one reading of this quantity that misleads: over TCP an exporter
     * announces its templates once per connection, so the rate falls to zero while it keeps exporting.
     */
    private final Counter templatesSeen;

    /**
     * The entries already named, so a repeat is quiet but a <em>new</em> element still speaks.
     *
     * <p><b>Keyed on element and exporter together.</b> Two rounds of review found this dedup too coarse in
     * two different ways. A single latch came first: an exporter announcing IE 396 took it, and the later
     * arrival of IE 390 — the exact event #596 defers on — moved the counter while no line ever named it.
     * Keying on the element alone then had the same shape one level up: a lab exporter announces IE 390 on
     * day one and is logged, a production exporter announces it six months later, and the only line in the
     * archive points at the lab box. The docs tell an operator to go and decide whether it matters, so the
     * line has to say which exporter to go and look at.
     *
     * <p>Unbounded in principle, bounded in practice by watchlist size times exporters that actually send
     * these elements — which is expected to be zero, and is the whole reason this exists.
     */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    UnmodelledElements(final MetricRegistry metrics, final String parserName) {
        this.templatesSeen = metrics.counter(
                MetricRegistry.name("parsers", parserName, "unmodelledElementTemplates"));
    }

    /**
     * Counts the templates in one packet that carry a watchlisted element, and names them the first time.
     *
     * <p>Reads {@link Packet}'s already-parsed template sets rather than intercepting registration, so
     * nothing is threaded through the per-packet constructor and no session type is widened. Both template
     * kinds are walked, and an options template's scope fields with them: a selector report puts its
     * identifying element in scope, so scanning only the data fields would miss exactly the record shape
     * this exists to find.
     */
    void observe(final Packet packet, final Session session) {
        // The overwhelming majority of packets carry data sets only and can never hit. This runs on the
        // ingest thread for every packet at line rate, so it leaves before allocating anything.
        if (packet.templateSets.isEmpty() && packet.optionTemplateSets.isEmpty()) {
            return;
        }

        final Set<String> found = new TreeSet<>();
        int templates = 0;

        for (final TemplateSet templateSet : packet.templateSets) {
            for (final TemplateRecord record : templateSet) {
                if (scan(record.fields, found)) {
                    templates++;
                }
            }
        }
        for (final OptionsTemplateSet optionsTemplateSet : packet.optionTemplateSets) {
            for (final OptionsTemplateRecord record : optionsTemplateSet) {
                boolean hit = scan(record.fields, found);
                hit |= scan(record.scopes, found);
                if (hit) {
                    templates++;
                }
            }
        }

        if (templates == 0) {
            return;
        }
        this.templatesSeen.inc(templates);

        // The count keeps moving regardless; only the sentence is suppressed, and only per element already
        // named. add() returns false for one already in the set, so what survives is exactly the elements
        // nobody has been told about yet — and the add is what claims them, so two threads cannot both name
        // the same one.
        final String exporter = "%s, observation domain %d"
                .formatted(session.getRemoteAddress(), packet.header.observationDomainId);
        final Set<String> unreported = new TreeSet<>(found);
        unreported.removeIf(entry -> !this.reported.add(exporter + " -> " + entry));
        if (!unreported.isEmpty()) {
            LOG.warn("Exporter {} announced a template carrying information elements riptide parses and then"
                            + " discards: {}. Nothing is wrong with the export and no flow is dropped, but"
                            + " what those elements state is not being read. Each element is named once per"
                            + " exporter, per parser; the counter beside it keeps counting.",
                    exporter, String.join("; ", unreported));
        }
    }

    /**
     * Typed to the IPFIX specifier rather than to {@code Field}, so the compiler enforces what a comment
     * used to assert. NetFlow v9 ships a class of the same simple name whose field types are a different
     * numbering space, and a v9 type 390 is not IE 390. An earlier version took {@code Iterable<?>} and
     * filtered with {@code instanceof}: vacuous at both call sites, and had a v9 list ever reached it the
     * method would have returned {@code false} forever rather than failing to compile — a permanent zero
     * reading, which is the one failure this class exists to prevent.
     *
     * <p>{@code enterpriseNumber == null} keeps it to the IANA space: an enterprise element may reuse any id.
     */
    private static boolean scan(final Iterable<FieldSpecifier> fields, final Set<String> found) {
        boolean hit = false;
        for (final FieldSpecifier specifier : fields) {
            if (specifier.enterpriseNumber != null) {
                continue;
            }
            final String entry = WATCHLIST.get(specifier.informationElementId);
            if (entry != null) {
                found.add(entry);
                hit = true;
            }
        }
        return hit;
    }
}
