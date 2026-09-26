/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The socket factory the collector's outbound HTTP reads use, built from the platform's own
 * certificate authorities plus whatever {@code riptide.http.ca-bundle} adds.
 *
 * <p><b>Adds, never replaces.</b> A chain is accepted when either the platform's authorities or the
 * configured bundle accepts it. A bundle that replaced the platform's would mean configuring an
 * internal authority for one endpoint silently breaks every publicly served one, with an error
 * naming neither the endpoint nor the key.</p>
 *
 * <p><b>There is deliberately no way to skip verification.</b> These connections carry a credential,
 * so an unverified peer is one anything on the path can impersonate, and a key that does this
 * outlives the afternoon that motivated it. An operator facing a certificate that does not verify
 * names that certificate in the bundle instead: it takes one {@code openssl s_client} command and
 * leaves a configuration stating what is trusted, which is what disabling the check does not.</p>
 *
 * <p>Hostname verification is untouched and stays the platform's, and so is the client's own key
 * material: a bundle configured here does not stop a mutual-TLS deployment presenting the
 * certificate {@code javax.net.ssl.keyStore} names.</p>
 *
 * <p><b>What reaches it.</b> {@code BoundedHttpRead} takes an instance for the discovery endpoint
 * and an {@code http(s)} classification ruleset. {@code PrometheusRemoteWriteSink} takes one too,
 * for the {@code riptide.metrics.remote-write.url} connection — it builds its own
 * {@code HttpURLConnection} directly rather than going through {@code BoundedHttpRead}, since that
 * class only reads. The ClickHouse client and Spring Vault's do not reach this class at all: both
 * carry their own transport and trust configuration.</p>
 */
public final class OutboundHttpTrust {

    /** Kept on one line so it stays greppable, like {@code DiscoveryClient.AUTH_SCHEME_PROPERTY}. */
    public static final String CA_BUNDLE_PROPERTY = "riptide.http.ca-bundle";

    private final SSLSocketFactory socketFactory;
    private final X509TrustManager trustManager;

    /** Trusts exactly what the platform trusts. */
    public OutboundHttpTrust() {
        this.socketFactory = null;
        this.trustManager = null;
    }

    public OutboundHttpTrust(final OutboundHttpConfig config) {
        if (config.getCaBundle() == null) {
            this.socketFactory = null;
            this.trustManager = null;
            return;
        }
        this.trustManager = managerFor(config.getCaBundle());
        try {
            final SSLContext context = SSLContext.getInstance("TLS");
            // the platform's key managers, not null: null hands the context no key material at all,
            // so an operator using -Djavax.net.ssl.keyStore for mutual TLS would stop presenting a
            // client certificate the moment they configured a CA bundle, and the endpoint's refusal
            // would name neither. "Adds, never replaces" has to hold for both halves of the
            // handshake, not only the half the key is about
            context.init(platformKeyManagers(), new TrustManager[]{this.trustManager}, null);
            this.socketFactory = context.getSocketFactory();
        } catch (final GeneralSecurityException | IOException e) {
            throw new IllegalStateException(
                    "%s could not be used: %s: %s"
                            .formatted(CA_BUNDLE_PROPERTY, config.getCaBundle(), e.getMessage()), e);
        }
    }

    /**
     * The manager deciding what is accepted, or {@code null} when nothing is configured. Visible for
     * the test that pins "widens, never narrows": counting accepted issuers is the only way to show
     * that without reading from a publicly signed endpoint, which would put a network dependency and
     * someone else's certificate lifetime into a unit test.
     */
    X509TrustManager trustManager() {
        return this.trustManager;
    }

    /**
     * The factory to use for an HTTPS connection, or {@code null} when nothing is configured and the
     * platform's default is already right. Null rather than the default factory so the caller leaves
     * the connection alone entirely in that case, which is what makes "unset" indistinguishable from
     * how this collector behaved before the key existed.
     */
    public SSLSocketFactory socketFactory() {
        return this.socketFactory;
    }

    private static X509TrustManager managerFor(final Path bundle) {
        final List<X509Certificate> extra = read(bundle);
        if (extra.isEmpty()) {
            // refused rather than ignored: falling back to the platform's authorities would leave an
            // operator believing an internal one was configured, and they would learn otherwise from
            // a failed read that names neither this key nor the file
            throw new IllegalStateException(
                    ("%s holds no certificate: %s. A PEM file with one or more CERTIFICATE blocks is "
                            + "expected. Leave the key unset to trust only the platform's authorities.")
                            .formatted(CA_BUNDLE_PROPERTY, bundle));
        }
        try {
            return new EitherTrustManager(platform(), configured(extra));
        } catch (final GeneralSecurityException | IOException e) {
            throw new IllegalStateException(
                    "%s could not be used: %s: %s".formatted(CA_BUNDLE_PROPERTY, bundle, e.getMessage()), e);
        }
    }

    private static List<X509Certificate> read(final Path bundle) {
        try (InputStream in = Files.newInputStream(bundle)) {
            final Collection<? extends Certificate> parsed =
                    CertificateFactory.getInstance("X.509").generateCertificates(in);
            final List<X509Certificate> certificates = new ArrayList<>();
            for (final Certificate certificate : parsed) {
                if (certificate instanceof X509Certificate x509) {
                    certificates.add(x509);
                }
            }
            return certificates;
        } catch (final IOException | CertificateException e) {
            throw new IllegalStateException(
                    ("%s could not be read: %s: %s. It must name a readable PEM file holding one or "
                            + "more certificate authorities.")
                            .formatted(CA_BUNDLE_PROPERTY, bundle, e.getMessage()), e);
        }
    }

    /**
     * The key material the platform would have used, read from the same system properties the
     * default context reads. {@code null} when none is configured, which is what the default context
     * amounts to in that case.
     */
    private static KeyManager[] platformKeyManagers() throws GeneralSecurityException, IOException {
        final String path = System.getProperty("javax.net.ssl.keyStore");
        if (path == null || path.isEmpty() || "NONE".equalsIgnoreCase(path)) {
            return null;
        }
        final char[] password = System.getProperty("javax.net.ssl.keyStorePassword", "").toCharArray();
        final KeyStore store = KeyStore.getInstance(
                System.getProperty("javax.net.ssl.keyStoreType", KeyStore.getDefaultType()));
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            store.load(in, password);
        }
        final KeyManagerFactory factory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(store, password);
        return factory.getKeyManagers();
    }

    private static X509TrustManager platform() throws GeneralSecurityException {
        return first(trustManagers(null));
    }

    private static X509TrustManager configured(final List<X509Certificate> extra)
            throws GeneralSecurityException, IOException {
        final KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        int index = 0;
        for (final X509Certificate certificate : extra) {
            store.setCertificateEntry("ca-" + index++, certificate);
        }
        return first(trustManagers(store));
    }

    /** A null store means the platform's own authorities, which is this API's way of saying so. */
    private static TrustManager[] trustManagers(final KeyStore store) throws GeneralSecurityException {
        final TrustManagerFactory factory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        try {
            factory.init(store);
        } catch (final RuntimeException e) {
            throw new GeneralSecurityException(e);
        }
        return factory.getTrustManagers();
    }

    private static X509TrustManager first(final TrustManager[] managers) throws GeneralSecurityException {
        for (final TrustManager manager : managers) {
            if (manager instanceof X509TrustManager x509) {
                return x509;
            }
        }
        throw new GeneralSecurityException("no X509 trust manager available from the platform");
    }

    /**
     * Accepts a chain either manager accepts, so a configured bundle widens trust rather than
     * narrowing it. The platform is asked first, because that is the common case and the one whose
     * failure carries the more familiar message.
     */
    private record EitherTrustManager(X509TrustManager platform, X509TrustManager configured)
            implements X509TrustManager {

        @Override
        public void checkClientTrusted(final X509Certificate[] chain, final String authType)
                throws CertificateException {
            try {
                this.platform.checkClientTrusted(chain, authType);
            } catch (final CertificateException notPlatform) {
                this.configured.checkClientTrusted(chain, authType);
            }
        }

        @Override
        public void checkServerTrusted(final X509Certificate[] chain, final String authType)
                throws CertificateException {
            try {
                this.platform.checkServerTrusted(chain, authType);
            } catch (final CertificateException notPlatform) {
                try {
                    this.configured.checkServerTrusted(chain, authType);
                } catch (final CertificateException notConfigured) {
                    // the platform's failure is the one an operator recognises, and the configured
                    // one is why their own authority did not save it; both or they debug blind
                    notConfigured.addSuppressed(notPlatform);
                    throw notConfigured;
                }
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            final X509Certificate[] fromPlatform = this.platform.getAcceptedIssuers();
            final X509Certificate[] fromBundle = this.configured.getAcceptedIssuers();
            final X509Certificate[] all = new X509Certificate[fromPlatform.length + fromBundle.length];
            System.arraycopy(fromPlatform, 0, all, 0, fromPlatform.length);
            System.arraycopy(fromBundle, 0, all, fromPlatform.length, fromBundle.length);
            return all;
        }
    }
}
