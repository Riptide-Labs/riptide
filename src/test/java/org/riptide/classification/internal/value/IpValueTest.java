package org.riptide.classification.internal.value;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.riptide.classification.IpAddr;
import org.riptide.utils.Tuple;

import java.util.stream.Stream;

public class IpValueTest {

    @TestFactory
    Stream<DynamicTest> verifyRangedValues() {
        final var ipValue = IpValue.of("10.1.1.1-10.1.1.100");
        return IpRange.of("10.1.1.1", "10.1.1.100")
                .stream()
                .map(ipAddress -> DynamicTest.dynamicTest("Verify ip address %s is in rage of %s".formatted(ipAddress, ipValue),
                        () -> Assertions.assertThat(ipValue.isInRange(ipAddress)).isTrue()));
    }

    @Test
    void verifySingleValue() {
        final IpValue ipValue = IpValue.of("192.168.0.1");
        Assertions.assertThat(ipValue.isInRange("192.168.0.0")).isFalse();
        Assertions.assertThat(ipValue.isInRange("192.168.0.1")).isTrue();
        Assertions.assertThat(ipValue.isInRange("192.168.0.2")).isFalse();
    }

    @Test
    void verifyMultiValues() {
        final IpValue ipValue = IpValue.of("192.168.0.1, 192.168.0.2, 192.168.0.10");
        Assertions.assertThat(ipValue.isInRange("192.168.0.0")).isFalse();
        Assertions.assertThat(ipValue.isInRange("192.168.0.1")).isTrue();
        Assertions.assertThat(ipValue.isInRange("192.168.0.2")).isTrue();
        Assertions.assertThat(ipValue.isInRange("192.168.0.3")).isFalse();
        Assertions.assertThat(ipValue.isInRange("192.168.0.4")).isFalse();
        Assertions.assertThat(ipValue.isInRange("192.168.0.5")).isFalse();
        Assertions.assertThat(ipValue.isInRange("192.168.0.6")).isFalse();
        Assertions.assertThat(ipValue.isInRange("192.168.0.7")).isFalse();
        Assertions.assertThat(ipValue.isInRange("192.168.0.8")).isFalse();
        Assertions.assertThat(ipValue.isInRange("192.168.0.9")).isFalse();
        Assertions.assertThat(ipValue.isInRange("192.168.0.10")).isTrue();
    }

    @TestFactory
    Stream<DynamicTest> verifyParseCIDR() {
        return Stream.of(
                Tuple.of("192.168.23.0/24", IpRange.of("192.168.23.0", "192.168.23.255")),
                Tuple.of("192.168.42.23/22", IpRange.of("192.168.40.0", "192.168.43.255")),
                Tuple.of("192.168.23.42/31", IpRange.of("192.168.23.42", "192.168.23.43")),
                Tuple.of("192.168.23.42/32", IpRange.of("192.168.23.42", "192.168.23.42")),
                Tuple.of("fe80::243d:e3ff:fe31:7660/64", IpRange.of("fe80::", "fe80::ffff:ffff:ffff:ffff"))
        ).map(tuple -> DynamicTest.dynamicTest("Verify %s (CIDR) is in range of %s".formatted(tuple.first(), tuple.second()),
                () -> {
                    final var parsed = IpValue.parseCIDR(tuple.first());
                    Assertions.assertThat(parsed).isEqualTo(tuple.second());
                }));
    }

    @TestFactory
    Stream<DynamicTest> verifyCIDRValue() {
        final var stringvalue = "10.0.0.5,192.168.0.0/24";
        final IpValue ipValue = IpValue.of(stringvalue);
        return IpRange.of("192.168.0.0", "192.168.0.255")
                .stream()
                .map(ipAddress -> DynamicTest.dynamicTest("Verify %s is in range of %s".formatted(ipAddress, stringvalue), () -> Assertions.assertThat(ipValue.isInRange(ipAddress)).isTrue()));
        }

    @Test
    void verifyCidrValueOutside() {
        final IpValue ipValue = IpValue.of("10.0.0.5,192.168.0.0/24");
        Assertions.assertThat(ipValue.isInRange(IpAddr.of("192.168.1.0"))).isEqualTo(false);
        Assertions.assertThat(ipValue.isInRange(IpAddr.of("192.168.2.0"))).isEqualTo(false);
        Assertions.assertThat(ipValue.isInRange(IpAddr.of("10.0.0.0"))).isEqualTo(false);
        Assertions.assertThat(ipValue.isInRange(IpAddr.of("10.0.0.1"))).isEqualTo(false);
        Assertions.assertThat(ipValue.isInRange(IpAddr.of("10.0.0.2"))).isEqualTo(false);
        Assertions.assertThat(ipValue.isInRange(IpAddr.of("10.0.0.3"))).isEqualTo(false);
        Assertions.assertThat(ipValue.isInRange(IpAddr.of("10.0.0.4"))).isEqualTo(false);
        Assertions.assertThat(ipValue.isInRange(IpAddr.of("10.0.0.5"))).isEqualTo(true);
        Assertions.assertThat(ipValue.isInRange(IpAddr.of("10.0.0.7"))).isEqualTo(false);
        Assertions.assertThat(ipValue.isInRange(IpAddr.of("10.0.0.8"))).isEqualTo(false);
        Assertions.assertThat(ipValue.isInRange(IpAddr.of("10.0.0.9"))).isEqualTo(false);
        Assertions.assertThat(ipValue.isInRange(IpAddr.of("10.0.0.10"))).isEqualTo(false);
    }

    @Test
    void verifyCIDRValue_2() {
        final IpValue ipValue = IpValue.of("192.168.0.17/16");
        for (var ipAddress : IpRange.of("192.168.0.0", "192.168.255.255")) {
            Assertions.assertThat(ipValue.isInRange(ipAddress)).isTrue();
        }
        Assertions.assertThat(ipValue.isInRange("192.169.0.0")).isFalse();
        Assertions.assertThat(ipValue.isInRange("192.0.0.0")).isFalse();
    }

    @Test
    void verifyCIDRValueNotAllowedInRange() {
        Assertions.assertThatThrownBy(() -> IpValue.of("192.0.0.0/8-192.168.0.0/24")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifyWildcard() {
        Assertions.assertThatThrownBy(() -> IpValue.of("*")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifyInvalidIpAddress() {
        Assertions.assertThatThrownBy(() -> IpValue.of("300.400.500.600")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifyInvalidIpAddressRanges() {
        Assertions.assertThatThrownBy(() -> IpValue.of("192.168.0.1-a.b.c.d")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifyInvalidIpAddressRangeEndIsBefore() {
        Assertions.assertThatThrownBy(() -> IpValue.of("192.168.10.255-192.168.0.1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifySingleValueIpV6() {
        final IpValue value = IpValue.of("2001:0DB8:0:CD30::1");
        Assertions.assertThat(value.isInRange(IpAddr.of("2001:0DB8:0:CD30::1"))).isTrue();
        Assertions.assertThat(value.isInRange(IpAddr.of("2001:0DB8:0:CD30::2"))).isFalse();
        Assertions.assertThat(value.isInRange(IpAddr.of("192.168.0.1"))).isFalse(); // incompatible, should be false
    }


    @Test
    public void verifyRangedValueIpV6() {
        final IpValue value = IpValue.of("2001:0DB8:0:CD30::1-2001:0DB8:0:CD30::FFFF");
        for (var address : IpRange.of("2001:0DB8:0:CD30::1", "2001:0DB8:0:CD30::FFFF")) {
            Assertions.assertThat(value.isInRange(address)).isTrue();
        }
    }

    @Test
    public void verifyCIDRValueIpV6() {
        final IpValue value = IpValue.of("2001:0DB8:0:CD30::1/120");
        for (var ipAddress : IpRange.of("2001:0DB8:0:CD30::0", "2001:0DB8:0:CD30::FF")) {
            Assertions.assertThat(value.isInRange(ipAddress)).isTrue();
        }
        Assertions.assertThat(value.isInRange(IpAddr.of("192.168.0.1"))).isFalse(); // incompatible, should be false
    }

    @Test
    public void verifyCIDRValueIpV6_2() {
        final IpValue value = IpValue.of("2001:0DB8:0:CD30::1/127");
        for (var ipAddress : IpRange.of("2001:0DB8:0:CD30::0", "2001:0DB8:0:CD30::1")) {
            Assertions.assertThat(value.isInRange(ipAddress)).isTrue();
        }
        Assertions.assertThat(value.isInRange(IpAddr.of("2001:0DB8:0:CD30::2"))).isFalse();
    }
}
