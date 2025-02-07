package org.riptide.flows.parser.ipfix.proto;

import java.util.Objects;

import org.riptide.flows.parser.exceptions.InvalidPacketException;

public abstract class FlowSet<R extends Record> implements Iterable<R> {

    /*
     +--------------------------------------------------+
     | Set Header                                       |
     +--------------------------------------------------+
     | record                                           |
     +--------------------------------------------------+
     | record                                           |
     +--------------------------------------------------+
      ...
     +--------------------------------------------------+
     | record                                           |
     +--------------------------------------------------+
     | Padding (opt.)                                   |
     +--------------------------------------------------+
    */

    public final Packet packet; // Enclosing packet

    public final FlowSetHeader header;

    public FlowSet(final Packet packet,
                   final FlowSetHeader header) throws InvalidPacketException {
        this.packet = Objects.requireNonNull(packet);
        this.header = Objects.requireNonNull(header);
    }
}
