package org.riptide.classification.internal;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import org.riptide.classification.ClassificationEngine;
import org.riptide.classification.ClassificationRequest;
import org.riptide.classification.Rule;

import java.util.List;
import java.util.Objects;

public class TimingClassificationEngine implements ClassificationEngine {

    private final ClassificationEngine delegate;
    private final Timer classifyTimer;
    private final Timer reloadTimer;
    private final Timer getInvalidRulesTimer;

    public TimingClassificationEngine(MetricRegistry metricRegistry, ClassificationEngine delegate) {
        this.delegate = Objects.requireNonNull(delegate);
        this.classifyTimer = metricRegistry.timer("classify");
        this.reloadTimer = metricRegistry.timer("reload");
        this.getInvalidRulesTimer = metricRegistry.timer("getInvalidrules");
    }

    @Override
    public String classify(ClassificationRequest classificationRequest) {
        try (Timer.Context ctx = classifyTimer.time()) {
            return delegate.classify(classificationRequest);
        }
    }

    @Override
    public void reload() throws InterruptedException {
        try (Timer.Context ctx = reloadTimer.time()) {
            delegate.reload();
        }
    }

    @Override
    public List<Rule> getInvalidRules() {
        try (Timer.Context ctx = getInvalidRulesTimer.time()) {
            return delegate.getInvalidRules();
        }
    }

}
