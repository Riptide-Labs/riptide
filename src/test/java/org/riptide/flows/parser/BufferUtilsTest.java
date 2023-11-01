package org.riptide.flows.parser;

import org.assertj.core.api.Assertions;
import com.google.common.io.BaseEncoding;
import com.google.common.primitives.UnsignedLong;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.riptide.flows.utils.BufferUtils.sint;
import static org.riptide.flows.utils.BufferUtils.uint32;
import static org.riptide.flows.utils.BufferUtils.uint64;
import static org.riptide.flows.utils.BufferUtils.sfloat;
import static org.riptide.flows.utils.BufferUtils.uint;
import static org.riptide.flows.utils.BufferUtils.uint8;
import static org.riptide.flows.utils.BufferUtils.uint16;
import static org.riptide.flows.utils.BufferUtils.uint24;


public class BufferUtilsTest {

    @Test
    void verifySignedInteger() throws Exception {
        Assertions.assertThat(Long.valueOf(0)).isEqualTo(sint(from("000000"), 3));
        Assertions.assertThat(Long.valueOf(-1)).isEqualTo(sint(from("FFFFFF"), 3));
        Assertions.assertThat(Long.valueOf(-2)).isEqualTo(sint(from("FFFFFE"), 3));
        Assertions.assertThat(Long.valueOf(1)).isEqualTo(sint(from("000001"), 3));
        Assertions.assertThat(Long.valueOf(2)).isEqualTo(sint(from("000002"), 3));
    }

    @Test
    void verifyUnsignedInteger32() throws Exception {
        Assertions.assertThat(0L).isEqualTo(uint32(from("00000000")));
        Assertions.assertThat(1L).isEqualTo(uint32(from("00000001")));
        Assertions.assertThat(1024L).isEqualTo(uint32(from("00000400")));
        Assertions.assertThat(65536L - 1).isEqualTo(uint32(from("0000FFFF")));
        Assertions.assertThat(65536L * 65536L - 1L).isEqualTo(uint32(from("FFFFFFFF")));
    }

    @Test
    void verifyUnsignedInteger64() throws Exception {
        Assertions.assertThat(UnsignedLong.valueOf(0L)).isEqualTo(uint64(from("0000000000000000")));
        Assertions.assertThat(UnsignedLong.valueOf(1L)).isEqualTo(uint64(from("0000000000000001")));
        Assertions.assertThat(UnsignedLong.valueOf(1024L)).isEqualTo(uint64(from("0000000000000400")));
        Assertions.assertThat(UnsignedLong.valueOf(65536L - 1L)).isEqualTo(uint64(from("000000000000FFFF")));
        Assertions.assertThat(UnsignedLong.valueOf(65536L * 65536L - 1L)).isEqualTo(uint64(from("00000000FFFFFFFF")));
        Assertions.assertThat(UnsignedLong.MAX_VALUE).isEqualTo(uint64(from("FFFFFFFFFFFFFFFF")));
    }

    @Test
    void verifySignedFloat() throws Exception {
        Assertions.assertThat(1.47F).isEqualTo(sfloat(from("3FBC28F6")));
        Assertions.assertThat(-1.47F).isEqualTo(sfloat(from("BFBC28F6")));
        Assertions.assertThat(0.0F).isEqualTo(sfloat(from("00000000")));
    }

    @Test
    void verifyUnsigned() throws Exception {
        // This is random data chosen from the serial number of the finger print of the LDAP server of the university on the applied science of the fulda
        Assertions.assertThat(UnsignedLong.valueOf(0x20L)).isEqualTo(uint(from("207138408FABED99"), 1));
        Assertions.assertThat(UnsignedLong.valueOf(0x2071L)).isEqualTo(uint(from("207138408FABED99"), 2));
        Assertions.assertThat(UnsignedLong.valueOf(0x207138L)).isEqualTo(uint(from("207138408FABED99"), 3));
        Assertions.assertThat(UnsignedLong.valueOf(0x20713840L)).isEqualTo(uint(from("207138408FABED99"), 4));
        Assertions.assertThat(UnsignedLong.valueOf(0x207138408fL)).isEqualTo(uint(from("207138408FABED99"), 5));
        Assertions.assertThat(UnsignedLong.valueOf(0x207138408fabL)).isEqualTo(uint(from("207138408FABED99"), 6));
        Assertions.assertThat(UnsignedLong.valueOf(0x207138408fabedL)).isEqualTo(uint(from("207138408FABED99"), 7));
        Assertions.assertThat(UnsignedLong.valueOf(0x207138408fabed99L)).isEqualTo(uint(from("207138408FABED99"), 8));

        Assertions.assertThat(uint8(from("207138408FABED99"))).isEqualTo(uint(from("207138408FABED99"), 1).intValue());
        Assertions.assertThat(uint16(from("207138408FABED99"))).isEqualTo(uint(from("207138408FABED99"), 2).intValue());
        Assertions.assertThat(uint24(from("207138408FABED99"))).isEqualTo(uint(from("207138408FABED99"), 3).intValue());
        Assertions.assertThat(uint32(from("207138408FABED99"))).isEqualTo(uint(from("207138408FABED99"), 4).intValue());
        Assertions.assertThat(uint64(from("207138408FABED99"))).isEqualTo(uint(from("207138408FABED99"), 8));
    }

    private static ByteBuf from(final String hex) {
        return Unpooled.wrappedBuffer(BaseEncoding.base16().decode(hex));
    }
}
