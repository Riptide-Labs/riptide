/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.repository.clickhouse;

import com.clickhouse.data.ClickHouseColumn;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code flows} columns a row must carry a value for, or the client refuses the whole insert
 * with "An attempt to write null into not nullable column" (#985).
 *
 * <p><b>Derived from the table the server reports, never listed.</b> A column is required when it
 * is not {@code Nullable}, has no default, and the {@link ClickhouseFlow} field written to it is a
 * reference type. A primitive field cannot be null; a {@code Nullable} column takes one; and for a
 * column with a default the client sends the default marker instead. That is the client's own rule,
 * and {@code UninsertableFlowsIT} measures both directions of it against a server: no null this
 * misses is refused, and every column this flags is refused when null.
 *
 * <p>Following the live table also means a validate-mode deployment whose operator relaxed a column
 * to {@code Nullable} is not held to the shipped schema.
 */
final class RequiredColumns {

    /** Requires nothing: what a repository holds before {@code start()} has read the table. */
    static final RequiredColumns NONE = new RequiredColumns(List.of());

    private record Required(String column, Field field) {
    }

    private final List<Required> required;

    private RequiredColumns(final List<Required> required) {
        this.required = required;
    }

    /**
     * @param columns the {@code flows} table's columns as read from {@code system.columns}, with
     *                nullability and default kind
     */
    static RequiredColumns of(final List<ClickHouseColumn> columns) {
        final List<Required> required = new ArrayList<>();
        for (final ClickHouseColumn column : columns) {
            if (column.isNullable() || column.hasDefault()) {
                continue;
            }
            final Field field;
            try {
                field = ClickhouseFlow.class.getDeclaredField(column.getColumnName());
            } catch (final NoSuchFieldException e) {
                // A column riptide has no field for is not one it can leave null.
                continue;
            }
            if (field.getType().isPrimitive()) {
                continue;
            }
            field.setAccessible(true);
            required.add(new Required(column.getColumnName(), field));
        }
        return new RequiredColumns(List.copyOf(required));
    }

    /** The required columns, in the table's order. */
    List<String> names() {
        return this.required.stream().map(Required::column).toList();
    }

    /**
     * Why this row cannot be inserted, naming every required column it leaves null, or
     * {@code null} when it can.
     */
    String missing(final ClickhouseFlow row) {
        StringBuilder missing = null;
        for (final Required column : this.required) {
            if (value(column.field(), row) == null) {
                if (missing == null) {
                    missing = new StringBuilder("null in non-nullable column(s) ");
                } else {
                    missing.append(", ");
                }
                missing.append(column.column());
            }
        }
        return missing == null ? null : missing.toString();
    }

    private static Object value(final Field field, final ClickhouseFlow row) {
        try {
            return field.get(row);
        } catch (final IllegalAccessException e) {
            // setAccessible succeeded in of(), on a field of a class in this package.
            throw new IllegalStateException(e);
        }
    }
}
