package org.riptide.flows.parser;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.ipfix.proto.Header;
import org.riptide.flows.parser.ipfix.proto.Packet;
import org.riptide.flows.parser.session.SequenceNumberTracker;
import org.riptide.flows.parser.session.TcpSession;
import org.assertj.core.api.Assertions;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.function.Consumer;

import static org.riptide.flows.utils.BufferUtils.slice;

public class ParserTest {

    @Test
    void canReadValidIPFIX() throws IOException, URISyntaxException {
        execute("/flows/ipfix.dat", buffer -> {
            try {

                final var session = new TcpSession(InetAddress.getLoopbackAddress(), () -> new SequenceNumberTracker(32));

                final Header h1 = new Header(slice(buffer, Header.SIZE));
                final Packet p1 = new Packet(session, h1, slice(buffer, h1.length - Header.SIZE));

                Assertions.assertThat(p1.header.versionNumber).isEqualTo(0x000a);
                Assertions.assertThat(p1.header.observationDomainId).isEqualTo(0L);
                Assertions.assertThat(p1.header.exportTime).isEqualTo(1431516026L); // "2015-05-13T11:20:26.000Z"

                final Header h2 = new Header(slice(buffer, Header.SIZE));
                final Packet p2 = new Packet(session, h2, slice(buffer, h2.length - Header.SIZE));

                Assertions.assertThat(p2.header.versionNumber).isEqualTo(0x000a);
                Assertions.assertThat(p2.header.observationDomainId).isEqualTo(0L);
                Assertions.assertThat(p2.header.exportTime).isEqualTo(1431516026L); // "2015-05-13T11:20:26.000Z"

                final Header h3 = new Header(slice(buffer, Header.SIZE));
                final Packet p3 = new Packet(session, h3, slice(buffer, h3.length - Header.SIZE));

                Assertions.assertThat(p3.header.versionNumber).isEqualTo(0x000a);
                Assertions.assertThat(p3.header.observationDomainId).isEqualTo(0L);
                Assertions.assertThat(p3.header.exportTime).isEqualTo(1431516028L); // "2015-05-13T11:20:26.000Z"

                Assertions.assertThat(buffer.isReadable()).isEqualTo(false);

            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    void execute(final String resource, final Consumer<ByteBuf> consumer) {
        Objects.requireNonNull(resource);
        Objects.requireNonNull(consumer);

        final URL resourceURL = getClass().getResource(resource);
        Objects.requireNonNull(resourceURL);

        try {
            try (final FileChannel channel = FileChannel.open(Paths.get(resourceURL.toURI()))) {
                final ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
                channel.read(buffer);
                buffer.flip();

                consumer.accept(Unpooled.wrappedBuffer(buffer));
            }

        } catch (final URISyntaxException | IOException e) {
            throw new RuntimeException(e);
        }
    }
}
