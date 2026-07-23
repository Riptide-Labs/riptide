/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.ie.values;

import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ie.values.visitor.ValueVisitor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class ValueConversionService {
    private static final Map<Class<?>, Class<?>> PRIMITIVE_TYPE_MAP = Map.of(
            void.class, Void.class,
            boolean.class, Boolean.class,
            byte.class, Byte.class,
            char.class, Character.class,
            short.class, Short.class,
            int.class, Integer.class,
            long.class, Long.class,
            float.class, Float.class,
            double.class, Double.class);

    public final @VisibleForTesting Class<?> targetType;
    private final @NotNull Map<Class<?>, ValueVisitor<?>> visitors;
    private final @NotNull Map<String, Field> fieldMap;

    public ValueConversionService(Class<?> targetType, List<ValueVisitor<?>> visitors) {
        Objects.requireNonNull(visitors);
        Objects.requireNonNull(targetType);
        this.targetType = targetType;
        this.visitors = visitors.stream().collect(Collectors.toMap(ValueVisitor::targetClass, it -> it));
        this.fieldMap = Stream.of(targetType.getDeclaredFields()).collect(Collectors.toMap(
                Field::getName,
                it -> {
                    it.setAccessible(true);
                    return it;
                }));
        validate();
    }

    /**
     * Visitors are keyed by their boxed target class, so a primitive field type must be boxed before
     * lookup. apply() and validate() both go through here: when they disagreed, validate() accepted a
     * primitive-typed field at construction while apply() looked it up raw, missed, and threw an NPE
     * for every such value.
     */
    private static Class<?> boxed(final Class<?> type) {
        return type.isPrimitive() ? PRIMITIVE_TYPE_MAP.get(type) : type;
    }

    private void validate() {
        final var requiredTypes = Stream.of(targetType.getDeclaredFields())
                .map(Field::getType)
                .map(ValueConversionService::boxed)
                .distinct()
                .toList();
        for (Class<?> fieldType : requiredTypes) {
            if (!visitors.containsKey(fieldType)) {
                throw new IllegalStateException(
                        "Class %s defined a field of type %s which does not have a fitting %s. Please ensure to implement a %s with targetType = %s"
                                .formatted(targetType, fieldType, ValueVisitor.class, ValueVisitor.class, fieldType));
            }
        }
    }

    public void apply(Value<?> source, Object targetFlow) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(targetFlow);
        try {
            final var key = source.getName();
            final var field = fieldMap.get(key);
            if (field != null) {
                final var converterVisitor = visitors.get(boxed(field.getType()));
                final var convertedValue = source.accept(converterVisitor);
                if (convertedValue != null) {
                    field.set(targetFlow, convertedValue);
                }
            }
        } catch (Exception ex) {
            // One unconvertible value must not drop the whole flow, so swallow and move on. Logged at
            // debug because this runs once per value on the untrusted-input path: a malformed packet
            // could otherwise flood the log at a higher level (which is exactly what masked this bug).
            log.debug("Could not convert value {} into {}: {}", source.getName(), targetType, ex.getMessage(), ex);
        }

    }
}
