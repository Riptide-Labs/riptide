package org.riptide.dns.netty;

import io.netty.channel.AddressedEnvelope;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DnsPtrRecord;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
class NettyDnsResolverWorker {
    private final DnsNameResolver resolver;
    private final NettyDnsResolver parent;

    NettyDnsResolverWorker(NettyDnsResolver parent, long queryTimeoutMillis) {
        this.parent = Objects.requireNonNull(parent);
        this.resolver = new DnsNameResolverBuilder(parent.group.next())
                .datagramChannelType(NioDatagramChannel.class)
                .nameServerProvider(parent.dnsNameserverProvider)
                .queryTimeoutMillis(queryTimeoutMillis)
                .maxQueriesPerResolve(1)
                .optResourceEnabled(false)
                .resolveCache(parent.nettyCache) // PTR lookups will not be cached, but we set a cache anyways
                .build();
    }

    CompletableFuture<Optional<String>> performReverseLookup(String reverseMapName) {
        final var resultFuture = new CompletableFuture<Optional<String>>();
        final var dnsQuestion = new DefaultDnsQuestion(reverseMapName, DnsRecordType.PTR, DnsRecord.CLASS_IN);
        Future<AddressedEnvelope<DnsResponse, InetSocketAddress>> queryFuture = resolver.query(dnsQuestion);
        queryFuture.addListener((responseFuture) -> {
            log.info("DNS Reverse lookup for {}", reverseMapName);
            try {
                final var envelope = (AddressedEnvelope<DnsResponse, InetSocketAddress>) responseFuture.get();
                if (envelope == null) {
                    log.warn("no result for {}", reverseMapName);
                    resultFuture.completeExceptionally(new RuntimeException("querying for '%s' resulted in a null response".formatted(dnsQuestion)));
                    return;
                }
                try {
                    final var dnsResponse = envelope.content();
                    if (dnsResponse.code() == DnsResponseCode.NOERROR) {
                        final DnsPtrRecord ptrRecord = dnsResponse.recordAt(DnsSection.ANSWER);
                        if (ptrRecord != null) {
                            log.warn("Result received for {}: {}", reverseMapName, ptrRecord);
                            final var cacheEntry = new DnsReverseCacheEntry(reverseMapName, ptrRecord, cleanHostname(ptrRecord.hostname()));
                            parent.reverseCache.put(reverseMapName, Optional.of(cacheEntry));
                            resultFuture.complete(Optional.of(cacheEntry.getCleanedHostname()));
                        } else {
                            log.warn("Empty result received for: {}", reverseMapName);
                            parent.reverseCache.put(reverseMapName, Optional.empty());
                            resultFuture.complete(Optional.empty());
                        }
                    } else {
                        parent.reverseCache.put(reverseMapName, Optional.empty());
                        resultFuture.complete(Optional.empty());
                    }
                } finally {
                    envelope.release();
                }
            } catch (Exception ex) {
                log.error("Error reverseLookup for {}:{}", reverseMapName, ex.getMessage(), ex);
                resultFuture.completeExceptionally(extractCauseIfAvailable(ex));
            }
        });
        return resultFuture;
    }

    void destroy() {
        resolver.close();
    }

    private static Throwable extractCauseIfAvailable(Exception ex) {
        if (ex.getCause() != null) {
            return ex.getCause(); // TODO MVR decide if you want to go all the way down to the actual root cause
        }
        return ex;
    }

    private static String cleanHostname(String hostname) {
        if (hostname.endsWith(".")) {
            return hostname.substring(0, hostname.length() - 1);
        }
        return hostname;
    }
}
