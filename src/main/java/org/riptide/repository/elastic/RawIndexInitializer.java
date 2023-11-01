package org.riptide.repository.elastic;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.riptide.repository.elastic.template.DefaultTemplateInitializer;
import org.riptide.repository.elastic.template.DefaultTemplateLoader;
import org.riptide.repository.elastic.template.MergingTemplateLoader;

public class RawIndexInitializer extends DefaultTemplateInitializer {

    public static final String TEMPLATE_RESOURCE = "/netflow-template";

    private static final String FLOW_TEMPLATE_NAME = "netflow";

    public RawIndexInitializer(final ElasticsearchClient client, final IndexSettings indexSettings) {
        super(client, TEMPLATE_RESOURCE, FLOW_TEMPLATE_NAME, new MergingTemplateLoader(new DefaultTemplateLoader(), indexSettings), indexSettings);
    }

    public RawIndexInitializer(final ElasticsearchClient client) {
        super(client, TEMPLATE_RESOURCE, FLOW_TEMPLATE_NAME, new DefaultTemplateLoader(), new IndexSettings());
    }
}
