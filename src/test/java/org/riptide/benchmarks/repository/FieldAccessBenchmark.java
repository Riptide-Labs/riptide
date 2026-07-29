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
 *   <li>There is deliberately no "direct call" mode. An earlier revision had one built from a
 *       lambda wrapping a captured {@link MethodHandle}, which cannot inline to a direct call and so
 *       measured lambda indirection rather than a floor. {@code methodHandle} is the honest floor
 *       here; a hand-written accessor would be at or below it.</li>
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
 *   reflect (current)       2,572,855        389                          1.14%
 *   reflect+setAccessible   2,883,674        347                          1.02%
 *   MethodHandle            4,149,052        241                          0.71%   <- the floor
 * </pre>
 *
 * <p>Measured on a POJO populated with values outside the {@code Integer}/{@code Long} caches, so
 * the figures are not flattered by an all-defaults row: doing that moved the reflective number by
 * about 2% (2,513,210 → 2,572,855 passes/s), which does not change the conclusion.
 *
 * <p>So the <em>entire</em> addressable saving from eliminating reflective field access is
 * <strong>~0.43% of one core</strong> at the throughput the collector was measured at, and
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

    @Setup(Level.Trial)
    public void setup() throws Exception {
        // Not an all-defaults instance: null references and small boxed values hit the
        // Integer/Long caches, so a default row understates the reflective cost in exactly the
        // direction that flatters the "not worth doing" conclusion. Fill it with values outside the
        // caches, like a real flow record.
        this.row = new ClickhouseFlow();
        this.row.setBytes(9_876_543_210L);
        this.row.setPackets(1_234_567L);
        this.row.setSrcPort(54_321);
        this.row.setDstPort(9_999);
        this.row.setSrcAs(64_512L);
        this.row.setDstAs(65_001L);
        this.row.setTenant("tenant-a");
        this.row.setExporterAddr("198.51.100.7");
        this.row.setTimestamp(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));

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


        // The javadoc extrapolates per-row cost from the column count, so fail loudly if the
        // discovered getters and the persisted columns ever diverge.
        final long columns = java.util.Arrays.stream(ClickhouseFlow.class.getDeclaredFields())
                .filter(f -> !java.lang.reflect.Modifier.isStatic(f.getModifiers()) && !f.isSynthetic())
                .count();
        if (this.plain.length < columns) {
            throw new IllegalStateException("discovered " + this.plain.length
                    + " getters for " + columns + " persisted columns — the extrapolation is stale");
        }
        System.out.printf("%n[setup] ClickhouseFlow getters=%d columns=%d%n", this.plain.length, columns);
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

}
