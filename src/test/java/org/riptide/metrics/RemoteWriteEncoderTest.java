/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.metrics;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import io.airlift.compress.snappy.SnappyDecompressor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteWriteEncoderTest {

    @Test
    void oneSampleBecomesOneTimeSeriesWithSortedLabelsAndTheNameFirst() throws IOException {
        final var sample = new Sample("ifHCInOctets", Map.of("ifName", "Gi0/0/3", "exporter", "edge-01", "ifIndex", "3"),
                1_000d, 1_700_000_000_000L);

        final List<Series> series = decode(RemoteWriteEncoder.encode(List.of(sample)));

        assertThat(series).hasSize(1);
        assertThat(series.get(0).labels).containsExactly(
                Map.entry("__name__", "ifHCInOctets"), Map.entry("exporter", "edge-01"),
                Map.entry("ifIndex", "3"), Map.entry("ifName", "Gi0/0/3"));
        assertThat(series.get(0).value).isEqualTo(1_000d);
        assertThat(series.get(0).timestamp).isEqualTo(1_700_000_000_000L);
    }

    @Test
    void snappyRoundTripsThroughAnIndependentDecompressor() {
        final byte[] plain = RemoteWriteEncoder.encode(List.of(new Sample("x", Map.of("a", "b"), 1d, 2L)));
        final byte[] packed = RemoteWriteEncoder.snappy(plain);
        final byte[] out = new byte[plain.length];
        final int n = new SnappyDecompressor().decompress(packed, 0, packed.length, out, 0, out.length);
        assertThat(n).isEqualTo(plain.length);
        assertThat(out).isEqualTo(plain);
    }

    // A minimal decoder for the remote-write 1.0 schema, using protobuf-java's wire reader so the
    // varints and tags are checked by an implementation other than the one under test.
    private record Series(Map<String, String> labels, double value, long timestamp) {
    }

    private static List<Series> decode(final byte[] bytes) throws IOException {
        final CodedInputStream in = CodedInputStream.newInstance(bytes);
        final List<Series> result = new ArrayList<>();
        int tag;
        while ((tag = in.readTag()) != 0) {
            assertThat(WireFormat.getTagFieldNumber(tag)).as("WriteRequest.timeseries").isEqualTo(1);
            final CodedInputStream ts = CodedInputStream.newInstance(in.readByteArray());
            final Map<String, String> labels = new LinkedHashMap<>();
            double value = Double.NaN;
            long timestamp = 0;
            int tsTag;
            while ((tsTag = ts.readTag()) != 0) {
                switch (WireFormat.getTagFieldNumber(tsTag)) {
                    case 1 -> {
                        final CodedInputStream label = CodedInputStream.newInstance(ts.readByteArray());
                        String name = null;
                        String val = null;
                        int lt;
                        while ((lt = label.readTag()) != 0) {
                            if (WireFormat.getTagFieldNumber(lt) == 1) {
                                name = label.readString();
                            } else {
                                val = label.readString();
                            }
                        }
                        labels.put(name, val);
                    }
                    case 2 -> {
                        final CodedInputStream s = CodedInputStream.newInstance(ts.readByteArray());
                        int st;
                        while ((st = s.readTag()) != 0) {
                            if (WireFormat.getTagFieldNumber(st) == 1) {
                                value = s.readDouble();
                            } else {
                                timestamp = s.readInt64();
                            }
                        }
                    }
                    default -> throw new AssertionError("unexpected field " + tsTag);
                }
            }
            result.add(new Series(labels, value, timestamp));
        }
        return result;
    }
}
