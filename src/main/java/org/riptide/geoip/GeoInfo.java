/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.geoip;

/**
 * The geo data a lookup resolves for one address, provider-neutral. Fields are null when
 * unresolved; {@link #EMPTY} is the all-null result.
 */
public record GeoInfo(String country, String city, Long asn, String asOrg) {

    public static final GeoInfo EMPTY = new GeoInfo(null, null, null, null);

    /** Per-field overlay: {@code other}'s non-null fields win over this one's. */
    public GeoInfo overlay(final GeoInfo other) {
        if (other == null) {
            return this;
        }
        return new GeoInfo(
                other.country != null ? other.country : this.country,
                other.city != null ? other.city : this.city,
                other.asn != null ? other.asn : this.asn,
                other.asOrg != null ? other.asOrg : this.asOrg);
    }

    public boolean isEmpty() {
        return this.country == null && this.city == null && this.asn == null && this.asOrg == null;
    }
}
