#!/usr/bin/env python3
#
# Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
# SPDX-License-Identifier: GPL-3.0-or-later
#
"""Generates the .mmdb test fixtures in this directory.

Self-generated (not MaxMind's CC-licensed test files) so the repo carries no
third-party data. Requires: pip install mmdb-writer netaddr

    python3 generate_fixtures.py
"""
from mmdb_writer import MMDBWriter
from netaddr import IPSet


def write(name, database_type, entries):
    # int_type u32 matches real GeoLite2 databases (autonomous_system_number is uint32),
    # which the Java decoder maps to Long.
    w = MMDBWriter(ip_version=6, database_type=database_type, ipv4_compatible=True, int_type="u32")
    for cidr, record in entries:
        w.insert_network(IPSet([cidr]), record)
    w.to_db_file(name)
    print(f"wrote {name} ({database_type})")


# MaxMind GeoLite2-City layout: nested country/city structures.
write("geolite2-city-test.mmdb", "GeoLite2-City", [
    ("203.0.113.0/24", {"country": {"iso_code": "DE"}, "city": {"names": {"en": "Fulda"}}}),
    ("2001:db8::/32", {"country": {"iso_code": "NL"}, "city": {"names": {"en": "Amsterdam"}}}),
])

# A second geo database overlapping the first — for later-file-wins merge tests.
write("geolite2-city-test2.mmdb", "GeoLite2-City", [
    ("203.0.113.0/24", {"country": {"iso_code": "US"}, "city": {"names": {"en": "Ashburn"}}}),
])

# MaxMind GeoLite2-ASN layout: flat autonomous_system_* fields.
write("geolite2-asn-test.mmdb", "GeoLite2-ASN", [
    ("203.0.113.0/24", {"autonomous_system_number": 64500, "autonomous_system_organization": "Example Org"}),
    ("198.51.100.0/24", {"autonomous_system_number": 15169, "autonomous_system_organization": "Google LLC"}),
])

# IPinfo layout: flat strings, ASN as "AS<number>".
write("ipinfo-test.mmdb", "ipinfo standard_location.mmdb", [
    ("192.0.2.0/24", {"country": "US", "region": "Virginia", "city": "Ashburn",
                      "asn": "AS64501", "as_name": "IPinfo Test Net"}),
    ("198.51.100.0/24", {"country": "AU", "region": "New South Wales",
                         "asn": "AS64502", "as_name": "Aussie Test Net"}),
])
