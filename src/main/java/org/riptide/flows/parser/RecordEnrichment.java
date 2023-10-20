package org.riptide.flows.parser;

import java.net.InetAddress;
import java.util.Optional;

/**
 * Methods used by the (BSON) serialization process to access augmented facts.
 *
 * These facts are expected to be pre-populated by visiting the record and should be non-blocking.
 */
public interface RecordEnrichment {

    Optional<String> getHostnameFor(InetAddress srcAddress);

}
