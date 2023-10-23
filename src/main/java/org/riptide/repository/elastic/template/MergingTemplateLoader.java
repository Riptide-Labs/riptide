package org.riptide.repository.elastic.template;

import org.riptide.repository.elastic.IndexSettings;

import java.io.IOException;
import java.util.Objects;

/**
 * Merges a template which is loaded from a delegate {@link TemplateLoader} with optional {@link IndexSettings}.
 */
public class MergingTemplateLoader implements TemplateLoader {

    private final TemplateLoader delegate;
    private final IndexSettings indexSettings;

    public MergingTemplateLoader(TemplateLoader delegate, IndexSettings indexSettings) {
        this.delegate = Objects.requireNonNull(delegate);
        this.indexSettings = indexSettings;
    }

    @Override
    public String load(Version serverVersion, String resource) throws IOException {
        final String template = delegate.load(serverVersion, resource);
        return merge(template);
    }

    private String merge(String template) {
        final String mergedTemplate = new TemplateMerger().merge(template, indexSettings);
        return mergedTemplate;
    }
}
