/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Device;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * The nl6 network simulator (https://github.com/labmonkeys-space/nl6) as a
 * Testcontainers container, used as a flow generator for e2e tests.
 *
 * The image tag is a deliberate cross-repo contract with nl6's wire format —
 * it is NOT managed by Dependabot; bump it in an explicit PR and investigate
 * any e2e failures the bump surfaces.
 *
 * nl6 creates one TUN interface per simulated device, which requires
 * NET_ADMIN + SYS_ADMIN capabilities and /dev/net/tun even when flow export
 * uses a single shared socket (-flow-source-per-device=false).
 */
public final class Nl6Container extends GenericContainer<Nl6Container> {

    private static final String IMAGE = "ghcr.io/labmonkeys-space/nl6:v0.15.1";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public Nl6Container() {
        super(IMAGE);
        withExposedPorts(8080);
        withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                .withCapAdd(Capability.NET_ADMIN, Capability.SYS_ADMIN)
                .withDevices(new Device("rwm", "/dev/net/tun", "/dev/net/tun")));
        // Lets simulated devices reach riptide's listeners on the host JVM.
        withExtraHost("host.docker.internal", "host-gateway");
        withCommand("-flow-source-per-device=false", "-flow-template-interval", "5");
        waitingFor(Wait.forHttp("/api/v1/flows/status").forPort(8080).forStatusCode(200));
    }

    @Override
    public void start() {
        try {
            super.start();
        } catch (final RuntimeException e) {
            String logs = "";
            try {
                logs = getLogs();
            } catch (final RuntimeException ignored) {
                // container may be gone; fall through with the original failure
            }
            if (logs.contains("/dev/net/tun")) {
                throw new IllegalStateException(
                        "nl6 requires NET_ADMIN + SYS_ADMIN capabilities and /dev/net/tun; "
                        + "this Docker environment does not grant them (see design of change e2e-flow-testing)", e);
            }
            throw e;
        }
    }

    /** Creates a batch of devices exporting flows to the given host port on the Docker host. */
    public void createDevices(final String startIp, final int count, final String protocol, final int collectorHostPort) throws Exception {
        final var body = objectMapper.writeValueAsString(Map.of(
                "start_ip", startIp,
                "device_count", count,
                "flow", Map.of(
                        "collector", "host.docker.internal:" + collectorHostPort,
                        "protocol", protocol,
                        "tick_interval", "2s",
                        "active_timeout", "5s",
                        "inactive_timeout", "5s")));

        final var request = HttpRequest.newBuilder(URI.create(apiBase() + "/devices"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        final var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("nl6 device creation failed (" + response.statusCode() + "): " + response.body());
        }
    }

    /** Records sent so far for the collector speaking the given protocol, per nl6's ledger. */
    public long sentRecords(final String protocol) throws Exception {
        final var request = HttpRequest.newBuilder(URI.create(apiBase() + "/flows/status")).GET().build();
        final var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        final var collectors = objectMapper.readTree(response.body()).path("data").path("collectors");
        for (final var collector : collectors) {
            if (protocol.equals(collector.path("protocol").asText())) {
                return collector.path("sent_records").asLong();
            }
        }
        return 0;
    }

    private String apiBase() {
        return "http://" + getHost() + ":" + getMappedPort(8080) + "/api/v1";
    }
}
