package org.riptide.flows.parser;

import java.net.InetAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.riptide.dns.api.DnsResolver;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ie.values.IPv4AddressValue;
import org.riptide.flows.parser.ie.values.IPv6AddressValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;

public class RecordEnricher {
    private static final Logger LOG = LoggerFactory.getLogger(RecordEnricher.class);

    private final DnsResolver dnsResolver;

    private final boolean dnsLookupsEnabled;

    public RecordEnricher(final DnsResolver dnsResolver,
                          final boolean dnsLookupsEnabled) {
        this.dnsResolver = Objects.requireNonNull(dnsResolver);
        this.dnsLookupsEnabled = dnsLookupsEnabled;
    }

    public CompletableFuture<RecordEnrichment> enrich(Map<String, Value<?>> record) {
        if (!this.dnsLookupsEnabled) {
            final CompletableFuture<RecordEnrichment> emptyFuture = new CompletableFuture<>();
            final RecordEnrichment emptyEnrichment = new DefaultRecordEnrichment(Collections.<InetAddress, String>emptyMap());
            emptyFuture.complete(emptyEnrichment);
            return emptyFuture;
        }
        final IpAddressCapturingVisitor ipAddressCapturingVisitor = new IpAddressCapturingVisitor();
        for (final Value<?> value : record.values()) {
            value.visit(ipAddressCapturingVisitor);
        }
        final Set<InetAddress> addressesToReverseLookup = ipAddressCapturingVisitor.getAddresses();
        final Map<InetAddress, String> hostnamesByAddress = new HashMap<>(addressesToReverseLookup.size());
        final var reverseLookupFutures = addressesToReverseLookup.stream()
                .map(addr -> {
                    LOG.trace("Issuing reverse lookup for: {}", addr);
                    return dnsResolver.reverseLookup(addr).whenComplete((hostname, ex) -> {
                        if (ex == null) {
                            LOG.trace("Got reverse lookup answer for '{}': {}", addr, hostname);
                            synchronized (hostnamesByAddress) {
                                hostnamesByAddress.put(addr, hostname.orElse(null));
                            }
                        } else {
                            LOG.trace("Reverse lookup failed for '{}'", addr, ex);
                            synchronized (hostnamesByAddress) {
                                hostnamesByAddress.put(addr, null);
                            }
                        }
                        if (LOG.isTraceEnabled()) {
                            LOG.trace("Other lookups pending: {}", Sets.difference(addressesToReverseLookup, hostnamesByAddress.keySet()));
                        }
                    });
                }).toArray(CompletableFuture[]::new);

        final CompletableFuture<RecordEnrichment> future = new CompletableFuture<>();
        CompletableFuture.allOf(reverseLookupFutures).whenComplete((any, ex) -> {
            LOG.trace("All reverse lookups complete. Queries: {} Results: {}", addressesToReverseLookup, hostnamesByAddress);
            // All of the reverse lookups have completed, note that some may have failed though
            // Build the enrichment object with the results we do have
            final RecordEnrichment enrichment = new DefaultRecordEnrichment(hostnamesByAddress);
            future.complete(enrichment);
        });
        return future;
    }

    private static class DefaultRecordEnrichment implements RecordEnrichment {
        private final Map<InetAddress, String> hostnamesByAddress;

        DefaultRecordEnrichment(Map<InetAddress, String> hostnamesByAddress) {
            this.hostnamesByAddress = hostnamesByAddress;
        }

        @Override
        public Optional<String> getHostnameFor(InetAddress address) {
            return Optional.ofNullable(hostnamesByAddress.get(address));
        }
    }

    private static class IpAddressCapturingVisitor implements Value.Visitor {
        private final Set<InetAddress> addresses = new HashSet<>();

        public Set<InetAddress> getAddresses() {
            return addresses;
        }

        @Override
        public void accept(IPv4AddressValue value) {
            addresses.add(value.getValue());
        }

        @Override
        public void accept(IPv6AddressValue value) {
            addresses.add(value.getValue());
        }
    }
}
