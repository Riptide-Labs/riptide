/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.fuzz;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.riptide.flows.parser.exceptions.InvalidPacketException;

import java.nio.BufferUnderflowException;
import org.riptide.flows.parser.ie.values.ValueConversionService;
import org.riptide.flows.parser.ie.values.visitor.BooleanVisitor;
import org.riptide.flows.parser.ie.values.visitor.DoubleVisitor;
import org.riptide.flows.parser.ie.values.visitor.DurationVisitor;
import org.riptide.flows.parser.ie.values.visitor.InetAddressVisitor;
import org.riptide.flows.parser.ie.values.visitor.InstantVisitor;
import org.riptide.flows.parser.ie.values.visitor.IntegerVisitor;
import org.riptide.flows.parser.ie.values.visitor.LongVisitor;
import org.riptide.flows.parser.ie.values.visitor.StringVisitor;
import org.riptide.flows.parser.ie.values.visitor.UnsignedLongVisitor;
import org.riptide.flows.parser.ie.values.visitor.ValueVisitor;
import org.riptide.flows.parser.ipfix.IpfixRawFlow;
import org.riptide.flows.parser.netflow9.Netflow9RawFlow;
import org.riptide.flows.parser.session.SequenceNumberTracker;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.TcpSession;

import java.net.InetAddress;
import java.util.List;

/**
 * Shared plumbing for the parser fuzz harnesses.
 *
 * <p>The oracle mirrors the production contract, not an idealized one. {@code UdpListener} wraps every
 * parse in {@code catch (Throwable)} and logs it as an invalid packet (see its #273 comment — it is
 * hardened to absorb pathological packets, Errors included). So the parsers' <em>sanctioned</em>
 * rejection of malformed input is not only {@link InvalidPacketException} but also the bounds
 * signals the byte decoding raises by design — {@link BufferUnderflowException} from
 * {@code BufferUtils.slice} and {@link IndexOutOfBoundsException} from Netty over-reads. Those are
 * swallowed here; flagging them would just report the bounds checking working.
 *
 * <p>Everything else propagates so Jazzer records it — a {@code NullPointerException},
 * {@code ArithmeticException}, {@code IllegalArgumentException}, or the ones no per-packet catch can
 * really contain on a network daemon: {@code OutOfMemoryError} from an attacker-controlled length,
 * a stack overflow from nesting, a hang (Jazzer's timeout). The sequence harnesses add the class the
 * listener's catch cannot save you from at all: cross-packet state corruption, where one crafted
 * packet poisons a session so later valid packets misparse.
 *
 * <p>The visitor list and conversion services mirror what {@code RiptideConfiguration} wires via
 * Spring, built by hand so a harness needs no application context.
 */
final class FuzzSupport {

    private FuzzSupport() {
    }

    /** The nine value visitors, in no particular order (the service keys them by target class). */
    private static List<ValueVisitor<?>> visitors() {
        return List.of(
                new BooleanVisitor(),
                new DoubleVisitor(),
                new DurationVisitor(),
                new InetAddressVisitor(),
                new InstantVisitor(),
                new IntegerVisitor(),
                new LongVisitor(),
                new StringVisitor(),
                new UnsignedLongVisitor());
    }

    static ValueConversionService netflow9ConversionService() {
        return new ValueConversionService(Netflow9RawFlow.class, visitors());
    }

    static ValueConversionService ipfixConversionService() {
        return new ValueConversionService(IpfixRawFlow.class, visitors());
    }

    /** A fresh stateful session, as the sequence harnesses reuse across packets. */
    static Session newSession() {
        return new TcpSession(InetAddress.getLoopbackAddress(), () -> new SequenceNumberTracker(32));
    }

    /** Wrap fuzzer bytes as a read-only ByteBuf. A copy, so parser slicing cannot corrupt the input. */
    static ByteBuf buffer(final byte[] data) {
        return Unpooled.wrappedBuffer(data.clone());
    }

    /**
     * True for a sanctioned "malformed packet" rejection the production listener already absorbs —
     * swallowed here, never a finding. Anything outside this set is a genuine unintended failure.
     */
    static boolean isDesignedRejection(final Throwable t) {
        return t instanceof InvalidPacketException
                || t instanceof BufferUnderflowException
                || t instanceof IndexOutOfBoundsException;
    }
}
