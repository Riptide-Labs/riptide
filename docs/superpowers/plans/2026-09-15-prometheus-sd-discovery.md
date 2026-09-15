# Exporter Discovery from Prometheus HTTP SD Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Populate the inventory's `exporters` tree from a Prometheus HTTP service discovery endpoint, so a site running NetBox stops maintaining a second copy of its device list by hand.

**Architecture:** Discovery gets no publisher of its own. It renders an `exporters` subtree, which is merged with the agent ranges read from the inventory file into one document that goes through the existing `InventoryLoader`, regression guard, snapshot swap and SNMP poller refresh unchanged. The merge is reached through a new `InventoryDocument` seam so that every site reading the inventory file sees the composed result.

**Tech Stack:** Java 25, Spring Boot 4.1.1, SnakeYAML (transitive, already used directly), Jackson databind 2.22.1 (already declared), JUnit 5, AssertJ, `com.sun.net.httpserver.HttpServer` for HTTP stubbing. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-15-prometheus-sd-discovery-design.md`

## Global Constraints

- Every new or edited Java file begins with exactly this header, above `package`, one blank line after `*/`:
  ```java
  /*
   * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
   * SPDX-License-Identifier: GPL-3.0-or-later
   */
  ```
- Do not add headers to Markdown, JSON or test fixture data files.
- Unit test classes are **package-private**, not `public`. Test methods are package-private with sentence-style names.
- Tests use JUnit 5 (`org.junit.jupiter.api.Test`) and AssertJ (`org.assertj.core.api.Assertions.assertThat` / `assertThatThrownBy`). Hamcrest and JUnit 4 are banned; `config/checkstyle-import-control.xml` disallows `org.junit` exact-match.
- Name every test class `*Test`, never `*IT`. `*IT` runs only under `mvn verify -Pe2e` with Docker.
- Method parameters and locals are declared `final`, matching the surrounding code.
- HTTP stubbing uses `com.sun.net.httpserver.HttpServer`. Call `org.riptide.utils.HttpServerConfig.ensureApplied()` before creating the first server in a fork. Do not add WireMock or MockWebServer.
- Commits are Conventional Commits, created with `git commit -s`, ending with the trailer `Assisted-by: ClaudeCode:claude-opus-5`.
- Gate command for the unit suite: `make jar` (which is `mvn -DskipTests=false --batch-mode --update-snapshots verify`). A single test runs with `mvn -Dtest=ClassName#methodName surefire:test`.
- Never report a test as passing without `BUILD SUCCESS` in the same output. A `target/surefire-reports` file from an earlier run is not evidence.

---

### Task 1: Extract the bounded HTTP read

Pure refactor. No behaviour change, no new configuration. `ClassificationRulesSource` currently owns a bounded remote read that discovery needs too, and this repo has three closed issues (#542, #560, #561) about copies of shared read machinery drifting apart.

**Files:**
- Create: `src/main/java/org/riptide/config/BoundedHttpRead.java`
- Modify: `src/main/java/org/riptide/classification/internal/ClassificationRulesSource.java`
- Test: `src/test/java/org/riptide/config/BoundedHttpReadTest.java`

**Interfaces:**
- Consumes: `org.riptide.config.ByteOrderMark` (existing, untouched).
- Produces:
  - `public BoundedHttpRead(Duration timeout, int maxBytes, String subject, Supplier<String> describe, Map<String, String> headers)`
  - `public byte[] readRemote(URL url) throws IOException` — throws `FileNotFoundException` on 404, `IOException` on any other non-200, a read past `maxBytes`, or the deadline.
  - `public byte[] readLocal(InputStream in) throws IOException` — no deadline, same size ceiling.
  - `URLConnection openBounded(URL url) throws IOException` — package-private, visible for tests.

`subject` keeps the existing size-ceiling message text identical for the classification path: it is the noun in `"... is larger than the %d byte ceiling for a %s"`, and `ClassificationRulesSource` passes `"ruleset"`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/riptide/config/BoundedHttpReadTest.java`:

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.riptide.utils.HttpServerConfig;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedHttpReadTest {

    private static final AtomicReference<String> AUTHORIZATION = new AtomicReference<>();

    private static final HttpServer SERVER = startServer();

    private static HttpServer startServer() {
        HttpServerConfig.ensureApplied();
        try {
            final HttpServer server =
                    HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/ok", exchange -> {
                try (exchange) {
                    AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    final byte[] answer = "hello".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, answer.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(answer);
                    }
                }
            });
            server.createContext("/missing", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(404, -1);
                }
            });
            server.createContext("/broken", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(500, -1);
                }
            });
            server.createContext("/huge", exchange -> {
                try (exchange) {
                    final byte[] answer = new byte[4096];
                    exchange.sendResponseHeaders(200, answer.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(answer);
                    }
                }
            });
            server.start();
            return server;
        } catch (final IOException e) {
            throw new UncheckedIOException("could not start the test server", e);
        }
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    private static URL url(final String path) throws IOException {
        return URI.create("http://" + SERVER.getAddress().getHostString()
                + ":" + SERVER.getAddress().getPort() + path).toURL();
    }

    private static BoundedHttpRead read(final int maxBytes, final Map<String, String> headers) {
        return new BoundedHttpRead(Duration.ofSeconds(5), maxBytes, "document", () -> "the endpoint", headers);
    }

    @Test
    void readsTheBodyAndSendsTheConfiguredHeaders() throws IOException {
        final byte[] body = read(1024, Map.of("Authorization", "Token secret")).readRemote(url("/ok"));

        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("hello");
        assertThat(AUTHORIZATION.get()).isEqualTo("Token secret");
    }

    @Test
    void aNotFoundIsAbsenceRatherThanFailure() {
        assertThatThrownBy(() -> read(1024, Map.of()).readRemote(url("/missing")))
                .isInstanceOf(FileNotFoundException.class)
                .hasMessageContaining("404");
    }

    @Test
    void anyOtherNonOkStatusFailsNamingTheCode() {
        assertThatThrownBy(() -> read(1024, Map.of()).readRemote(url("/broken")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("500");
    }

    @Test
    void aBodyPastTheCeilingIsRefusedNamingTheSubject() {
        assertThatThrownBy(() -> read(1024, Map.of()).readRemote(url("/huge")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ceiling for a document");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -Dtest=BoundedHttpReadTest surefire:test`
Expected: compilation failure, `cannot find symbol: class BoundedHttpRead`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/org/riptide/config/BoundedHttpRead.java`:

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The one bounded read every remote configuration source in this project shares: a connect
 * timeout, a per-read timeout, a whole-response deadline and a size ceiling.
 *
 * <p><b>Why it exists</b>: {@code Resource.getInputStream()} on a remote location has neither
 * a timeout nor a ceiling, so a hung server parks a poll thread forever and an oversized answer
 * is read into the heap. {@link ClassificationRulesSource} grew all four bounds; discovery needs
 * the same four. A second copy is the shape {@link FileWatchTrigger}'s own history warns about,
 * where two copies of one rule drifted until a whitespace-only file was a benign skip for one
 * reloader and a counted failure for the other (#561).</p>
 *
 * <p><b>404 is absence, not failure.</b> It arrives as {@link FileNotFoundException} so a caller
 * can map it to {@link FileWatchTrigger.Fetch.Absent} and keep serving. Every other non-200 is an
 * {@link IOException} naming the status, because a 500 is a server in trouble, not a deletion.</p>
 *
 * <p>No byte-order-mark handling here. Callers strip one with {@link ByteOrderMark} after the read,
 * because the local and remote branches converge there rather than here.</p>
 */
public final class BoundedHttpRead {

    /** How much of an error response is drained so the connection can be pooled again. */
    private static final int ERROR_DRAIN_LIMIT = 64 * 1024;

    private final Duration timeout;
    private final int maxBytes;
    private final String subject;
    private final Supplier<String> describe;
    private final Map<String, String> headers;

    /**
     * @param timeout bounds the connect, each read, and the whole response. A cycle against a hung
     *     server therefore ends within roughly twice this, because a read already blocked when the
     *     deadline passes still has to time out on its own.
     * @param maxBytes refusal ceiling for the response body
     * @param subject the noun in the ceiling message, e.g. {@code "ruleset"}
     * @param describe the location with credentials redacted; safe to log, evaluated per message
     * @param headers request headers to set, e.g. an {@code Authorization} header
     */
    public BoundedHttpRead(final Duration timeout,
                           final int maxBytes,
                           final String subject,
                           final Supplier<String> describe,
                           final Map<String, String> headers) {
        this.timeout = Objects.requireNonNull(timeout);
        this.maxBytes = maxBytes;
        this.subject = Objects.requireNonNull(subject);
        this.describe = Objects.requireNonNull(describe);
        this.headers = Map.copyOf(headers);
    }

    /**
     * Reads a remote location to the end, within every bound.
     *
     * @throws FileNotFoundException on a 404, which is absence rather than failure
     * @throws IOException on any other non-200, a refused connection, a timeout, or a body past
     *     the ceiling
     */
    public byte[] readRemote(final URL url) throws IOException {
        final URLConnection connection = openBounded(url);
        final HttpURLConnection http = connection instanceof HttpURLConnection h ? h : null;
        final long deadline = System.nanoTime() + this.timeout.toNanos();
        try {
            if (http != null) {
                final int status = http.getResponseCode();
                if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                    // the one status that is absence rather than failure
                    throw new FileNotFoundException("%s answered 404".formatted(this.describe.get()));
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new IOException(
                            "%s answered HTTP %d, not 200".formatted(this.describe.get(), status));
                }
            }
            try (InputStream in = connection.getInputStream()) {
                return readBounded(in, deadline);
            }
        } catch (final IOException e) {
            // drain first: an undrained error body keeps the socket out of the keep-alive pool. A
            // socket we abandoned mid-response cannot be reused, so that one is closed instead
            release(http, !(e instanceof SocketTimeoutException));
            throw e;
        }
    }

    /** Reads an already-open local stream to the end, with the ceiling but no deadline. */
    public byte[] readLocal(final InputStream in) throws IOException {
        return readBounded(in, null);
    }

    /**
     * Opens the connection this class is willing to read from. Package-private so the timeouts a
     * production constructor applies are observable without a ten-second test.
     */
    URLConnection openBounded(final URL url) throws IOException {
        final URLConnection connection = url.openConnection();
        connection.setConnectTimeout(timeoutMillis());
        connection.setReadTimeout(timeoutMillis());
        // the content hash decides whether anything is rebuilt, so a cached response would only
        // hide a change from it
        connection.setUseCaches(false);
        this.headers.forEach(connection::setRequestProperty);
        return connection;
    }

    /**
     * Reads to the end, refusing to grow past the ceiling and to keep reading past {@code deadline}
     * — a {@code System.nanoTime()} reading, or {@code null} for a local read, which has no peer to
     * stall on. Boxed rather than sentinelled because {@code nanoTime()} may legitimately be
     * negative, so no {@code long} value is free to mean "none".
     */
    private byte[] readBounded(final InputStream in, final Long deadline) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        while (true) {
            if (deadline != null && System.nanoTime() - deadline > 0) {
                throw new SocketTimeoutException("%s did not finish responding within %s"
                        .formatted(this.describe.get(), this.timeout));
            }
            final int read = in.read(buffer);
            if (read < 0) {
                return out.toByteArray();
            }
            if (out.size() + read > this.maxBytes) {
                throw new IOException("%s is larger than the %d byte ceiling for a %s"
                        .formatted(this.describe.get(), this.maxBytes, this.subject));
            }
            out.write(buffer, 0, read);
        }
    }

    private static void release(final HttpURLConnection http, final boolean reusable) {
        if (http == null) {
            return;
        }
        boolean drained = true;
        try (InputStream errors = http.getErrorStream()) {
            if (errors != null) {
                errors.readNBytes(ERROR_DRAIN_LIMIT);
            }
        } catch (final IOException e) {
            drained = false;
        }
        if (!reusable || !drained) {
            http.disconnect();
        }
    }

    /**
     * The timeout as {@code URLConnection} wants it. Clamped rather than thrown: an operator-sized
     * {@code Duration} beyond 24 days is absurd, but turning it into an {@code ArithmeticException}
     * on the poll thread would be worse than treating it as the longest timeout the API can express.
     */
    private int timeoutMillis() {
        return (int) Math.min(this.timeout.toMillis(), Integer.MAX_VALUE);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -Dtest=BoundedHttpReadTest surefire:test`
Expected: PASS, 4 tests run, 0 failures. Confirm `BUILD SUCCESS` appears.

- [ ] **Step 5: Rewire ClassificationRulesSource onto the shared read**

In `src/main/java/org/riptide/classification/internal/ClassificationRulesSource.java`, delete the private `fetchRemote`, `readBounded`, `release` and `timeoutMillis` methods and the `ERROR_DRAIN_LIMIT` constant, then replace the field set and the two read branches. Keep `MAX_BYTES`, `DEFAULT_TIMEOUT`, `USERINFO`, `describe()`, `fetch()` and `remoteUrl(...)` exactly as they are.

Add a field and build the reader in the constructor:

```java
    private final ClassificationConfig config;
    private final Duration timeout;
    private final BoundedHttpRead http;

    public ClassificationRulesSource(final ClassificationConfig config) {
        this(config, DEFAULT_TIMEOUT);
    }

    /** Visible for tests, which cannot wait out the default timeout on every hung-server row. */
    ClassificationRulesSource(final ClassificationConfig config, final Duration timeout) {
        this.config = Objects.requireNonNull(config);
        this.timeout = Objects.requireNonNull(timeout);
        // describe() is a supplier because the location is re-read from config on every call, and
        // "ruleset" keeps the ceiling message byte-identical to what ClassificationRuleReloaderTest
        // asserts
        this.http = new BoundedHttpRead(timeout, MAX_BYTES, "ruleset", this::describe, Map.of());
    }
```

Replace the body of the private `read(Resource)` with:

```java
    private byte[] read(final Resource resource) throws IOException {
        final URL remote = remoteUrl(resource);
        if (remote == null) {
            try (InputStream in = resource.getInputStream()) {
                return ByteOrderMark.strip(this.http.readLocal(in));
            }
        }
        return ByteOrderMark.strip(this.http.readRemote(remote));
    }
```

Add `import org.riptide.config.BoundedHttpRead;` and `import java.util.Map;`. Remove now-unused imports: `java.io.ByteArrayOutputStream`, `java.net.HttpURLConnection`, `java.net.SocketTimeoutException`, `java.net.URLConnection`. Keep `java.net.URL`, `java.io.InputStream`, `java.io.FileNotFoundException`, `java.io.IOException`.

The package-private `openBounded(URL)` on `ClassificationRulesSource` is read by tests. Keep it, delegating:

```java
    /** Visible for tests: the timeouts this source applies, observable without a ten-second wait. */
    URLConnection openBounded(final URL url) throws IOException {
        return this.http.openBounded(url);
    }
```

Keep the `java.net.URLConnection` import for that signature.

- [ ] **Step 6: Run the classification suite to prove no behaviour changed**

Run: `mvn -Dtest='ClassificationRuleReloaderTest,HttpRulesRefreshOnIntervalTest,ClassificationRulesSourceTest' surefire:test`
Expected: PASS with no assertion changes. These rows cover 404s, hung servers, oversized bodies and credential redaction, and they are the regression gate for this extraction.

If `ClassificationRulesSourceTest` does not exist, run only the two that do. If any test fails on message text, the `subject` parameter is wrong: it must be `"ruleset"`.

- [ ] **Step 7: Run the full unit suite**

Run: `make jar`
Expected: `BUILD SUCCESS`. This also runs Checkstyle, SpotBugs and Error Prone, which `mvn test` skips.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/riptide/config/BoundedHttpRead.java \
        src/main/java/org/riptide/classification/internal/ClassificationRulesSource.java \
        src/test/java/org/riptide/config/BoundedHttpReadTest.java
git commit -s -m "refactor(config): extract the bounded HTTP read both remote sources need

Discovery needs the same four bounds the classification ruleset source
grew: a connect timeout, a per-read timeout, a whole-response deadline
and a size ceiling. A second copy is what #542, #560 and #561 were each
about. The ceiling message keeps its noun through a subject parameter,
so the classification path's text is unchanged.

Assisted-by: ClaudeCode:claude-opus-5"
```

---

### Task 2: Discovery configuration properties

**Files:**
- Create: `src/main/java/org/riptide/discovery/DiscoveryConfig.java`
- Modify: `src/main/resources/application.properties`
- Test: `src/test/java/org/riptide/discovery/DiscoveryConfigTest.java`

**Interfaces:**
- Consumes: `org.riptide.secrets.SecretRef` (bound automatically by the existing `SecretRefConverter`).
- Produces: `DiscoveryConfig` with `getUrl()` returning `String`, `getToken()` returning `SecretRef`, `getAuthScheme()` returning `String`, `getInterval()` and `getTimeout()` returning `Duration`, `getAddressLabels()` returning `List<String>`, and `isEnabled()` returning `boolean`.

`url` is a `String`, not a `URL`, so an unparseable value fails with a message this class writes rather than a binder stack trace.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/riptide/discovery/DiscoveryConfigTest.java`:

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import org.junit.jupiter.api.Test;

import java.net.MalformedURLException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscoveryConfigTest {

    @Test
    void anUnsetUrlDisablesTheFeature() {
        assertThat(new DiscoveryConfig().isEnabled()).isFalse();
    }

    @Test
    void aBlankUrlAlsoDisablesTheFeature() {
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl("   ");

        assertThat(config.isEnabled()).isFalse();
    }

    @Test
    void theDefaultsAreTheOnesTheDesignNames() {
        final DiscoveryConfig config = new DiscoveryConfig();

        assertThat(config.getAuthScheme()).isEqualTo("Token");
        assertThat(config.getInterval()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.getTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(config.getAddressLabels())
                .containsExactly("__meta_netbox_primary_ip4", "__meta_netbox_primary_ip6");
    }

    @Test
    void anUnparseableUrlFailsNamingTheKeyAndTheValue() {
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl("not a url");

        assertThatThrownBy(config::endpoint)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("riptide.discovery.url")
                .hasMessageContaining("not a url")
                .hasCauseInstanceOf(MalformedURLException.class);
    }

    @Test
    void aParseableUrlBecomesAnEndpoint() {
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl("https://netbox.example.com/api/plugins/prometheus-sd/devices/");

        assertThat(config.endpoint().getHost()).isEqualTo("netbox.example.com");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -Dtest=DiscoveryConfigTest surefire:test`
Expected: compilation failure, `cannot find symbol: class DiscoveryConfig`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/org/riptide/discovery/DiscoveryConfig.java`:

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import lombok.Data;
import org.riptide.secrets.SecretRef;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.util.List;

/**
 * Dynamic exporter discovery from a Prometheus HTTP service discovery endpoint.
 *
 * <p>Absence of {@code riptide.discovery.url} disables the feature entirely, which is why there is
 * no separate enable flag: a key whose only job is to say that another key means it is a key that
 * can disagree with itself.</p>
 *
 * <p>There is deliberately no {@code type} key. Adding one later with a default preserving this
 * behaviour is not a breaking change, so it buys nothing today (#799).</p>
 */
@Data
@ConfigurationProperties(prefix = "riptide.discovery")
public class DiscoveryConfig {

    /** The service discovery endpoint. Unset or blank disables discovery. */
    private String url;

    /**
     * Credential for the endpoint, as a secret reference like every other credential here. NetBox
     * wants its API token; a producer needing no authentication leaves this unset.
     */
    private SecretRef token;

    /**
     * The {@code Authorization} scheme paired with {@link #token}. NetBox expects
     * {@code Authorization: Token <key>}, which is why this is not {@code Bearer} by default.
     */
    private String authScheme = "Token";

    /**
     * Poll interval for the endpoint. A minute rather than the file watcher's interval because the
     * NetBox service discovery plugin disables pagination and has no entity tag support, so every
     * poll transfers a full serialization of every visible device.
     */
    private Duration interval = Duration.ofSeconds(60);

    /** Bounds the connect, each read, and the whole response. */
    private Duration timeout = Duration.ofSeconds(10);

    /**
     * Labels consulted in order for an entry's address, first one present wins. The default pair is
     * what the NetBox service discovery plugin emits; every IP it emits already has its CIDR mask
     * stripped. When no label in this list is present the target itself is used, which is what makes
     * non-NetBox producers work.
     */
    private List<String> addressLabels =
            List.of("__meta_netbox_primary_ip4", "__meta_netbox_primary_ip6");

    /** Whether discovery is configured at all. */
    public boolean isEnabled() {
        return this.url != null && !this.url.isBlank();
    }

    /**
     * The endpoint as a {@link URL}.
     *
     * @throws IllegalStateException naming the key and the value, rather than letting a binder
     *     stack trace reach an operator who wrote one bad character
     */
    public URL endpoint() {
        try {
            return new URI(this.url).toURL();
        } catch (final MalformedURLException | URISyntaxException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "riptide.discovery.url is not a usable URL: '%s' (%s)"
                            .formatted(this.url, e.getMessage()),
                    e instanceof MalformedURLException ? e : new MalformedURLException(e.getMessage()));
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -Dtest=DiscoveryConfigTest surefire:test`
Expected: PASS, 5 tests run. Confirm `BUILD SUCCESS`.

- [ ] **Step 5: Register the properties class**

Find the existing `@ConfigurationPropertiesScan` or the list of `@EnableConfigurationProperties` classes. Run:

```bash
grep -rn "ConfigurationPropertiesScan\|EnableConfigurationProperties" src/main/java/
```

If a scan covers `org.riptide`, nothing is needed and this step is a no-op. If classes are listed explicitly, add `DiscoveryConfig.class` to that list.

- [ ] **Step 6: Document the keys in application.properties**

Append to `src/main/resources/application.properties`, below the inventory block:

```properties
# Dynamic exporter discovery from a Prometheus HTTP service discovery endpoint
# (docs/docs/configuration/discovery.md). Unset url means discovery is off; the
# inventory file then owns both trees as before. When it is set, discovery owns
# the exporters tree and an exporters tree in the inventory file fails startup.
#riptide.discovery.url=https://netbox.example.com/api/plugins/prometheus-sd/devices/
#riptide.discovery.token=vault://secret/netbox#token
#riptide.discovery.auth-scheme=Token
#riptide.discovery.interval=60s
#riptide.discovery.timeout=10s
#riptide.discovery.address-labels=__meta_netbox_primary_ip4,__meta_netbox_primary_ip6
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/riptide/discovery/DiscoveryConfig.java \
        src/test/java/org/riptide/discovery/DiscoveryConfigTest.java \
        src/main/resources/application.properties
git commit -s -m "feat(discovery): configuration properties for the discovery endpoint

Six keys under riptide.discovery. No type key: adding one later with a
default that preserves this behaviour is not a breaking change (#799).
The url is a String rather than a URL so a bad value fails with a message
naming the key, not a binder stack trace.

Assisted-by: ClaudeCode:claude-opus-5"
```

---

### Task 3: Parse the service discovery document

**Files:**
- Create: `src/main/java/org/riptide/discovery/TargetGroup.java`
- Create: `src/main/java/org/riptide/discovery/ServiceDiscoveryParser.java`
- Test: `src/test/java/org/riptide/discovery/ServiceDiscoveryParserTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `public record TargetGroup(List<String> targets, Map<String, String> labels)`
  - `public static List<TargetGroup> ServiceDiscoveryParser.parse(byte[] json, String sourceName)` — throws `IllegalStateException` naming `sourceName` on any shape violation.

The Prometheus contract is a bare JSON array of objects each carrying `targets` (array of strings) and `labels` (object of string to string). There is no `results` envelope.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/riptide/discovery/ServiceDiscoveryParserTest.java`:

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceDiscoveryParserTest {

    private static java.util.List<TargetGroup> parse(final String json) {
        return ServiceDiscoveryParser.parse(json.getBytes(StandardCharsets.UTF_8), "the endpoint");
    }

    @Test
    void readsTargetsAndLabels() {
        final var groups = parse("""
                [{"targets":["firewall-01"],"labels":{"__meta_netbox_primary_ip4":"10.0.0.1"}}]
                """);

        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().targets()).containsExactly("firewall-01");
        assertThat(groups.getFirst().labels()).containsEntry("__meta_netbox_primary_ip4", "10.0.0.1");
    }

    @Test
    void anEmptyArrayParsesToNoGroups() {
        assertThat(parse("[]")).isEmpty();
    }

    @Test
    void labelsMayBeOmittedEntirely() {
        final var groups = parse("""
                [{"targets":["10.0.0.1:9100"]}]
                """);

        assertThat(groups.getFirst().labels()).isEmpty();
    }

    @Test
    void aTopLevelObjectIsRefusedNamingTheSource() {
        assertThatThrownBy(() -> parse("""
                {"results":[]}
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the endpoint")
                .hasMessageContaining("JSON array");
    }

    @Test
    void aMissingTargetsFieldIsRefused() {
        assertThatThrownBy(() -> parse("""
                [{"labels":{"a":"b"}}]
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("targets");
    }

    @Test
    void aNonStringLabelValueIsRefused() {
        assertThatThrownBy(() -> parse("""
                [{"targets":["a"],"labels":{"port":9100}}]
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("port");
    }

    @Test
    void malformedJsonIsRefusedNamingTheSource() {
        assertThatThrownBy(() -> parse("[{"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the endpoint")
                .hasMessageContaining("not valid JSON");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -Dtest=ServiceDiscoveryParserTest surefire:test`
Expected: compilation failure, `cannot find symbol: class ServiceDiscoveryParser`.

- [ ] **Step 3: Write TargetGroup**

Create `src/main/java/org/riptide/discovery/TargetGroup.java`:

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import java.util.List;
import java.util.Map;

/**
 * One element of a Prometheus HTTP service discovery document: a set of targets sharing a label set.
 *
 * <p>The NetBox service discovery plugin always emits exactly one target per group. Generic
 * producers emit several, which is why each target becomes its own exporter entry downstream: they
 * share a label set and would otherwise collide on name.</p>
 */
public record TargetGroup(List<String> targets, Map<String, String> labels) {

    public TargetGroup {
        targets = List.copyOf(targets);
        labels = Map.copyOf(labels);
    }
}
```

- [ ] **Step 4: Write ServiceDiscoveryParser**

Create `src/main/java/org/riptide/discovery/ServiceDiscoveryParser.java`:

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a Prometheus HTTP service discovery document into {@link TargetGroup}s.
 *
 * <p>The contract is a bare JSON array whose elements each carry {@code targets}, an array of
 * strings, and {@code labels}, an object of string to string. There is no {@code results} envelope,
 * which is the shape a NetBox native client would answer with and is refused here by name so the
 * difference is legible rather than a null pointer three classes away.</p>
 *
 * <p>Every refusal names the source, because at this layer there is nothing else to go on: the
 * document has no line numbers an operator authored.</p>
 */
public final class ServiceDiscoveryParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ServiceDiscoveryParser() {
    }

    /**
     * @param json the response body
     * @param sourceName how the endpoint is named in errors, credentials already redacted
     * @throws IllegalStateException on malformed JSON or any shape violation
     */
    public static List<TargetGroup> parse(final byte[] json, final String sourceName) {
        final JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (final JacksonException e) {
            throw new IllegalStateException(
                    "%s did not answer with valid JSON: %s".formatted(sourceName, e.getOriginalMessage()), e);
        } catch (final IOException e) {
            throw new IllegalStateException(
                    "%s could not be read: %s".formatted(sourceName, e.getMessage()), e);
        }
        if (root == null || !root.isArray()) {
            throw new IllegalStateException(
                    ("%s did not answer with a JSON array. Prometheus service discovery is a bare array "
                            + "of {targets, labels} objects, with no enclosing envelope.").formatted(sourceName));
        }
        final List<TargetGroup> groups = new ArrayList<>();
        int index = 0;
        for (final JsonNode element : root) {
            groups.add(group(element, index++, sourceName));
        }
        return List.copyOf(groups);
    }

    private static TargetGroup group(final JsonNode element, final int index, final String sourceName) {
        if (!element.isObject()) {
            throw new IllegalStateException(
                    "%s: entry %d is not an object".formatted(sourceName, index));
        }
        final JsonNode targets = element.get("targets");
        if (targets == null || !targets.isArray()) {
            throw new IllegalStateException(
                    "%s: entry %d has no 'targets' array".formatted(sourceName, index));
        }
        final List<String> hosts = new ArrayList<>();
        for (final JsonNode target : targets) {
            if (!target.isTextual()) {
                throw new IllegalStateException(
                        "%s: entry %d has a non-string target".formatted(sourceName, index));
            }
            hosts.add(target.textValue());
        }
        final Map<String, String> labels = new LinkedHashMap<>();
        final JsonNode labelNode = element.get("labels");
        if (labelNode != null && !labelNode.isNull()) {
            if (!labelNode.isObject()) {
                throw new IllegalStateException(
                        "%s: entry %d has a 'labels' field that is not an object".formatted(sourceName, index));
            }
            labelNode.properties().forEach(entry -> {
                if (!entry.getValue().isTextual()) {
                    // Prometheus label values are strings. A number here means a producer that has
                    // not read the contract, and coercing it would hide that from whoever has to fix it
                    throw new IllegalStateException(
                            "%s: entry %d label '%s' is not a string".formatted(
                                    sourceName, index, entry.getKey()));
                }
                labels.put(entry.getKey(), entry.getValue().textValue());
            });
        }
        return new TargetGroup(hosts, labels);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -Dtest=ServiceDiscoveryParserTest surefire:test`
Expected: PASS, 7 tests run. Confirm `BUILD SUCCESS`.

If `labelNode.properties()` does not compile, the Jackson version predates it; use `labelNode.fields().forEachRemaining(entry -> { ... })` with the same body.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/riptide/discovery/TargetGroup.java \
        src/main/java/org/riptide/discovery/ServiceDiscoveryParser.java \
        src/test/java/org/riptide/discovery/ServiceDiscoveryParserTest.java
git commit -s -m "feat(discovery): parse Prometheus HTTP service discovery documents

A bare JSON array of {targets, labels}, per the Prometheus contract. A
top-level object is refused by name rather than dereferenced, because
that is the shape a NetBox native API answers with and confusing the two
is the likeliest operator mistake.

Assisted-by: ClaudeCode:claude-opus-5"
```

---

### Task 4: Render the exporters subtree

The mapping rules and all four failure cases from the spec live here. This is the task whose tests matter most.

**Files:**
- Create: `src/main/java/org/riptide/discovery/ExporterRenderer.java`
- Create: `src/main/java/org/riptide/discovery/RenderedExporters.java`
- Test: `src/test/java/org/riptide/discovery/ExporterRendererTest.java`

**Interfaces:**
- Consumes: `TargetGroup` and `DiscoveryConfig` from Tasks 2 and 3.
- Produces:
  - `public record RenderedExporters(SortedMap<String, String> byName, int skipped)`
  - `public static RenderedExporters ExporterRenderer.render(List<TargetGroup> groups, List<String> addressLabels, String sourceName)` — throws `IllegalStateException` on name collisions and on a zero-entry result.

`byName` is a `SortedMap` so the rendered document is byte-identical for the same groups in any order. The Prometheus contract states target lists are unordered, and the content-hash short-circuit in `FileWatchTrigger` is the only thing stopping an unstable ordering from swapping the inventory every poll.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/riptide/discovery/ExporterRendererTest.java`:

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExporterRendererTest {

    private static final List<String> DEFAULT_LABELS =
            List.of("__meta_netbox_primary_ip4", "__meta_netbox_primary_ip6");

    private static RenderedExporters render(final List<TargetGroup> groups) {
        return ExporterRenderer.render(groups, DEFAULT_LABELS, "the endpoint");
    }

    private static TargetGroup group(final List<String> targets, final Map<String, String> labels) {
        return new TargetGroup(targets, labels);
    }

    @Test
    void theNameComesFromTheNetboxNameLabelAndTheAddressFromALabel() {
        final var rendered = render(List.of(group(
                List.of("firewall-01"),
                Map.of("__meta_netbox_name", "firewall-01",
                        "__meta_netbox_primary_ip4", "10.0.0.1"))));

        assertThat(rendered.byName()).containsExactly(Map.entry("firewall-01", "10.0.0.1"));
        assertThat(rendered.skipped()).isZero();
    }

    @Test
    void theAddressLabelsAreConsultedInOrder() {
        final var rendered = render(List.of(group(
                List.of("router-01"),
                Map.of("__meta_netbox_name", "router-01",
                        "__meta_netbox_primary_ip6", "2001:db8::1"))));

        assertThat(rendered.byName()).containsEntry("router-01", "2001:db8::1");
    }

    @Test
    void anIpv4LabelWinsOverAnIpv6OneBecauseItIsListedFirst() {
        final var rendered = render(List.of(group(
                List.of("router-01"),
                Map.of("__meta_netbox_name", "router-01",
                        "__meta_netbox_primary_ip4", "10.0.0.1",
                        "__meta_netbox_primary_ip6", "2001:db8::1"))));

        assertThat(rendered.byName()).containsEntry("router-01", "10.0.0.1");
    }

    @Test
    void withNoMatchingLabelTheTargetItselfIsTheAddress() {
        final var rendered = render(List.of(group(List.of("10.0.0.5:9100"), Map.of())));

        assertThat(rendered.byName()).containsExactly(Map.entry("10.0.0.5", "10.0.0.5"));
    }

    @Test
    void aBracketedIpv6TargetLosesItsBracketsAndItsPort() {
        final var rendered = render(List.of(group(List.of("[2001:db8::2]:9100"), Map.of())));

        assertThat(rendered.byName()).containsExactly(Map.entry("2001:db8::2", "2001:db8::2"));
    }

    @Test
    void aNetboxNameWithAPortOnTheTargetKeepsTheLabelName() {
        final var rendered = render(List.of(group(
                List.of("firewall-01:4242"),
                Map.of("__meta_netbox_name", "firewall-01",
                        "__meta_netbox_primary_ip4", "10.0.0.1"))));

        assertThat(rendered.byName()).containsExactly(Map.entry("firewall-01", "10.0.0.1"));
    }

    @Test
    void anEntryWithNoUsableAddressIsSkippedAndCounted() {
        final var rendered = render(List.of(
                group(List.of("has-no-ip"), Map.of("__meta_netbox_name", "has-no-ip")),
                group(List.of("fine"), Map.of("__meta_netbox_name", "fine",
                        "__meta_netbox_primary_ip4", "10.0.0.9"))));

        assertThat(rendered.byName()).containsOnlyKeys("fine");
        assertThat(rendered.skipped()).isEqualTo(1);
    }

    @Test
    void aGroupWithSeveralTargetsBecomesOneEntryPerTarget() {
        final var rendered = render(List.of(group(
                List.of("10.0.0.1:9100", "10.0.0.2:9100"), Map.of())));

        assertThat(rendered.byName()).containsOnlyKeys("10.0.0.1", "10.0.0.2");
    }

    @Test
    void collidingNamesAreRefusedAndEveryCollisionIsNamed() {
        assertThatThrownBy(() -> render(List.of(
                group(List.of("a"), Map.of("__meta_netbox_name", "sw1",
                        "__meta_netbox_primary_ip4", "10.0.0.1")),
                group(List.of("b"), Map.of("__meta_netbox_name", "sw1",
                        "__meta_netbox_primary_ip4", "10.0.0.2")),
                group(List.of("c"), Map.of("__meta_netbox_name", "sw2",
                        "__meta_netbox_primary_ip4", "10.0.0.3")),
                group(List.of("d"), Map.of("__meta_netbox_name", "sw2",
                        "__meta_netbox_primary_ip4", "10.0.0.4")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sw1")
                .hasMessageContaining("sw2")
                .hasMessageContaining("10.0.0.1")
                .hasMessageContaining("10.0.0.2");
    }

    @Test
    void aDocumentYieldingNoEntriesIsRefusedRatherThanPublishedEmpty() {
        assertThatThrownBy(() -> render(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the endpoint")
                .hasMessageContaining("no exporter");
    }

    @Test
    void aDocumentWhoseEveryEntryIsSkippedIsAlsoRefused() {
        assertThatThrownBy(() -> render(List.of(
                group(List.of("no-ip"), Map.of("__meta_netbox_name", "no-ip")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no exporter");
    }

    @Test
    void theSameGroupsInADifferentOrderRenderIdentically() {
        final TargetGroup one = group(List.of("a"), Map.of("__meta_netbox_name", "alpha",
                "__meta_netbox_primary_ip4", "10.0.0.1"));
        final TargetGroup two = group(List.of("b"), Map.of("__meta_netbox_name", "bravo",
                "__meta_netbox_primary_ip4", "10.0.0.2"));

        assertThat(render(List.of(one, two)).byName().toString())
                .as("the content hash short-circuit depends on this; an unstable order would swap "
                        + "the inventory on every poll")
                .isEqualTo(render(List.of(two, one)).byName().toString());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -Dtest=ExporterRendererTest surefire:test`
Expected: compilation failure, `cannot find symbol: class ExporterRenderer`.

- [ ] **Step 3: Write RenderedExporters**

Create `src/main/java/org/riptide/discovery/RenderedExporters.java`:

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The exporters a discovery document yielded, keyed by exporter name, plus how many entries were
 * dropped for want of a usable address.
 *
 * <p>Sorted, and that is load-bearing rather than tidy: the Prometheus contract states target lists
 * are unordered, and the content-hash short-circuit in {@code FileWatchTrigger} is the only thing
 * that stops an unstable response ordering from republishing the inventory on every poll.</p>
 *
 * @param byName exporter name to address
 * @param skipped entries with no usable address, reported rather than guessed at
 */
public record RenderedExporters(SortedMap<String, String> byName, int skipped) {

    public RenderedExporters {
        byName = Collections.unmodifiableSortedMap(new TreeMap<>(byName));
    }
}
```

- [ ] **Step 4: Write ExporterRenderer**

Create `src/main/java/org/riptide/discovery/ExporterRenderer.java`:

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import inet.ipaddr.IPAddressString;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Maps service discovery target groups onto exporter entries.
 *
 * <p><b>Why the mapping inverts.</b> For a NetBox device the service discovery target is the device
 * <em>name</em>, never an address: the plugin's serializer returns {@code [obj.name]}, and its own
 * test sets a primary IPv4, an IPv6 and an out-of-band IP and still asserts the target is the name.
 * Addresses are available only as labels, and every IP the plugin emits has already had its CIDR
 * mask stripped. So the name comes from the target and the address from a label, and no
 * mask-stripping is needed anywhere.</p>
 *
 * <p>The fallbacks are what make non-NetBox producers work: generic service discovery puts a real
 * address in the target where NetBox puts a name.</p>
 */
public final class ExporterRenderer {

    /** The label the NetBox service discovery plugin always emits for a device's name. */
    private static final String NAME_LABEL = "__meta_netbox_name";

    private ExporterRenderer() {
    }

    /**
     * @param groups the parsed document
     * @param addressLabels labels consulted in order for an address, first present wins
     * @param sourceName how the endpoint is named in errors, credentials already redacted
     * @throws IllegalStateException when two entries claim one name, or when the document yields no
     *     entries at all
     */
    public static RenderedExporters render(final List<TargetGroup> groups,
                                           final List<String> addressLabels,
                                           final String sourceName) {
        final TreeMap<String, String> byName = new TreeMap<>();
        // insertion-ordered so the collision report reads in document order, and every colliding
        // address is listed rather than only the winner
        final Map<String, List<String>> claims = new LinkedHashMap<>();
        int skipped = 0;

        for (final TargetGroup group : groups) {
            for (final String target : group.targets()) {
                final String host = host(target);
                final String name = group.labels().getOrDefault(NAME_LABEL, host);
                final String address = address(group, addressLabels, host);
                if (address == null || address.isBlank()) {
                    // never guessed at: an exporter entry with the wrong address silently enriches
                    // the wrong device's flows, which is worse than one that enriches nothing
                    skipped++;
                    continue;
                }
                claims.computeIfAbsent(name, key -> new ArrayList<>()).add(address);
                byName.put(name, address);
            }
        }

        refuseCollisions(claims, sourceName);

        if (byName.isEmpty()) {
            // a successful fetch that yields nothing is the failure mode gnmic users hit, where an
            // empty answer deletes every target. A filter typo or a permission change must not be
            // able to wipe every exporter name
            throw new IllegalStateException(
                    ("%s yielded no exporter entries (%d entr%s skipped for want of a usable address). "
                            + "Keeping the running inventory: a source of truth that answers with nothing "
                            + "is more often a filter or permission mistake than an emptied fleet.")
                            .formatted(sourceName, skipped, skipped == 1 ? "y was" : "ies were"));
        }
        return new RenderedExporters(byName, skipped);
    }

    /**
     * Every colliding name in one report, not the first. NetBox enforces device-name uniqueness per
     * site rather than globally, so two sites each holding a {@code sw1} is ordinary, and being told
     * about one of them at a time costs an operator one poll interval per collision (#630's rule).
     */
    private static void refuseCollisions(final Map<String, List<String>> claims, final String sourceName) {
        final List<String> collisions = claims.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> "  %s -> %s".formatted(entry.getKey(), String.join(", ", entry.getValue())))
                .toList();
        if (collisions.isEmpty()) {
            return;
        }
        throw new IllegalStateException(
                ("%s returned %d exporter name(s) claimed by more than one entry. Exporter names are "
                        + "inventory keys, so a collision would silently drop every claimant but one. "
                        + "NetBox enforces device-name uniqueness per site, not globally.%n%s")
                        .formatted(sourceName, collisions.size(), String.join(System.lineSeparator(), collisions)));
    }

    /**
     * The first address label present, else the target's own host, but only when that parses as an
     * address.
     *
     * <p>The parse check is what separates the two producers. A generic service discovery target is
     * {@code 10.0.0.5:9100}, whose host is a real address and is used. A NetBox device target is a
     * name, so a device with no primary IP falls through to {@code null} and is skipped and counted,
     * rather than becoming an entry whose address is a hostname the loader would refuse later with
     * a message about the wrong thing.</p>
     */
    private static String address(final TargetGroup group, final List<String> addressLabels, final String host) {
        for (final String label : addressLabels) {
            final String value = group.labels().get(label);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        // the generic-producer fallback, gated on the host actually being one. A CIDR passes too,
        // and that is deliberate: the exporter matcher is a prefix trie, so a range is a legal entry
        return new IPAddressString(host).isValid() ? host : null;
    }

    /**
     * A target's host: brackets and any trailing port removed.
     *
     * <p>{@code [2001:db8::2]:9100} and {@code 10.0.0.1:9100} both lose their port, while a bare
     * {@code 2001:db8::2} keeps every colon it has. The bracket form is what disambiguates the two,
     * which is exactly why the Prometheus contract uses it.</p>
     */
    private static String host(final String target) {
        final String trimmed = target.trim();
        if (trimmed.startsWith("[")) {
            final int close = trimmed.indexOf(']');
            return close < 0 ? trimmed : trimmed.substring(1, close);
        }
        final int colon = trimmed.indexOf(':');
        if (colon < 0) {
            return trimmed;
        }
        // more than one colon and no brackets: a bare IPv6 literal, where no part is a port
        return trimmed.indexOf(':', colon + 1) >= 0 ? trimmed : trimmed.substring(0, colon);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -Dtest=ExporterRendererTest surefire:test`
Expected: PASS, 12 tests run. Confirm `BUILD SUCCESS`.

- [ ] **Step 6: Prove the collision test actually catches a collision**

The collision assertion checks for four substrings, and a message that merely echoed its input would satisfy some of them. Verify it kills a real mutation.

Change `refuseCollisions` to report only the first collision:

```java
        final List<String> collisions = claims.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> "  %s -> %s".formatted(entry.getKey(), String.join(", ", entry.getValue())))
                .limit(1)
                .toList();
```

Run: `mvn -Dtest=ExporterRendererTest#collidingNamesAreRefusedAndEveryCollisionIsNamed surefire:test`
Expected: FAIL. Confirm the output contains a `Tests run:` line with a failure, not a compilation error and not zero tests. If it passes, the assertion is not pinning what it claims.

Revert the mutation with `git checkout -- src/main/java/org/riptide/discovery/ExporterRenderer.java`, then re-run and confirm it passes again. Check the output says `Compiling` so you are not reading a stale class file.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/riptide/discovery/ExporterRenderer.java \
        src/main/java/org/riptide/discovery/RenderedExporters.java \
        src/test/java/org/riptide/discovery/ExporterRendererTest.java
git commit -s -m "feat(discovery): map target groups onto exporter entries

For a NetBox device the service discovery target is the device name and
never an address, so the name comes from the target and the address from
a label. Every IP the plugin emits already has its CIDR mask stripped.

Four cases are explicit because each is otherwise a silent corruption: no
usable address is a counted skip, a multi-target group is one entry per
target, colliding names are refused with every collision named, and a
document yielding nothing is refused rather than published empty.

Assisted-by: ClaudeCode:claude-opus-5"
```

---

### Task 5: Fetch the document over HTTP

**Files:**
- Create: `src/main/java/org/riptide/discovery/DiscoveryClient.java`
- Test: `src/test/java/org/riptide/discovery/DiscoveryClientTest.java`

**Interfaces:**
- Consumes: `DiscoveryConfig` (Task 2), `BoundedHttpRead` (Task 1), `org.riptide.secrets.SecretResolvers` (existing).
- Produces:
  - `public DiscoveryClient(DiscoveryConfig config, SecretResolvers secretResolvers)`
  - `public byte[] fetch() throws IOException` — throws `FileNotFoundException` on a 404.
  - `public String describe()` — the endpoint with credentials redacted.

The token resolves once at construction, matching the house convention visible in `ClickhouseRepository`: an unresolvable reference fails startup rather than every poll.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/riptide/discovery/DiscoveryClientTest.java`:

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.riptide.secrets.SecretRef;
import org.riptide.secrets.SecretResolvers;
import org.riptide.utils.HttpServerConfig;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscoveryClientTest {

    private static final AtomicReference<String> AUTHORIZATION = new AtomicReference<>();

    private static final HttpServer SERVER = startServer();

    private static HttpServer startServer() {
        HttpServerConfig.ensureApplied();
        try {
            final HttpServer server =
                    HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/devices", exchange -> {
                try (exchange) {
                    AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    final byte[] answer = "[]".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, answer.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(answer);
                    }
                }
            });
            server.createContext("/gone", exchange -> {
                try (exchange) {
                    exchange.sendResponseHeaders(404, -1);
                }
            });
            server.start();
            return server;
        } catch (final IOException e) {
            throw new UncheckedIOException("could not start the test server", e);
        }
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    private static String endpoint(final String path) {
        return "http://" + SERVER.getAddress().getHostString()
                + ":" + SERVER.getAddress().getPort() + path;
    }

    private static DiscoveryClient client(final String path, final String token) {
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl(endpoint(path));
        config.setToken(SecretRef.of(token));
        return new DiscoveryClient(config, SecretResolvers.defaults());
    }

    @Test
    void sendsTheTokenWithTheConfiguredScheme() throws IOException {
        final byte[] body = client("/devices", "s3cret").fetch();

        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("[]");
        assertThat(AUTHORIZATION.get())
                .as("NetBox wants 'Token', not 'Bearer'")
                .isEqualTo("Token s3cret");
    }

    @Test
    void sendsNoAuthorizationHeaderWhenNoTokenIsConfigured() throws IOException {
        AUTHORIZATION.set(null);

        client("/devices", null).fetch();

        assertThat(AUTHORIZATION.get()).isNull();
    }

    @Test
    void aNotFoundArrivesAsAbsenceRatherThanFailure() {
        assertThatThrownBy(() -> client("/gone", null).fetch())
                .isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void describeRedactsCredentialsEmbeddedInTheUrl() {
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl("https://user:hunter2@netbox.example.com/api/devices/");

        final String described = new DiscoveryClient(config, SecretResolvers.defaults()).describe();

        assertThat(described).doesNotContain("hunter2").contains("***@");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -Dtest=DiscoveryClientTest surefire:test`
Expected: compilation failure, `cannot find symbol: class DiscoveryClient`.

- [ ] **Step 3: Write the implementation**

Create `src/main/java/org/riptide/discovery/DiscoveryClient.java`:

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import org.riptide.config.BoundedHttpRead;
import org.riptide.config.ByteOrderMark;
import org.riptide.secrets.SecretResolvers;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Fetches a Prometheus HTTP service discovery document, within every bound the shared
 * {@link BoundedHttpRead} applies.
 *
 * <p>The token resolves once, here, rather than on every poll: an unresolvable reference is a
 * startup failure in this project, which is the same rule {@code ClickhouseRepository} follows for
 * its credentials. A reference that cannot resolve at poll time would instead surface as a counted
 * reload failure every minute forever.</p>
 */
public final class DiscoveryClient {

    /**
     * Refusal ceiling for a discovery document. Generous: the NetBox plugin disables pagination, so
     * a large fleet legitimately answers with several megabytes, and the ceiling exists to stop a
     * misdirected endpoint from being read into the heap rather than to bound a real fleet.
     */
    static final int MAX_BYTES = 32 * 1024 * 1024;

    /** Credentials embedded in a location, as {@code scheme://user:token@host}. */
    private static final Pattern USERINFO = Pattern.compile("([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@\\s\\]]*@");

    private final DiscoveryConfig config;
    private final BoundedHttpRead http;

    public DiscoveryClient(final DiscoveryConfig config, final SecretResolvers secretResolvers) {
        this.config = Objects.requireNonNull(config);
        Objects.requireNonNull(secretResolvers, "secretResolvers");
        final String token = secretResolvers.resolve(config.getToken());
        final Map<String, String> headers = token == null
                ? Map.of()
                : Map.of("Authorization", config.getAuthScheme() + " " + token);
        this.http = new BoundedHttpRead(
                config.getTimeout(), MAX_BYTES, "discovery document", this::describe, headers);
    }

    /**
     * The document as the endpoint has it now, with any byte-order mark removed.
     *
     * @throws java.io.FileNotFoundException on a 404, which is absence rather than failure
     * @throws IOException on any other non-200, a refused connection, a timeout, or a body past the
     *     ceiling
     */
    public byte[] fetch() throws IOException {
        return ByteOrderMark.strip(this.http.readRemote(this.config.endpoint()));
    }

    /** The endpoint, with any embedded credentials removed; safe to log. */
    public String describe() {
        final String url = this.config.getUrl();
        return url == null ? "riptide.discovery.url (unset)" : USERINFO.matcher(url).replaceAll("$1***@");
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -Dtest=DiscoveryClientTest surefire:test`
Expected: PASS, 4 tests run. Confirm `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/riptide/discovery/DiscoveryClient.java \
        src/test/java/org/riptide/discovery/DiscoveryClientTest.java
git commit -s -m "feat(discovery): fetch the service discovery document

Reuses the shared bounded read. The token resolves once at construction,
matching how ClickHouse credentials are handled here: an unresolvable
reference fails startup rather than every poll for the process lifetime.
The default scheme is Token, because that is what NetBox expects.

Assisted-by: ClaudeCode:claude-opus-5"
```

---

### Task 6: The inventory document seam

Pure refactor with no discovery in it. This is the task that makes the composition reach every site, and the two sites in `ConfigFileReloader` are the ones a reviewer should check hardest: missing them would wedge credential rotation once Task 7 lands.

**Files:**
- Create: `src/main/java/org/riptide/inventory/InventoryDocument.java`
- Create: `src/main/java/org/riptide/inventory/FileInventoryDocument.java`
- Modify: `src/main/java/org/riptide/inventory/Inventory.java`
- Modify: `src/main/java/org/riptide/config/ConfigFileReloader.java:259` and `:434`
- Test: `src/test/java/org/riptide/inventory/FileInventoryDocumentTest.java`

**Interfaces:**
- Consumes: `InventoryConfig`, `InventoryLoader`, `ByteOrderMark` (all existing).
- Produces:
  - `public interface InventoryDocument { String text(); String name(); }` — `text()` returns `null` when no inventory is configured, and throws `IllegalStateException` when one is configured but unreadable.
  - `public final class FileInventoryDocument implements InventoryDocument`, a Spring `@Component`.
  - `Inventory.rebuildAndSwap(SnmpProfilesConfig profiles)` — the `Path` parameter is gone.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/riptide/inventory/FileInventoryDocumentTest.java`:

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileInventoryDocumentTest {

    @TempDir
    Path tempDir;

    private static FileInventoryDocument document(final Path file) {
        final InventoryConfig config = new InventoryConfig();
        config.setFile(file);
        return new FileInventoryDocument(config);
    }

    @Test
    void anUnsetFileHasNoText() {
        assertThat(document(null).text()).isNull();
    }

    @Test
    void anUnsetFileStillNamesItselfReadably() {
        assertThat(document(null).name()).contains("riptide.inventory.file");
    }

    @Test
    void aSetFileIsReadAndNamedByItsPath() throws IOException {
        final Path file = this.tempDir.resolve("inventory.yaml");
        Files.writeString(file, "riptide:\n  exporters: {}\n");

        assertThat(document(file).text()).contains("exporters");
        assertThat(document(file).name()).isEqualTo(file.toString());
    }

    @Test
    void aSetButMissingFileFailsNamingThePath() {
        final Path missing = this.tempDir.resolve("missing.yaml");

        assertThatThrownBy(() -> document(missing).text())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not readable")
                .hasMessageContaining("missing.yaml");
    }

    @Test
    void aByteOrderMarkOnTheFrontIsRemoved() throws IOException {
        final Path file = this.tempDir.resolve("bom.yaml");
        Files.write(file, ("﻿riptide:\n  exporters: {}\n").getBytes(StandardCharsets.UTF_8));

        assertThat(document(file).text()).startsWith("riptide:");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -Dtest=FileInventoryDocumentTest surefire:test`
Expected: compilation failure, `cannot find symbol: class FileInventoryDocument`.

- [ ] **Step 3: Write the seam**

Create `src/main/java/org/riptide/inventory/InventoryDocument.java`:

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

/**
 * Where the inventory document comes from.
 *
 * <p><b>Why this exists</b>: the file is read at four sites — boot, the inventory watcher, and two
 * {@code rebuildAndSwap} calls in {@code ConfigFileReloader} for a credential rotation and its
 * pending retry. Discovery composes a document from two sources, and a composition that reached
 * only the site a report pointed at would rebuild an inventory with no exporters during a
 * rotation, which the regression guard would refuse, wedging rotation until a restart. One seam,
 * so there is nowhere left to miss.</p>
 */
public interface InventoryDocument {

    /**
     * The document as it is now, or {@code null} when no inventory is configured at all, which is
     * valid and means the empty inventory.
     *
     * @throws IllegalStateException when an inventory IS configured and cannot be produced
     */
    String text();

    /** How the document is named in loader errors. */
    String name();
}
```

Create `src/main/java/org/riptide/inventory/FileInventoryDocument.java`:

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.inventory;

import org.riptide.config.ByteOrderMark;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The inventory file named by {@code riptide.inventory.file}, which is the whole document when
 * discovery is off and supplies only the agent ranges when it is on.
 *
 * <p>The size ceiling and the "not readable" message are the ones {@code InventoryLoader.load} has
 * always applied, moved rather than rewritten, so an operator sees the same sentence they always
 * did.</p>
 */
@Component
public class FileInventoryDocument implements InventoryDocument {

    /** A 64 MiB inventory is already two orders past anything real; past this it is a wrong path. */
    static final long MAX_FILE_BYTES = 64L * 1024 * 1024;

    private final InventoryConfig config;

    public FileInventoryDocument(final InventoryConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    @Override
    public String text() {
        final Path file = this.config.getFile();
        if (file == null) {
            return null;
        }
        final long size;
        try {
            size = Files.size(file);
        } catch (final IOException e) {
            throw new IllegalStateException(
                    "Inventory file %s is not readable: %s".formatted(file, e.getMessage()), e);
        }
        if (size > MAX_FILE_BYTES) {
            throw new IllegalStateException(
                    "Inventory file %s is larger than the %d byte ceiling".formatted(file, MAX_FILE_BYTES));
        }
        try {
            return ByteOrderMark.strip(Files.readString(file));
        } catch (final IOException e) {
            throw new IllegalStateException(
                    "Inventory file %s is not readable: %s".formatted(file, e.getMessage()), e);
        }
    }

    @Override
    public String name() {
        final Path file = this.config.getFile();
        return file == null ? "riptide.inventory.file (unset)" : file.toString();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -Dtest=FileInventoryDocumentTest surefire:test`
Expected: PASS, 5 tests run. Confirm `BUILD SUCCESS`.

- [ ] **Step 5: Rewire Inventory onto the seam**

In `src/main/java/org/riptide/inventory/Inventory.java`:

Replace the `InventoryConfig config` field with the document, and drop the `java.nio.file.Path` import:

```java
    @NonNull
    private final InventoryDocument document;
```

Replace the body of `load()`:

```java
    @PostConstruct
    public void load() {
        // boot commits through the same path as reload, so the whole-instance swap stays the only
        // way serving state ever changes. Warnings flush after the swap: boot either publishes or
        // dies, and the log must never describe a candidate that did not go live (#539)
        final String text = this.document.text();
        final InventoryLoader.ParseResult result = text == null
                ? new InventoryLoader.ParseResult(InventorySnapshot.empty(), java.util.List.of())
                : InventoryLoader.parseWithWarnings(this.profiles, text, this.document.name());
        final InventorySnapshot loaded = result.snapshot();
        swap(loaded);
        result.flushWarnings();
        if (text == null) {
            // silence here would read as "working" while every flow goes unenriched
            log.info("No inventory configured ({}): serving the empty inventory", this.document.name());
        } else {
            log.info("Inventory loaded from {}: {} agent ranges, {} enrichment entries",
                    this.document.name(), loaded.agentCount(), loaded.exporterCount());
        }
    }
```

Add `import java.util.List;` and use `List.of()` rather than the fully qualified form.

Replace the signature and first lines of `rebuildAndSwap`, keeping every comment and the guard below it exactly as they are:

```java
    public synchronized InventorySnapshot rebuildAndSwap(final SnmpProfilesConfig profiles) {
        final String text = this.document.text();
        final InventoryLoader.ParseResult result = text == null
                ? new InventoryLoader.ParseResult(InventorySnapshot.empty(), List.of())
                : InventoryLoader.parseWithWarnings(profiles, text, this.document.name());
        final InventorySnapshot rebuilt = result.snapshot();
```

- [ ] **Step 6: Fix both ConfigFileReloader call sites**

In `src/main/java/org/riptide/config/ConfigFileReloader.java`, change line 259 and line 434 from `this.inventory.rebuildAndSwap(pending, this.inventoryConfig.getFile())` and `this.inventory.rebuildAndSwap(candidateProfiles, this.inventoryConfig.getFile())` to drop the second argument:

```java
            published = this.inventory.rebuildAndSwap(pending);
```

```java
                    this.inventory.rebuildAndSwap(candidateProfiles);
```

Leave every other use of `this.inventoryConfig` alone. Lines 389, 393, 448 and 480 read the path for log messages and for the restart-required check, and they stay.

Verify nothing else calls the two-argument form:

```bash
grep -rn "rebuildAndSwap" src/main/java/ src/test/java/
```

Every hit must be the one-argument form. Fix any test that calls the old shape.

- [ ] **Step 7: Run the inventory and config suites**

Run: `mvn -Dtest='Inventory*Test,ConfigFileReloader*Test,InventoryFileReloader*Test' surefire:test`
Expected: PASS. If a test constructs `Inventory` directly with an `InventoryConfig`, update it to pass a `FileInventoryDocument` wrapping that config.

- [ ] **Step 8: Run the full unit suite**

Run: `make jar`
Expected: `BUILD SUCCESS`. This is a refactor, so any failure here is a real behaviour change that must be understood rather than patched around.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/org/riptide/inventory/InventoryDocument.java \
        src/main/java/org/riptide/inventory/FileInventoryDocument.java \
        src/main/java/org/riptide/inventory/Inventory.java \
        src/main/java/org/riptide/config/ConfigFileReloader.java \
        src/test/java/org/riptide/inventory/FileInventoryDocumentTest.java
git commit -s -m "refactor(inventory): one seam for where the inventory document comes from

The file is read at four sites: boot, the inventory watcher, and two
rebuildAndSwap calls in ConfigFileReloader for a credential rotation and
its pending retry. Discovery is about to compose that document from two
sources, and a composition reaching only some of them would rebuild an
inventory with no exporters during a rotation, which the guard refuses,
wedging rotation until a restart. One seam, nowhere left to miss.

No behaviour change: the size ceiling and the not-readable message move
rather than being rewritten.

Assisted-by: ClaudeCode:claude-opus-5"
```

---

### Task 7: Compose, wire and prove it end to end

**Files:**
- Create: `src/main/java/org/riptide/discovery/ComposedInventoryDocument.java`
- Modify: `src/main/java/org/riptide/config/InventoryFileReloader.java`
- Test: `src/test/java/org/riptide/discovery/ComposedInventoryDocumentTest.java`
- Test: `src/test/java/org/riptide/discovery/DiscoveryReloadTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1 through 6.
- Produces: `ComposedInventoryDocument implements InventoryDocument, FileWatchTrigger.Source`, registered as the primary `InventoryDocument` bean when `riptide.discovery.url` is set.

- [ ] **Step 1: Write the failing composition test**

Create `src/test/java/org/riptide/discovery/ComposedInventoryDocumentTest.java`:

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.riptide.inventory.InventoryDocument;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComposedInventoryDocumentTest {

    private static final String DEVICES = """
            [{"targets":["firewall-01"],
              "labels":{"__meta_netbox_name":"firewall-01",
                        "__meta_netbox_primary_ip4":"10.0.0.1"}}]
            """;

    /** A file document answering fixed text, standing in for riptide.inventory.file. */
    private record FixedFile(String text) implements InventoryDocument {
        @Override
        public String name() {
            return "inventory.yaml";
        }
    }

    private static ComposedInventoryDocument composed(final String fileText, final String json) {
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl("https://netbox.example.com/api/devices/");
        return new ComposedInventoryDocument(
                new FixedFile(fileText),
                () -> json.getBytes(StandardCharsets.UTF_8),
                () -> "the endpoint",
                config,
                new MetricRegistry());
    }

    @Test
    void theAgentsTreeFromTheFileSurvivesAndExportersComeFromDiscovery() {
        final String text = composed("""
                riptide:
                  snmp:
                    agents:
                      "10.0.0.0/8":
                        credentials: corp-v3
                """, DEVICES).text();

        assertThat(text).contains("10.0.0.0/8").contains("corp-v3");
        assertThat(text).contains("firewall-01").contains("10.0.0.1");
    }

    @Test
    void anExportersTreeInTheFileFailsNamingBothOwners() {
        assertThatThrownBy(() -> composed("""
                riptide:
                  exporters:
                    hand-written:
                      address: 10.9.9.9
                """, DEVICES).text())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("riptide.discovery.url")
                .hasMessageContaining("exporters")
                .hasMessageContaining("inventory.yaml");
    }

    @Test
    void anAbsentInventoryFileIsFineBecauseDiscoveryOwnsExportersAlone() {
        final String text = composed(null, DEVICES).text();

        assertThat(text).contains("firewall-01");
    }

    @Test
    void theSameInputsRenderByteIdenticalDocuments() {
        assertThat(composed(null, DEVICES).text()).isEqualTo(composed(null, DEVICES).text());
    }

    @Test
    void anEmptyDocumentIsRefusedRatherThanEmptyingTheTree() {
        assertThatThrownBy(() -> composed(null, "[]").text())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no exporter");
    }

    @Test
    void theGaugesReportWhatTheLastRenderProduced() {
        final MetricRegistry metrics = new MetricRegistry();
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl("https://netbox.example.com/api/devices/");
        final ComposedInventoryDocument document = new ComposedInventoryDocument(
                new FixedFile(null),
                () -> """
                        [{"targets":["a"],"labels":{"__meta_netbox_name":"a",
                          "__meta_netbox_primary_ip4":"10.0.0.1"}},
                         {"targets":["b"],"labels":{"__meta_netbox_name":"b"}}]
                        """.getBytes(StandardCharsets.UTF_8),
                () -> "the endpoint",
                config,
                metrics);

        document.text();

        assertThat(metrics.getGauges().get("discovery.targets").getValue()).isEqualTo(1);
        assertThat(metrics.getGauges().get("discovery.skipped").getValue()).isEqualTo(1);
    }

    @Test
    void anUnreachableEndpointFailsNamingIt() {
        final DiscoveryConfig config = new DiscoveryConfig();
        config.setUrl("https://netbox.example.com/api/devices/");
        final ComposedInventoryDocument document = new ComposedInventoryDocument(
                new FixedFile(null),
                () -> {
                    throw new IOException("connection refused");
                },
                () -> "the endpoint",
                config,
                new MetricRegistry());

        assertThatThrownBy(document::text)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the endpoint")
                .hasMessageContaining("connection refused");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -Dtest=ComposedInventoryDocumentTest surefire:test`
Expected: compilation failure, `cannot find symbol: class ComposedInventoryDocument`.

- [ ] **Step 3: Write the composed document**

Create `src/main/java/org/riptide/discovery/ComposedInventoryDocument.java`:

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.codahale.metrics.MetricRegistry;
import org.riptide.config.FileWatchTrigger;
import org.riptide.inventory.InventoryDocument;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The inventory document when discovery is on: agent ranges from the file, exporters from the
 * endpoint, merged into one document the existing loader validates as a whole.
 *
 * <p><b>Why compose rather than publish.</b> {@code Inventory} holds one immutable snapshot with
 * both trees behind a single volatile write. A discovery publisher of its own would publish an
 * empty agents tree, which the regression guard would either refuse forever or, once declared,
 * apply by deregistering the entire polled fleet. Composing keeps one document, one hash and one
 * commit path, and every guard downstream keeps working unchanged.</p>
 *
 * <p><b>The merge is structural.</b> The file is parsed, its {@code riptide.exporters} subtree is
 * replaced with the rendered one, and the result is dumped for the loader. Textual splicing is
 * rejected because it would have to find a subtree by position in a file an operator hand-writes.
 * Round-tripping the file's {@code riptide.snmp} subtree loses comments and collapses a duplicate
 * key to its last occurrence, and neither is a behaviour change: the loader already parses the same
 * file with the same SnakeYAML semantics.</p>
 */
public class ComposedInventoryDocument implements InventoryDocument, FileWatchTrigger.Source {

    /** What a discovery fetch answers with; an interface so tests need no HTTP server. */
    @FunctionalInterface
    public interface Fetcher {
        byte[] fetch() throws IOException;
    }

    private final InventoryDocument file;
    private final Fetcher fetcher;
    private final Supplier<String> describe;
    private final DiscoveryConfig config;
    private final AtomicInteger targets = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();

    public ComposedInventoryDocument(final InventoryDocument file,
                                     final Fetcher fetcher,
                                     final Supplier<String> describe,
                                     final DiscoveryConfig config,
                                     final MetricRegistry metrics) {
        this.file = Objects.requireNonNull(file);
        this.fetcher = Objects.requireNonNull(fetcher);
        this.describe = Objects.requireNonNull(describe);
        this.config = Objects.requireNonNull(config);
        Objects.requireNonNull(metrics, "metrics");
        // remove-then-register, not Dropwizard's get-or-create: a restarted bean would otherwise
        // be handed the old bean's lambda, permanently reading dead fields
        register(metrics, "discovery.targets", this.targets);
        register(metrics, "discovery.skipped", this.skipped);
    }

    private static void register(final MetricRegistry metrics, final String name, final AtomicInteger value) {
        metrics.remove(name);
        metrics.register(name, (com.codahale.metrics.Gauge<Integer>) value::get);
    }

    @Override
    public String text() {
        final byte[] json;
        try {
            json = this.fetcher.fetch();
        } catch (final IOException e) {
            throw new IllegalStateException(
                    "%s could not be read: %s".formatted(this.describe.get(), e.getMessage()), e);
        }
        final List<TargetGroup> groups = ServiceDiscoveryParser.parse(json, this.describe.get());
        final RenderedExporters rendered =
                ExporterRenderer.render(groups, this.config.getAddressLabels(), this.describe.get());
        this.targets.set(rendered.byName().size());
        this.skipped.set(rendered.skipped());
        return merge(rendered);
    }

    @Override
    public String name() {
        return "%s + %s".formatted(this.file.name(), this.describe.get());
    }

    /**
     * One fetch for the watch loop. A 404 is absence, so the last good inventory keeps serving and
     * the trigger warns once; every other failure is a throw the trigger counts.
     */
    @Override
    public FileWatchTrigger.Fetch fetch() throws IOException {
        try {
            return new FileWatchTrigger.Fetch.Present(text().getBytes(StandardCharsets.UTF_8));
        } catch (final IllegalStateException e) {
            if (e.getCause() instanceof FileNotFoundException) {
                // a 404: the endpoint is not there, which is not the same as a broken one. Never
                // Vanished — no remote source can tell an atomic replacement from a deletion
                return new FileWatchTrigger.Fetch.Absent();
            }
            throw e;
        }
    }

    @Override
    public String describe() {
        return name();
    }

    /** The file's trees with {@code riptide.exporters} replaced by the rendered one. */
    @SuppressWarnings("unchecked")
    private String merge(final RenderedExporters rendered) {
        final Map<String, Object> root = new LinkedHashMap<>();
        final String fileText = this.file.text();
        if (fileText != null && !fileText.isBlank()) {
            final Object parsed = new Yaml(new SafeConstructor(loaderOptions())).load(fileText);
            if (parsed instanceof Map<?, ?> map) {
                map.forEach((key, value) -> root.put(String.valueOf(key), value));
            }
        }
        final Map<String, Object> riptide = new LinkedHashMap<>();
        if (root.get("riptide") instanceof Map<?, ?> existing) {
            existing.forEach((key, value) -> riptide.put(String.valueOf(key), value));
        }
        if (riptide.containsKey("exporters")) {
            throw new IllegalStateException(
                    ("%s declares an 'exporters' tree while riptide.discovery.url is set. Discovery owns "
                            + "the exporters tree and the inventory file owns snmp.agents, so an entry can "
                            + "never have two possible sources. Remove the exporters tree from the file, or "
                            + "unset riptide.discovery.url.").formatted(this.file.name()));
        }
        final Map<String, Object> exporters = new LinkedHashMap<>();
        rendered.byName().forEach((name, address) -> exporters.put(name, Map.of("address", address)));
        riptide.put("exporters", exporters);
        root.put("riptide", riptide);
        return new Yaml(dumperOptions()).dump(root);
    }

    private static LoaderOptions loaderOptions() {
        final LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(64 * 1024 * 1024);
        return options;
    }

    /** Block style so the dumped document reads like one an operator wrote, and parses the same. */
    private static DumperOptions dumperOptions() {
        final DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return options;
    }
}
```

- [ ] **Step 4: Run the composition test to verify it passes**

Run: `mvn -Dtest=ComposedInventoryDocumentTest surefire:test`
Expected: PASS, 7 tests run. Confirm `BUILD SUCCESS`.

- [ ] **Step 5: Wire the bean and the reload source**

Create the Spring wiring. Add to `src/main/java/org/riptide/discovery/DiscoveryConfiguration.java`:

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.codahale.metrics.MetricRegistry;
import org.riptide.inventory.FileInventoryDocument;
import org.riptide.inventory.InventoryDocument;
import org.riptide.secrets.SecretResolvers;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires discovery only when {@code riptide.discovery.url} is set. With it unset nothing here is
 * created, the {@link FileInventoryDocument} stays the only {@link InventoryDocument}, and every
 * path behaves exactly as it did before discovery existed.
 */
@Configuration
@ConditionalOnProperty(prefix = "riptide.discovery", name = "url")
public class DiscoveryConfiguration {

    @Bean
    public DiscoveryClient discoveryClient(final DiscoveryConfig config,
                                           final SecretResolvers secretResolvers) {
        return new DiscoveryClient(config, secretResolvers);
    }

    /**
     * Primary, so {@code Inventory} and both reloaders take the composed document without any of
     * them knowing discovery exists.
     */
    @Bean
    @Primary
    public ComposedInventoryDocument composedInventoryDocument(final FileInventoryDocument file,
                                                               final DiscoveryClient client,
                                                               final DiscoveryConfig config,
                                                               final MetricRegistry metrics) {
        return new ComposedInventoryDocument(file, client::fetch, client::describe, config, metrics);
    }
}
```

In `src/main/java/org/riptide/config/InventoryFileReloader.java`, inject the optional composed source and choose it over the file path. Add to the constructor parameters and fields:

```java
    private final org.springframework.beans.factory.ObjectProvider<FileWatchTrigger.Source> discoverySource;
```

Add it as the last constructor parameter, assign it, then in `start()` replace the trigger construction. Keep the disabled-early-returns and the `messages(...)` call exactly as they are for the file case:

```java
        final FileWatchTrigger.Source source = this.discoverySource.getIfAvailable();
        if (source != null) {
            // discovery composes the document, so the watched thing is no longer the file alone and
            // the file source's Vanished answer no longer applies: a remote source cannot tell an
            // atomic replacement from a deletion
            this.trigger = new FileWatchTrigger(log, source, this.properties.getReloadInterval(),
                    "InventoryFileReloader", messages(this.location), this.metrics, "inventory",
                    this.reloadFailures, true, cycle());
        } else {
            this.trigger = new FileWatchTrigger(log, this.location, this.properties.getReloadInterval(),
                    "InventoryFileReloader", messages(this.location), this.metrics, "inventory",
                    this.reloadFailures, true, cycle());
        }
```

Extract the existing anonymous `FileWatchTrigger.Cycle` into a private `cycle()` method returning it, so both branches share one copy.

The poll interval for discovery comes from `riptide.discovery.interval`, not `riptide.config.reload-interval`. Change the disabled check so discovery does not require the config reload interval:

```java
        final FileWatchTrigger.Source source = this.discoverySource.getIfAvailable();
        final Duration interval = source != null
                ? this.discoveryInterval
                : this.properties.getReloadInterval();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            log.debug("Inventory hot-reload disabled (no interval configured)");
            return;
        }
```

Inject `DiscoveryConfig` as an `ObjectProvider` too and read `getInterval()` from it, defaulting to null when absent. Use `interval` in the trigger constructions above in place of `this.properties.getReloadInterval()`.

Also relax the `this.location == null` early return: with discovery on, an unset `riptide.inventory.file` is valid, because discovery supplies the whole document. Guard it as:

```java
        if (this.location == null && source == null) {
            log.debug("Inventory hot-reload disabled (no riptide.inventory.file)");
            return;
        }
```

and make `messages(...)` tolerate a null location by naming the source instead.

- [ ] **Step 6: Write the end-to-end Spring test**

Create `src/test/java/org/riptide/discovery/DiscoveryReloadTest.java`:

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.discovery;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.riptide.inventory.Inventory;
import org.riptide.pipeline.ExporterIdentity;
import org.riptide.utils.HttpServerConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DiscoveryReloadTest {

    private static final AtomicReference<String> BODY = new AtomicReference<>("""
            [{"targets":["firewall-01"],
              "labels":{"__meta_netbox_name":"firewall-01",
                        "__meta_netbox_primary_ip4":"10.0.0.1"}}]
            """);

    private static final AtomicReference<Integer> STATUS = new AtomicReference<>(200);

    private static final HttpServer SERVER = startServer();

    @Autowired
    private Inventory inventory;

    private static HttpServer startServer() {
        HttpServerConfig.ensureApplied();
        try {
            final HttpServer server =
                    HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/devices", exchange -> {
                try (exchange) {
                    final int status = STATUS.get();
                    if (status != 200) {
                        exchange.sendResponseHeaders(status, -1);
                        return;
                    }
                    final byte[] answer = BODY.get().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, answer.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(answer);
                    }
                }
            });
            server.start();
            return server;
        } catch (final IOException e) {
            throw new UncheckedIOException("could not start the discovery server", e);
        }
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @DynamicPropertySource
    static void discoveryEndpoint(final DynamicPropertyRegistry registry) {
        registry.add("riptide.discovery.url",
                () -> "http://" + SERVER.getAddress().getHostString()
                        + ":" + SERVER.getAddress().getPort() + "/devices");
        registry.add("riptide.discovery.interval", () -> "200ms");
    }

    private static ExporterIdentity identity(final String address) throws UnknownHostException {
        return new ExporterIdentity.NetflowIpfix(InetAddress.getByName(address), 0L);
    }

    @Test
    @Timeout(60)
    void bootPublishesTheDiscoveredExporters() throws Exception {
        assertThat(this.inventory.snapshot().exporterView().match(identity("10.0.0.1")))
                .get()
                .extracting("name")
                .isEqualTo("firewall-01");
    }

    @Test
    @Timeout(60)
    void aChangedDocumentSwapsTheInventory() throws Exception {
        BODY.set("""
                [{"targets":["firewall-01"],
                  "labels":{"__meta_netbox_name":"renamed-01",
                            "__meta_netbox_primary_ip4":"10.0.0.1"}}]
                """);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(this.inventory.snapshot().exporterView().match(identity("10.0.0.1")))
                        .get()
                        .extracting("name")
                        .isEqualTo("renamed-01"));
    }

    @Test
    @Timeout(60)
    void anEmptyAnswerKeepsTheLastGoodInventoryServing() throws Exception {
        BODY.set("[]");

        // long enough for several poll cycles at the 200ms interval
        Thread.sleep(2000);

        assertThat(this.inventory.snapshot().exporterCount())
                .as("an empty answer must never empty the exporters tree")
                .isPositive();
    }
}
```

If Awaitility is not on the test classpath, replace `await()` with the repo's own helper: check `src/test/java/org/riptide/classification/internal/ClassificationRulesTestSupport.java` for its `await(...)` and use that instead. Do not add a dependency.

These three tests share one context and mutate static state, so they must not run in parallel. The project sets no surefire `parallel` option, so this is already true.

- [ ] **Step 7: Run the end-to-end test**

Run: `mvn -Dtest=DiscoveryReloadTest surefire:test`
Expected: PASS, 3 tests run. Confirm `BUILD SUCCESS`.

- [ ] **Step 8: Prove the credential-rotation regression test**

This is the assertion most likely to be quietly wrong, because it covers the two `ConfigFileReloader` sites. Verify it by mutation.

In `ComposedInventoryDocument.merge`, temporarily make the exporters tree empty:

```java
        final Map<String, Object> exporters = new LinkedHashMap<>();
        // rendered.byName().forEach((name, address) -> exporters.put(name, Map.of("address", address)));
```

Run: `mvn -Dtest=DiscoveryReloadTest#bootPublishesTheDiscoveredExporters surefire:test`
Expected: FAIL. Confirm a `Tests run:` line with a failure, not a compilation error and not zero tests run.

Restore with `git checkout -- src/main/java/org/riptide/discovery/ComposedInventoryDocument.java`, re-run, and confirm the output says `Compiling` before it passes, so you are not reading a stale class file.

- [ ] **Step 9: Run the full unit suite**

Run: `make jar`
Expected: `BUILD SUCCESS`. Note explicitly in your report that this runs no `*IT` class, so it is not evidence about the ClickHouse or nl6 end-to-end paths.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/org/riptide/discovery/ComposedInventoryDocument.java \
        src/main/java/org/riptide/discovery/DiscoveryConfiguration.java \
        src/main/java/org/riptide/config/InventoryFileReloader.java \
        src/test/java/org/riptide/discovery/ComposedInventoryDocumentTest.java \
        src/test/java/org/riptide/discovery/DiscoveryReloadTest.java
git commit -s -m "feat(discovery): compose the inventory document from file and endpoint

Agent ranges from the inventory file, exporters from the endpoint, merged
into one document the existing loader validates as a whole. Discovery gets
no publisher of its own, so the regression guard, the reload counters, the
staleness gauge and the poller refresh all keep working unchanged.

An exporters tree in the file while discovery is on fails with a message
naming both owners. An empty answer is refused rather than emptying the
tree, which is the gnmic failure mode this deliberately does not copy.

Assisted-by: ClaudeCode:claude-opus-5"
```

---

### Task 8: Documentation

The either-or rule is a sibling that lives in prose as well as in code. A reader reaches the enrichment page first and would otherwise write an `exporters` tree that fails startup without knowing why.

**Files:**
- Create: `docs/docs/configuration/discovery.md`
- Modify: `docs/docs/configuration/exporter-enrichment.md`
- Modify: `docs/docs/configuration/agent-configuration.md`

- [ ] **Step 1: Write the discovery page**

Create `docs/docs/configuration/discovery.md`. Write one sentence per line; the renderer joins soft breaks. Do not hard-wrap at a column width.

```markdown
# Dynamic exporter discovery

Riptide can read its exporter list from a Prometheus HTTP service discovery endpoint instead of from the inventory file.
A site already running NetBox as its source of truth then maintains one device list rather than two.

Discovery is off until `riptide.discovery.url` is set.

## What discovery owns

Discovery owns the `exporters` tree.
The inventory file keeps owning `snmp.agents`, because agent ranges are a handful of CIDRs that gain nothing from per-device discovery, and because SNMP enrichment would otherwise stop the moment discovery was enabled.

No entry ever has two possible sources.
Writing an `exporters` tree into the inventory file while `riptide.discovery.url` is set fails startup with a message naming both locations.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `riptide.discovery.url` | unset | The endpoint. Unset disables discovery. |
| `riptide.discovery.token` | unset | Credential, as a [secret reference](secret-references.md). |
| `riptide.discovery.auth-scheme` | `Token` | Paired with the token in the `Authorization` header. NetBox expects `Token`, not `Bearer`. |
| `riptide.discovery.interval` | `60s` | Poll interval. |
| `riptide.discovery.timeout` | `10s` | Bounds the connect, each read, and the whole response. |
| `riptide.discovery.address-labels` | `__meta_netbox_primary_ip4,__meta_netbox_primary_ip6` | Labels consulted in order for an address. |

Example against the NetBox service discovery plugin:

```yaml
riptide:
  discovery:
    url: https://netbox.example.com/api/plugins/prometheus-sd/devices/
    token: vault://secret/netbox#token
    interval: 60s
  inventory:
    file: /etc/riptide/inventory.yaml
```

The inventory file then carries agent ranges only:

```yaml
riptide:
  snmp:
    agents:
      "10.20.0.0/16":
        credentials: corp-v3
        polling: default
```

## How a target becomes an exporter

The exporter name comes from the `__meta_netbox_name` label when it is present, and otherwise from the target itself with any trailing port removed.

The address comes from the first label present in `address-labels`.
When none of them is present, the target itself is used as the address.

That second rule is what makes non-NetBox producers work.
Generic service discovery puts a real address in the target, where NetBox puts a device name.

Note that a NetBox device's target is its **name**, not an address, and that every IP address NetBox's service discovery plugin emits already has its CIDR mask stripped.
You do not need to strip one yourself.

## What gets refused

Riptide keeps the running inventory rather than publishing a doubtful one.

- A device with no usable address is skipped and counted, never guessed at. The count is on the `discovery.skipped` gauge.
- Two devices resolving to the same exporter name are refused, and every collision is named at once. NetBox enforces device-name uniqueness per site, not globally, so two sites each holding a `sw1` is ordinary.
- A response that parses but yields no entries is refused. A filter typo or a permission change must not be able to wipe every exporter name.
- An unreachable endpoint keeps the last good inventory serving and raises `inventory.reload.stale`.

A 404 is treated as absence: the last good inventory keeps serving and the log warns once.

## Startup

Riptide does not fail to start when the endpoint is unreachable.
It warns, serves the inventory file alone, and heals on the next poll.
A collector that refuses to start because NetBox is down is worse than one that starts without device names.

## Metrics

| Metric | Meaning |
|---|---|
| `discovery.targets` | Entries in the last rendered document. |
| `discovery.skipped` | Entries dropped for want of a usable address. |
| `inventory.reload.successes` | Shared with the inventory file watcher. |
| `inventory.reload.failures` | A refused or unreachable poll counts here. |
| `inventory.reload.stale` | The endpoint differs from what is serving. |

## Limits

Certificate authorities are not configurable.
An endpoint served by an internal CA needs the JVM trust store, via `-Djavax.net.ssl.trustStore`.

The NetBox service discovery plugin disables pagination and supports no conditional requests, so every poll transfers a full serialization of every visible device.
Bound it with NetBox filters, for example `?status=active&role=leaf&role=spine`.
```

- [ ] **Step 2: Add the sibling sentence to the enrichment page**

Near the top of `docs/docs/configuration/exporter-enrichment.md`, above the first `exporters` example, add:

```markdown
:::note
When [dynamic discovery](discovery.md) is enabled, discovery owns this tree and an `exporters` tree in the inventory file fails startup.
The inventory file then carries [agent ranges](agent-configuration.md) only.
:::
```

Check how other admonitions are written in this file first. The repo has a lint that fails when admonition markup renders as body copy, so match the surrounding syntax exactly.

- [ ] **Step 3: Add the sibling sentence to the agent page**

Near the top of `docs/docs/configuration/agent-configuration.md`, add:

```markdown
:::note
Agent ranges always come from the inventory file.
[Dynamic discovery](discovery.md) supplies exporter entries only, so enabling it does not affect anything on this page.
:::
```

- [ ] **Step 4: Build the docs and read the rendered output**

Run: `make docs`
Expected: `BUILD SUCCESS` and the admonition lint passing.

Then actually open `docs/build/docs/configuration/discovery/index.html` and confirm the tables and admonitions render as tables and admonitions rather than as literal text. Source that looks right is not evidence; a v2-syntax admonition rendering as literal `:::` text is a bug this repo has already shipped once (#721).

- [ ] **Step 5: Commit**

```bash
git add docs/docs/configuration/discovery.md \
        docs/docs/configuration/exporter-enrichment.md \
        docs/docs/configuration/agent-configuration.md
git commit -s -m "docs(discovery): document dynamic exporter discovery

Includes the either-or rule on both sibling pages, not just the new one.
A reader reaches the enrichment page first and would otherwise write an
exporters tree that fails startup without knowing why.

Assisted-by: ClaudeCode:claude-opus-5"
```

---

## Self-Review

**Spec coverage.** Every section of the spec maps to a task. The mapping rules and all four refusal cases are Task 4. The composition and the four file-read sites are Tasks 6 and 7. The six configuration keys are Task 2. The shared bounded read is Task 1. The two gauges are Task 7. The documentation siblings are Task 8. The `*Test` naming constraint is in Global Constraints.

**Two deviations from the spec, both deliberate.**

The spec named integration coverage as `*IT` classes. That was wrong for this repo and the spec has been corrected: `*IT` runs only under `mvn verify -Pe2e` with Docker, and these tests need a loopback HTTP server and no container. They are named `*Test` so they run in the default gate.

The spec did not say where the poll interval comes from. Task 7 reads `riptide.discovery.interval` rather than `riptide.config.reload-interval`, so enabling discovery does not require also enabling config hot-reload.

**One defect this review caught and fixed.**

The first draft of `ExporterRenderer.address()` fell back to the target host unconditionally, which meant a NetBox device with no primary IP produced an entry whose address was the device name. That contradicted `anEntryWithNoUsableAddressIsSkippedAndCounted`, which would have failed, and it would have pushed a hostname into the address field for the loader to refuse later with a message about the wrong problem. The fallback is now gated on the host parsing as an address, which is also what separates the two producer shapes: a generic target carries a real address, a NetBox target carries a name.

**Known gap a reviewer should press on.**

Task 7 Step 5 describes the `InventoryFileReloader` changes in prose rather than as a finished file, because the edit is interleaved with existing early-returns whose exact current text the implementer must read first. That is the least specified step in this plan and the most likely to need judgment. The `messages(...)` helper in particular takes a `Path` that can now be null, and the implementer has to decide what the missing-source sentence says when the source is an endpoint. Expect to iterate there, and do not let a green suite stand in for having read that method.
