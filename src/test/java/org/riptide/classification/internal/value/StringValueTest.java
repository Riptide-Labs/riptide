package org.riptide.classification.internal.value;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

public class StringValueTest {

    @Test
    public void verifyNormalStringValue() {
        // "Normal" value
        final var input = "test";
        final var value = new StringValue(input);
        Assertions.assertThat(value.getValue()).isEqualTo(input);
        Assertions.assertThat(value.isRanged()).isFalse();
        Assertions.assertThat(value.hasWildcard()).isFalse();
        Assertions.assertThat(value.isNull()).isFalse();
        Assertions.assertThat(value.isEmpty()).isFalse();
        Assertions.assertThat(value.isNullOrEmpty()).isFalse();
    }
    
    @Test
    void verifyEmptyValue() {
        // "" (empty) value
        final var input = "";
        final var value = new StringValue(input);
        Assertions.assertThat(value.getValue()).isEqualTo(input);
        Assertions.assertThat(value.isRanged()).isFalse();
        Assertions.assertThat(value.hasWildcard()).isFalse();
        Assertions.assertThat(value.isNull()).isFalse();
        Assertions.assertThat(value.isEmpty()).isTrue();
        Assertions.assertThat(value.isNullOrEmpty()).isTrue();

    }
    
    @Test
    void verifyNullValue() {
        // "null" value
        final var value = new StringValue(null);
        Assertions.assertThat(value.getValue()).isNull();
        Assertions.assertThat(value.isRanged()).isFalse();
        Assertions.assertThat(value.hasWildcard()).isFalse();
        Assertions.assertThat(value.isNull()).isTrue();
        Assertions.assertThat(value.isEmpty()).isFalse();
        Assertions.assertThat(value.isNullOrEmpty()).isTrue();
    }

    @Test
    void verifyWildcardValue() {
        // * (wildcard) value
        final var input = "test*";
        final var value = new StringValue(input);
        Assertions.assertThat(value.getValue()).isEqualTo(input);
        Assertions.assertThat(value.isRanged()).isFalse();
        Assertions.assertThat(value.hasWildcard()).isTrue();
        Assertions.assertThat(value.isNull()).isFalse();
        Assertions.assertThat(value.isEmpty()).isFalse();
        Assertions.assertThat(value.isNullOrEmpty()).isFalse();
    }

    @Test
    void verifyRangedValue() {
        // - (ranged) value
        final var input = "80-100";
        final var value = new StringValue(input);
        Assertions.assertThat(value.getValue()).isEqualTo(input);
        Assertions.assertThat(value.isRanged()).isTrue();
        Assertions.assertThat(value.hasWildcard()).isFalse();
        Assertions.assertThat(value.isNull()).isFalse();
        Assertions.assertThat(value.isEmpty()).isFalse();
        Assertions.assertThat(value.isNullOrEmpty()).isFalse();
    }

}