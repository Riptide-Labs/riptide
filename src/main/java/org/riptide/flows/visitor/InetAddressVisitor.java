package org.riptide.flows.visitor;

import org.riptide.flows.parser.ie.values.IPv4AddressValue;
import org.riptide.flows.parser.ie.values.IPv6AddressValue;
import org.springframework.stereotype.Service;

import java.net.InetAddress;

@Service
public class InetAddressVisitor implements ValueVisitor<InetAddress> {
    @Override
    public Class<InetAddress> targetClass() {
        return InetAddress.class;
    }

    @Override
    public InetAddress visit(IPv4AddressValue value) {
        return value.getValue();
    }

    @Override
    public InetAddress visit(IPv6AddressValue value) {
        return value.getValue();
    }
}
