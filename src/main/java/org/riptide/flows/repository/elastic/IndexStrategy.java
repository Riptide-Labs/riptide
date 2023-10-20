package org.riptide.flows.repository.elastic;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.TimeZone;

import com.google.common.base.Strings;

/**
 * Defines a strategy on how to define the index when persisting.
 *
 * This implementation is thread safe.
 */
public enum IndexStrategy {
    YEARLY("yyyy"),
    MONTHLY("yyyy-MM"),
    DAILY("yyyy-MM-dd"),
    HOURLY("yyyy-MM-dd-HH");

    /**
     * Use the {@link DateTimeFormatter} since its thread-safe.
     */
    private final DateTimeFormatter dateFormat;

    private final String pattern; // remember pattern since DateFormat doesn't provide access to it

    IndexStrategy(final String pattern) {
        this.pattern = pattern;

        final ZoneId UTC = TimeZone.getTimeZone("UTC").toZoneId();
        this.dateFormat = DateTimeFormatter.ofPattern(pattern)
                .withZone(UTC);
    }

    public String getIndex(final IndexSettings indexSettings,
                           final String indexName,
                           final TemporalAccessor temporal) {
        final StringBuilder sb = new StringBuilder();
        if (!Strings.isNullOrEmpty(indexSettings.getIndexPrefix())) {
            sb.append(indexSettings.getIndexPrefix());
        }
        sb.append(indexName);
        sb.append("-");
        sb.append(this.dateFormat.format(temporal));
        return sb.toString();
    }

    public String getPattern(){
        return this.pattern;
    }
}
