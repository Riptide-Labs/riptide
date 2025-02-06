package org.riptide.dns.api;

import java.net.InetAddress;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/// Async DNS resolution.
public interface DnsResolver {

    /// Performs a reverse DNS lookup for the provided IP address.
    ///
    /// The returned future contains the lookup result.
    /// The optional may be empty if the lookup was successfully, but no result was found
    ///
    /// @param inetAddress the IP address to lookup
    /// @return completable future for aync support
    CompletableFuture<Optional<String>> reverseLookup(InetAddress inetAddress);

}
