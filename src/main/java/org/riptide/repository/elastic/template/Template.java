package org.riptide.repository.elastic.template;


import com.google.gson.annotations.SerializedName;
import lombok.Data;
import org.riptide.repository.elastic.IndexSettings;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

@Data
public class Template {
    @SerializedName("index_patterns")
    private List<String> indexPatterns;

    private Map<String, IndexSettings> settings;

    public Optional<IndexSettings> getIndexSettingsSafe() {
        if (settings != null) {
            return Optional.ofNullable(settings.get("index"));
        }
        return Optional.empty();
    }

    public IndexSettings getIndexSettings() {
        return getIndexSettingsSafe()
                .orElseThrow(() -> new NoSuchElementException("Provided template does not contain IndexSettings"));
    }
}
