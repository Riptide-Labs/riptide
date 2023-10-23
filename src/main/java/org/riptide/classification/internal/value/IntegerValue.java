package org.riptide.classification.internal.value;

public class IntegerValue {
    private Integer value;

    public IntegerValue(final StringValue input) {
        if (input == null || input.isNullOrEmpty()) {
            this.value = null;
        } else {
            this.value = Integer.parseInt(input.getValue());
        }
    }

    public boolean isNull() {
        return value == null;
    }

    public int getValue() {
        return value.intValue(); // Check before with isNull() otherwise NPE may be thrown
    }
}
