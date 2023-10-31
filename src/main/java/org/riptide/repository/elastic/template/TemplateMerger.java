package org.riptide.repository.elastic.template;

import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.riptide.repository.elastic.IndexSettings;

/**
 * Merges an existing elastic template with provided (optional) settings.
 */
public class TemplateMerger {

    private static final String SETTINGS_KEY = "settings";
    private static final String INDEX_KEY = "index";

    public String merge(final String template, final IndexSettings indexSettings) {
        final JsonElement json = new JsonParser().parse(template);
        if (!json.isJsonObject()) {
            throw new IllegalArgumentException("Provided template is not a valid json object");
        }
        JsonObject object = merge(json.getAsJsonObject(), indexSettings);
        return new Gson().toJson(object);
    }

    public JsonObject merge(final JsonObject template, final IndexSettings indexSettings) {
        if (indexSettings != null && !indexSettings.isEmpty()) {
            addMissingProperties(template);

            // Prepend the index prefix to the template pattern
            if (!Strings.isNullOrEmpty(indexSettings.getIndexPrefix())) {
                JsonArray indexPatterns = template.getAsJsonArray("index_patterns");
                if (indexPatterns == null) {
                    indexPatterns = new JsonArray();
                    indexPatterns.add(indexSettings.getIndexPrefix() + "*");
                    template.add("index_patterns", indexPatterns);
                } else {
                    for (int i = 0; i < indexPatterns.size(); i++) {
                        final String newPattern = indexSettings.getIndexPrefix() + indexPatterns.get(i).getAsString();
                        indexPatterns.set(i, new JsonPrimitive(newPattern));
                    }
                }
                template.add("index_patterns", indexPatterns);
            }

            final JsonObject indexObject = template.get(SETTINGS_KEY).getAsJsonObject().get(INDEX_KEY).getAsJsonObject();
            if (indexSettings.getNumberOfShards() != null) {
                indexObject.addProperty("number_of_shards", indexSettings.getNumberOfShards());
            }
            if (indexSettings.getNumberOfReplicas() != null) {
                indexObject.addProperty("number_of_replicas", indexSettings.getNumberOfReplicas());
            }
            if (indexSettings.getRefreshInterval() != null) {
                indexObject.addProperty("refresh_interval", indexSettings.getRefreshInterval());
            }
            if (indexSettings.getRoutingPartitionSize() != null) {
                indexObject.addProperty("routing_partition_size", indexSettings.getRoutingPartitionSize());
            }
        }
        return template;
    }

    private void addMissingProperties(final JsonObject template) {
        final JsonObject settings = addMissingProperty(template, SETTINGS_KEY);
        addMissingProperty(settings, INDEX_KEY);
    }

    private JsonObject addMissingProperty(JsonObject root, String property) {
        if (root.get(property) == null) {
            root.add(property, new JsonObject());
        }
        if (!root.get(property).isJsonObject()) {
            throw new IllegalArgumentException("Provided template contains property '" + property + "' must be of type JsonObject");
        }
        return root.get(property).getAsJsonObject();
    }


}
