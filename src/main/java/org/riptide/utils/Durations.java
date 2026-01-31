package org.riptide.utils;

import java.time.Duration;

public final class Durations {
    private Durations() {
    }

    public static double seconds(final Duration duration) {
        final double seconds = (double) duration.getSeconds();
        final double nanos = (double) duration.getNano() / 1000_000_000.;

        return seconds + nanos;
    }
}
