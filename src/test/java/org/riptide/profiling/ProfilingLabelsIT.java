/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.profiling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pyroscope.javaagent.PyroscopeAgent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.riptide.e2e.ContainerImages;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stage and component labels reach a real Pyroscope, and a stage's profile holds only that stage.
 *
 * <p>Runs riptide in-process like the other ITs, so the profiling agent it starts is process-wide;
 * {@link #stopTheAgent()} stops it, or it would keep sampling and uploading for every IT after this one.
 * The agent reads its server from {@code pyroscope.*} system properties as well as the environment,
 * which is how this test points it at the container.
 */
@SpringBootTest
@ActiveProfiles("e2e")
class ProfilingLabelsIT {

    private static final String SERVICE = "riptide-profiling-it";

    private static final GenericContainer<?> CLICKHOUSE = new GenericContainer<>(ContainerImages.clickhouse())
            .withEnv("CLICKHOUSE_DB", "riptide")
            .withEnv("CLICKHOUSE_USER", "riptide")
            .withEnv("CLICKHOUSE_PASSWORD", "riptide")
            .withExposedPorts(8123)
            .waitingFor(Wait.forHttp("/ping").forPort(8123).forStatusCode(200));

    private static final GenericContainer<?> PYROSCOPE = new GenericContainer<>(ContainerImages.pyroscope())
            .withExposedPorts(4040)
            // /ready answers 503 until the ingester has joined its ring: 63 s measured locally, past the
            // 60 s default wait.
            .waitingFor(Wait.forHttp("/ready").forPort(4040).forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    private static final int FLOWS_PORT = freeUdpPort();

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final ObjectMapper JSON = new ObjectMapper();

    static {
        CLICKHOUSE.start();
        PYROSCOPE.start();
        System.setProperty("pyroscope.server.address", pyroscopeUrl());
        System.setProperty("pyroscope.application.name", SERVICE);
        System.setProperty("pyroscope.upload.interval", "5s");
    }

    @DynamicPropertySource
    static void riptideProperties(final DynamicPropertyRegistry registry) {
        registry.add("riptide.clickhouse.endpoint",
                () -> "http://" + CLICKHOUSE.getHost() + ":" + CLICKHOUSE.getMappedPort(8123));
        registry.add("riptide.clickhouse.username", () -> "riptide");
        registry.add("riptide.clickhouse.password", () -> "riptide");
        registry.add("riptide.enricher.hostnames.enabled", () -> "false");
        registry.add("riptide.profiling.enabled", () -> "true");
        registry.add("riptide.receivers.flows.type", () -> "multi");
        registry.add("riptide.receivers.flows.host", () -> "127.0.0.1");
        registry.add("riptide.receivers.flows.port", () -> FLOWS_PORT);
    }

    @AfterAll
    static void stopTheAgent() {
        PyroscopeAgent.stop();
        ProfilingLabels.disable();
        for (final String key : List.of("pyroscope.server.address", "pyroscope.application.name",
                "pyroscope.upload.interval")) {
            System.clearProperty(key);
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.codahale.metrics.MetricRegistry metrics;

    @Test
    void stagesReachPyroscopeAndAStagesProfileHoldsOnlyThatStage() throws Exception {
        sendNetflow5(Duration.ofSeconds(60));
        // The flusher only has work to label if flows reach ClickHouse: without the e2e profile a
        // test-scoped NoopRepository takes them and the batch writer never runs.
        assertThat(this.metrics.timer("persister.batch.flush").getCount())
                .as("the batch writer inserted batches, so the flusher had work to label").isPositive();

        final Set<String> stages = awaitLabelValues("stage", Set.of("listener", "parser-dispatch", "batch-writer"));
        assertThat(stages).contains("listener", "parser-dispatch", "batch-writer");
        assertThat(labelValues("component", "{service_name=\"" + SERVICE + "\"}"))
                .as("the receiver names the listener, the receiver:protocol names its parser")
                .contains("flows", "flows:netflow5", "flusher");

        final Set<String> types = labelValues("__profile_type__", "{service_name=\"" + SERVICE + "\"}");
        System.out.println("ProfilingLabelsIT profile types: " + types);
        assertThat(types).anyMatch(t -> t.startsWith("process_cpu:"));
        assertThat(types).as("allocation profiles on by default").anyMatch(t -> t.startsWith("memory:alloc"));

        final Set<String> flusherFrames = frames("process_cpu:cpu:nanoseconds:cpu:nanoseconds",
                "{service_name=\"" + SERVICE + "\", stage=\"batch-writer\"}");
        assertThat(flusherFrames).as("the batch writer's profile holds the flush")
                .anyMatch(f -> f.endsWith("BatchingFlowRepository.flush"));
        assertThat(flusherFrames).as("and none of the listener's read handler")
                .noneMatch(f -> f.contains("UdpListener$AccountingHandler.channelRead"));

        final String service = "{service_name=\"" + SERVICE + "\"";
        System.out.println("ProfilingLabelsIT CPU seconds: all=" + total(service + "}") / 1e9
                + " unlabelled=" + total(service + ", stage=\"\"}") / 1e9
                + " batch-writer=" + total(service + ", stage=\"batch-writer\"}") / 1e9
                + " listener=" + total(service + ", stage=\"listener\"}") / 1e9
                + " parser-dispatch=" + total(service + ", stage=\"parser-dispatch\"}") / 1e9
                + " sender (unlabelled, this test's own thread)=" + selfFrames(service + ", stage=\"\"}", "sendNetflow5"));
        final Set<String> outside = frames("process_cpu:cpu:nanoseconds:cpu:nanoseconds",
                "{service_name=\"" + SERVICE + "\", stage=\"\"}");
        System.out.println("ProfilingLabelsIT stage=\"\" frames: " + outside.size()
                + ", flush among them: " + outside.stream().anyMatch(f -> f.endsWith("BatchingFlowRepository.flush")));
        assertThat(outside).as("stage=\"\" selects the unlabelled samples, outside the pipeline")
                .isNotEmpty()
                .noneMatch(f -> f.endsWith("BatchingFlowRepository.flush"))
                .noneMatch(f -> f.contains("UdpListener$AccountingHandler.channelRead"));
    }

    /** NetFlow v5 with 30 records per datagram, as fast as one thread sends for {@code duration}. */
    private static void sendNetflow5(final Duration duration) throws IOException {
        final Instant end = Instant.now().plus(duration);
        int sequence = 0;
        try (var socket = new DatagramSocket()) {
            final var loopback = InetAddress.getLoopbackAddress();
            while (Instant.now().isBefore(end)) {
                final ByteBuffer packet = ByteBuffer.allocate(24 + 30 * 48);
                packet.putShort((short) 5).putShort((short) 30).putInt(5000)
                        .putInt((int) Instant.now().getEpochSecond()).putInt(0).putInt(sequence).putInt(0);
                for (int i = 0; i < 30; i++) {
                    packet.put(new byte[] {10, 1, (byte) i, (byte) (sequence % 250 + 1)}).put(new byte[] {10, 2, 0, 1})
                            .putInt(0).putShort((short) 1).putShort((short) 2).putInt(10).putInt(1500)
                            .putInt(1000).putInt(2000).putShort((short) (40000 + i)).putShort((short) 443)
                            .put((byte) 0).put((byte) 0x18).put((byte) 6).put((byte) 0)
                            .putShort((short) 0).putShort((short) 0).put((byte) 24).put((byte) 24).putShort((short) 0);
                }
                socket.send(new DatagramPacket(packet.array(), packet.capacity(), loopback, FLOWS_PORT));
                sequence += 30;
                if (sequence % 3000 == 0) {
                    Thread.onSpinWait();
                }
            }
        }
    }

    private static Set<String> awaitLabelValues(final String name, final Set<String> wanted) throws Exception {
        final Instant deadline = Instant.now().plus(Duration.ofMinutes(2));
        Set<String> seen = Set.of();
        while (Instant.now().isBefore(deadline)) {
            seen = labelValues(name, "{service_name=\"" + SERVICE + "\"}");
            if (seen.containsAll(wanted)) {
                return seen;
            }
            Thread.sleep(2_000);
        }
        return seen;
    }

    private static Set<String> labelValues(final String name, final String matcher) throws Exception {
        final long now = System.currentTimeMillis();
        final JsonNode body = post("/querier.v1.QuerierService/LabelValues", JSON.createObjectNode()
                .put("name", name).put("start", now - 3_600_000).put("end", now)
                .set("matchers", JSON.createArrayNode().add(matcher)));
        final Set<String> values = new TreeSet<>();
        body.path("names").forEach(v -> values.add(v.asText()));
        return values;
    }

    private static long total(final String selector) throws Exception {
        final long now = System.currentTimeMillis();
        return post("/querier.v1.QuerierService/SelectMergeStacktraces", JSON.createObjectNode()
                .put("profileTypeID", "process_cpu:cpu:nanoseconds:cpu:nanoseconds").put("labelSelector", selector)
                .put("start", now - 3_600_000).put("end", now).put("maxNodes", 16_384))
                .path("flamegraph").path("total").asLong();
    }

    /** Whether any frame of the merged flame graph for {@code selector} names {@code method}. */
    private static boolean selfFrames(final String selector, final String method) throws Exception {
        return frames("process_cpu:cpu:nanoseconds:cpu:nanoseconds", selector).stream().anyMatch(f -> f.contains(method));
    }

    /** Every frame name in the merged flame graph for {@code selector}. */
    private static Set<String> frames(final String profileType, final String selector) throws Exception {
        final long now = System.currentTimeMillis();
        final JsonNode body = post("/querier.v1.QuerierService/SelectMergeStacktraces", JSON.createObjectNode()
                .put("profileTypeID", profileType).put("labelSelector", selector)
                .put("start", now - 3_600_000).put("end", now).put("maxNodes", 16_384));
        final List<String> names = new ArrayList<>();
        body.path("flamegraph").path("names").forEach(v -> names.add(v.asText()));
        final Set<String> present = new TreeSet<>();
        body.path("flamegraph").path("levels").forEach(level -> {
            final JsonNode values = level.path("values");
            for (int i = 3; i < values.size(); i += 4) {
                present.add(names.get(values.get(i).asInt()));
            }
        });
        return present;
    }

    private static JsonNode post(final String path, final JsonNode request) throws Exception {
        final HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(pyroscopeUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("Pyroscope %s: %s", path, response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static String pyroscopeUrl() {
        return "http://" + PYROSCOPE.getHost() + ":" + PYROSCOPE.getMappedPort(4040);
    }

    private static int freeUdpPort() {
        try (var socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (final IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
