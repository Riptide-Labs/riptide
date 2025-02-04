package org.riptide.classification.internal.csv;

import com.google.common.base.Strings;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.riptide.classification.DefaultRule;
import org.riptide.classification.Rule;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CsvImporter {
    public static final String[] HEADERS = {"name", "protocol", "srcAddress", "srcPort", "dstAddress", "dstPort", "exporterFilter", "omnidirectional"};

    public List<Rule> parse(final InputStream inputStream, final boolean hasHeader) throws IOException {
        Objects.requireNonNull(inputStream);

        final var rules = new ArrayList<Rule>();
        final var format = createFormat(hasHeader);
        final var parser = format.parse(new InputStreamReader(inputStream));
        for (CSVRecord record : parser.getRecords()) {
            if (record.size() < HEADERS.length) {
                throw new IOException("The provided rule ''%s'' cannot be parsed. Expected columns %s but received %s.".formatted(record, HEADERS.length, record.size()));
            }

            final String name = record.get(0);
            final String protocol = record.get(1);
            final String srcAddress = record.get(2);
            final String srcPort = record.get(3);
            final String dstAddress = record.get(4);
            final String dstPort = record.get(5);
            final String exportFilter = record.get(6);
            final String omnidirectional = record.get(7);

            // Set values
            final var rule = DefaultRule.builder()
                .withName(Strings.emptyToNull(name))
                .withDstPort(Strings.emptyToNull(dstPort))
                .withDstAddress(Strings.emptyToNull(dstAddress))
                .withSrcPort(Strings.emptyToNull(srcPort))
                .withSrcAddress(Strings.emptyToNull(srcAddress))
                .withProtocol(Strings.emptyToNull(protocol))
                .withExporterFilter(Strings.emptyToNull(exportFilter))
                .withOmnidirectional(Boolean.parseBoolean(omnidirectional))
                .build();

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
