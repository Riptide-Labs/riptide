/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.ie.values;

import com.google.common.primitives.UnsignedLong;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.Protocol;
import org.riptide.flows.parser.ie.InformationElementDatabase;
import org.riptide.flows.parser.ie.Value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * RFC 6759 §4.1: one byte of Classification Engine ID, then the Selector ID. The packed form
 * puts the engine in the top byte, which is what a Cisco application table uses as its scope and
 * what Wireshark shows.
 */
class ApplicationIdValueTest {

    private static Value<?> parse(final byte[] bytes) throws Exception {
        return ApplicationIdValue.parser("applicationId", null, null)
                .parse(null, Unpooled.wrappedBuffer(bytes));
    }

    @Test
    void fourByteIdPacksTheEngineIntoTheTopByte() throws Exception {
        assertThat(parse(new byte[]{3, 0, 0, 0x50}).getValue())
                .as("IANA-L4 (3), selector 80: http on a c8000v")
                .isEqualTo(UnsignedLong.valueOf(0x03000050L));
    }

    @Test
    void threeByteIdKeepsTheEngineInTheTopByte() throws Exception {
        assertThat(parse(new byte[]{3, 0, 0x50}).getValue())
                .as("a shorter selector must not slide the engine down a byte")
                .isEqualTo(UnsignedLong.valueOf(0x03000050L));
    }

    @Test
    void twoByteIdKeepsTheEngineInTheTopByte() throws Exception {
        assertThat(parse(new byte[]{13, 1}).getValue())
                .isEqualTo(UnsignedLong.valueOf(0x0d000001L));
    }

    @Test
    void oneByteIdYieldsZeroWithoutThrowing() {
        assertThatCode(() -> assertThat(parse(new byte[]{3}).getValue())
                .isEqualTo(UnsignedLong.ZERO))
                .doesNotThrowAnyException();
    }

    @Test
    void fiveByteIdYieldsZeroWithoutThrowing() {
        assertThatCode(() -> assertThat(parse(new byte[]{3, 0, 0, 0, 0x50}).getValue())
                .isEqualTo(UnsignedLong.ZERO))
                .doesNotThrowAnyException();
    }

    @Test
    void packedIsEngineShiftedAboveTheSelector() {
        assertThat(ApplicationIdValue.packed(13, 0x1df)).isEqualTo(0x0d0001dfL);
    }

    /** The IANA XML says octetArray for 95; the override must win or nothing downstream binds. */
    @Test
    void theRegistryServesTheTypedParserForIpfix95() throws Exception {
        final var element = InformationElementDatabase.instance.lookup(Protocol.IPFIX, 95).orElseThrow();

        final Value<?> value = element.parse(null, Unpooled.wrappedBuffer(new byte[]{3, 0, 0, 0x35}));

        assertThat(element.getName()).isEqualTo("applicationId");
        assertThat(value).isInstanceOf(UnsignedValue.class);
        assertThat(value.getValue()).isEqualTo(UnsignedLong.valueOf(0x03000035L));
    }

    /**
     * Cisco's v9 field list calls 95 {@code APPLICATION TAG}, an octet array; a name with a space
     * cannot bind to a raw-flow field and an octet array has no visitor, so the v9 registry must
     * serve the same typed parser under the same name as IPFIX.
     */
    @Test
    void theRegistryServesTheTypedParserForNetflow9Field95() throws Exception {
        final var element = InformationElementDatabase.instance.lookup(Protocol.NETFLOW9, 95).orElseThrow();

        final Value<?> value = element.parse(null, Unpooled.wrappedBuffer(new byte[]{1, 0, 0, 1}));

        assertThat(element.getName()).isEqualTo("applicationId");
        assertThat(value).isInstanceOf(UnsignedValue.class);
        assertThat(value.getValue()).as("IANA-L3 (1), selector 1: icmp").isEqualTo(UnsignedLong.valueOf(0x01000001L));
    }

    /** Only 95 changes name; the table matches the v9 names of 94 and 96 as they are. */
    @Test
    void theNetflow9NameAndDescriptionFieldsKeepTheirNames() throws Exception {
        final var name = InformationElementDatabase.instance.lookup(Protocol.NETFLOW9, 96).orElseThrow();
        final var description = InformationElementDatabase.instance.lookup(Protocol.NETFLOW9, 94).orElseThrow();

        assertThat(name.getName()).isEqualTo("APPLICATION NAME");
        assertThat(description.getName()).isEqualTo("APPLICATION DESCRIPTION");
        assertThat(name.parse(null, Unpooled.wrappedBuffer("egp\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII))))
                .isInstanceOf(StringValue.class);
        assertThat(description.parse(null, Unpooled.wrappedBuffer("EGP\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII))))
                .isInstanceOf(StringValue.class);
    }
}
