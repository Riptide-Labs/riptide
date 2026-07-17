/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.pipeline;

/**
 * Explicit {@code @Order} values for the enricher chain. The chain runs sequentially in this
 * order (see {@link Pipeline}); before these constants the order was implicit bean order. The
 * only hard dependency is GEOIP after ROUTING: GeoIP AS data is the ladder's lowest rung and
 * must see exporter- and routing-provided values first. Gaps leave room to slot new enrichers
 * without renumbering.
 */
public final class EnricherOrder {

    public static final int EXPORTER_NAME = 50;
    public static final int CLASSIFICATION = 100;
    public static final int CLOCK_CORRECTION = 200;
    public static final int HOSTNAMES = 300;
    public static final int LOCALITY = 400;
    public static final int ROUTING = 500;
    public static final int GEOIP = 600;
    public static final int SNMP = 700;

    private EnricherOrder() {
    }
}
