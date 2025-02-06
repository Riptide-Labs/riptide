package org.riptide.dns.netty;

import com.google.common.net.HostAndPort;
import io.netty.resolver.dns.DnsServerAddressStream;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import io.netty.resolver.dns.DnsServerAddressStreamProviders;
import io.netty.resolver.dns.SequentialDnsServerAddressStreamProvider;
import io.netty.util.internal.SocketUtils;
import org.riptide.config.enricher.HostnamesConfig;

import java.net.InetSocketAddress;
import java.util.Objects;

public class DefaultDnsServerAddressProvider implements DnsServerAddressStreamProvider {

    private final DnsServerAddressStreamProvider provider;

    public DefaultDnsServerAddressProvider(HostnamesConfig config) {
        Objects.requireNonNull(config);
        if (config.getNameservers().isEmpty()) {
            this.provider = DnsServerAddressStreamProviders.platformDefault();
        } else {
            final var socketAddresses = config.getNameservers()
                    .stream().map(it -> {
                        final HostAndPort hp = HostAndPort.fromString(it.trim())
                                .withDefaultPort(53)
                                .requireBracketsForIPv6();
                        return SocketUtils.socketAddress(hp.getHost(), hp.getPort());
                    }).toList().toArray(new InetSocketAddress[0]);
            this.provider = new SequentialDnsServerAddressStreamProvider(socketAddresses);
        }
    }

    @Override
    public DnsServerAddressStream nameServerAddressStream(String hostname) {
        return this.provider.nameServerAddressStream(hostname);
    }
}
