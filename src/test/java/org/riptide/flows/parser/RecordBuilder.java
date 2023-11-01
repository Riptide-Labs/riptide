package org.riptide.flows.parser;

import org.riptide.flows.parser.ie.Value;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class RecordBuilder {
    private final Map<String, Value<?>> values = new HashMap<>();

    public RecordBuilder add(final Value<?> value) {
        this.values.put(value.getName(), value);
        return this;
    }

    public Map<String, Value<?>> values() {
        return Collections.unmodifiableMap(this.values);
    }
}
