package org.riptide.utils;

public record Tuple<LEFT, RIGHT>(LEFT first, RIGHT second) {
    public static <LEFT, RIGHT> Tuple<LEFT, RIGHT> of(LEFT first, RIGHT second) {
        return new Tuple<>(first, second);
    }
}
