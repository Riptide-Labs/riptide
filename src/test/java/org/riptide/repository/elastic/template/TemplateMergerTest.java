package org.riptide.repository.elastic.template;


import com.google.gson.Gson;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.riptide.repository.elastic.IndexSettings;

public class TemplateMergerTest {
    @Test
    void verifyEmptyTemplateMergeWithEmptySettings() {
        Assertions.assertThat(new TemplateMerger().merge("{}", new IndexSettings())).isEqualTo("{}");
        Assertions.assertThat(new TemplateMerger().merge(new Template(), new IndexSettings())).isEqualTo(new Template());
    }

    @Test
    void verifyEmptyTemplateMergeWithNullSettings() {
        Assertions.assertThat(new TemplateMerger().merge("{}", null)).isEqualTo("{}");
        Assertions.assertThat(new TemplateMerger().merge(new Template(), null)).isEqualTo(new Template());
    }

    @Test
    void verifyEmptyTemplateMergeWithFullyDefinedSettings() {
        final String inputJson = """
                {
                    "index_patterns":["prefix*"],
                    settings: {
                        index: {
                          number_of_shards: 5,
                          number_of_replicas: 10,
                          refresh_interval: 10s,
                          routing_partition_size: 20
                        }
                    }
                }
                """;

        // Configure settings
        final IndexSettings settings = new IndexSettings();
        settings.setIndexPrefix("prefix");
        settings.setNumberOfShards(5);
        settings.setNumberOfReplicas(10);
        settings.setRefreshInterval("10s");
        settings.setRoutingPartitionSize(20);

        // Verify
        final var template = new Gson().fromJson(inputJson, Template.class);
        final var expectedJson = new Gson().toJson(template);
        Assertions.assertThat(expectedJson).isEqualTo(new TemplateMerger().merge("{}", settings));
        Assertions.assertThat(template).isEqualTo(new TemplateMerger().merge(new Template(), settings));
    }

    @Test
    void verifyIndexPrefixHandling() {
        final String expectedJson = "{\"index_patterns\":[\"prefix-test-*\"],\"settings\":{\"index\":{}}}";

         // Configure settings
        final IndexSettings settings = new IndexSettings();
        settings.setIndexPrefix("prefix-");

        // Verify
        final var expectedJsonObject = new Gson().fromJson(expectedJson, Template.class);
        Assertions.assertThat(expectedJson).isEqualTo(new TemplateMerger().merge("{\"index_patterns\":[\"test-*\"]}", settings));
    }
}