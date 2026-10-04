/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Training workload for the image's AOT cache (#987): replays the flow fixtures in
 * src/test/resources/flows to a riptide receiver for a fixed time. Run with the JDK's
 * single-file launcher, so the build needs nothing beyond the JDK:
 *
 * <pre>java Send.java 127.0.0.1 9999 /path/to/flows 45</pre>
 *
 * <p>NetFlow v9 sessions are keyed by exporter address and IPFIX by socket, so fixtures from
 * different exporters would overwrite each other's template ids. Each exporter family gets its own
 * loopback source address (127.0.1.N, bindable on Linux), and its template files go first.
 */
public final class Send {

    /** The first file-name token from here on names the file within its family, not the family. */
    private static final Pattern PART = Pattern.compile(
            "^(valid|data|tpl|tpls|template|opttpl|tplopt|records|apptable|iftable|sampling|data\\d+|tpl\\d+|opttpl\\d+).*");

    /**
     * Fixtures that exist to be rejected; the cache should profile the paths flows take. The Cisco
     * WLC fixtures decode to flows with no srcAddr, which fails the whole ClickHouse batch they land
     * in, so training would dead-letter everything and profile only the failure path. Drop the
     * exclusion when #985 is fixed.
     */
    private static final Pattern REJECTED = Pattern.compile(".*(invalid|broken|illegal|cisco_wlc).*");

    public static void main(final String[] args) throws Exception {
        final InetSocketAddress target = new InetSocketAddress(InetAddress.getByName(args[0]), Integer.parseInt(args[1]));
        final Path dir = Path.of(args[2]);
        final long deadline = System.nanoTime() + Long.parseLong(args[3]) * 1_000_000_000L;

        final Map<String, List<byte[]>> families = families(dir);
        final List<DatagramSocket> sockets = new ArrayList<>();
        int n = 1;
        for (final String family : families.keySet()) {
            sockets.add(socket(n++));
        }

        long sent = 0;
        long rounds = 0;
        while (System.nanoTime() < deadline) {
            int i = 0;
            for (final List<byte[]> packets : families.values()) {
                final DatagramSocket socket = sockets.get(i++);
                for (final byte[] packet : packets) {
                    socket.send(new DatagramPacket(packet, packet.length, target));
                    if (++sent % 16 == 0) {
                        Thread.sleep(1);
                    }
                }
            }
            rounds++;
        }
        System.out.printf("aot-train: %d families, %d packets per round, %d rounds, %d packets sent%n",
                families.size(), families.values().stream().mapToInt(List::size).sum(), rounds, sent);
    }

    private static DatagramSocket socket(final int n) throws IOException {
        try {
            return new DatagramSocket(new InetSocketAddress(InetAddress.getByAddress(
                    new byte[] {127, 0, (byte) (1 + n / 250), (byte) (1 + n % 250)}), 0));
        } catch (final IOException e) {
            // Not Linux: only 127.0.0.1 is bindable. Families then share one exporter address.
            return new DatagramSocket();
        }
    }

    /** Families in name order; within a family, template files before the rest. */
    private static Map<String, List<byte[]>> families(final Path dir) throws IOException {
        final Map<String, List<byte[]>> families = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            final List<Path> sorted = files
                    .filter(f -> f.toString().endsWith(".dat"))
                    .filter(f -> !REJECTED.matcher(f.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(Send::family)
                            .thenComparing(f -> isTemplate(f) ? 0 : 1)
                            .thenComparing(Path::toString))
                    .toList();
            for (final Path file : sorted) {
                families.computeIfAbsent(family(file), k -> new ArrayList<>()).addAll(split(Files.readAllBytes(file)));
            }
        }
        return families;
    }

    static String family(final Path file) {
        final String[] tokens = file.getFileName().toString().replace(".dat", "").split("_");
        final StringBuilder family = new StringBuilder(tokens[0]);
        for (int i = 1; i < tokens.length && !PART.matcher(tokens[i]).matches(); i++) {
            family.append('_').append(tokens[i]);
        }
        return family.toString();
    }

    private static boolean isTemplate(final Path file) {
        final String name = file.getFileName().toString();
        return name.contains("tpl") || name.contains("template") || name.contains("opt");
    }

    /** A fixture may hold several export packets back to back; cut it at each packet boundary. */
    static List<byte[]> split(final byte[] bytes) {
        final List<byte[]> packets = new ArrayList<>();
        final ByteBuffer buf = ByteBuffer.wrap(bytes);
        while (buf.remaining() >= 4) {
            final int start = buf.position();
            final int length = switch (buf.getShort(start) & 0xffff) {
                case 5 -> 24 + 48 * (buf.getShort(start + 2) & 0xffff);
                case 10 -> buf.getShort(start + 2) & 0xffff;
                case 9 -> netflow9Length(buf, start);
                default -> buf.remaining();
            };
            final int end = length <= 0 ? bytes.length : Math.min(bytes.length, start + length);
            packets.add(java.util.Arrays.copyOfRange(bytes, start, end));
            buf.position(end);
        }
        return packets;
    }

    /** NetFlow v9 has no packet length: walk the flowsets until the next header or the end. */
    private static int netflow9Length(final ByteBuffer buf, final int start) {
        int pos = start + 20;
        while (pos + 4 <= buf.limit()) {
            final int id = buf.getShort(pos) & 0xffff;
            final int len = buf.getShort(pos + 2) & 0xffff;
            // Flowset ids 2..255 are reserved, so a 9 here is the next packet's version field.
            if (id == 9 || len < 4) {
                break;
            }
            pos += len;
        }
        return Math.min(pos, buf.limit()) - start;
    }
}
