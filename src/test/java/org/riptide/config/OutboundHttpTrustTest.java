/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.riptide.classification.internal.ClassificationRulesSource;
import org.riptide.utils.HttpServerConfig;
import org.springframework.core.io.UrlResource;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * An endpoint served by an internal certificate authority, which is what {@code riptide.http.ca-bundle}
 * exists for (#802).
 *
 * <p>The authority and the certificate it signs are generated here rather than checked in: committed
 * key material expires, and a fixture that expires turns into a test failure a year from now that
 * looks like a regression. {@code keytool} ships with the JDK, so this needs nothing the build does
 * not already have.</p>
 *
 * <p><b>The failing half is the point.</b> A test that only shows the read succeeding with a bundle
 * configured would pass just as well if the bundle were ignored and the certificate happened to
 * verify some other way. Each case here is paired with the read failing without it.</p>
 */
class OutboundHttpTrustTest {

    private static final String STORE_PASSWORD = "changeit";

    private static Path workspace;
    private static Path caBundle;
    private static HttpsServer server;

    @BeforeAll
    static void startInternallySignedServer() throws Exception {
        HttpServerConfig.ensureApplied();
        workspace = Files.createTempDirectory("riptide-internal-ca");
        caBundle = generateAuthorityAndServerCertificate(workspace);

        final KeyStore serverStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(workspace.resolve("server.p12"))) {
            serverStore.load(in, STORE_PASSWORD.toCharArray());
        }
        final KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(serverStore, STORE_PASSWORD.toCharArray());
        final SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);

        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/rules", exchange -> {
            try (exchange) {
                final byte[] answer = "served over an internally signed certificate".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, answer.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(answer);
                }
            }
        });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static URL endpoint() {
        try {
            return URI.create("https://localhost:" + server.getAddress().getPort() + "/rules").toURL();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BoundedHttpRead read(final OutboundHttpTrust trust) {
        return new BoundedHttpRead(Duration.ofSeconds(5), 1024 * 1024, "document",
                () -> "the endpoint", Map::of, trust);
    }

    private static OutboundHttpTrust trusting(final Path bundle) {
        final OutboundHttpConfig config = new OutboundHttpConfig();
        config.setCaBundle(bundle);
        return new OutboundHttpTrust(config);
    }

    @Test
    void withNoBundleTheInternallySignedEndpointIsRefused() {
        assertThatThrownBy(() -> read(new OutboundHttpTrust()).readRemote(endpoint()))
                .as("this is what an operator hits today, and what the JVM flag exists to work around")
                .isInstanceOf(IOException.class);
    }

    @Test
    void withTheAuthorityInTheBundleTheSameEndpointIsRead() throws Exception {
        final byte[] body = read(trusting(caBundle)).readRemote(endpoint());

        assertThat(new String(body, StandardCharsets.UTF_8))
                .isEqualTo("served over an internally signed certificate");
    }

    /**
     * The bundle widens trust rather than replacing it.
     *
     * <p>Structural rather than a live read: proving it by reading from a publicly signed endpoint
     * would put a network dependency and someone else's certificate lifetime into a unit test. What
     * is asserted instead is that every issuer the platform accepts is still accepted with a bundle
     * configured that contains none of them.</p>
     */
    @Test
    void aConfiguredAuthorityDoesNotNarrowWhatWasTrustedBefore() throws Exception {
        final TrustManagerFactory platform =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        platform.init((KeyStore) null);
        final X509TrustManager platformManager = (X509TrustManager) platform.getTrustManagers()[0];

        final OutboundHttpTrust trust = trusting(caBundle);

        assertThat(trust.socketFactory())
                .as("a bundle was configured, so the reader stops using the default factory")
                .isNotNull();
        assertThat(trust.trustManager().getAcceptedIssuers())
                .as("the platform's authorities plus the one in the bundle, not instead of them")
                .hasSize(platformManager.getAcceptedIssuers().length + 1)
                .contains(platformManager.getAcceptedIssuers());
    }

    /** The second call site, which the report never pointed at and which had the gap for longer. */
    @Test
    void theClassificationRulesetGetsTheSameTrust() throws Exception {
        final ClassificationConfig config = new ClassificationConfig();
        config.setRules(new UrlResource(endpoint().toURI()));

        assertThatThrownBy(() -> new ClassificationRulesSource(config, new OutboundHttpTrust()).read())
                .as("without the bundle, the ruleset fetch fails exactly as discovery's does")
                .isInstanceOf(IOException.class);

        assertThat(new String(new ClassificationRulesSource(config, trusting(caBundle)).read(),
                StandardCharsets.UTF_8))
                .isEqualTo("served over an internally signed certificate");
    }

    @Test
    void aBundleThatCannotBeReadFailsNamingTheKeyAndTheFile() {
        final Path missing = workspace.resolve("not-here.pem");

        assertThatThrownBy(() -> trusting(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("riptide.http.ca-bundle")
                .hasMessageContaining("not-here.pem");
    }

    @Test
    void aBundleHoldingNoCertificateIsRefusedRatherThanIgnored() throws Exception {
        final Path empty = Files.writeString(workspace.resolve("empty.pem"), "# no certificates here\n");

        assertThatThrownBy(() -> trusting(empty))
                .as("falling back to the platform's authorities would leave an operator believing "
                        + "an internal one was configured")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("riptide.http.ca-bundle")
                .hasMessageContaining("empty.pem");
    }

    /**
     * A throwaway authority, and a server certificate it signs, through the JDK's own keytool.
     *
     * @return the authority as a PEM file, which is what an operator would configure
     */
    private static Path generateAuthorityAndServerCertificate(final Path dir) throws Exception {
        final Path ca = dir.resolve("ca.p12");
        final Path serverStore = dir.resolve("server.p12");
        final Path csr = dir.resolve("server.csr");
        final Path signed = dir.resolve("server.cer");
        final Path bundle = dir.resolve("ca.pem");
        final String san = "san=dns:localhost,ip:127.0.0.1";

        keytool("-genkeypair", "-alias", "ca", "-dname", "CN=Riptide Test CA", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "1", "-ext", "bc:c",
                "-keystore", ca.toString(), "-storetype", "PKCS12",
                "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD);
        keytool("-genkeypair", "-alias", "server", "-dname", "CN=localhost", "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "1", "-ext", san,
                "-keystore", serverStore.toString(), "-storetype", "PKCS12",
                "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD);
        keytool("-certreq", "-alias", "server", "-keystore", serverStore.toString(),
                "-storepass", STORE_PASSWORD, "-file", csr.toString());
        keytool("-gencert", "-alias", "ca", "-keystore", ca.toString(), "-storepass", STORE_PASSWORD,
                "-infile", csr.toString(), "-outfile", signed.toString(), "-ext", san,
                "-validity", "1", "-rfc");
        keytool("-exportcert", "-rfc", "-alias", "ca", "-keystore", ca.toString(),
                "-storepass", STORE_PASSWORD, "-file", bundle.toString());
        keytool("-importcert", "-noprompt", "-alias", "ca", "-file", bundle.toString(),
                "-keystore", serverStore.toString(), "-storepass", STORE_PASSWORD);

        final Path chain = dir.resolve("chain.pem");
        Files.writeString(chain, Files.readString(bundle) + Files.readString(signed));
        keytool("-importcert", "-noprompt", "-alias", "server", "-file", chain.toString(),
                "-keystore", serverStore.toString(), "-storepass", STORE_PASSWORD);
        return bundle;
    }

    private static void keytool(final String... arguments) throws Exception {
        final Path tool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        final java.util.List<String> command = new java.util.ArrayList<>(List.of(tool.toString()));
        command.addAll(List.of(arguments));
        final Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }
    }
}
