package org.riptide.flows.parser.ie;

import org.riptide.flows.parser.InvalidPacketException;
import org.riptide.flows.parser.MissingTemplateException;
import org.riptide.flows.parser.session.Session;

import io.netty.buffer.ByteBuf;

public interface InformationElement {

    Value<?> parse(final Session.Resolver resolver,
                   final ByteBuf buffer) throws InvalidPacketException, MissingTemplateException;

    String getName();

    int getMinimumFieldLength();

    int getMaximumFieldLength();
}
