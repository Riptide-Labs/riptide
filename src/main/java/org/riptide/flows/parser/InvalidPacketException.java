package org.riptide.flows.parser;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

public class InvalidPacketException extends Exception {

    public InvalidPacketException(final ByteBuf buffer, final String fmt, final Object... args) {
        super(appendPosition(String.format(fmt, args), buffer));
    }

    public InvalidPacketException(final ByteBuf buffer, final String message, final Throwable cause) {
        super(appendPosition(message, buffer), cause);
    }

    private static String appendPosition(final String message, final ByteBuf buffer) {
        // We want to hex-dump the whole PDU, wo we need to get the unsliced buffer
        final ByteBuf unwrappedBuffer = Unpooled.wrappedUnmodifiableBuffer(buffer.unwrap() != null ? buffer.unwrap() : buffer).resetReaderIndex();
        // Compare the readableBytes() to determine the adjustment
        final int delta = unwrappedBuffer.readableBytes() - (buffer.readableBytes() + buffer.readerIndex());
        // Compute the offset for which this exception had occurred
        final int offset = buffer.readerIndex() + delta;

        return String.format("%s, Offset: [0x%04X], Payload:\n%s", message, offset, ByteBufUtil.prettyHexDump(unwrappedBuffer));
    }
}
