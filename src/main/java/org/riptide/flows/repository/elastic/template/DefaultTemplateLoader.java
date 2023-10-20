package org.riptide.flows.repository.elastic.template;

import java.io.IOException;
import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.ByteStreams;

public class DefaultTemplateLoader implements TemplateLoader {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultTemplateLoader.class);

    @Override
    public String load(Version serverVersion, String resource) throws IOException {
        // Attempt to find a template that ends in .es#.json where # is the major version
        // of the ES server. If no template is found for the current major version, try the previous,
        // and so on. If no template is found then attempt to look it up without the version in the suffix.
        for (int i = serverVersion.getMajor(); i >= 0; i--) {
            final String versionSuffix = i == 0 ? "" : String.format(".es%d", i);
            final String resourceWithSuffix = String.format("%s%s.json", resource, versionSuffix);
            LOG.debug("Attempting to find template with resource name: {} (requested: {})", resourceWithSuffix, resource);
            final String template = getResource(resourceWithSuffix);
            if (template != null) {
                LOG.info("Using template with resource name: {} (requested: {})", resourceWithSuffix, resource);
                return template;
            }
            LOG.debug("No template found with resource name: {} (requested: {})", resourceWithSuffix, resource);
        }
        throw new NullPointerException(String.format("No template found for server version %s and resource %s.",
                serverVersion, resource));
    }

    protected String getResource(String resource) throws IOException {
        try (InputStream inputStream = getResourceAsStream(resource)) {
            if (inputStream == null) {
                return null;
            }
            // Read template
            final byte[] bytes = new byte[inputStream.available()];
            ByteStreams.readFully(inputStream, bytes);
            return new String(bytes);
        }
    }

    protected InputStream getResourceAsStream(String resource) throws IOException {
        return getClass().getResourceAsStream(resource);
    }
}
