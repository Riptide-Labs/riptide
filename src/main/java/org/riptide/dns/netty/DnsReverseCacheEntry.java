/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.dns.netty;

import io.netty.handler.codec.dns.DnsPtrRecord;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;

@AllArgsConstructor
@Getter
public class DnsReverseCacheEntry {
    @NonNull
    private final String reverseMapName;
    @NonNull
    private final DnsPtrRecord record;
    @NonNull
    private final String cleanedHostname;
    /** Minimum TTL across the answer's records; a chained answer expires with its shortest link. */
    private final long effectiveTtlSeconds;
}
