/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.ie.values;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ie.Value;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cisco AVC HTTP host (PEN 9 / 12235) as the Catalyst 8000V reference capture shows it: the
 * four-byte http application id and the two-byte sub-application id, then the hostname. A record
 * with no host carries exactly the six bytes.
 */
class HttpHostValueTest {

    /** Spelled out rather than read from the class under test, so the two cannot drift together. */
    private static final byte[] PREFIX = {0x03, 0x00, 0x00, 0x50, 0x34, 0x02};

    private static Value<?> parse(final ByteBuf buffer) throws Exception {
        return HttpHostValue.parser("httpHost", null, null).parse(null, buffer);
    }

    private static ByteBuf prefixed(final byte[] tail) {
        return Unpooled.wrappedBuffer(PREFIX, tail);
    }

    @Test
    void theHostFollowsTheSixBytePrefix() throws Exception {
        assertThat(parse(prefixed("www.example.com".getBytes(StandardCharsets.US_ASCII))).getValue())
                .isEqualTo("www.example.com");
    }

    @Test
    void thePrefixAloneMeansNoHost() throws Exception {
        assertThat(parse(prefixed(new byte[0])).getValue())
                .as("the egress record of the capture carries exactly these six bytes")
                .isEqualTo("");
    }

    /** The prefix is matched, not skipped by length: a bare hostname must not lose six characters. */
    @Test
    void aFieldWithoutThePrefixIsStoredAsSent() throws Exception {
        assertThat(parse(Unpooled.wrappedBuffer("www.example.com".getBytes(StandardCharsets.US_ASCII))).getValue())
                .isEqualTo("www.example.com");
    }

    @Test
    void aDifferentPrefixIsVisibleNotStripped() throws Exception {
        final byte[] other = {0x03, 0x00, 0x00, 0x50, 0x34, 0x03, 'h'};

        assertThat(parse(Unpooled.wrappedBuffer(other)).getValue())
                .as("a mismatch stores the control bytes, which a reader can see, rather than a truncated name")
                .isEqualTo("\u0003\u0000\u0000P4\u0003h");
    }

    @Test
    void aShortFieldIsStoredAsSentAndConsumed() throws Exception {
        final ByteBuf buffer = Unpooled.wrappedBuffer(new byte[]{'a', 'b'});

        assertThat(parse(buffer).getValue()).isEqualTo("ab");
        assertThat(buffer.readableBytes()).as("a template with an odd length must not desync the record").isZero();
    }

    @Test
    void anEmptyFieldMeansNoHost() throws Exception {
        assertThat(parse(Unpooled.EMPTY_BUFFER).getValue()).isEqualTo("");
    }

    @Test
    void aMalformedByteIsReplacedNotRejected() throws Exception {
        assertThat(parse(prefixed(new byte[]{'a', (byte) 0xFF, 'b'})).getValue())
                .isEqualTo("a\uFFFDb");
    }

    @Test
    void theValueIsAStringValueNamedForBinding() throws Exception {
        final Value<?> value = parse(prefixed("h".getBytes(StandardCharsets.US_ASCII)));

        assertThat(value).isInstanceOf(StringValue.class);
        assertThat(value.getName()).isEqualTo("httpHost");
    }
}
