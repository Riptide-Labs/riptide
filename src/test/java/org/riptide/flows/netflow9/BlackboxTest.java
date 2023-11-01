package org.riptide.flows.netflow9;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.riptide.flows.parser.netflow9.proto.Header;
import org.riptide.flows.parser.netflow9.proto.Packet;
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
                List.of("netflow9_test_valid01.dat"),
                List.of("netflow9_test_macaddr_tpl.dat", "netflow9_test_macaddr_data.dat"),
                List.of("netflow9_test_cisco_asa_1_tpl.dat", "netflow9_test_cisco_asa_1_data.dat"),
                List.of("netflow9_test_nprobe_tpl.dat", "netflow9_test_softflowd_tpl_data.dat", "netflow9_test_nprobe_data.dat"),
                List.of("netflow9_test_cisco_asa_2_tpl_26x.dat", "netflow9_test_cisco_asa_2_tpl_27x.dat", "netflow9_test_cisco_asa_2_data.dat"),
                List.of("netflow9_test_ubnt_edgerouter_tpl.dat", "netflow9_test_ubnt_edgerouter_data1024.dat", "netflow9_test_ubnt_edgerouter_data1025.dat"),
                List.of("netflow9_test_nprobe_dpi.dat"),
                List.of("netflow9_test_fortigate_fortios_521_tpl.dat", "netflow9_test_fortigate_fortios_521_data256.dat", "netflow9_test_fortigate_fortios_521_data257.dat"),
                List.of("netflow9_test_streamcore_tpl_data256.dat", "netflow9_test_streamcore_tpl_data260.dat"),
                List.of("netflow9_test_juniper_srx_tplopt.dat"),
                List.of("netflow9_test_0length_fields_tpl_data.dat"),
                List.of("netflow9_test_cisco_asr9k_opttpl256.dat", "netflow9_test_cisco_asr9k_data256.dat"),
                List.of("netflow9_test_cisco_asr9k_tpl260.dat", "netflow9_test_cisco_asr9k_data260.dat"),
                List.of("netflow9_test_cisco_nbar_opttpl260.dat"),
                List.of("netflow9_test_cisco_nbar_tpl262.dat", "netflow9_test_cisco_nbar_data262.dat"),
                List.of("netflow9_test_cisco_wlc_tpl.dat", "netflow9_test_cisco_wlc_data261.dat"),
                List.of("netflow9_test_cisco_wlc_8510_tpl_262.dat"),
                List.of("netflow9_test_cisco_1941K9.dat"),
                List.of("netflow9_cisco_asr1001x_tpl259.dat"),
                List.of("netflow9_test_paloalto_panos_tpl.dat", "netflow9_test_paloalto_panos_data.dat"),
                List.of("netflow9_test_juniper_data_b4_tmpl.dat"),
                List.of("nms-14130.dat"));
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
                    final var packet = new Packet(session, header, buf);

                    assertThat(packet.header.versionNumber).isEqualTo(0x0009);

                } while (buf.isReadable());
            }
        }
    }
}
