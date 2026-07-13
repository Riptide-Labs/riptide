/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser;

import org.riptide.flows.parser.data.Flow;
import org.riptide.pipeline.ExporterIdentity;

import java.net.InetAddress;
import java.time.Instant;
import java.util.stream.Stream;

public interface FlowPacket {
    Stream<Flow> buildFlows(Instant receivedAt);

    /**
     * The exporter identity for the flows in this packet. Defaults to the UDP source
     * scoped by observation domain; protocols whose identity lives in the payload
     * (sFlow: agent address + sub-agent ID) override this.
     */
    default ExporterIdentity identity(final InetAddress remoteAddress) {
        return new ExporterIdentity.NetflowIpfix(remoteAddress, this.getObservationDomainId());
    }

    /** Returns the observation domain ID as specified by the underlying packet used to generate these records.
     *
     * @return the observation domain ID or <code>0</code> if there is no such concept available.
     */
    long getObservationDomainId();

    /** Returns the sequence number as provided by the underlying packet used to generate these records.
     *
     * @return the sequence number
     */
    long getSequenceNumber();
}
