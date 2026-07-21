/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.riptide.geoip;

import com.maxmind.db.CHMCache;
import com.maxmind.db.MaxMindDbConstructor;
import com.maxmind.db.MaxMindDbParameter;
import com.maxmind.db.Reader;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Locale;
import java.util.Map;

/**
 * One open {@code .mmdb} file with its provider decoder. The provider is detected from the
 * mmdb metadata {@code database_type}: values starting with {@code ipinfo} (case-insensitive)
 * use the IPinfo field layout, everything else the MaxMind (GeoLite2/GeoIP2) layout. Both
 * decode into a provider-neutral {@link GeoInfo}, reading only the fields riptide persists.
 */
public final class GeoIpDatabase implements Closeable {

    private final Reader reader;
    private final boolean ipinfo;

    private GeoIpDatabase(final Reader reader, final boolean ipinfo) {
        this.reader = reader;
        this.ipinfo = ipinfo;
    }

    static GeoIpDatabase open(final File file) throws IOException {
        final Reader reader = new Reader(file, Reader.FileMode.MEMORY_MAPPED, new CHMCache());
        // maxmind-db 4.x: Metadata is a record — accessor is databaseType(), not getDatabaseType().
        final String type = reader.getMetadata().databaseType();
        final boolean ipinfo = type != null && type.toLowerCase(Locale.ROOT).startsWith("ipinfo");
        return new GeoIpDatabase(reader, ipinfo);
    }

    /** Resolve one address; null fields for anything the file does not cover. */
    GeoInfo lookup(final InetAddress address) throws IOException {
        if (this.ipinfo) {
            final IpinfoRecord record = this.reader.get(address, IpinfoRecord.class);
            return record != null ? record.toGeoInfo() : GeoInfo.EMPTY;
        }
        final MaxMindRecord record = this.reader.get(address, MaxMindRecord.class);
        return record != null ? record.toGeoInfo() : GeoInfo.EMPTY;
    }

    @Override
    public void close() throws IOException {
        this.reader.close();
    }

    /** MaxMind layout: nested country/city plus the flat ASN-database fields. */
    public static final class MaxMindRecord {
        private final Country country;
        private final City city;
        private final Long asn;
        private final String asOrg;

        @MaxMindDbConstructor
        public MaxMindRecord(@MaxMindDbParameter(name = "country") final Country country,
                      @MaxMindDbParameter(name = "city") final City city,
                      @MaxMindDbParameter(name = "autonomous_system_number") final Long asn,
                      @MaxMindDbParameter(name = "autonomous_system_organization") final String asOrg) {
            this.country = country;
            this.city = city;
            this.asn = asn;
            this.asOrg = asOrg;
        }

        GeoInfo toGeoInfo() {
            return new GeoInfo(
                    this.country != null ? this.country.isoCode : null,
                    this.city != null && this.city.names != null ? this.city.names.get("en") : null,
                    this.asn,
                    this.asOrg);
        }

        public static final class Country {
            private final String isoCode;

            @MaxMindDbConstructor
            public Country(@MaxMindDbParameter(name = "iso_code") final String isoCode) {
                this.isoCode = isoCode;
            }
        }

        public static final class City {
            private final Map<String, String> names;

            @MaxMindDbConstructor
            public City(@MaxMindDbParameter(name = "names") final Map<String, String> names) {
                this.names = names;
            }
        }
    }

    /** IPinfo layout: flat strings, ASN in the {@code AS<number>} form. */
    public static final class IpinfoRecord {
        private final String country;
        private final String region;
        private final String city;
        private final String asn;
        private final String asName;

        @MaxMindDbConstructor
        public IpinfoRecord(@MaxMindDbParameter(name = "country") final String country,
                     @MaxMindDbParameter(name = "region") final String region,
                     @MaxMindDbParameter(name = "city") final String city,
                     @MaxMindDbParameter(name = "asn") final String asn,
                     @MaxMindDbParameter(name = "as_name") final String asName) {
            this.country = country;
            this.region = region;
            this.city = city;
            this.asn = asn;
            this.asName = asName;
        }

        GeoInfo toGeoInfo() {
            return new GeoInfo(this.country, this.city != null ? this.city : this.region, parseAsn(this.asn), this.asName);
        }

        private static Long parseAsn(final String value) {
            if (value == null || !value.regionMatches(true, 0, "AS", 0, 2)) {
                return null;
            }
            try {
                return Long.parseLong(value.substring(2));
            } catch (final NumberFormatException e) {
                return null;
            }
        }
    }
}
