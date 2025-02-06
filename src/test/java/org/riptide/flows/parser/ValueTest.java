package org.riptide.flows.parser;

import io.netty.buffer.Unpooled;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ie.values.BooleanValue;
import org.riptide.flows.parser.ie.values.DateTimeValue;
import org.riptide.flows.parser.ie.values.FloatValue;
import org.riptide.flows.parser.ie.values.IPv4AddressValue;
import org.riptide.flows.parser.ie.values.IPv6AddressValue;
import org.riptide.flows.parser.ie.values.MacAddressValue;
import org.riptide.flows.parser.ie.values.OctetArrayValue;
import org.riptide.flows.parser.ie.values.SignedValue;
import org.riptide.flows.parser.ie.values.StringValue;
import org.riptide.flows.parser.ie.values.UnsignedValue;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ValueTest {

    // TODO fooker: Test BasicList, SubTemplateList, SubTemplateMultiList

    @Test
    void verifyParsingBooleanValue() throws Exception {
        final var bTrue = (BooleanValue) BooleanValue.parser("booleanName", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{1}));
        Assertions.assertThat(bTrue.getValue()).isTrue();
        Assertions.assertThat(bTrue.getName()).isEqualTo("booleanName");

        final var bFalse = (BooleanValue) BooleanValue.parser("booleanName", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{2}));
        Assertions.assertThat(bFalse.getValue()).isFalse();
        Assertions.assertThat(bFalse.getName()).isEqualTo("booleanName");
    }

    @Test
    void verifyParsingBooleanValueInvalid() throws Exception {
        Assertions.assertThatThrownBy(() -> {
            BooleanValue.parser("booleanName", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{3}));
        }).isInstanceOf(InvalidPacketException.class);
    }

    @Test
    void verifyDateTimeMicrosecondsValue() throws Exception {
        final var dateTimeMicrosecondsValue1 = (DateTimeValue) DateTimeValue.parserWithMicroseconds("dateTimeMicrosecondsName1", null, null).parse(null, Unpooled.wrappedBuffer(new byte[] {0, 0, 0, 0, 0, 0, 0, 0}));
        assertEquals(Instant.from(ZonedDateTime.of(1900, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)), dateTimeMicrosecondsValue1.getValue());
        assertEquals("dateTimeMicrosecondsName1", dateTimeMicrosecondsValue1.getName());

        final var dateTimeMicrosecondsName2 = (DateTimeValue) DateTimeValue.parserWithMicroseconds("dateTimeMicrosecondsName2", null, null).parse(null, Unpooled.buffer(8).writeInt((int) (1509533996L + DateTimeValue.SECONDS_TO_EPOCH)).writeInt(0));
        assertEquals(Instant.from(ZonedDateTime.of(2017, 11, 1, 10, 59, 56, 0, ZoneOffset.UTC)), dateTimeMicrosecondsName2.getValue());
        assertEquals("dateTimeMicrosecondsName2", dateTimeMicrosecondsName2.getName());

        final var dateTimeMicrosecondsName3 = (DateTimeValue) DateTimeValue.parserWithMicroseconds("dateTimeMicrosecondsName3", null, null).parse(null, Unpooled.buffer(8).writeInt((int) (1509533996L + DateTimeValue.SECONDS_TO_EPOCH)).writeInt(16775168));
        assertEquals(Instant.from(ZonedDateTime.of(2017, 11, 1, 10, 59, 56, 3905773, ZoneOffset.UTC)), dateTimeMicrosecondsName3.getValue());
        assertEquals("dateTimeMicrosecondsName3", dateTimeMicrosecondsName3.getName());

        final var dateTimeMicrosecondsName4 = (DateTimeValue) DateTimeValue.parserWithMicroseconds("dateTimeMicrosecondsName4", null, null).parse(null, Unpooled.buffer(8).writeInt((int) (1509533996L + DateTimeValue.SECONDS_TO_EPOCH)).writeInt(16775169));
        assertEquals(Instant.from(ZonedDateTime.of(2017, 11, 1, 10, 59, 56, 3905773, ZoneOffset.UTC)), dateTimeMicrosecondsName4.getValue());
        assertEquals("dateTimeMicrosecondsName4", dateTimeMicrosecondsName4.getName());
    }

    @Test
    void verifyDateTimeMillisecondsValue() throws Exception {
        final var dateTimeMillisecondsValue1 = (DateTimeValue) DateTimeValue.parserWithMilliseconds("dateTimeMillisecondsName1", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 0, 0, 0, 0, 0, 0, 0}));
        assertEquals(Instant.ofEpochMilli(0), dateTimeMillisecondsValue1.getValue());
        assertEquals("dateTimeMillisecondsName1", dateTimeMillisecondsValue1.getName());

        final var dateTimeMillisecondsValue2 = (DateTimeValue) DateTimeValue.parserWithMilliseconds("dateTimeMillisecondsName2", null, null).parse(null, Unpooled.buffer(8).writeLong(1509532300714L));
        assertEquals(Instant.ofEpochMilli(1509532300714L), dateTimeMillisecondsValue2.getValue());
        assertEquals("dateTimeMillisecondsName2", dateTimeMillisecondsValue2.getName());
    }

    @Test
    void verifyDateTimeNanosecondsValue() throws Exception {
        final var dateTimeNanosecondsValue1 = (DateTimeValue) DateTimeValue.parserWithNanoseconds("dateTimeNanosecondsName1", null, null).parse(null, Unpooled.wrappedBuffer(new byte[] {0, 0, 0, 0, 0, 0, 0, 0}));
        assertEquals(Instant.from(ZonedDateTime.of(1900, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)), dateTimeNanosecondsValue1.getValue());
        assertEquals("dateTimeNanosecondsName1", dateTimeNanosecondsValue1.getName());

        final var dateTimeNanosecondsValue2 = (DateTimeValue) DateTimeValue.parserWithNanoseconds("dateTimeNanosecondsName2", null, null).parse(null, Unpooled.buffer(8).writeInt((int) (1509533996L + DateTimeValue.SECONDS_TO_EPOCH)).writeInt(0));
        assertEquals(Instant.from(ZonedDateTime.of(2017, 11, 1, 10, 59, 56, 0, ZoneOffset.UTC)), dateTimeNanosecondsValue2.getValue());
        assertEquals("dateTimeNanosecondsName2", dateTimeNanosecondsValue2.getName());

        final var dateTimeNanosecondsValue3 = (DateTimeValue) DateTimeValue.parserWithNanoseconds("dateTimeNanosecondsName3", null, null).parse(null, Unpooled.buffer(8).writeInt((int) (1509533996L + DateTimeValue.SECONDS_TO_EPOCH)).writeInt(34509786));
        assertEquals(Instant.from(ZonedDateTime.of(2017, 11, 1, 10, 59, 56, 8034935, ZoneOffset.UTC)), dateTimeNanosecondsValue3.getValue());
        assertEquals("dateTimeNanosecondsName3", dateTimeNanosecondsValue3.getName());
    }

    @Test
    void verifyFloat32Value() throws Exception {
        final var float32Value1 = (FloatValue) FloatValue.parserWith32Bit("float32Name1", null, null).parse(null, Unpooled.buffer(4).writeFloat(0));
        assertEquals(0f, float32Value1.getValue(), 0);
        assertEquals("float32Name1", float32Value1.getName());

        final var float32Value2 = (FloatValue) FloatValue.parserWith32Bit("float32Name2", null, null).parse(null, Unpooled.buffer(4).writeFloat(123.456f));
        assertEquals(123.456f, float32Value2.getValue(), 0);
        assertEquals("float32Name2", float32Value2.getName());

        assertEquals(1.4E-45f, ((FloatValue) FloatValue.parserWith32Bit("float32Name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 0, 0, 1}))).getValue(), 0);
        assertEquals(1.4E-45f, ((FloatValue) FloatValue.parserWith32Bit("float32Name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 0, 1}))).getValue(), 0);
        assertEquals(1.4E-45f, ((FloatValue) FloatValue.parserWith32Bit("float32Name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 1}))).getValue(), 0);
        assertEquals(1.4E-45f, ((FloatValue) FloatValue.parserWith32Bit("float32Name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{1}))).getValue(), 0);
    }

    @Test
    void verifyFloat64Value() throws Exception {
        final var float64Value1 = (FloatValue) FloatValue.parserWith64Bit("float64Name1", null, null).parse(null, Unpooled.buffer(8).writeDouble(0));
        assertEquals(0f, float64Value1.getValue(), 0);
        assertEquals("float64Name1", float64Value1.getName());

        final var float64Value2 = (FloatValue) FloatValue.parserWith64Bit("float64Name2", null, null).parse(null, Unpooled.buffer(8).writeDouble(123.456));
        assertEquals(123.456, float64Value2.getValue(), 0);
        assertEquals("float64Name2", float64Value2.getName());

        assertEquals(4.9E-324, ((FloatValue) FloatValue.parserWith64Bit("float64Name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 0, 0, 0, 0, 0, 0, 1}))).getValue(), 0);
        assertEquals(4.9E-324, ((FloatValue) FloatValue.parserWith64Bit("float64Name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 0, 0, 0, 0, 0, 1}))).getValue(), 0);
        assertEquals(4.9E-324, ((FloatValue) FloatValue.parserWith64Bit("float64Name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 0, 0, 0, 0, 1}))).getValue(), 0);
        assertEquals(4.9E-324, ((FloatValue) FloatValue.parserWith64Bit("float64Name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 0, 0, 0, 1}))).getValue(), 0);
        assertEquals(4.9E-324, ((FloatValue) FloatValue.parserWith64Bit("float64Name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 0, 0, 1}))).getValue(), 0);
        assertEquals(4.9E-324, ((FloatValue) FloatValue.parserWith64Bit("float64Name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 0, 1}))).getValue(), 0);
        assertEquals(4.9E-324, ((FloatValue) FloatValue.parserWith64Bit("float64Name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 1}))).getValue(), 0);
        assertEquals(4.9E-324, ((FloatValue) FloatValue.parserWith64Bit("float64Name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{1}))).getValue(), 0);
    }

    @Test
    void verifyIPv4AddressValue() throws Exception {
        final var ipv4AddressValue = (IPv4AddressValue) IPv4AddressValue.parser("ipv4AddressName", null, null).parse(null, Unpooled.wrappedBuffer(InetAddress.getByName("127.0.0.1").getAddress()));
        assertEquals("ipv4AddressName", ipv4AddressValue.getName());
        assertEquals("127.0.0.1", ipv4AddressValue.getValue().getHostAddress());
    }

    @Test
    void verifyIPv6AddressValue() throws Exception {
        final var ipv6AddressValue = (IPv6AddressValue) IPv6AddressValue.parser("ipv6AddressName", null, null).parse(null, Unpooled.wrappedBuffer(InetAddress.getByName("2001:638:301:11a0:d498:3253:ca5f:3777").getAddress()));
        assertEquals("ipv6AddressName", ipv6AddressValue.getName());
        assertEquals("2001:638:301:11a0:d498:3253:ca5f:3777", ipv6AddressValue.getValue().getHostAddress());
    }

    @Test
    void verifyMacAddressValue() throws Exception {
        final var macAddressValue = (MacAddressValue) MacAddressValue.parser("macAddressName", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{1, 2, 4, 8, 16, 32}));
        assertEquals("macAddressName", macAddressValue.getName());
        assertArrayEquals(new byte[]{1, 2, 4, 8, 16, 32}, macAddressValue.getValue());
    }

    @Test
    void verifyOctetArrayValue() throws Exception {
        final var octetArrayValue = (OctetArrayValue) OctetArrayValue.parser("octetArrayName", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{1, 2, 4, 8, 16}));
        assertEquals("octetArrayName", octetArrayValue.getName());
        assertArrayEquals(new byte[]{1, 2, 4, 8, 16}, octetArrayValue.getValue());
    }

    @Test
    void verifySigned64Value() throws Exception {
        final var v1 = (SignedValue) SignedValue.parserWith64Bit("name1", null, null).parse(null, Unpooled.buffer(8).writeLong(0));
        assertEquals(0, v1.getValue(), 0);
        assertEquals("name1", v1.getName());

        final var v2 = (SignedValue) SignedValue.parserWith64Bit("name2", null, null).parse(null, Unpooled.buffer(8).writeLong(42));
        assertEquals(42, v2.getValue(), 0);
        assertEquals("name2", v2.getName());

        final var v3 = (SignedValue) SignedValue.parserWith64Bit("name3", null, null).parse(null, Unpooled.buffer(8).writeLong(-42));
        assertEquals(-42, v3.getValue(), 0);
        assertEquals("name3", v3.getName());

        assertEquals(1, ((SignedValue) SignedValue.parserWith64Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 0, 0, 0, 0, 0, 0, 1}))).getValue(), 0);
        assertEquals(1, ((SignedValue) SignedValue.parserWith64Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 0, 0, 0, 0, 0, 1}))).getValue(), 0);
        assertEquals(1, ((SignedValue) SignedValue.parserWith64Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 0, 0, 0, 0, 1}))).getValue(), 0);
        assertEquals(1, ((SignedValue) SignedValue.parserWith64Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 0, 0, 0, 1}))).getValue(), 0);
        assertEquals(1, ((SignedValue) SignedValue.parserWith64Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 0, 0, 1}))).getValue(), 0);
        assertEquals(1, ((SignedValue) SignedValue.parserWith64Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 0, 1}))).getValue(), 0);
        assertEquals(1, ((SignedValue) SignedValue.parserWith64Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 1}))).getValue(), 0);
        assertEquals(1, ((SignedValue) SignedValue.parserWith64Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{1}))).getValue(), 0);
    }

    @Test
    void verifySigned32Value() throws Exception {
        final var v1 = (SignedValue) SignedValue.parserWith32Bit("name1", null, null).parse(null, Unpooled.buffer(4).writeInt(0));
        assertEquals(0, v1.getValue(), 0);
        assertEquals("name1", v1.getName());

        final var v2 = (SignedValue) SignedValue.parserWith32Bit("name2", null, null).parse(null, Unpooled.buffer(4).writeInt(42));
        assertEquals(42, v2.getValue(), 0);
        assertEquals("name2", v2.getName());

        final var v3 = (SignedValue) SignedValue.parserWith32Bit("name3", null, null).parse(null, Unpooled.buffer(4).writeInt(-42));
        assertEquals(-42, v3.getValue(), 0);
        assertEquals("name3", v3.getName());

        assertEquals(1, ((SignedValue) SignedValue.parserWith32Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 0, 0, 1}))).getValue(), 0);
        assertEquals(1, ((SignedValue) SignedValue.parserWith32Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 0, 1}))).getValue(), 0);
        assertEquals(1, ((SignedValue) SignedValue.parserWith32Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 1}))).getValue(), 0);
        assertEquals(1, ((SignedValue) SignedValue.parserWith32Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{1}))).getValue(), 0);
    }

    @Test
    void verifySigned16Value() throws Exception {
        final var v1 = (SignedValue) SignedValue.parserWith16Bit("name1", null, null).parse(null, Unpooled.buffer(2).writeShort((short) 0));
        assertEquals(0, v1.getValue(), 0);
        assertEquals("name1", v1.getName());

        final var v2 = (SignedValue) SignedValue.parserWith16Bit("name2", null, null).parse(null, Unpooled.buffer(2).writeShort((short) 42));
        assertEquals(42, v2.getValue(), 0);
        assertEquals("name2", v2.getName());

        final var v3 = (SignedValue) SignedValue.parserWith16Bit("name3", null, null).parse(null, Unpooled.buffer(2).writeShort((short) -42));
        assertEquals(-42, v3.getValue(), 0);
        assertEquals("name3", v3.getName());

        assertEquals(1, ((SignedValue) SignedValue.parserWith16Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 1}))).getValue(), 0);
        assertEquals(1, ((SignedValue) SignedValue.parserWith16Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{1}))).getValue(), 0);
    }

    @Test
    void verifySigned8Value() throws Exception {
        final var v1 = (SignedValue) SignedValue.parserWith8Bit("name1", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0}));
        assertEquals(0, v1.getValue(), 0);
        assertEquals("name1", v1.getName());

        final var v2 = (SignedValue) SignedValue.parserWith8Bit("name2", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{42}));
        assertEquals(42, v2.getValue(), 0);
        assertEquals("name2", v2.getName());

        final var v3 = (SignedValue) SignedValue.parserWith8Bit("name3", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{-42}));
        assertEquals(-42, v3.getValue(), 0);
        assertEquals("name3", v3.getName());
    }

    @Test
    void verifyUnsigned64Value() throws Exception {
        final var v1 = (UnsignedValue) UnsignedValue.parserWith64Bit("name1", null, null).parse(null, Unpooled.buffer(8).writeLong(0));
        assertEquals(0L, v1.getValue().longValue());
        assertEquals("name1", v1.getName());

        final var v2 = (UnsignedValue) UnsignedValue.parserWith64Bit("name2", null, null).parse(null, Unpooled.buffer(8).writeLong(42));
        assertEquals(42L, v2.getValue().longValue());
        assertEquals("name2", v2.getName());

        assertEquals(1L, ((UnsignedValue) UnsignedValue.parserWith64Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 0, 0, 0, 0, 0, 0, 1}))).getValue().longValue(), 0);
        assertEquals(1L, ((UnsignedValue) UnsignedValue.parserWith64Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 0, 0, 0, 0, 0, 1}))).getValue().longValue(), 0);
        assertEquals(1L, ((UnsignedValue) UnsignedValue.parserWith64Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 0, 0, 0, 0, 1}))).getValue().longValue(), 0);
        assertEquals(1L, ((UnsignedValue) UnsignedValue.parserWith64Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 0, 0, 0, 1}))).getValue().longValue(), 0);
        assertEquals(1L, ((UnsignedValue) UnsignedValue.parserWith64Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 0, 0, 1}))).getValue().longValue(), 0);
        assertEquals(1L, ((UnsignedValue) UnsignedValue.parserWith64Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 0, 1}))).getValue().longValue(), 0);
        assertEquals(1L, ((UnsignedValue) UnsignedValue.parserWith64Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 1}))).getValue().longValue(), 0);
        assertEquals(1L, ((UnsignedValue) UnsignedValue.parserWith64Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{1}))).getValue().longValue(), 0);
    }

    @Test
    void verifyUnsigned32Value() throws Exception {
        final var v1 = (UnsignedValue) UnsignedValue.parserWith32Bit("name1", null, null).parse(null, Unpooled.buffer(4).writeInt(0));
        assertEquals(0L, v1.getValue().longValue());
        assertEquals("name1", v1.getName());

        final var v2 = (UnsignedValue) UnsignedValue.parserWith32Bit("name2", null, null).parse(null, Unpooled.buffer(4).writeInt(42));
        assertEquals(42L, v2.getValue().longValue());
        assertEquals("name2", v2.getName());

        assertEquals(1L, ((UnsignedValue) UnsignedValue.parserWith32Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 0, 0, 1}))).getValue().longValue(), 0);
        assertEquals(1L, ((UnsignedValue) UnsignedValue.parserWith32Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 0, 1}))).getValue().longValue(), 0);
        assertEquals(1L, ((UnsignedValue) UnsignedValue.parserWith32Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 1}))).getValue().longValue(), 0);
        assertEquals(1L, ((UnsignedValue) UnsignedValue.parserWith32Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{1}))).getValue().longValue(), 0);
    }

    @Test
    void verifyUnsigned16Value() throws Exception {
        final var v1 = (UnsignedValue) UnsignedValue.parserWith16Bit("name1", null, null).parse(null, Unpooled.buffer(2).writeShort((short) 0));
        assertEquals(0L, v1.getValue().longValue());
        assertEquals("name1", v1.getName());

        final var v2 = (UnsignedValue) UnsignedValue.parserWith16Bit("name2", null, null).parse(null, Unpooled.buffer(2).writeShort((short) 42));
        assertEquals(42L, v2.getValue().longValue());
        assertEquals("name2", v2.getName());

        assertEquals(1L, ((UnsignedValue) UnsignedValue.parserWith16Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0, 1}))).getValue().longValue(), 0);
        assertEquals(1L, ((UnsignedValue) UnsignedValue.parserWith16Bit("name", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{1}))).getValue().longValue(), 0);
    }

    @Test
    void verifyUnsigned8Value() throws Exception {
        final var v1 = (UnsignedValue) UnsignedValue.parserWith8Bit("name1", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{0}));
        assertEquals(0L, v1.getValue().longValue());
        assertEquals("name1", v1.getName());

        final var v2 = (UnsignedValue) UnsignedValue.parserWith8Bit("name2", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{42}));
        assertEquals(42L, v2.getValue().longValue());
        assertEquals("name2", v2.getName());
    }

    @Test
    void verifyStringValue() throws Exception {
        final var v1 = (StringValue) StringValue.parser("name1", null, null).parse(null, Unpooled.wrappedBuffer("Hello World".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Hello World", v1.getValue());
        assertEquals("name1", v1.getName());

        final var v2 = (StringValue) StringValue.parser("name2", null, null).parse(null, Unpooled.wrappedBuffer("Foo".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Foo", v2.getValue());
        assertEquals("name2", v2.getName());

        final var v3 = (StringValue) StringValue.parser("name3", null, null).parse(null, Unpooled.wrappedBuffer(new byte[]{}));
        assertEquals("", v3.getValue());
        assertEquals("name3", v3.getName());
    }
}
