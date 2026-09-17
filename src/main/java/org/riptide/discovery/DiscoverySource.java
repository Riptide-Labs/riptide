/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import java.io.IOException;
import java.util.List;

/**
 * Where discovery gets its devices. A source's job ends when it has produced target groups.
 *
 * <p><b>Why the seam is here and not further on.</b> Everything after this point is shared, and it
 * is where the expensive behaviour lives: {@link ExporterRenderer}'s address rule and its strict
 * parser gate, the skip-and-count for a device with no usable address, the refusal of a name
 * claimed twice, the refusal of an address claimed twice, the de-duplication of an identical pair,
 * the refusal of an empty result, and byte-deterministic ordering. Several of those were added only
 * after a review found them missing, and were then mutation-proven. A source that produced a
 * finished exporters tree would need its own copy of all of it, and this project's recurring defect
 * is a rule fixed where a report pointed and missed at its sibling.</p>
 *
 * <p><b>The two failure kinds are not interchangeable.</b> An {@link IOException} means the endpoint
 * could not be reached, which boot degrades on rather than refusing to start. Anything else,
 * signalled as an {@link IllegalStateException}, is content an operator has to see: an answer that
 * does not parse, a shape that is wrong, a collision. Collapsing the two would either make a
 * misconfiguration look like an outage or stop a collector starting because a remote system is
 * down.</p>
 */
public interface DiscoverySource {

    /**
     * The devices this source currently offers, as target groups.
     *
     * @throws IOException when the endpoint could not be reached; boot degrades on this
     * @throws IllegalStateException when the answer was received but cannot be used
     */
    List<TargetGroup> targets() throws IOException;
}
