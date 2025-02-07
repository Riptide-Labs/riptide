package org.riptide.flows.parser.session;

import org.riptide.flows.parser.exceptions.InvalidPacketException;
import org.riptide.flows.parser.exceptions.MissingTemplateException;
import org.riptide.flows.parser.ie.Value;

import io.netty.buffer.ByteBuf;

public interface Field {
    int length();

    Value<?> parse(Session.Resolver resolver, ByteBuf buffer) throws InvalidPacketException, MissingTemplateException;
}
