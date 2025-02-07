package org.riptide.flows.parser.ie;

import org.riptide.flows.parser.exceptions.InvalidPacketException;
import org.riptide.flows.parser.exceptions.MissingTemplateException;
import org.riptide.flows.parser.session.Session;

import io.netty.buffer.ByteBuf;

// TODO fooker: genericalize the parser output
public interface InformationElement {

    Value<?> parse(Session.Resolver resolver, ByteBuf buffer) throws InvalidPacketException, MissingTemplateException;

    String getName();

    int getMinimumFieldLength();

    int getMaximumFieldLength();
}
