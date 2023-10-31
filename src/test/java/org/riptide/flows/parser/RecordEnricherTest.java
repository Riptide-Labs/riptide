package org.riptide.flows.parser;

import io.netty.buffer.Unpooled;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.riptide.dns.api.DnsResolver;
import org.riptide.flows.parser.netflow5.proto.Header;
import org.riptide.flows.parser.netflow5.proto.Packet;
import org.riptide.flows.parser.netflow5.proto.Record;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class RecordEnricherTest {

    @Test
    void verifyEnrichFlow() throws InvalidPacketException, ExecutionException, InterruptedException, UnknownHostException {
        enrichFlow(CompletableFuture.completedFuture(Optional.of("test")), "test", true);
        enrichFlow(CompletableFuture.completedFuture(Optional.empty()), null, true);
        enrichFlow(CompletableFuture.failedFuture(new RuntimeException()), null, true);
    }

    @Test
    void verifyDisableEnrichFlow() throws InvalidPacketException, ExecutionException, InterruptedException, UnknownHostException {
        enrichFlow(CompletableFuture.completedFuture(Optional.of("test")), null, false);
        enrichFlow(CompletableFuture.failedFuture(new RuntimeException()), null, false);
    }

    private void enrichFlow(final CompletableFuture<Optional<String>> reverseLookupFuture,
                            final String expectedValue,
                            final boolean dnsLookupsEnabled) throws InvalidPacketException, ExecutionException, InterruptedException, UnknownHostException {
        final var dnsResolver = new DnsResolver() {
            @Override
            public CompletableFuture<Optional<InetAddress>> lookup(final String hostname) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CompletableFuture<Optional<String>> reverseLookup(final InetAddress inetAddress) {
                return reverseLookupFuture;
            }
        };

        final var enricher = new RecordEnricher(dnsResolver, dnsLookupsEnabled);

        final var packet = getSampleNf5Packet();
        final var enrichmentFutures = packet.getRecords().map(enricher::enrich).toList();

        CompletableFuture.allOf(enrichmentFutures.toArray(new CompletableFuture[]{}));

        for (CompletableFuture<RecordEnrichment> future : enrichmentFutures) {
            Assertions.assertThat(future.isCompletedExceptionally()).isFalse();
            Assertions.assertThat(future.get().getHostnameFor(InetAddress.getByName("255.255.255.255"))).isEqualTo(Optional.ofNullable(expectedValue));
        }
    }

    private static Packet getSampleNf5Packet() throws InvalidPacketException {
        // Generate minimal Netflow v5 packet with 1 record
        final var bytes = new byte[Header.SIZE + Record.SIZE];
        Arrays.fill(bytes, (byte) 0xFF);
        bytes[0] = 0x00;
        bytes[1] = 0x05;
        bytes[2] = 0x00;
        bytes[3] = 0x01;

        final var buffer = Unpooled.wrappedBuffer(bytes);
        final var header = new Header(buffer);
        return new Packet(header, buffer);
    }

}
