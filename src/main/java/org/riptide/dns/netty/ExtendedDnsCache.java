package org.riptide.dns.netty;

import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DnsPtrRecord;
import io.netty.resolver.dns.DnsCache;

/**
 * An extended {@link DnsCache} with support for storing PTR records
 * from reverse lookups.
 */
public interface ExtendedDnsCache extends DnsCache {

    ExtendedDnsCacheEntry cache(String hostname, DnsPtrRecord ptrRecord, EventLoop loop);

}
