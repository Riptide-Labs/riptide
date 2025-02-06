package org.riptide.flows.parser;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ipfix.IpfixRawFlow;
import org.riptide.flows.parser.netflow9.Netflow9RawFlow;
import org.riptide.flows.visitor.ValueVisitor;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
@Slf4j
public class ValueConversionService {

    private static final List<Class<?>> RAW_FLOW_TYPES = List.of(IpfixRawFlow.class, Netflow9RawFlow.class);
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

    private final @NotNull Map<Class<?>, ValueVisitor<?>> visitors;
    private final @NotNull Map<Class<?>, Set<String>> fieldMaps;

    public ValueConversionService(List<ValueVisitor<?>> visitors) {
        Objects.requireNonNull(visitors);
        this.visitors = visitors.stream().collect(Collectors.toMap(ValueVisitor::targetClass, it -> it));
        this.fieldMaps = RAW_FLOW_TYPES.stream().collect(Collectors.toMap(type -> type, type -> Stream.of(type.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet())));
        validate();
    }

    private void validate() {
        for (Class<?> eachType : RAW_FLOW_TYPES) {
            validate(eachType);
        }
    }

    private void validate(Class<?> type) {
        final var requiredTypes = Stream.of(type.getDeclaredFields())
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
                throw new IllegalStateException("Class %s defined a field of type %s which does not have a fitting %s. Please ensure to implement a %s with targetType = %s".formatted(type, fieldType, ValueVisitor.class, ValueVisitor.class, fieldType));
            }
        }
    }

    public void convert(Value<?> source, Object targetFlow) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(targetFlow);
        final var fieldSet = fieldMaps.get(targetFlow.getClass());
        try {
            final var key = source.getName();
            if (fieldSet.contains(key)) {
                final var field = targetFlow.getClass().getDeclaredField(key);
                final var converterVisitor = visitors.get(field.getType());
                final var convertedValue = source.accept(converterVisitor);
                field.setAccessible(true);
                if (convertedValue != null) {
                    field.set(targetFlow, convertedValue);
                }
            }
        } catch (Exception ex) {
            log.error("🤡🦄💩: {}", ex.getMessage(), ex);
        }

    }
}
