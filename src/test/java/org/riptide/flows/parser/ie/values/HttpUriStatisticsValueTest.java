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
 * Cisco AVC HTTP URI statistics (PEN 9 / 9357): repeating {@code URI NUL count} pairs, the count
 * two bytes big-endian, no trailing delimiter. The Catalyst 8000V reference capture carries one
 * pair per record; the layout allows more, and one column holds one URI.
 */
class HttpUriStatisticsValueTest {

    private static Value<?> parse(final byte[] bytes) throws Exception {
        return HttpUriStatisticsValue.parser("httpUri", null, null).parse(null, Unpooled.wrappedBuffer(bytes));
    }

    /** {@code uri NUL hi lo}, the encoding the capture confirmed. */
    private static byte[] pair(final String uri, final int hits) {
        final byte[] text = uri.getBytes(StandardCharsets.US_ASCII);
        final byte[] out = new byte[text.length + 3];
        System.arraycopy(text, 0, out, 0, text.length);
        out[text.length] = 0;
        out[text.length + 1] = (byte) (hits >>> 8);
        out[text.length + 2] = (byte) hits;
        return out;
    }

    private static byte[] concat(final byte[]... parts) {
        final ByteBuf joined = Unpooled.wrappedBuffer(parts);
        final byte[] out = new byte[joined.readableBytes()];
        joined.readBytes(out);
        return out;
    }

    @Test
    void onePairYieldsItsUri() throws Exception {
        assertThat(parse(new byte[]{0x2f, 0x00, 0x00, 0x01}).getValue())
                .as("the capture's value for '/'")
                .isEqualTo("/");
    }

    @Test
    void severalPairsPickTheHighestCount() throws Exception {
        assertThat(parse(concat(pair("/api", 2), pair("/static", 5))).getValue()).isEqualTo("/static");
    }

    @Test
    void aTieKeepsTheFirstPair() throws Exception {
        assertThat(parse(concat(pair("/api", 3), pair("/static", 3))).getValue()).isEqualTo("/api");
    }

    @Test
    void theCountIsBigEndian() throws Exception {
        final byte[] field = concat(
                new byte[]{'/', 'a', 0, 0x01, 0x00},
                new byte[]{'/', 'b', 0, 0x00, 0x02});

        assertThat(parse(field).getValue())
                .as("01 00 is 256, not 1")
                .isEqualTo("/a");
    }

    @Test
    void aMalformedTailIsIgnored() throws Exception {
        assertThat(parse(concat(pair("/", 1), new byte[]{'/', 'x'})).getValue()).isEqualTo("/");
    }

    @Test
    void aPairMissingItsCountIsIgnored() throws Exception {
        assertThat(parse(concat(pair("/", 1), new byte[]{'/', 'x', 0, 0x00})).getValue()).isEqualTo("/");
    }

    @Test
    void anEmptyFieldMeansNoUri() throws Exception {
        assertThat(parse(new byte[0]).getValue()).isEqualTo("");
    }

    @Test
    void aFieldWithNoCompletePairMeansNoUri() throws Exception {
        assertThat(parse(new byte[]{'/', 'a', 'p', 'i'}).getValue()).isEqualTo("");
    }

    @Test
    void anEmptyUriIsAPair() throws Exception {
        assertThat(parse(concat(pair("", 9), pair("/api", 1))).getValue())
                .as("the empty URI outranks '/api' on count")
                .isEqualTo("");
    }

    @Test
    void theFieldIsConsumedWhateverItsShape() throws Exception {
        final ByteBuf buffer = Unpooled.wrappedBuffer(concat(pair("/", 1), new byte[]{'/', 'x'}));

        HttpUriStatisticsValue.parser("httpUri", null, null).parse(null, buffer);

        assertThat(buffer.readableBytes()).isZero();
    }

    @Test
    void theValueIsAStringValueNamedForBinding() throws Exception {
        final Value<?> value = parse(pair("/", 1));

        assertThat(value).isInstanceOf(StringValue.class);
        assertThat(value.getName()).isEqualTo("httpUri");
    }
}
