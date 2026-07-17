---
sidebar_position: 6
title: GeoIP
---

# GeoIP enrichment

Riptide can enrich every flow with country and city for the source and destination
address (`srcCountry`/`srcCity`/`dstCountry`/`dstCity` columns, empty string = unknown),
and fill AS number/organisation as the [enrichment ladder's](../enrichment.md) lowest
rung. Lookups run in-process against memory-mapped `.mmdb` files — no I/O on the hot
path. Without any configured database or override the feature is inert.

```yaml
riptide:
  geoip:
    databases:
      - /usr/share/GeoIP/GeoLite2-ASN.mmdb
      - /usr/share/GeoIP/GeoLite2-City.mmdb
    refresh-interval: 5m
    overrides:
      "192.168.0.0/16": { country: "DE", city: "Homelab" }
```

## Databases and providers

`databases` is an **ordered list** of `.mmdb` paths. Each file's provider is
auto-detected from its metadata `database_type`:

- **MaxMind** (GeoLite2/GeoIP2) — Country, City, and ASN databases; anything not
  detected as IPinfo.
- **IPinfo** — `database_type` starting with `ipinfo`; combined files (e.g.
  `country_asn.mmdb`) contribute geo and ASN data from one entry.

A lookup consults every file: each contributes the fields it resolves, and when two
files resolve the same field the **later file in the list wins**. The usual MaxMind
setup lists the ASN and City databases as two entries.

A configured file that does not exist yet (the update sidecar has not downloaded it) is
skipped with a warning and picked up automatically once it appears. Files are re-opened
when they change on disk (checked every `refresh-interval`, default 5m); a replacement
that cannot be opened keeps the previous data serving.

## Manual overrides

`overrides` maps CIDR prefixes to partial geo data that **pins its set fields over the
databases** — the operator's correction for wrong or missing provider data, and the
answer for RFC1918/CGNAT space where providers return nothing:

```yaml
riptide:
  geoip:
    overrides:
      "192.168.0.0/16": { country: "DE", city: "Homelab" }   # city AND country pinned
      "203.0.113.0/24": { city: "Fulda" }                    # country still from the DBs
      "10.20.0.0/16":   { asn: 64500, org: "Lab Fabric" }
```

Only the set fields pin; unset fields fall through to the databases. The longest
matching prefix wins. Keys are canonicalized to their prefix block (the host form
`10.0.0.5/24` means `10.0.0.0/24`), and two keys resolving to the same block fail
startup — the same contract as [`riptide.routing.prefixes`](routing.md).

## AS precedence

GeoIP AS data never beats operator or network intent. Per side, the AS number resolves
as: **exporter-provided nonzero → `riptide.routing.prefixes` → geoip override → geoip
databases**. The AS organisation follows the same ladder, and a GeoIP organisation is
only applied to an AS number that GeoIP itself resolved.

## Getting the database files

Riptide does not download databases — point it at files a sidecar keeps fresh:

- **MaxMind GeoLite2** (free, account required): run
  [`geoipupdate`](https://github.com/maxmind/geoipupdate) via container or systemd
  timer writing to `/usr/share/GeoIP`. A working bare-metal timer is two units:
  `geoipupdate.timer` (e.g. `OnCalendar=daily`) invoking
  `/usr/bin/geoipupdate -f /etc/GeoIP.conf -d /usr/share/GeoIP`.
- **IPinfo** (free tier available): fetch
  `https://ipinfo.io/data/free/country_asn.mmdb?token=<token>` on a timer, download to
  a temp file and `mv` it into place — the atomic rename is what makes riptide's
  hot-reload race-free.

Both update mechanisms replace the file by rename, which riptide's mtime-based refresh
detects; a collector restart is never needed.

:::note Schema

The four geo columns are additive. Manage-mode deployments gain them automatically at
startup (`ALTER TABLE … ADD COLUMN IF NOT EXISTS`); provisioned deployments re-run
`riptide onboard`, which emits the same idempotent statements on every run. Existing
rows read as empty strings.

:::
