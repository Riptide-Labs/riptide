package org.riptide.dns.netty;

import io.netty.resolver.dns.DnsCacheEntry;

public interface ExtendedDnsCacheEntry extends DnsCacheEntry {

    /**
     * Hostname from the PTR record, or null if none is available.
     */
    String hostnameFromPtrRecord();

}
