package org.riptide.flows.parser.ie.values.visitor;

import org.riptide.flows.parser.ie.values.BooleanValue;
import org.riptide.flows.parser.ie.values.DateTimeValue;
import org.riptide.flows.parser.ie.values.FloatValue;
import org.riptide.flows.parser.ie.values.IPv4AddressValue;
import org.riptide.flows.parser.ie.values.IPv6AddressValue;
import org.riptide.flows.parser.ie.values.ListValue;
import org.riptide.flows.parser.ie.values.MacAddressValue;
import org.riptide.flows.parser.ie.values.NullValue;
import org.riptide.flows.parser.ie.values.OctetArrayValue;
import org.riptide.flows.parser.ie.values.SignedValue;
import org.riptide.flows.parser.ie.values.StringValue;
import org.riptide.flows.parser.ie.values.UndeclaredValue;
import org.riptide.flows.parser.ie.values.UnsignedValue;

public interface ValueVisitor<T> {

    // the class this visitor can map to
    Class<T> targetClass();

    default T visit(BooleanValue value) {
        return null;
    }

    default T visit(NullValue value) {
        return null;
    }

    default T visit(StringValue value) {
        return null;
    }

    default T visit(DateTimeValue value) {
        return null;
    }

    default T visit(IPv6AddressValue value) {
        return null;
    }

    default T visit(IPv4AddressValue value) {
        return null;
    }

    default T visit(UnsignedValue value) {
        return null;
    }

    default T visit(SignedValue value) {
        return null;
    }

    default T visit(OctetArrayValue value) {
        return null;
    }

    default T visit(ListValue value) {
        return null;
    }

    default T visit(FloatValue value) {
        return null;
    }

    default T visit(MacAddressValue value) {
        return null;
    }

    default T visit(UndeclaredValue value) {
        return null;
    }
}
