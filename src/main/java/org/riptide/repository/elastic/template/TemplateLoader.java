package org.riptide.repository.elastic.template;

import java.io.IOException;

public interface TemplateLoader {
    String load(Version serverVersion, String resource) throws IOException;
}
