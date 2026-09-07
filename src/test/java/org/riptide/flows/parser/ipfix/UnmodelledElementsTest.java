/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.flows.parser.ipfix;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.codahale.metrics.MetricRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ipfix.proto.Header;
import org.riptide.flows.parser.ipfix.proto.Packet;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.SequenceNumberTracker;
import org.riptide.flows.parser.session.TcpSession;

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
import org.riptide.pipeline.Identity;
import org.riptide.testsupport.LogCapture;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.riptide.flows.utils.BufferUtils.slice;

/**
 * #596: an exporter announcing an element riptide parses and then discards has to be visible.
 *
 * <p>The gap this pins is narrow and easy to misread. {@code FieldSpecifier} already warns on an
 * <em>undeclared</em> element, and every element here is declared — the shipped registry carries all ten of
 * IE 390-399 — so they take the silent branch instead: parsed against the registry, bound to nothing, gone.
 * #596 defers modelling them until "an exporter turns up that sends IE 390", and before this nothing would
 * have reported one turning up.
 *
 * <p>The templates below are synthesised rather than captured, and that limit is the point of the exercise
 * rather than a shortcut: no exporter is known to emit these, which is exactly why #596 declined to model
 * them and exactly why the arrival needs an observer instead of a survey.
 */
class UnmodelledElementsTest {

    private static final int TEMPLATE_SET_ID = 2;
    private static final int OPTIONS_TEMPLATE_SET_ID = 3;

    private final MetricRegistry metrics = new MetricRegistry();
    private final Session session =
            new TcpSession(InetAddress.getLoopbackAddress(), () -> new SequenceNumberTracker(32));

    /** The case #596 is waiting for: a template announcing the flow-selection algorithm. */
    @Test
    void aTemplateCarryingAWatchlistedElementIsCounted() throws Exception {
        final var watch = new UnmodelledElements(this.metrics, "test");

        watch.observe(packet(templateSet(256, field(390, 2), field(1, 8))), this.session);

        assertThat(count())
                .as("an exporter announced IE 390 and riptide discards it; that has to be observable,"
                        + " because #596 defers the modelling until one turns up")
                .isEqualTo(1L);
    }

    /**
     * The row that stops this becoming the always-on warning the design rejects. A template of elements
     * riptide models must produce nothing at all: the registry declares 475 elements against roughly seventy
     * carried on the flow record, so a signal derived from "declared but unbound" rather than from the
     * watchlist would fire on almost every template from almost every exporter.
     */
    @Test
    void aTemplateOfModelledElementsIsNotCounted() throws Exception {
        final var watch = new UnmodelledElements(this.metrics, "test");

        watch.observe(packet(templateSet(256, field(1, 8), field(8, 4))), this.session);

        assertThat(count()).as("nothing here is unmodelled, so there is nothing to report").isZero();
    }

    /**
     * A selector report puts its identifying element in <em>scope</em>, not among the data fields — which is
     * the shape #598 found riptide mishandling for the packet-selection family. Scanning only the data
     * fields would miss the record shape this class most needs to see.
     */
    @Test
    void aWatchlistedElementInAnOptionsTemplateScopeIsCounted() throws Exception {
        final var watch = new UnmodelledElements(this.metrics, "test");

        // Every data field is modelled, so ONLY the scope scan can find anything here. An earlier version
        // paired the scoped IE 390 with a watchlisted IE 394 among the data fields, and deleting the scope
        // scan left this green: the data scan was finding 394 and the row proved nothing.
        watch.observe(packet(optionsTemplateSet(257, 1, field(390, 2), field(1, 8))), this.session);

        assertThat(count())
                .as("the element is in scope rather than in the data fields, and it still has to be seen")
                .isEqualTo(1L);
    }

    /** The meter keeps counting across announcements; an exporter re-sends its templates on a timer. */
    @Test
    void repeatedAnnouncementsKeepCounting() throws Exception {
        final var watch = new UnmodelledElements(this.metrics, "test");

        watch.observe(packet(templateSet(256, field(390, 2), field(1, 8))), this.session);
        watch.observe(packet(templateSet(256, field(390, 2), field(1, 8))), this.session);

        assertThat(count())
                .as("only the sentence is once-per-episode. A counter that stopped would answer 'has one"
                        + " arrived' and not 'is this still happening'")
                .isEqualTo(2L);
    }

    /**
     * The row that binds this to production. Everything above drives {@link UnmodelledElements} directly,
     * which cannot tell a wired parser from an unwired one — a meter registered and never fed reads zero
     * forever and looks exactly like "no such exporter has called", which is the reading operators are told
     * to make. So this goes through {@code IpfixUdpParser.parse} itself.
     *
     * <p>The TCP parser is the sibling site and is not covered here: constructing one needs a live channel.
     * Both were wired in the same commit and the pair is complete — {@code Packet} is built at
     * {@code IpfixUdpParser:56} and {@code IpfixTcpParser:82} and nowhere else — but only one of the two is
     * pinned, and that is a real limit rather than an oversight.
     */
    @Test
    void theUdpParserActuallyObservesWhatItParses() throws Exception {
        final var parser = new IpfixUdpParser(
                "ipfix-test",
                (source, flows) -> { },
                new Identity("t", "o", "z", "s"),
                this.metrics,
                new ValueConversionService(IpfixRawFlow.class, List.of(
                        new StringVisitor(), new BooleanVisitor(), new DoubleVisitor(),
                        new DurationVisitor(), new InetAddressVisitor(), new InstantVisitor(),
                        new IntegerVisitor(), new LongVisitor(), new UnsignedLongVisitor())));

        final var out = new ByteArrayOutputStream();
        final byte[] set = templateSet(256, field(390, 2), field(1, 8));
        writeShort(out, 10);
        writeShort(out, Header.SIZE + set.length);
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, 1);
        out.writeBytes(set);

        parser.parse(this.session, Unpooled.wrappedBuffer(out.toByteArray()));

        assertThat(this.metrics.counter(
                MetricRegistry.name("parsers", "ipfix-test", "unmodelledElementTemplates")).getCount())
                .as("the parser has to feed the meter; registering it and never calling it would publish a"
                        + " permanent zero that reads as 'nothing has arrived'")
                .isEqualTo(1L);
    }

    /**
     * A second, different element still gets named — which a single latch would have swallowed.
     *
     * <p>Review found this: with one boolean guarding the log, an exporter announcing IE 396 took the latch,
     * and the later arrival of IE 390 — the exact event #596 defers on — moved the meter while no line ever
     * named it. An operator reading the counter beside a log that says "IE 396" would have concluded it was
     * the same exporter as before, which is the wrong conclusion in the one case this class exists for.
     */
    @Test
    void aNewElementIsNamedEvenAfterAnEarlierOneWasReported() throws Exception {
        final var appender = LogCapture.startedAppender();
        final var logger = (Logger) LoggerFactory.getLogger(UnmodelledElements.class);
        logger.addAppender(appender);
        try {
            final var watch = new UnmodelledElements(this.metrics, "test");

            watch.observe(packet(templateSet(256, field(396, 4), field(1, 8))), this.session);
            watch.observe(packet(templateSet(257, field(390, 2), field(1, 8))), this.session);

            final var lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(lines).as("the first arrival is named").anyMatch(l -> l.contains("IE 396"));
            assertThat(lines)
                    .as("and so is the second, different one; a latch here would report the meter moving"
                            + " with nothing saying what moved it")
                    .anyMatch(l -> l.contains("IE 390"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    /**
     * The same element from a <em>different</em> exporter is named again, which keying on the element alone
     * would have swallowed.
     *
     * <p>Second review found this, one level up from the latch: a lab exporter announces IE 390 on day one
     * and is logged; a production exporter announces it six months later, the counter ticks, and the only
     * line in the archive points at the lab box. The docs tell an operator to go and decide whether it
     * matters, so the line has to say which exporter to go and look at.
     */
    @Test
    void theSameElementFromADifferentExporterIsNamedAgain() throws Exception {
        final var appender = LogCapture.startedAppender();
        final var logger = (Logger) LoggerFactory.getLogger(UnmodelledElements.class);
        logger.addAppender(appender);
        try {
            final var watch = new UnmodelledElements(this.metrics, "test");
            final Session other = new TcpSession(
                    InetAddress.getByName("192.0.2.7"), () -> new SequenceNumberTracker(32));

            watch.observe(packet(templateSet(256, field(390, 2), field(1, 8))), this.session);
            watch.observe(packet(templateSet(256, field(390, 2), field(1, 8))), other);

            final var lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(lines)
                    .as("one line per exporter, each naming the exporter it is about")
                    .hasSize(2);
            assertThat(lines).anyMatch(l -> l.contains("192.0.2.7"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    /** A repeat of an element already named stays quiet, or the line becomes noise on a template timer. */
    @Test
    void anElementAlreadyNamedIsNotNamedAgain() throws Exception {
        final var appender = LogCapture.startedAppender();
        final var logger = (Logger) LoggerFactory.getLogger(UnmodelledElements.class);
        logger.addAppender(appender);
        try {
            final var watch = new UnmodelledElements(this.metrics, "test");

            watch.observe(packet(templateSet(256, field(390, 2), field(1, 8))), this.session);
            watch.observe(packet(templateSet(256, field(390, 2), field(1, 8))), this.session);

            assertThat(appender.list)
                    .as("an exporter re-announces its templates on a timer; the meter carries the repetition")
                    .hasSize(1);
        } finally {
            logger.detachAppender(appender);
        }
    }

    private long count() {
        return this.metrics.counter(
                MetricRegistry.name("parsers", "test", "unmodelledElementTemplates")).getCount();
    }

    // --- IPFIX message construction -------------------------------------------------------------------
    // Synthesised rather than captured, because no exporter is known to emit these elements.

    private Packet packet(final byte[] set) throws Exception {
        final var out = new ByteArrayOutputStream();
        final int length = Header.SIZE + set.length;
        writeShort(out, 10);          // version
        writeShort(out, length);      // total message length
        writeInt(out, 0);             // export time
        writeInt(out, 0);             // sequence number
        writeInt(out, 1);             // observation domain
        out.writeBytes(set);

        final ByteBuf buf = Unpooled.wrappedBuffer(out.toByteArray());
        return new Packet(this.session, new Header(slice(buf, Header.SIZE)), buf);
    }

    private static byte[] templateSet(final int templateId, final byte[]... fields) {
        final var records = new ByteArrayOutputStream();
        writeShort(records, templateId);
        writeShort(records, fields.length);
        for (final byte[] f : fields) {
            records.writeBytes(f);
        }
        return set(TEMPLATE_SET_ID, records.toByteArray());
    }

    private static byte[] optionsTemplateSet(final int templateId, final int scopeCount, final byte[]... fields) {
        final var records = new ByteArrayOutputStream();
        writeShort(records, templateId);
        writeShort(records, fields.length);   // total field count, scopes included
        writeShort(records, scopeCount);
        for (final byte[] f : fields) {
            records.writeBytes(f);
        }
        return set(OPTIONS_TEMPLATE_SET_ID, records.toByteArray());
    }

    private static byte[] set(final int setId, final byte[] body) {
        final var out = new ByteArrayOutputStream();
        writeShort(out, setId);
        writeShort(out, body.length + 4);
        out.writeBytes(body);
        return out.toByteArray();
    }

    /** One field specifier: element id and length, no enterprise bit. */
    private static byte[] field(final int elementId, final int length) {
        final var out = new ByteArrayOutputStream();
        writeShort(out, elementId);
        writeShort(out, length);
        return out.toByteArray();
    }

    private static void writeShort(final ByteArrayOutputStream out, final int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeInt(final ByteArrayOutputStream out, final int value) {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}
