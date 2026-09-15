/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a Prometheus HTTP service discovery document into {@link TargetGroup}s.
 *
 * <p>The contract is a bare JSON array whose elements each carry {@code targets}, an array of
 * strings, and {@code labels}, an object of string to string. There is no {@code results} envelope,
 * which is the shape a NetBox native client would answer with and is refused here by name so the
 * difference is legible rather than a null pointer three classes away.</p>
 *
 * <p>Every refusal names the source, because at this layer there is nothing else to go on: the
 * document has no line numbers an operator authored.</p>
 */
public final class ServiceDiscoveryParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ServiceDiscoveryParser() {
    }

    /**
     * @param json the response body
     * @param sourceName how the endpoint is named in errors, credentials already redacted
     * @throws IllegalStateException on malformed JSON or any shape violation
     */
    public static List<TargetGroup> parse(final byte[] json, final String sourceName) {
        final JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (final JacksonException e) {
            throw new IllegalStateException(
                    "%s's response is not valid JSON: %s".formatted(sourceName, e.getOriginalMessage()), e);
        } catch (final IOException e) {
            throw new IllegalStateException(
                    "%s could not be read: %s".formatted(sourceName, e.getMessage()), e);
        }
        if (root == null || !root.isArray()) {
            throw new IllegalStateException(
                    ("%s did not answer with a JSON array. Prometheus service discovery is a bare array "
                            + "of {targets, labels} objects, with no enclosing envelope.").formatted(sourceName));
        }
        final List<TargetGroup> groups = new ArrayList<>();
        int index = 0;
        for (final JsonNode element : root) {
            groups.add(group(element, index++, sourceName));
        }
        return List.copyOf(groups);
    }

    private static TargetGroup group(final JsonNode element, final int index, final String sourceName) {
        if (!element.isObject()) {
            throw new IllegalStateException(
                    "%s: entry %d is not an object".formatted(sourceName, index));
        }
        final JsonNode targets = element.get("targets");
        if (targets == null || !targets.isArray()) {
            throw new IllegalStateException(
                    "%s: entry %d has no 'targets' array".formatted(sourceName, index));
        }
        final List<String> hosts = new ArrayList<>();
        for (final JsonNode target : targets) {
            if (!target.isTextual()) {
                throw new IllegalStateException(
                        "%s: entry %d has a non-string target".formatted(sourceName, index));
            }
            hosts.add(target.textValue());
        }
        final Map<String, String> labels = new LinkedHashMap<>();
        final JsonNode labelNode = element.get("labels");
        if (labelNode != null && !labelNode.isNull()) {
            if (!labelNode.isObject()) {
                throw new IllegalStateException(
                        "%s: entry %d has a 'labels' field that is not an object".formatted(sourceName, index));
            }
            labelNode.properties().forEach(entry -> {
                if (!entry.getValue().isTextual()) {
                    // Prometheus label values are strings. A number here means a producer that has
                    // not read the contract, and coercing it would hide that from whoever has to fix it
                    throw new IllegalStateException(
                            "%s: entry %d label '%s' is not a string".formatted(
                                    sourceName, index, entry.getKey()));
                }
                labels.put(entry.getKey(), entry.getValue().textValue());
            });
        }
        return new TargetGroup(hosts, labels);
    }
}
