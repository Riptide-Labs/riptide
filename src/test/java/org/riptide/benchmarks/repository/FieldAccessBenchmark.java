/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.benchmarks.repository;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.riptide.repository.clickhouse.ClickhouseFlow;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * How much does the ClickHouse client's reflective POJO field access cost per row?
 *
 * <p>Profiling the batch flusher at ~29k rows/s attributed ~21% of its samples to
 * {@code DirectMethodHandleAccessor.invoke} and ~7% to {@code AccessibleObject.verifyAccess} —
 * i.e. roughly a quarter of the thread spent getting values *out of* the POJO rather than
 * serializing them. {@code POJOFieldSerializer.serialize} declares
 * {@code InvocationTargetException, IllegalAccessException}, which is only possible if it calls
 * {@code Method.invoke} per field per row; {@link ClickhouseFlow} has 55 fields, so that is ~55
 * reflective calls per row.
 *
 * <p>Both the POJO path and a hand-written {@code RowBinaryFormatWriter} path do *identical*
 * serialization work — same bytes, same {@code SerializerUtils}. The only difference is how the
 * value is read out of the object. So this benchmark measures exactly that delta, over the real
 * field set, and nothing else:
 *
 * <ul>
 *   <li>{@code reflectNoSetAccessible} — {@code Method.invoke} on a Method left as-is, which is
 *       what the profile's {@code verifyAccess} frames imply the client does</li>
 *   <li>{@code reflectSetAccessible} — the same, after {@code setAccessible(true)}; isolates how
 *       much of the cost is the per-call access check alone. If this is most of the gap it is a
 *       one-line upstream fix, not a reason to rewrite riptide's insert path.</li>
 *   <li>{@code methodHandle} — unreflected {@link MethodHandle}</li>
 *   <li>{@code direct} — a lambda per field, standing in for generated or hand-written accessors,
 *       i.e. the floor a RowBinaryFormatWriter rewrite could reach</li>
 * </ul>
 *
 * <p>One invocation reads every field once, so {@code ops/s} is rows/s of field access and the
 * ratios transfer directly to the 55-field row.
 *
 * <h2>Result: not worth bypassing the POJO path (measured 2026-07-29)</h2>
 *
 * <p>55 getters, on the 10-core dev machine:
 *
 * <pre>
 *   mode                     passes/s     ns/row   % of one core @ 29,440 rows/s
 *   reflect (current)       2,513,210        398                          1.17%
 *   reflect+setAccessible   2,828,542        354                          1.04%
 *   MethodHandle            4,154,064        241                          0.71%
 * </pre>
 *
 * <p>So the <em>entire</em> addressable saving from eliminating reflective field access is
 * <strong>~0.46% of one core</strong> at the throughput the collector was measured at, and
 * {@code setAccessible} alone would account for 0.13%. Hand-writing a
 * {@code RowBinaryFormatWriter} path over 55 columns — with the attendant risk of silent type,
 * null and enum drift against {@code FlowsSchema} — buys under half a percent of a core on a host
 * that had ~2.9 cores idle. **Deliberately not done.**
 *
 * <p>For scale: LZ4 request compression was ~20% of the same thread, i.e. roughly five times the
 * whole reflection saving, and it is a config flag rather than a rewrite (see
 * {@code ClickhouseConfig#compressRequests}). That is why the flag landed and the rewrite did not.
 *
 * <p>Note the flusher was only 11.6% of one core when profiled, and that run accepted 29,440 of
 * 29,867 offered rows — it was generator-limited, so the flusher has never been shown to be a
 * ceiling at all. Re-run this if the collector is ever demonstrably flusher-bound.
 */
@Fork(value = 1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class FieldAccessBenchmark {

    private ClickhouseFlow row;
    private Method[] plain;
    private Method[] accessible;
    private MethodHandle[] handles;
    private List<Function<ClickhouseFlow, Object>> direct;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        this.row = new ClickhouseFlow();

        // Every zero-arg getter the client's column-to-method matching would find.
        final List<Method> getters = new ArrayList<>();
        for (final Method m : ClickhouseFlow.class.getMethods()) {
            if (m.getParameterCount() == 0
                    && (m.getName().startsWith("get") || m.getName().startsWith("is"))
                    && !m.getName().equals("getClass")) {
                getters.add(m);
            }
        }
        if (getters.isEmpty()) {
            throw new IllegalStateException("no getters discovered on ClickhouseFlow");
        }

        this.plain = getters.toArray(new Method[0]);

        this.accessible = new Method[this.plain.length];
        for (int i = 0; i < this.plain.length; i++) {
            final Method m = ClickhouseFlow.class.getMethod(this.plain[i].getName());
            m.setAccessible(true);
            this.accessible[i] = m;
        }

        final var lookup = MethodHandles.lookup();
        this.handles = new MethodHandle[this.plain.length];
        for (int i = 0; i < this.plain.length; i++) {
            this.handles[i] = lookup.unreflect(this.plain[i]);
        }

        // Lambdas over the same getters: after JIT these are direct calls, so they stand in for
        // generated accessors without hand-writing 55 method references.
        this.direct = new ArrayList<>(this.plain.length);
        for (final Method m : this.plain) {
            final MethodHandle h = lookup.unreflect(m);
            this.direct.add(flow -> {
                try {
                    return h.invoke(flow);
                } catch (final Throwable e) {
                    throw new IllegalStateException(e);
                }
            });
        }

        System.out.printf("%n[setup] ClickhouseFlow getters discovered: %d%n", this.plain.length);
    }

    @Benchmark
    public void reflectNoSetAccessible(final Blackhole bh) throws Exception {
        for (final Method m : this.plain) {
            bh.consume(m.invoke(this.row));
        }
    }

    @Benchmark
    public void reflectSetAccessible(final Blackhole bh) throws Exception {
        for (final Method m : this.accessible) {
            bh.consume(m.invoke(this.row));
        }
    }

    @Benchmark
    public void methodHandle(final Blackhole bh) throws Throwable {
        for (final MethodHandle h : this.handles) {
            bh.consume(h.invoke(this.row));
        }
    }

    @Benchmark
    public void direct(final Blackhole bh) {
        for (final Function<ClickhouseFlow, Object> f : this.direct) {
            bh.consume(f.apply(this.row));
        }
    }
}
