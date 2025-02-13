package org.riptide.classification;

import java.util.List;

public interface ClassificationEngine {
    String classify(ClassificationRequest classificationRequest);

    List<Rule> getInvalidRules();

    void reload() throws InterruptedException;

}
