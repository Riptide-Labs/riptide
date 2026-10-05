/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.benchmarks.inventory;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.riptide.inventory.CredentialSet;
import org.riptide.inventory.InventoryLoader;
import org.riptide.inventory.InventorySnapshot;
import org.riptide.inventory.SnmpProfilesConfig;
import org.riptide.pipeline.ExporterIdentity;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * What one inventory match costs at the benchmark lab's inventory size: 11,000 single-host
 * enrichment entries, as nl6's Prometheus SD discovery produces them, plus a few agent ranges.
 * {@code batch} repeats the three matches every flow batch pays ({@code ExporterNameEnricher}
 * once, {@code SnmpEnricher} for the agent and the exporter view), so its figure times the lab's
 * batch rate is the lookup's share of a running riptide. Run with {@code -prof gc} for B/op.
 */
@Fork(value = 1)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class InventoryLookupBenchmark {

    private static final int EXPORTERS = 11_000;

    @Param({"v4-hit", "v4-miss", "v4-pinned-hit", "v6-hit"})
    public String probe;

    private InventorySnapshot snapshot;
    private ExporterIdentity identity;

    @Setup
    public void setup() throws Exception {
        final var yaml = new StringBuilder("""
                riptide:
                  snmp:
                    agents:
                      "10.0.0.0/10": { credentials: bench }
                      "10.64.0.0/10": { credentials: bench }
                      "2001:db8::/32": { credentials: bench }
                  exporters:
                    pinned-exporter: { address: 192.0.2.7, observation-domain: 7 }
                """);
        for (int i = 0; i < EXPORTERS; i++) {
            yaml.append("    exporter-").append(i).append(": { address: 10.")
                    .append((i >> 16) & 0xff).append('.').append((i >> 8) & 0xff).append('.').append(i & 0xff)
                    .append(" }\n");
        }
        for (int i = 0; i < 100; i++) {
            yaml.append("    exporter-v6-").append(i).append(": { address: \"2001:db8::").append(Integer.toHexString(i + 1))
                    .append("\" }\n");
        }
        final var profiles = new SnmpProfilesConfig(
                Map.of("bench", CredentialSet.usm("riptide")),
                Map.of());
        this.snapshot = InventoryLoader.parse(profiles, yaml.toString(), "bench.yaml");
        this.identity = switch (this.probe) {
            case "v4-hit" -> new ExporterIdentity.NetflowIpfix(InetAddress.getByName("10.0.42.17"), 0);
            case "v4-miss" -> new ExporterIdentity.NetflowIpfix(InetAddress.getByName("172.27.0.1"), 0);
            case "v4-pinned-hit" -> new ExporterIdentity.NetflowIpfix(InetAddress.getByName("192.0.2.7"), 7);
            case "v6-hit" -> new ExporterIdentity.NetflowIpfix(InetAddress.getByName("2001:db8::2a"), 0);
            default -> throw new IllegalArgumentException(this.probe);
        };
        if (!this.probe.equals("v4-miss") && this.snapshot.exporterView().match(this.identity).isEmpty()) {
            throw new IllegalStateException("probe " + this.probe + " should hit an enrichment entry");
        }
    }

    @Benchmark
    public Object exporterMatch() {
        return this.snapshot.exporterView().match(this.identity);
    }

    @Benchmark
    public void batch(final Blackhole blackhole) {
        blackhole.consume(this.snapshot.exporterView().match(this.identity));
        blackhole.consume(this.snapshot.agentView().match(this.identity));
        blackhole.consume(this.snapshot.exporterView().match(this.identity));
    }
}
