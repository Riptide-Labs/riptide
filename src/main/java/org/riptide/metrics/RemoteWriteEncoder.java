/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.metrics;

import io.airlift.compress.v3.snappy.SnappyJavaCompressor;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Prometheus remote-write 1.0 {@code WriteRequest}, hand-encoded. The schema is four messages
 * and nine fields, so the encoder is smaller than the dependency it replaces, in keeping with
 * {@code PrometheusExposition}'s policy of not pulling a bridge library for a small format.
 *
 * <pre>
 * WriteRequest { repeated TimeSeries timeseries = 1; }
 * TimeSeries   { repeated Label labels = 1; repeated Sample samples = 2; }
 * Label        { string name = 1; string value = 2; }
 * Sample       { double value = 1; int64 timestamp = 2; }
 * </pre>
 *
 * Labels are sorted by name, as the protocol requires, and {@code __name__} carries the metric.
 */
public final class RemoteWriteEncoder {

    private static final int WIRE_LENGTH_DELIMITED = 2;
    private static final int WIRE_FIXED64 = 1;
    private static final int WIRE_VARINT = 0;

    private RemoteWriteEncoder() {
    }

    public static byte[] encode(final List<Sample> samples) {
        final ByteArrayOutputStream request = new ByteArrayOutputStream(samples.size() * 96);
        for (final Sample sample : samples) {
            writeBytes(request, 1, timeSeries(sample));
        }
        return request.toByteArray();
    }

    public static byte[] snappy(final byte[] plain) {
        // Constructed directly, not through SnappyCompressor.create(): the factory prefers a
        // native library bundled in the jar and reaches for it through the Foreign Function
        // API, which warns on every call. This class never touches that loader.
        final SnappyJavaCompressor compressor = new SnappyJavaCompressor();
        final byte[] out = new byte[compressor.maxCompressedLength(plain.length)];
        final int n = compressor.compress(plain, 0, plain.length, out, 0, out.length);
        return Arrays.copyOf(out, n);
    }

    private static byte[] timeSeries(final Sample sample) {
        final ByteArrayOutputStream ts = new ByteArrayOutputStream(96);
        final Map<String, String> labels = new TreeMap<>(sample.labels());
        labels.put("__name__", sample.name());
        for (final Map.Entry<String, String> label : labels.entrySet()) {
            final ByteArrayOutputStream l = new ByteArrayOutputStream(32);
            writeString(l, 1, label.getKey());
            writeString(l, 2, label.getValue());
            writeBytes(ts, 1, l.toByteArray());
        }
        final ByteArrayOutputStream s = new ByteArrayOutputStream(20);
        writeTag(s, 1, WIRE_FIXED64);
        long bits = Double.doubleToLongBits(sample.value());
        for (int i = 0; i < 8; i++) {
            s.write((int) (bits & 0xFF));
            bits >>>= 8;
        }
        writeTag(s, 2, WIRE_VARINT);
        writeVarint(s, sample.timestampMs());
        writeBytes(ts, 2, s.toByteArray());
        return ts.toByteArray();
    }

    private static void writeString(final ByteArrayOutputStream out, final int field, final String value) {
        writeBytes(out, field, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(final ByteArrayOutputStream out, final int field, final byte[] value) {
        writeTag(out, field, WIRE_LENGTH_DELIMITED);
        writeVarint(out, value.length);
        out.writeBytes(value);
    }

    private static void writeTag(final ByteArrayOutputStream out, final int field, final int wireType) {
        writeVarint(out, ((long) field << 3) | wireType);
    }

    private static void writeVarint(final ByteArrayOutputStream out, final long value) {
        long v = value;
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }
}
