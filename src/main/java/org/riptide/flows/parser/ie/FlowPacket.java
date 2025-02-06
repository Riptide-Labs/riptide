package org.riptide.flows.parser.ie;

import org.riptide.flows.parser.data.Flow;

import java.time.Instant;
import java.util.stream.Stream;

public interface FlowPacket {
    Stream<Flow> buildFlows(Instant receivedAt);

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
