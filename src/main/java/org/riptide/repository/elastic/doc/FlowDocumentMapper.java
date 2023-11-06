package org.riptide.repository.elastic.doc;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValueCheckStrategy;
import org.riptide.pipeline.EnrichedFlow;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;

@Mapper(nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS,
        componentModel = "spring")
public abstract class FlowDocumentMapper {

    @Mapping(target = "version", ignore = true) // Has a default value
    @Mapping(target = "hosts", ignore = true) // Is set by calling setSrcAddr and setDstAddr
    @BeanMapping(ignoreUnmappedSourceProperties = {"receivedAt"})
    public abstract FlowDocument flowToDocument(EnrichedFlow flow);

    protected Long timestamp(final Instant timestamp) {
        if (timestamp == null) {
            return null;
        }

        return timestamp.toEpochMilli();
    }

    protected Long duration(final Duration duration) {
        if (duration == null) {
            return null;
        }

        return duration.toMillis();
    }

    protected String address(final InetAddress address) {
        if (address == null) {
            return null;
        }

        return address.getHostAddress();
    }
}
