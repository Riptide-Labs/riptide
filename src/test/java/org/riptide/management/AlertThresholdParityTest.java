/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The self-monitoring dashboards repeat the thresholds of riptide's alert rules, and must not drift
 * from them: a panel that turns amber at 75% for an alert that fires at 80% tells an operator the
 * wrong story. The rules file is the only source; this test holds every panel to it.
 *
 * <p>A panel or field override mirrors an alert when it links to that alert's runbook section. Its
 * first threshold step above the base is then the alert's threshold. Only the ratio alerts are
 * held to this; the loss, down and reload alerts fire on any movement and their panels mark that
 * with a small positive step or a value mapping instead.
 */
class AlertThresholdParityTest {

    private static final Path RULES = Path.of("deployment/clickhouse/container-fs/prometheus/riptide-alerts.yml");
    private static final Path DASHBOARDS = Path.of("deployment/clickhouse/container-fs/grafana/provisioning/dashboards");
    private static final List<String> MIRRORING = List.of("riptide-health.json", "riptide-profiling.json", "riptide-stage-detail.json");

    /** "... > 0.8" at the end of an alert expression. */
    private static final Pattern THRESHOLD = Pattern.compile(">\\s*([0-9.]+)\\s*$");
    private static final Pattern RUNBOOK = Pattern.compile("#(riptide[a-z]+)$");

    @Test
    void everyMirroringPanelUsesItsAlertsThreshold() throws IOException {
        final Map<String, Double> alerts = ratioAlertThresholds();
        assertThat(alerts).as("ratio alerts in the rules file").hasSize(6);

        final List<String> drift = new ArrayList<>();
        final Set<String> mirrored = new HashSet<>();
        for (final String file : MIRRORING) {
            final JsonNode dashboard = new ObjectMapper().readTree(DASHBOARDS.resolve(file).toFile());
            for (final JsonNode panel : dashboard.path("panels")) {
                final String where = file + " panel " + panel.path("id").asInt() + " \"" + panel.path("title").asText() + "\"";
                final JsonNode defaults = panel.path("fieldConfig").path("defaults");
                check(where, defaults.path("links"), defaults.path("thresholds"), alerts, drift, mirrored);
                for (final JsonNode override : panel.path("fieldConfig").path("overrides")) {
                    JsonNode links = null;
                    JsonNode thresholds = null;
                    for (final JsonNode property : override.path("properties")) {
                        if ("links".equals(property.path("id").asText())) {
                            links = property.path("value");
                        } else if ("thresholds".equals(property.path("id").asText())) {
                            thresholds = property.path("value");
                        }
                    }
                    if (links != null) {
                        check(where + " override " + override.path("matcher").path("options").asText(),
                                links, thresholds != null ? thresholds : defaults.path("thresholds"), alerts, drift, mirrored);
                    }
                }
            }
        }

        assertThat(drift).as("panels whose threshold differs from their alert's").isEmpty();
        assertThat(mirrored).as("every ratio alert is shown by at least one panel").isEqualTo(alerts.keySet());
    }

    private static void check(final String where, final JsonNode links, final JsonNode thresholds,
                              final Map<String, Double> alerts, final List<String> drift, final Set<String> mirrored) {
        for (final JsonNode link : links) {
            final Matcher m = RUNBOOK.matcher(link.path("url").asText());
            if (!m.find() || !alerts.containsKey(m.group(1))) {
                continue;
            }
            final String alert = m.group(1);
            mirrored.add(alert);
            final JsonNode steps = thresholds.path("steps");
            final double panel = steps.size() > 1 ? steps.get(1).path("value").asDouble(Double.NaN) : Double.NaN;
            if (Double.compare(panel, alerts.get(alert)) != 0) {
                drift.add(where + ": " + panel + " for " + alert + " at " + alerts.get(alert));
            }
        }
    }

    /** Alert name in lower case, as the runbook anchor spells it, to its threshold. */
    @SuppressWarnings("unchecked")
    private static Map<String, Double> ratioAlertThresholds() throws IOException {
        final Map<String, Object> rules = new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(Files.readString(RULES));
        final Map<String, Double> thresholds = new LinkedHashMap<>();
        for (final Map<String, Object> group : (List<Map<String, Object>>) rules.get("groups")) {
            for (final Map<String, Object> rule : (List<Map<String, Object>>) group.get("rules")) {
                final Object alert = rule.get("alert");
                final Matcher m = THRESHOLD.matcher(String.valueOf(rule.get("expr")).strip());
                final double value = m.find() ? Double.parseDouble(m.group(1)) : 0;
                if (alert != null && value > 0) {
                    thresholds.put(String.valueOf(alert).toLowerCase(java.util.Locale.ROOT), value);
                }
            }
        }
        return thresholds;
    }
}
