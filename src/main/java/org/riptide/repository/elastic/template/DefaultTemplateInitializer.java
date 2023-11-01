package org.riptide.repository.elastic.template;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.InfoResponse;
import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;
import org.riptide.repository.elastic.IndexSettings;

import java.io.IOException;
import java.io.StringReader;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class DefaultTemplateInitializer implements TemplateInitializer {
    private static final long[] COOL_DOWN_TIMES_IN_MS = {250, 500, 1000, 5000, 10000, 60000};

    private final AtomicInteger retryCount = new AtomicInteger(0);
    private final ElasticsearchClient client;
    private final TemplateLoader templateLoader;
    private final String templateLocation;
    private final String templateName;

    private final IndexSettings indexSettings;

    private boolean initialized;

    public DefaultTemplateInitializer(final ElasticsearchClient client, final String templateLocation, final String templateName) {
        this(client, templateLocation, templateName, new CachingTemplateLoader(new DefaultTemplateLoader()), new IndexSettings());
    }

    public DefaultTemplateInitializer(ElasticsearchClient client, String templateLocation, String templateName, IndexSettings indexSettings) {
        this(client, templateLocation, templateName, new CachingTemplateLoader(new MergingTemplateLoader(new DefaultTemplateLoader(), indexSettings)), indexSettings);
    }

    protected DefaultTemplateInitializer(final ElasticsearchClient client, final String templateLocation, final String templateName, final TemplateLoader templateLoader, final IndexSettings indexSettings) {
        this.client = Objects.requireNonNull(client);
        this.templateLocation = templateLocation;
        this.templateName = Objects.requireNonNull(templateName);
        this.templateLoader = Objects.requireNonNull(templateLoader);
        this.indexSettings = Objects.requireNonNull(indexSettings);
    }

    @Override
    public synchronized void initialize() {
        while (!initialized && !Thread.interrupted()) {
            try {
                log.debug("Template {} is not initialized. Initializing...", templateName);
                doInitialize();
                initialized = true;
            } catch (Exception ex) {
                log.error("An error occurred while initializing template {}: {}.", templateName, ex.getMessage(), ex);
                long coolDownTimeInMs = COOL_DOWN_TIMES_IN_MS[retryCount.get()];
                log.debug("Retrying in {} ms", coolDownTimeInMs);
                waitBeforeRetrying(coolDownTimeInMs);
                if (retryCount.get() != COOL_DOWN_TIMES_IN_MS.length - 1) {
                    retryCount.incrementAndGet();
                }
            }
        }
    }

    @Override
    public synchronized boolean isInitialized() {
        return initialized;
    }

    private void waitBeforeRetrying(long cooldown) {
        try {
            Thread.sleep(cooldown);
        } catch (InterruptedException e) {
            log.warn("Sleep was interrupted", e);
        }
    }

    private void doInitialize() throws IOException {
        // Retrieve the server version
        final Version version = getServerVersion();
        // Load the appropriate template
        final String template = templateLoader.load(version, templateLocation);

        // Apply the index prefix to the template name as well so that templates from multiple instances
        // do not overwrite each other
        final String effectiveTemplateName = Strings.nullToEmpty(indexSettings.getIndexPrefix()) + templateName;

        // Post it to elastic
        try {
            client.indices().putTemplate(builder -> builder
                    .name(effectiveTemplateName)
                    .withJson(new StringReader(template)));
        } catch (final ElasticsearchException e) {
            // In case the template could not be created, we bail
            throw new IllegalStateException("Template '" + templateName + "' could not be persisted. Reason: " + e.getMessage());
        }
    }

    private Version getServerVersion() throws IOException {
        final InfoResponse info;
        try {
            info = client.info();
        } catch (final ElasticsearchException e) {
            throw new IllegalStateException("Ping failed. Template '" + templateName + "' will not be persisted.");
        }

        final var versionDetails = info.version();
        if (versionDetails == null) {
            throw new IllegalStateException("Ping response does not contain version");
        }
        final var versionNumber = versionDetails.number();
        if (versionNumber == null) {
            throw new IllegalStateException("Ping response does not contain version number");
        }
        return Version.fromVersionString(versionNumber);
    }

    public IndexSettings getIndexSettings() {
        return indexSettings;
    }
}
