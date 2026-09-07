/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.profiling;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Continuous profiling. One key, and deliberately only one.
 *
 * <p><b>Everything else is Pyroscope's own vocabulary.</b> The agent reads {@code PYROSCOPE_*} from the
 * environment itself — server address, credentials, sampling event, upload interval, profiler type — and
 * riptide does not restate any of it. Mirroring that surface into {@code riptide.profiling.*} would mean a
 * consumer and a test for every mirrored key, against an upstream that keeps adding options, and an option
 * added there would be silently unavailable here with nothing failing to say so. The mirror would be one
 * more place that has to remember something.
 *
 * <p>So this class holds the one decision that is riptide's rather than Pyroscope's: whether to profile at
 * all. See {@code ProfilingConfiguration} for the consumer, and the operator documentation for the
 * {@code PYROSCOPE_*} variables that shape what it does once on.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "riptide.profiling")
public class RiptideProfilingProperties {

    /**
     * Start the profiler. Off unless asked for, which is not merely a conservative default.
     *
     * <p>A profiler samples the process and ships what it finds to a server. Both are things an operator
     * chooses, not things they discover — a collector that began profiling because a dependency arrived
     * would be making an unrequested outbound connection on their behalf.
     *
     * <p>Environment form: {@code RIPTIDE_PROFILING_ENABLED=true}.
     */
    private boolean enabled = false;
}
