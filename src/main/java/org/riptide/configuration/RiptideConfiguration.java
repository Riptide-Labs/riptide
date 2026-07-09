/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.configuration;

import com.codahale.metrics.MetricRegistry;
import lombok.extern.slf4j.Slf4j;
import org.riptide.classification.ClassificationEngine;
import org.riptide.classification.ClassificationRuleProvider;
import org.riptide.classification.internal.AsyncReloadingClassificationEngine;
import org.riptide.classification.internal.DefaultClassificationEngine;
import org.riptide.classification.internal.TimingClassificationEngine;
import org.riptide.classification.internal.csv.CsvImporter;
import org.riptide.config.ClassificationConfig;
import org.riptide.flows.parser.ie.values.ValueConversionService;
import org.riptide.flows.parser.ie.values.visitor.ValueVisitor;
import org.riptide.flows.parser.ipfix.IpfixRawFlow;
import org.riptide.flows.parser.netflow9.Netflow9RawFlow;
import org.riptide.pipeline.FlowPersister;
import org.riptide.repository.FlowRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

@Configuration
@Slf4j
public class RiptideConfiguration {

    @Bean
    MetricRegistry metricRegistry() {
        return new MetricRegistry();
    }

    @Bean
    ValueConversionService ipfixValueConversionService(List<ValueVisitor<?>> visitors) {
        return new ValueConversionService(IpfixRawFlow.class, visitors);
    }

    @Bean
    ValueConversionService netflow9ValueConversionService(List<ValueVisitor<?>> visitors) {
        return new ValueConversionService(Netflow9RawFlow.class, visitors);
    }

    @Bean
    FlowPersister flowRepositories(FlowRepository repository, MetricRegistry metricRegistry) {
        return new FlowPersister("persister", repository, metricRegistry);
    }

    @Bean
    CsvImporter csvImporter() {
        return new CsvImporter();
    }

    @Bean
    ClassificationRuleProvider classificationRuleProvider(final CsvImporter importer,
                                                          final ClassificationConfig config) {
        // Re-parse on every call so the reloading engine picks up edits to a
        // file-based rules resource; a one-shot snapshot would make reload() a no-op.
        final ClassificationRuleProvider provider = () -> {
            try (var stream = config.getRules().getInputStream()) {
                return importer.parse(stream, true);
            } catch (final IOException e) {
                throw new UncheckedIOException("Cannot load classification rules from " + config.getRules(), e);
            }
        };
        // Fail fast at startup instead of on the async reload thread.
        provider.getRules();
        return provider;
    }

    @Bean
    ClassificationEngine classificationEngine(final ClassificationRuleProvider classificationRuleProvider,
                                              final MetricRegistry metricRegistry) throws InterruptedException {
        final var engine = new DefaultClassificationEngine(classificationRuleProvider, false);
        final var timingEngine = new TimingClassificationEngine(metricRegistry, engine);
        return new AsyncReloadingClassificationEngine(timingEngine);
    }
}
