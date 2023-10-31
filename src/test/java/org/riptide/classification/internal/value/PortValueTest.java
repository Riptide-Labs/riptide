package org.riptide.classification.internal.value;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

public class PortValueTest {

    @Test
    void verifySingleValue() {
        final PortValue portValue = PortValue.of("5");
        Assertions.assertThat(portValue.matches(5)).isTrue();
        Assertions.assertThat(portValue.matches(1)).isFalse();
    }

    @Test
    void verifyMultipleValues() {
        final PortValue portValue = PortValue.of("1,2,3");
        Assertions.assertThat(portValue.matches(1)).isTrue();
        Assertions.assertThat(portValue.matches(2)).isTrue();
        Assertions.assertThat(portValue.matches(3)).isTrue();
        Assertions.assertThat(portValue.matches(4)).isFalse();
        Assertions.assertThat(portValue.matches(5)).isFalse();
    }

    @Test
    void verifyRange() {
        final PortValue portValue = PortValue.of("10-13");
        Assertions.assertThat(portValue.matches(10)).isTrue();
        Assertions.assertThat(portValue.matches(11)).isTrue();
        Assertions.assertThat(portValue.matches(12)).isTrue();
        Assertions.assertThat(portValue.matches(13)).isTrue();
        Assertions.assertThat(portValue.matches(1)).isFalse();
        Assertions.assertThat(portValue.matches(2)).isFalse();
        Assertions.assertThat(portValue.matches(3)).isFalse();
    }
}
