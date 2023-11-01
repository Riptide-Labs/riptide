package org.riptide.flows.ipfix;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.riptide.flows.parser.ipfix.proto.Header;
import org.riptide.flows.parser.ipfix.proto.Packet;
import org.riptide.flows.parser.session.SequenceNumberTracker;
import org.riptide.flows.parser.session.Session;
import org.riptide.flows.parser.session.TcpSession;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.riptide.flows.utils.BufferUtils.slice;

public class BlackboxTest {
    private static final Path FOLDER = Paths.get("src/test/resources/flows");

    private static Stream<List<String>> files() {
        return Stream.of(
                List.of("ipfix.dat"),
                List.of("ipfix_test_openbsd_pflow_tpl.dat", "ipfix_test_openbsd_pflow_data.dat"),
                List.of("ipfix_test_mikrotik_tpl.dat", "ipfix_test_mikrotik_data258.dat", "ipfix_test_mikrotik_data259.dat"),
                List.of("ipfix_test_vmware_vds_tpl.dat", "ipfix_test_vmware_vds_data264.dat", "ipfix_test_vmware_vds_data266.dat", "ipfix_test_vmware_vds_data266_267.dat"),
                List.of("ipfix_test_barracuda_tpl.dat", "ipfix_test_barracuda_data256.dat"),
                List.of("ipfix_test_yaf_tpls_option_tpl.dat", "ipfix_test_yaf_tpl45841.dat", "ipfix_test_yaf_data45841.dat", "ipfix_test_yaf_data45873.dat", "ipfix_test_yaf_data53248.dat"));
    }

    @ParameterizedTest
    @MethodSource("files")
    public void testFiles(final List<String> files) throws Exception {
        final Session session = new TcpSession(InetAddress.getLoopbackAddress(), () -> new SequenceNumberTracker(32));

        for (final String file : files) {
            try (FileChannel channel = FileChannel.open(FOLDER.resolve(file))) {
                final ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
                channel.read(buffer);
                buffer.flip();

                final ByteBuf buf = Unpooled.wrappedBuffer(buffer);

                do {
                    final var header = new Header(slice(buf, Header.SIZE));
                    final var packet = new Packet(session, header, slice(buf, header.length - Header.SIZE));

                    assertThat(packet.header.versionNumber).isEqualTo(0x000a);

                } while (buf.isReadable());
            }
        }
    }
}
