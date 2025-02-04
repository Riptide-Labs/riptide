package org.riptide.configuration;

import com.codahale.metrics.MetricRegistry;
import lombok.extern.slf4j.Slf4j;
import org.riptide.RiptideApplication;
import org.riptide.classification.ClassificationEngine;
import org.riptide.classification.ClassificationRuleProvider;
import org.riptide.classification.internal.AsyncReloadingClassificationEngine;
import org.riptide.classification.internal.DefaultClassificationEngine;
import org.riptide.classification.internal.TimingClassificationEngine;
import org.riptide.classification.internal.csv.CsvImporter;
import org.riptide.pipeline.FlowPersister;
import org.riptide.repository.FlowRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Configuration
@Slf4j
public class RiptideConfiguration {

    @Bean
    MetricRegistry metricRegistry() {
        return new MetricRegistry();
    }

    @Bean
    List<FlowPersister> flowRepositories(Map<String, FlowRepository> repositories, MetricRegistry metricRegistry) {
        if (repositories.isEmpty()) {
            log.error("No flow persistence repository configured");
        }
        return repositories.entrySet().stream().map(entry -> {
            final var name = entry.getKey();
            final var repository = entry.getValue();
            final var timer = metricRegistry.timer(MetricRegistry.name("logPersisting", name));
            return new FlowPersister(name, repository, metricRegistry);
        }).toList();
    }

    @Bean
    CsvImporter csvImporter() {
        return new CsvImporter();
    }

    @Bean
    ClassificationRuleProvider classificationRuleProvider(final CsvImporter importer) throws IOException {
        final var rules = importer.parse(RiptideApplication.class.getResourceAsStream("/classification-rules.csv"), true);
        return ClassificationRuleProvider.forList(rules);
    }

    @Bean
    ClassificationEngine classificationEngine(final ClassificationRuleProvider classificationRuleProvider,
                                              final MetricRegistry metricRegistry) throws InterruptedException {
        final var engine = new DefaultClassificationEngine(classificationRuleProvider, false);
        final var timingEngine = new TimingClassificationEngine(metricRegistry, engine);
        return new AsyncReloadingClassificationEngine(timingEngine);
    }
}
