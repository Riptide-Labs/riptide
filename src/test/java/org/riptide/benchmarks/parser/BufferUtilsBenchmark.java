/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.benchmarks.parser;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.riptide.flows.parser.ie.InformationElement;
import org.riptide.flows.parser.ie.Value;
import org.riptide.flows.parser.ie.values.UnsignedValue;
import org.riptide.flows.parser.ie.values.visitor.LongVisitor;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

import static org.riptide.flows.utils.BufferUtils.slice;
import static org.riptide.flows.utils.BufferUtils.uint;

/**
 * What decoding one unsigned field costs, the way {@code DataRecord.parseField} does it: a
 * {@code slice} of the field's width, then {@code uint} over the slice (#992).
 *
 * <ul>
 *   <li>{@code uintField} is the acceptance gate of #992: {@code gc.alloc.rate.norm} under
 *       {@code -prof gc} for 64 fields, below 1,536 B/op. It counts the slice too, so a baseline
 *       above 64 boxes means escape analysis did not remove the slice.</li>
 *   <li>{@code unsignedValueField} is the real per-field path, through
 *       {@link UnsignedValue#parserWith64Bit} and {@link LongVisitor}. It parses every field into
 *       an array before converting any, as {@code DataRecord} collects {@code parsedFields}, so the
 *       values escape as they do in riptide. Parsed and converted in one step, escape analysis
 *       removes the {@link UnsignedValue} and its box, and the mode would measure nothing. It
 *       still allocates the {@link UnsignedValue} and the {@link Long} the visitor returns
 *       (visitors are generic, and the raw flows hold boxed fields), so it shows the gain without
 *       overstating it.</li>
 * </ul>
 *
 * <p>One op decodes {@value #FIELDS} fields, each result to the {@link Blackhole}. Run with
 * {@code make bench-jmh BENCH_TARGET=BufferUtilsBenchmark BENCH_OPTS='-prof gc'}.</p>
 */
@Fork(value = 1)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class BufferUtilsBenchmark {

    private static final int FIELDS = 64;

    @Param({"1", "2", "3", "4", "5", "6", "7", "8"})
    public int width;

    @Param({"heap", "direct"})
    public String buffer;

    private ByteBuf fields;
    private final InformationElement element = UnsignedValue.parserWith64Bit("field", null, null);
    private final LongVisitor visitor = new LongVisitor();
    private final Value<?>[] parsed = new Value<?>[FIELDS];

    @Setup(Level.Trial)
    public void setUp() {
        final byte[] bytes = new byte[FIELDS * this.width];
        // Seeded for the same bytes on every run; not a security use, so not java.util.Random.
        new SplittableRandom(992).nextBytes(bytes);
        this.fields = "direct".equals(this.buffer) ? Unpooled.directBuffer(bytes.length) : Unpooled.buffer(bytes.length);
        this.fields.writeBytes(bytes);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        this.fields.release();
    }

    @Benchmark
    public void uintField(final Blackhole bh) {
        this.fields.readerIndex(0);
        for (int i = 0; i < FIELDS; i++) {
            bh.consume(uint(slice(this.fields, this.width), this.width));
        }
    }

    @Benchmark
    public void unsignedValueField(final Blackhole bh) throws Exception {
        this.fields.readerIndex(0);
        for (int i = 0; i < FIELDS; i++) {
            this.parsed[i] = this.element.parse(null, slice(this.fields, this.width));
        }
        for (int i = 0; i < FIELDS; i++) {
            bh.consume(this.parsed[i].accept(this.visitor));
        }
    }
}
