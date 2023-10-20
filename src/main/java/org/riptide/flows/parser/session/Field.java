package org.riptide.flows.parser.session;

import org.riptide.flows.parser.InvalidPacketException;
import org.riptide.flows.parser.MissingTemplateException;
import org.riptide.flows.parser.ie.Value;

import io.netty.buffer.ByteBuf;

public interface Field {
    int length();

    Value<?> parse(final Session.Resolver resolver,
                   final ByteBuf buffer) throws InvalidPacketException, MissingTemplateException;
}
