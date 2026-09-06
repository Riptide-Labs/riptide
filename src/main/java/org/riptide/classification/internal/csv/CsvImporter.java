/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.classification.internal.csv;

import com.google.common.base.Strings;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.riptide.classification.DefaultRule;
import org.riptide.classification.Rule;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CsvImporter {
    private static final String[] HEADERS = {"name", "protocol", "srcAddress", "srcPort", "dstAddress", "dstPort", "exporterFilter", "omnidirectional"};

    public List<Rule> parse(final InputStream inputStream, final boolean hasHeader) throws IOException {
        Objects.requireNonNull(inputStream);

        final var rules = new ArrayList<Rule>();
        final var format = createFormat(hasHeader);
        // Reject files whose first line is not the expected header: commons-csv would otherwise
        // consume a rule row as pseudo-header, silently dropping it and shifting every position —
        // and positions are the evaluation priority. Empty fields in that row make commons-csv
        // itself refuse ("a header name is missing") before the name comparison can run.
        final CSVParser parser;
        try {
            // UTF-8 explicitly: the rules ship in the jar and are read on whatever platform the
            // collector runs on, so a non-UTF-8 default locale would mangle any non-ASCII
            // application or organisation name rather than fail visibly.
            parser = format.parse(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        } catch (final IllegalArgumentException e) {
            throw new IOException("The rules file's first line is not the expected header '%s'. The header row is required."
                    .formatted(String.join(";", HEADERS)), e);
        }
        if (hasHeader && !parser.getHeaderNames().equals(List.of(HEADERS))) {
            throw new IOException("The rules file's first line is not the expected header '%s' but '%s'. The header row is required."
                    .formatted(String.join(";", HEADERS), String.join(";", parser.getHeaderNames())));
        }
        for (CSVRecord record : parser.getRecords()) {
            if (record.size() < HEADERS.length) {
                throw new IOException("The provided rule '%s' cannot be parsed. Expected columns %s but received %s.".formatted(record, HEADERS.length, record.size()));
            }

            final String name = record.get(0);
            final String protocol = record.get(1);
            final String srcAddress = record.get(2);
            final String srcPort = record.get(3);
            final String dstAddress = record.get(4);
            final String dstPort = record.get(5);
            final String exportFilter = record.get(6);
            final String omnidirectional = record.get(7);

            // Set values. The row order is the evaluation priority (Rule.getPosition): the
            // earliest matching row wins — even over a later, more specific rule — so specific
            // rules must precede broad ones.
            final var rule = DefaultRule.builder()
                .withName(Strings.emptyToNull(name))
                .withDstPort(Strings.emptyToNull(dstPort))
                .withDstAddress(Strings.emptyToNull(dstAddress))
                .withSrcPort(Strings.emptyToNull(srcPort))
                .withSrcAddress(Strings.emptyToNull(srcAddress))
                .withProtocol(Strings.emptyToNull(protocol))
                .withExporterFilter(Strings.emptyToNull(exportFilter))
                .withOmnidirectional(Boolean.parseBoolean(omnidirectional))
                .withPosition(rules.size())
                .build();

            // The importer reads columns; it does not judge their contents. A rule carrying an
            // exporterFilter (#759), or naming a protocol/port/address that resolves to nothing
            // (#763), is rejected in preprocessing instead — PreprocessedRule.of and the value
            // parsers it calls — so it arrives as one rejected rule the engine names and skips,
            // rather than a ruleset that will not load. Rejecting here would abort the whole file
            // for one cell, and — since RiptideConfiguration loads eagerly — fail the boot.
            rules.add(rule);
        }

        return rules;
    }

    private static CSVFormat createFormat(boolean hasHeader) {
        var builder = CSVFormat.RFC4180
                .builder()
                .setDelimiter(';');
        if (hasHeader) {
            builder = builder.setHeader();
        }
        return builder.get();
    }
}
