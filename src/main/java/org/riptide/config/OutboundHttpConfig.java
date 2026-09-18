/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * What the collector's own outbound HTTP reads trust.
 *
 * <p>Named for the collector rather than for either consumer. Two features read over HTTP through
 * {@link BoundedHttpRead} — discovery and the classification ruleset — and an operator with an
 * internal certificate authority has one authority, not one per feature. Calling this key
 * {@code riptide.discovery.ca-bundle} would also send an operator whose ruleset fetch fails looking
 * in the discovery documentation.</p>
 *
 * <p><b>What it does not reach.</b> Two outbound clients do not go through {@link BoundedHttpRead}:
 * the ClickHouse client and Spring Vault's. Both carry their own transports and their own trust
 * configuration, and neither would notice what is set here. The documentation says so where this
 * key is documented, because a setting whose boundary is implied is one an operator discovers by
 * being wrong about it.</p>
 */
@ConfigurationProperties(prefix = "riptide.http")
@Data
public class OutboundHttpConfig {

    /**
     * A PEM file holding one or more certificate authorities to trust in addition to the platform's
     * own, or unset to trust exactly what the platform does.
     *
     * <p>In addition, never instead: replacing the platform's authorities would mean that adding an
     * internal one for NetBox silently stops a publicly served endpoint from being readable, with a
     * certificate error naming neither the endpoint nor this key.</p>
     *
     * <p>Read once, at startup. A rotated bundle therefore needs a restart, unlike a rotated
     * credential, which is re-read on every poll. The asymmetry is deliberate: a certificate
     * authority rotates on a certificate's lifetime while a token rotates on an operational one,
     * and rebuilding the trust material per poll would spend real work on a file that almost never
     * changes.</p>
     */
    private Path caBundle;
}
