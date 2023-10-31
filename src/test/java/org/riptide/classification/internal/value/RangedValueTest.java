package org.riptide.classification.internal.value;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

public class RangedValueTest {
    @Test
    void verifyRangedValues() {
        // Verify simple range
        RangedValue value = new RangedValue("80-100");
        RangedValue value2 = new RangedValue(new StringValue("80-100"));
        RangedValue value3 = new RangedValue(80, 100);
        for (int i = 80; i <= 100; i++) {
            Assertions.assertThat(value.isInRange(i)).isTrue();
            Assertions.assertThat(value2.isInRange(i)).isTrue();
            Assertions.assertThat(value3.isInRange(i)).isTrue();
        }
        for (int i = -1000; i <= 1000; i++) {
            if (i >= 80 && i <= 100) continue; // skip for in range
            Assertions.assertThat(value.isInRange(i)).isFalse();
            Assertions.assertThat(value2.isInRange(i)).isFalse();
            Assertions.assertThat(value3.isInRange(i)).isFalse();
        }

        // Verify if single value
        RangedValue singleValue = new RangedValue("80");
        Assertions.assertThat(singleValue.isInRange(80)).isTrue();
        Assertions.assertThat(singleValue.isInRange(79)).isFalse();
        Assertions.assertThat(singleValue.isInRange(81)).isFalse();

        // Verify multi range
        RangedValue rangedValue = new RangedValue("80-100-200");
        for (int i = 80; i <= 100; i++) {
            Assertions.assertThat(rangedValue.isInRange(i)).isTrue();
        }
    }

    @TestFactory
    Stream<DynamicTest> verifyNullRange() {
        return Stream.of(null, // null
                        "",  // empty
                        "null" // weird null string
                )
                .map(value -> DynamicTest.dynamicTest("Verify %s is not parseable".formatted(value),
                        () -> Assertions.assertThatThrownBy(() -> new RangedValue(value)).isInstanceOf(IllegalArgumentException.class)));
    }
}
