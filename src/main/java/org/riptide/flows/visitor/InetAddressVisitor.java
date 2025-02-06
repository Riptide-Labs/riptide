package org.riptide.flows.visitor;

import org.riptide.flows.parser.ie.values.IPv4AddressValue;
import org.riptide.flows.parser.ie.values.IPv6AddressValue;

import java.net.InetAddress;

public class InetAddressVisitor implements ValueVisitor<InetAddress> {
    @Override
    public InetAddress visit(IPv4AddressValue value) {
        return value.getValue();
    }

    @Override
    public InetAddress visit(IPv6AddressValue value) {
        return value.getValue();
    }
}
