package org.riptide.flows.repository.elastic;

import io.searchbox.client.JestClient;
import org.riptide.flows.repository.elastic.template.DefaultTemplateInitializer;
import org.riptide.flows.repository.elastic.template.DefaultTemplateLoader;
import org.riptide.flows.repository.elastic.template.MergingTemplateLoader;

public class RawIndexInitializer extends DefaultTemplateInitializer {

    public static final String TEMPLATE_RESOURCE = "/netflow-template";

    private static final String FLOW_TEMPLATE_NAME = "netflow";

    public RawIndexInitializer(final JestClient client, final IndexSettings indexSettings) {
        super(client, TEMPLATE_RESOURCE, FLOW_TEMPLATE_NAME, new MergingTemplateLoader(new DefaultTemplateLoader(), indexSettings), indexSettings);
    }

    public RawIndexInitializer(final JestClient client) {
        super(client, TEMPLATE_RESOURCE, FLOW_TEMPLATE_NAME, new DefaultTemplateLoader(), new IndexSettings());
    }
}
