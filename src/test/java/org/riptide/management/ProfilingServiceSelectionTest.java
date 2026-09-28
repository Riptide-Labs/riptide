/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A collector may report under its own {@code PYROSCOPE_APPLICATION_NAME}, which the profiling guide
 * recommends when several collectors share one server. A dashboard that names the service in its
 * selectors shows nothing for such a collector (#938), so every Pyroscope selector reads it from the
 * {@code service} variable, and that variable offers every service but Pyroscope's own.
 */
class ProfilingServiceSelectionTest {

    private static final Path DASHBOARDS = Path.of("deployment/clickhouse/container-fs/grafana/provisioning/dashboards");
    private static final String SELECTED = "service_name=\"$service\"";

    @Test
    void noPyroscopeSelectorNamesTheService() throws IOException {
        final List<String> named = new ArrayList<>();
        int selectors = 0;
        try (Stream<Path> files = Files.list(DASHBOARDS)) {
            for (final Path file : files.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                for (final JsonNode selector : new ObjectMapper().readTree(file.toFile()).findValues("labelSelector")) {
                    selectors++;
                    final String text = selector.asText();
                    if (text.contains("service_name") && !text.contains(SELECTED)) {
                        named.add(file.getFileName() + ": " + text);
                    }
                }
            }
        }
        assertThat(selectors).as("Pyroscope selectors found").isGreaterThanOrEqualTo(6);
        assertThat(named).as("selectors that name the service instead of $service").isEmpty();
    }

    @Test
    void aLinkBackIntoTheDashboardKeepsTheService() throws IOException {
        final List<String> links = new ArrayList<>();
        for (final JsonNode url : new ObjectMapper().readTree(DASHBOARDS.resolve("riptide-profiling.json").toFile()).findValues("url")) {
            if (url.asText().contains("/d/riptide-profiling/")) {
                links.add(url.asText());
            }
        }
        assertThat(links).as("stage drill-down links").hasSizeGreaterThanOrEqualTo(3);
        assertThat(links).as("links that reset Service to its default").allMatch(link -> link.contains("${service:queryparam}"));
    }

    @Test
    void theServiceVariableListsEveryCollectorButPyroscopeItself() throws IOException {
        final JsonNode service = variable("riptide-profiling.json", "service");
        assertThat(service.path("type").asText()).isEqualTo("query");
        assertThat(service.path("query").path("type").asText()).isEqualTo("labelValue");
        assertThat(service.path("query").path("labelName").asText()).isEqualTo("service_name");
        assertThat(service.path("current").path("value").asText()).as("default service").isEqualTo("riptide");

        final String regex = service.path("regex").asText();
        assertThat(regex).startsWith("/").endsWith("/");
        final Pattern offered = Pattern.compile(regex.substring(1, regex.length() - 1));
        assertThat(List.of("riptide", "riptide-edge-01", "edge-01")).allMatch(name -> offered.matcher(name).find());
        assertThat(offered.matcher("pyroscope").find()).as("Pyroscope's own service is offered").isFalse();
    }

    private static JsonNode variable(final String dashboard, final String name) throws IOException {
        for (final JsonNode variable : new ObjectMapper().readTree(DASHBOARDS.resolve(dashboard).toFile()).path("templating").path("list")) {
            if (name.equals(variable.path("name").asText())) {
                return variable;
            }
        }
        throw new AssertionError(dashboard + " has no variable " + name);
    }
}
