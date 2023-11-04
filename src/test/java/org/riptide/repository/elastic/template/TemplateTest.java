package org.riptide.repository.elastic.template;

import com.google.gson.Gson;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class TemplateTest {

    @Test
    void verifyParsing() {
        final String input = """
                {                                
                    settings: {                     
                        index: {                      
                          number_of_shards: 5,        
                          number_of_replicas: 10,     
                          refresh_interval: 10s,      
                          routing_partition_size: 20  
                        }                             
                    },                              
                    "index_patterns":["prefix*"]
                }
                """;
        final var template = new Gson().fromJson(input, Template.class);
        Assertions.assertThat(template.getIndexSettings()).isNotNull();
        Assertions.assertThat(template.getIndexSettings().getNumberOfShards()).isEqualTo(5);
        Assertions.assertThat(template.getIndexSettings().getNumberOfReplicas()).isEqualTo(10);
        Assertions.assertThat(template.getIndexSettings().getRefreshInterval()).isEqualTo("10s");
        Assertions.assertThat(template.getIndexSettings().getRoutingPartitionSize()).isEqualTo(20);
        Assertions.assertThat(template.getIndexSettings().isEmpty()).isFalse();
    }

}