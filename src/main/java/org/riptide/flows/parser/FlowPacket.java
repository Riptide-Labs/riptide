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

    /**
     * The number of sequence-number units this packet advances the exporter's counter by. IPFIX and
     * NetFlow v5 count Data Records/flows (RFC 7011 §3.1, RFC 3954), so a packet carrying N records
     * advances the counter by N; NetFlow v9 counts export packets and sFlow counts datagrams, so both
     * advance by 1 (the default).
     *
     * @return the sequence increment; always {@code >= 1}
     */
    default int getSequenceIncrement() {
        return 1;
    }

    /**
     * Data Sets this packet carried that could not be decoded because their Template was not known.
     *
     * <p>RFC 7011 §8 makes collector-side buffering of such records a {@code MAY}, so discarding them
     * is compliant — but it is still lost data, and it is lost a whole Set at a time. The count is of
     * <strong>Sets, not records</strong>, and that is not a shortcut: without the Template the record
     * size is unknown, so the records inside an undecodable Set cannot be counted. Treat it as a lower
     * bound on records lost.
     *
     * @return the number of Data Sets discarded for a missing Template; {@code 0} for protocols
     *         without templates (NetFlow v5, sFlow)
     */
    default int undecodableSets() {
        return 0;
    }
}
