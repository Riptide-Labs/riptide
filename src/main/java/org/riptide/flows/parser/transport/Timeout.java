package org.riptide.flows.parser.transport;

import java.time.Duration;
import java.time.Instant;

public class Timeout {

    private final Duration flowActiveTimeout;
    private final Duration flowInactiveTimeout;

    private Long numBytes;
    private Long numPackets;
    private Instant firstSwitched;
    private Instant lastSwitched;

    public Timeout(final Duration active, final Duration inactive) {
        this.flowActiveTimeout = active;
        this.flowInactiveTimeout = inactive;
    }

    public void setNumBytes(final Long numBytes) {
        this.numBytes = numBytes;
    }

    public void setNumPackets(final Long numPackets) {
        this.numPackets = numPackets;
    }

    public void setFirstSwitched(final Instant firstSwitched) {
        this.firstSwitched = firstSwitched;
    }

    public void setLastSwitched(final Instant lastSwitched) {
        this.lastSwitched = lastSwitched;
    }

    public Instant getDeltaSwitched() {
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
