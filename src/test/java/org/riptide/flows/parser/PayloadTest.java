package org.riptide.flows.parser;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.riptide.flows.parser.netflow9.proto.Header;
import org.riptide.flows.parser.netflow9.proto.Packet;
import org.riptide.flows.parser.session.SequenceNumberTracker;
import org.riptide.flows.parser.session.TcpSession;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.function.Consumer;

import static org.riptide.flows.utils.BufferUtils.slice;

public class PayloadTest {

    @Test
    public void outputPayloadTest() {
        execute("/flows/nf9_broken.dat", buffer -> {
            Assertions.assertThatThrownBy(() -> {
                final var session = new TcpSession(InetAddress.getLoopbackAddress(), () -> new SequenceNumberTracker(32));
                final Header h1 = new Header(slice(buffer, Header.SIZE));
                final Packet p1 = new Packet(session, h1, buffer);
            }).isInstanceOf(InvalidPacketException.class)
                .hasMessageContaining("Invalid template ID: 8, Offset: [0x001E], Payload:")
                .hasMessageContaining("|00000000| 00 09 00 01 23 bc 9f 78 5f 1e 2e 03 05 cc 4e f2 |....#..x_.....N.|")
                .hasMessageContaining("|00000070| 00 12 00 04 00 3d 00 01                         |.....=..        |");
        });
    }

    private void execute(final String resource, final Consumer<ByteBuf> consumer) {
        Objects.requireNonNull(resource);
        Objects.requireNonNull(consumer);

        final var resourceURL = getClass().getResource(resource);
        Objects.requireNonNull(resourceURL);

        try {
            try (var channel = FileChannel.open(Paths.get(resourceURL.toURI()))) {
                final var buffer = ByteBuffer.allocate((int) channel.size());
                channel.read(buffer);
                buffer.flip();

                consumer.accept(Unpooled.wrappedBuffer(buffer));
            }
        } catch (final URISyntaxException | IOException e) {
            throw new RuntimeException(e);
        }
    }
}
