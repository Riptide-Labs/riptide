---
sidebar_position: 6
title: GeoIP
description: Settings for country, city and AS enrichment from MaxMind or IPinfo mmdb files, the override and precedence rules, refresh behaviour, and the sidecar recipes that keep the files current.
---

# GeoIP reference

Riptide fills `srcCountry`, `srcCity`, `dstCountry` and `dstCity` on every flow, and AS number and organisation where nothing above GeoIP on the [enrichment ladder](../enrichment.md#the-enrichment-ladder) supplied them.
Lookups run in-process against memory-mapped `.mmdb` files.
With no database and no override configured the enricher does nothing.

## Settings

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| **`riptide.enricher.geoip.enabled`** | bool | `true` | Set to `false` to switch the enricher off without removing the database list. |
| **`riptide.geoip.databases`** | ordered list of paths | empty | `.mmdb` files. Every file is consulted; where two resolve the same field, the later file wins. A file that does not exist yet is skipped with a warning and picked up at a later refresh. |
| **`riptide.geoip.refresh-interval`** | duration | `PT5M` | How often the files' modification times are checked. A changed file is re-opened; a replacement that cannot be opened keeps the previous data serving. |
| **`riptide.geoip.overrides.<prefix>.country`** | string | unset | Pins the country for the prefix. |
| **`riptide.geoip.overrides.<prefix>.city`** | string | unset | Pins the city. |
| **`riptide.geoip.overrides.<prefix>.asn`** | long | unset | Fills the AS number when the flow has none or `0`; see [precedence](#precedence). |
| **`riptide.geoip.overrides.<prefix>.org`** | string | unset | Fills the AS organisation under the same rule. |

```yaml
riptide:
  geoip:
    databases:
      - /usr/share/GeoIP/GeoLite2-ASN.mmdb
      - /usr/share/GeoIP/GeoLite2-City.mmdb
    refresh-interval: 5m
    overrides:
      "192.168.0.0/16": { country: "DE", city: "Homelab" }
      "203.0.113.0/24": { city: "Fulda" }
      "10.20.0.0/16": { asn: 64500, org: "Lab Fabric" }
```

Expected output at startup while the files are not there yet:

```text
org.riptide.geoip.GeoIpSnapshot          : GeoIP database /usr/share/GeoIP/GeoLite2-ASN.mmdb does not exist (yet) — skipping, a later refresh picks it up
org.riptide.geoip.GeoIpSnapshot          : GeoIP database /usr/share/GeoIP/GeoLite2-City.mmdb does not exist (yet) — skipping, a later refresh picks it up
```

With readable files nothing is logged at startup.

### Providers

The provider of each file is read from its metadata `database_type`.

| Provider | Detected when | Contributes |
| --- | --- | --- |
| MaxMind GeoLite2 and GeoIP2 | anything not detected as IPinfo | Country, City and ASN databases; the usual setup lists ASN and City as two entries |
| IPinfo | `database_type` starts with `ipinfo` | geo and ASN fields from one entry, so a combined file such as `country_asn.mmdb` is one list entry |

### Manual overrides

An override pins the fields it sets and leaves the others to the databases.
The longest matching prefix wins.
Keys are canonicalised to their prefix block, so `10.0.0.5/24` means `10.0.0.0/24`, and two keys resolving to the same block fail startup, the same contract as [`riptide.routing.prefixes`](routing.md).

## Precedence

| Field | Resolved as |
| --- | --- |
| Country, city | override, then databases in list order with the later file winning. Nothing else supplies them. |
| AS number, per side | exporter-provided non-zero value, then `riptide.routing.prefixes`, then override, then databases. GeoIP writes an AS number only where the flow has none or `0`. |
| AS organisation | routing's AS-name table names any non-zero AS number first, exporter-provided ones included. A GeoIP organisation is applied only where none is set and the AS number on the flow equals the one GeoIP resolved. |

## Refresh

| Event | Effect | Logged |
| --- | --- | --- |
| A configured file's modification time changes, or the file appears or disappears | All files re-opened and swapped in; the old readers close after a grace period. A file that disappeared is simply absent from the new set, so its fields go blank | `GeoIP databases changed on disk — reloading` |
| A replacement file cannot be opened | Previous databases keep serving. The failed state is remembered, so the reload is not retried until a file changes again; a `chmod` alone does not change the modification time, so `touch` the file to force a retry | `GeoIP database /path could not be opened — skipping: ...` then `GeoIP reload found unreadable database(s) — keeping the previous databases` |
| A file is unreadable at startup | Skipped, the others serve; retried only when the file changes | `GeoIP database /path could not be opened — skipping: ...` |
| The refresh itself throws | Previous databases keep serving | `GeoIP refresh failed — keeping the previous databases: ...` |

Replace a file by atomic rename, as both recipes below do, and the refresh never sees a half-written file.
No restart is needed, and the packaged systemd unit needs no change to read the files.

## Keep the files current

Riptide does not download databases.

### MaxMind GeoLite2 with a systemd timer

Prerequisites: a MaxMind account, and [`geoipupdate`](https://github.com/maxmind/geoipupdate) installed from your distribution.

1. Put the credentials in `/etc/GeoIP.conf`:

   ```ini
   AccountID 123456
   LicenseKey your-license-key
   EditionIDs GeoLite2-ASN GeoLite2-City
   ```

2. Create `/etc/systemd/system/geoipupdate.service`:

   ```ini
   [Unit]
   Description=Refresh MaxMind GeoIP databases
   Wants=network-online.target
   After=network-online.target

   [Service]
   Type=oneshot
   ExecStart=/usr/bin/geoipupdate -f /etc/GeoIP.conf -d /usr/share/GeoIP
   ```

3. Create `/etc/systemd/system/geoipupdate.timer`. GeoLite2 publishes on Tuesdays and Fridays `(unverified)`, so twice a week with jitter is the polite cadence:

   ```ini
   [Unit]
   Description=Refresh MaxMind GeoIP databases twice a week

   [Timer]
   OnCalendar=Tue,Fri 06:00
   RandomizedDelaySec=4h
   Persistent=true

   [Install]
   WantedBy=timers.target
   ```

4. Enable the timer and run the first download now:

   ```bash
   sudo systemctl enable --now geoipupdate.timer
   sudo systemctl start geoipupdate.service
   ls -l /usr/share/GeoIP/
   ```

   Expected output:

   ```text
   -rw-r--r-- 1 root root  ... GeoLite2-ASN.mmdb
   -rw-r--r-- 1 root root  ... GeoLite2-City.mmdb
   ```

### IPinfo with a systemd timer

Prerequisites: an IPinfo token in `/etc/ipinfo.env` as `IPINFO_TOKEN=...`, mode 0600.

1. Create `/etc/systemd/system/ipinfo-update.service`. The download goes to a temporary name and is renamed into place:

   ```ini
   [Unit]
   Description=Refresh IPinfo GeoIP database
   Wants=network-online.target
   After=network-online.target

   [Service]
   Type=oneshot
   EnvironmentFile=/etc/ipinfo.env
   ExecStart=/bin/sh -c 'curl -fsSL "https://ipinfo.io/data/free/country_asn.mmdb?token=${IPINFO_TOKEN}" \
       -o /usr/share/GeoIP/.country_asn.mmdb.tmp \
       && mv /usr/share/GeoIP/.country_asn.mmdb.tmp /usr/share/GeoIP/country_asn.mmdb'
   ```

2. Create `/etc/systemd/system/ipinfo-update.timer`:

   ```ini
   [Unit]
   Description=Refresh IPinfo GeoIP database daily

   [Timer]
   OnCalendar=daily
   RandomizedDelaySec=1h
   Persistent=true

   [Install]
   WantedBy=timers.target
   ```

3. Enable the timer and run the first download now:

   ```bash
   sudo systemctl enable --now ipinfo-update.timer
   sudo systemctl start ipinfo-update.service
   ```

With either recipe riptide picks up a refreshed file within `refresh-interval` and logs `GeoIP databases changed on disk — reloading`.

## Schema

The four geo columns are additive.
Manage-mode deployments gain them at startup through `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`; provisioned deployments re-run `riptide onboard`, which emits the same idempotent statements.
Existing rows read as empty strings.

## Error catalog

Both fail startup.

| Message | Probable cause | Recovery |
| --- | --- | --- |
| `riptide.geoip.overrides: '10.0.0.300/24' is not a valid prefix` | Malformed key | Write a host address or CIDR prefix |
| `riptide.geoip.overrides: '10.0.0.5/24' and '10.0.0.0/24' are the same prefix block (10.0.0.0/24) — matching between them would be arbitrary. Keep one.` | Two keys canonicalise to one block | Keep one |

## Open questions

- The MaxMind publishing days and the `geoipupdate` and IPinfo download recipes are vendor behaviour, not riptide's; neither was run for this page. The `ls` output after the first download is illustrative.
