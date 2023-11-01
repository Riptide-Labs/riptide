package org.riptide.dns.api;

import java.net.InetAddress;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous DNS resolution.
 *
 * @author jwhite
 */
public interface DnsResolver {

    /**
     * Perform a DNS lookup for the given hostname.
     *
     * Returns a future that contains the lookup results.
     * If the optional is empty the lookup was completed but no result was found.
     *
     * @param hostname hostname to lookup
     * @return a future
     */
    CompletableFuture<Optional<InetAddress>> lookup(String hostname);

    /**
     * Perform a reverse DNS lookup for the given IP address.
     *
     * Returns a future that contains the lookup results.
     * If the optional is empty the lookup was completed but no result was found.
     *
     * @param inetAddress IP address to lookup
     * @return a future
     */
    CompletableFuture<Optional<String>> reverseLookup(InetAddress inetAddress);

}
