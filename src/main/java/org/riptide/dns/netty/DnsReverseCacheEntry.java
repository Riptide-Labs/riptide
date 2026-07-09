/*
 * Copyright 2025 Ronny Trommer <ronny@no42.org>
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
}
