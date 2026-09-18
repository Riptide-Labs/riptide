/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * A dotted path into a JSON object, and that is the whole language.
 *
 * <p><b>What it does.</b> {@code primary_ip4.address} walks two fields and returns what it finds.
 * Nothing else: no transforms, no defaults, no conditionals, no concatenation, no indexing, no
 * wildcards.</p>
 *
 * <p><b>Why it is this small.</b> The alternatives were considered and rejected in #800. A template
 * engine is the widest surface available; an expression language fed from an operator's
 * configuration file is a code-execution surface, which is precisely what this collector resolves
 * every credential through an indirection to avoid. Every transform beyond this is a language
 * feature to specify, document, test and keep, and the first one should arrive with the endpoint
 * that needed it named in a report rather than because it might be wanted.</p>
 *
 * <p><b>What that costs.</b> A value that is not usable as found cannot be made usable. An address
 * arriving as {@code 10.0.0.1/24} is the known case: the strict address parse downstream refuses
 * host bits, and nothing here can remove them. That is why {@code NetboxDeviceSource} exists and
 * keeps its own mapping, and why {@link MappedJsonSource} says so when it sees one rather than
 * letting every device be skipped for a reason the operator has to guess.</p>
 *
 * <p><b>A dot is a separator and nothing else.</b> A field whose name contains a dot is not
 * reachable, and there is no escape for it. Endpoints with such field names exist; an operator who
 * has one needs a different mechanism, not a quoting rule bolted onto this one.</p>
 */
final class JsonPath {

    private final String[] segments;
    private final String path;

    private JsonPath(final String path, final String[] segments) {
        this.path = path;
        this.segments = segments;
    }

    /**
     * The path an operator wrote.
     *
     * @throws IllegalStateException when it is blank or carries an empty segment, which are
     *     mistakes rather than paths: {@code a..b} and a trailing dot both mean the operator meant
     *     something they did not write, and reading them as "the field named the empty string"
     *     would silently match nothing forever.
     */
    static JsonPath of(final String path, final String key) {
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("%s must name a field: it is not set.".formatted(key));
        }
        final String[] segments = path.strip().split("\\.", -1);
        for (final String segment : segments) {
            if (segment.isBlank()) {
                throw new IllegalStateException(
                        ("%s is not a usable path: '%s'. A dot separates field names, so an empty "
                                + "segment means a field with no name, which nothing can match.")
                                .formatted(key, path));
            }
        }
        return new JsonPath(path.strip(), segments);
    }

    /** The text at this path, or empty when any step is missing, null, or not a scalar. */
    Optional<String> read(final JsonNode from) {
        JsonNode at = from;
        for (final String segment : this.segments) {
            if (at == null || !at.isObject()) {
                return Optional.empty();
            }
            at = at.get(segment);
        }
        // a scalar or nothing: an object or an array here means the path stopped short of a value,
        // and rendering one into a name would produce an exporter called "{id=1, name=edge-01}"
        if (at == null || at.isNull() || at.isContainerNode()) {
            return Optional.empty();
        }
        return Optional.of(at.asText());
    }

    /** The node at this path, for a caller that wants the array rather than a value. */
    Optional<JsonNode> node(final JsonNode from) {
        JsonNode at = from;
        for (final String segment : this.segments) {
            if (at == null || !at.isObject()) {
                return Optional.empty();
            }
            at = at.get(segment);
        }
        return at == null || at.isNull() ? Optional.empty() : Optional.of(at);
    }

    @Override
    public String toString() {
        return this.path;
    }
}
