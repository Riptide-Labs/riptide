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
import org.riptide.repository.FlowRepository;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.Map;

@Configuration
@Slf4j
public class RiptideConfiguration {

    @Bean
    public MetricRegistry metricRegistry() {
        return new MetricRegistry();
    }

    // TODO MVR using Map<String, Repository> seems weird
    @Bean
    public Map<String, FlowRepository> flowRepositories(final ListableBeanFactory beanFactory) {
        final var repositories = beanFactory.getBeansOfType(FlowRepository.class);
        if (repositories.isEmpty()) {
            log.error("No flow persistence repository configured");
        }
        return repositories;
    }

    @Bean
    public ClassificationRuleProvider classificationRuleProvider() throws IOException {
        final var rules = CsvImporter.parse(RiptideApplication.class.getResourceAsStream("/classification-rules.csv"), true);
        return ClassificationRuleProvider.forList(rules);
    }

    @Bean
    public ClassificationEngine classificationEngine(final ClassificationRuleProvider classificationRuleProvider,
                                                     final MetricRegistry metricRegistry) throws InterruptedException {
        final var engine = new DefaultClassificationEngine(classificationRuleProvider, false);
        final var timingEngine = new TimingClassificationEngine(metricRegistry, engine);
        final var reloadingEngine = new AsyncReloadingClassificationEngine(timingEngine);
        return reloadingEngine;
    }
}
