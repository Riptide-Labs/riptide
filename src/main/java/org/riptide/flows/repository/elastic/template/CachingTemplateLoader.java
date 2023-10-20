package org.riptide.flows.repository.elastic.template;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

/**
 * Caches the loading of templates.
 */
public class CachingTemplateLoader implements TemplateLoader {

    private final LoadingCache<TemplateKey, String> cache;

    public CachingTemplateLoader(final TemplateLoader delegate) {
        Objects.requireNonNull(delegate);
        this.cache = CacheBuilder.newBuilder().maximumSize(100).build(new CacheLoader<TemplateKey, String>() {
            @Override
            public String load(TemplateKey key) throws Exception {
                return delegate.load(key.getServerVersion(), key.getResource());
            }
        });
    }

    @Override
    public String load(Version serverVersion, String resource) throws IOException {
        try {
            return cache.get(new TemplateKey(serverVersion, resource));
        } catch (ExecutionException e) {
            throw new IOException("Could not read data from cache", e);
        }
    }

    private static final class TemplateKey {
        private final Version serverVersion;
        private final String resource;

        private TemplateKey(Version serverVersion, String resource) {
            this.serverVersion = serverVersion;
            this.resource = resource;
        }

        private Version getServerVersion() {
            return serverVersion;
        }

        private String getResource() {
            return resource;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TemplateKey that = (TemplateKey) o;
            return Objects.equals(serverVersion, that.serverVersion) &&
                    Objects.equals(resource, that.resource);
        }

        @Override
        public int hashCode() {
            return Objects.hash(serverVersion, resource);
        }
    }
}
