package org.riptide.flows.listeners;

import io.netty.buffer.ByteBuf;

/**
 * A listener may define multiple parsers, in order to dispatch it to only one queue,
 * the parser must decide if it can handle the incoming data.
 *
 * A parser implementing the {@link Dispatchable} interface is capable of making this decision.
 *
 * @author mvrueden
 */
public interface Dispatchable {

    /**
     * Returns true if the implementor can handle the incoming data, otherwise false.
     *
     * @param buffer Representing the incoming data
     * @return true if the implementor can handle the data, otherwise false.
     */
    boolean handles(final ByteBuf buffer);
}
