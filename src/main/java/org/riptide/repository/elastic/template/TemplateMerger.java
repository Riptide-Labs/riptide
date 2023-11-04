package org.riptide.repository.elastic.template;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import org.riptide.repository.elastic.IndexSettings;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Merges an existing elastic template with provided (optional) settings.
 */
public class TemplateMerger {

    public String merge(final String template, final IndexSettings indexSettings) {
        final var gson = new Gson();
        final var templateObject = gson.fromJson(template, Template.class);
        final var mergedTemplate = merge(templateObject, indexSettings);
        return gson.toJson(mergedTemplate);
    }

    public Template merge(final Template template, final IndexSettings indexSettings) {
        if (indexSettings != null && !indexSettings.isEmpty()) {
            // Ensure settings -> index is always set
            if (template.getSettings() == null) {
                template.setSettings(new HashMap<>());
            }
            template.getSettings().putIfAbsent("index", new IndexSettings());

            // Prepend the index prefix to the template pattern
            if (!Strings.isNullOrEmpty(indexSettings.getIndexPrefix())) {
                if (template.getIndexPatterns() == null) {
                    template.setIndexPatterns(new ArrayList<>());
                }
                if (template.getIndexPatterns().isEmpty()) {
                    template.getIndexPatterns().add(indexSettings.getIndexPrefix() + "*");
                } else {
                    template.getIndexPatterns().replaceAll(pattern -> indexSettings.getIndexPrefix() + pattern);
                }
            }
            template.getIndexSettingsSafe().ifPresent(indexObject -> {
                if (indexSettings.getNumberOfShards() != null) {
                    indexObject.setNumberOfShards(indexSettings.getNumberOfShards());
                }
                if (indexSettings.getNumberOfReplicas() != null) {
                    indexObject.setNumberOfReplicas(indexSettings.getNumberOfReplicas());
                }
                if (indexSettings.getRefreshInterval() != null) {
                    indexObject.setRefreshInterval(indexSettings.getRefreshInterval());
                }
                if (indexSettings.getRoutingPartitionSize() != null) {
                    indexObject.setRoutingPartitionSize(indexSettings.getRoutingPartitionSize());
                }
            });
        }
        return template;
    }

}
