/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.dns.netty;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.riptide.config.enricher.HostnamesConfig;
import org.riptide.dns.MockDnsServer;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class NettyDnsResolverTest {

    private static final Map<String, AtomicInteger> QUERY_COUNTS = new ConcurrentHashMap<>();

    @RegisterExtension
    static MockDnsServer dnsServer = new MockDnsServer(request -> {
        QUERY_COUNTS.computeIfAbsent(request.getName().toString(), key -> new AtomicInteger()).incrementAndGet();
        if (request.getType() == Type.PTR && request.getName().toString().equals("1.2.0.192.in-addr.arpa.")) {
            return List.of(Record.fromString(request.getName(), request.getType(), request.getDClass(), 300, "one.example.com.", null));
        }
        if (request.getName().toString().equals("50.2.0.192.in-addr.arpa.")) {
            return null; // SERVFAIL
        }
        if (request.getType() == Type.PTR && request.getName().toString().equals("25.2.0.192.in-addr.arpa.")) {
            // RFC 2317 classless delegation: CNAME onto the /27 sub-zone, PTR on the target
            return List.of(
                    Record.fromString(request.getName(), Type.CNAME, request.getDClass(), 300, "25.0/27.2.0.192.in-addr.arpa.", null),
                    Record.fromString(Name.fromString("25.0/27.2.0.192.in-addr.arpa."), Type.PTR, DClass.IN, 300, "classless.example.com.", null));
        }
        if (request.getType() == Type.PTR && request.getName().toString().equals("60.2.0.192.in-addr.arpa.")) {
            // CNAME without the chased PTR, as a non-recursive server would answer
            return List.of(
                    Record.fromString(request.getName(), Type.CNAME, request.getDClass(), 300, "60.32/27.2.0.192.in-addr.arpa.", null));
        }
        return List.of();
    });

    private NettyDnsResolver resolver;

    @BeforeEach
    void setUp() {
        final var config = new HostnamesConfig();
        config.setNameservers(List.of("127.0.0.1:" + dnsServer.getPort()));
        config.setMaximumDnsResolverThreads(1);
        this.resolver = new NettyDnsResolver(new MetricRegistry(), config,
                new DefaultDnsReverseCache(config), new DefaultDnsServerAddressProvider(config));
    }

    @AfterEach
    void tearDown() throws Exception {
        this.resolver.destroy();
    }

    @Test
    void positiveResultIsCached() throws Exception {
        final var address = InetAddress.getByName("192.0.2.1");

        assertThat(this.resolver.reverseLookup(address).get(5, TimeUnit.SECONDS)).contains("one.example.com");
        assertThat(this.resolver.reverseLookup(address).get(5, TimeUnit.SECONDS)).contains("one.example.com");

        assertThat(QUERY_COUNTS.get("1.2.0.192.in-addr.arpa.")).hasValue(1);
    }

    @Test
    void negativeResultIsCached() throws Exception {
        final var address = InetAddress.getByName("192.0.2.99");

        assertThat(this.resolver.reverseLookup(address).get(5, TimeUnit.SECONDS)).isEmpty();
        assertThat(this.resolver.reverseLookup(address).get(5, TimeUnit.SECONDS)).isEmpty();

        assertThat(QUERY_COUNTS.get("99.2.0.192.in-addr.arpa.")).hasValue(1);
    }

    @Test
    void rfc2317CnameChainedPtrIsResolvedAndCached() throws Exception {
        final var address = InetAddress.getByName("192.0.2.25");

        assertThat(this.resolver.reverseLookup(address).get(5, TimeUnit.SECONDS)).contains("classless.example.com");
        assertThat(this.resolver.reverseLookup(address).get(5, TimeUnit.SECONDS)).contains("classless.example.com");

        assertThat(QUERY_COUNTS.get("25.2.0.192.in-addr.arpa.")).hasValue(1);
    }

    @Test
    void cnameOnlyAnswerIsEmptyAndCached() throws Exception {
        final var address = InetAddress.getByName("192.0.2.60");

        assertThat(this.resolver.reverseLookup(address).get(5, TimeUnit.SECONDS)).isEmpty();
        assertThat(this.resolver.reverseLookup(address).get(5, TimeUnit.SECONDS)).isEmpty();

        assertThat(QUERY_COUNTS.get("60.2.0.192.in-addr.arpa.")).hasValue(1);
    }

    @Test
    void servfailIsNotCached() throws Exception {
        final var address = InetAddress.getByName("192.0.2.50");

        assertThat(this.resolver.reverseLookup(address).get(5, TimeUnit.SECONDS)).isEmpty();
        assertThat(this.resolver.reverseLookup(address).get(5, TimeUnit.SECONDS)).isEmpty();

        assertThat(QUERY_COUNTS.get("50.2.0.192.in-addr.arpa.")).hasValue(2);
    }
}
