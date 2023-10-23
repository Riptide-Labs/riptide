package org.riptide.flows.parser.transport;

import java.time.Duration;
import java.time.Instant;

public class Timeout {

    private Duration flowActiveTimeout;
    private Duration flowInactiveTimeout;

    private Long numBytes;
    private Long numPackets;
    private Instant firstSwitched;
    private Instant lastSwitched;

    public Timeout withActiveTimeout(final Duration activeTimeout) {
        this.flowActiveTimeout = activeTimeout;
        return this;
    }

    public Timeout withInactiveTimeout(final Duration inactiveTimeout) {
        this.flowInactiveTimeout = inactiveTimeout;
        return this;
    }

    public Timeout withNumBytes(final Long numBytes) {
        this.numBytes = numBytes;
        return this;
    }

    public Timeout withNumPackets(final Long numPackets) {
        this.numPackets = numPackets;
        return this;
    }

    public Timeout withFirstSwitched(final Instant firstSwitched) {
        this.firstSwitched = firstSwitched;
        return this;
    }

    public Timeout withLastSwitched(final Instant lastSwitched) {
        this.lastSwitched = lastSwitched;
        return this;
    }

    public Instant calculateDeltaSwitched() {
        if (this.flowActiveTimeout == null || this.flowInactiveTimeout == null) {
            return this.firstSwitched;
        }

        if (this.firstSwitched == null || this.lastSwitched == null) {
            return null;
        }

        final var numBytes = this.numBytes != null ? this.numBytes : 0;
        final var numPackets = this.numPackets != null ? this.numPackets : 0;

        final var timeout = (numBytes > 0 || numPackets > 0)
                ? this.flowActiveTimeout
                : this.flowInactiveTimeout;

        final var deltaSwitched = this.lastSwitched.minus(timeout);

        return deltaSwitched.isAfter(this.firstSwitched)
                ? deltaSwitched
                : this.firstSwitched;
    }
}
