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
    //    private static final List<Class<?>> RAW_FLOW_TYPES = List.of(IpfixRawFlow.class, Netflow9RawFlow.class);
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

    private void validate() {
        final var requiredTypes = Stream.of(targetType.getDeclaredFields())
                .map(Field::getType)
                .map(it -> {
                    if (it.isPrimitive()) {
                        return PRIMITIVE_TYPE_MAP.get(it);
                    }
                    return it;
                })
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
                final var converterVisitor = visitors.get(field.getType());
                final var convertedValue = source.accept(converterVisitor);
                if (convertedValue != null) {
                    field.set(targetFlow, convertedValue);
                }
            }
        } catch (Exception ex) {
            log.error("🤡🦄💩: {}", ex.getMessage(), ex);
        }

    }
}
