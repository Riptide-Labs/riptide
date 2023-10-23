package org.riptide.classification.internal.csv;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.google.common.base.Strings;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.riptide.classification.DefaultRule;
import org.riptide.classification.Rule;

public class CsvImporter {

    public static final String[] HEADERS = {"name", "protocol", "srcAddress", "srcPort", "dstAddress", "dstPort", "exporterFilter", "omnidirectional"};

    public static List<Rule> parse(final InputStream inputStream,
                            final boolean hasHeader) throws IOException {
        Objects.requireNonNull(inputStream);

        final var rules = new ArrayList<Rule>();

        CSVFormat csvFormat = CSVFormat.RFC4180.withDelimiter(';');
        if (hasHeader) csvFormat = csvFormat.withHeader();

        final CSVParser parser = csvFormat.parse(new InputStreamReader(inputStream));
        for (CSVRecord record : parser.getRecords()) {
            if (record.size() < HEADERS.length) {
                throw new IOException("The provided rule ''" + record.toString() + "'' cannot be parsed. Expected columns " + HEADERS.length + " but received " + record.size() + ".");
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
            final var rule = new DefaultRule();
            rule.setName(Strings.emptyToNull(name));
            rule.setDstPort(Strings.emptyToNull(dstPort));
            rule.setDstAddress(Strings.emptyToNull(dstAddress));
            rule.setSrcPort(Strings.emptyToNull(srcPort));
            rule.setSrcAddress(Strings.emptyToNull(srcAddress));
            rule.setProtocol(Strings.emptyToNull(protocol));
            rule.setExporterFilter(Strings.emptyToNull(exportFilter));
            rule.setOmnidirectional(Boolean.parseBoolean(omnidirectional));

            rules.add(rule);
        }

        return rules;
    }
}
