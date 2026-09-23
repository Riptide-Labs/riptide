---
sidebar_position: 6
title: Routing and AS mapping
description: Settings for the static prefix-to-AS table and the AS-name table, how they combine with exporter-provided and GeoIP AS data, the key escaping the prefixes need, and every error the tables can raise.
---

# Routing and AS mapping reference

Riptide fills `srcAs`, `dstAs`, `srcAsOrg` and `dstAsOrg` from two static tables: prefixes to AS numbers, and AS numbers to names.
It is the [enrichment ladder's](../architecture/enrichment.md#the-enrichment-ladder) rung for AS data between the exporter's own BGP view and [GeoIP](geoip.md).
With neither table configured the enricher does nothing.

## Settings

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| **`riptide.routing.prefixes.<prefix>.asn`** | long | unset | AS number written to `srcAs` or `dstAs` when the address is inside `<prefix>` and the exporter sent `0` or nothing. |
| **`riptide.routing.prefixes.<prefix>.org`** | string | unset | Organisation written to `srcAsOrg` or `dstAsOrg` under the same rule. An entry may set `org` without `asn`. |
| **`riptide.routing.as-names.<asn>`** | string | unset | Name written to `srcAsOrg` or `dstAsOrg` when the flow ends up with AS number `<asn>` and no organisation was set, whether the number came from the exporter or from `prefixes`. |

```yaml
riptide:
  routing:
    prefixes:
      "[203.0.113.0/24]": { asn: 64500, org: "Example Carrier" }
      "[2001:db8::/32]":  { asn: 64501 }
    as-names:
      64501: "Example IX"
      65001: "Peering Partner"
```

The first prefix and the first AS name in `.properties` form:

```properties
riptide.routing.prefixes.[203.0.113.0/24].asn=64500
riptide.routing.prefixes.[203.0.113.0/24].org=Example Carrier
riptide.routing.as-names.64501=Example IX
```

### Prefix keys

Write the key inside square brackets, quoted in YAML.
A prefix contains dots and a slash, and Spring reads a dot in a key as nesting.
An unquoted key such as `203.0.113.0/24:` is not rejected: the application starts, the entry binds to nothing, and it never matches a flow.

| Form | Written as |
| --- | --- |
| YAML | `"[203.0.113.0/24]":` |
| `.properties` and command line | `riptide.routing.prefixes.[203.0.113.0/24].asn=64500` |

Keys are canonicalised to their prefix block at startup, so `10.0.0.5/24` means `10.0.0.0/24`.
Two keys that resolve to the same block fail startup.
The message is in the [error catalog](#error-catalog).

## Precedence

Each side of a flow is resolved on its own, from the address on that side.

| Field | Resolved as |
| --- | --- |
| `srcAs`, `dstAs` | The exporter's non-zero value. Otherwise the `asn` of the longest matching prefix. Otherwise left for GeoIP. |
| `srcAsOrg`, `dstAsOrg` | The `org` of the longest matching prefix, consulted only when the exporter sent `0` or nothing. Otherwise the `as-names` entry for the number the flow ends up with. Otherwise left for GeoIP. |

The prefix table never overrides a non-zero AS number from the exporter, and `as-names` never overwrites an organisation the prefix table set.
Longest prefix wins, IPv4 and IPv6 alike.
Where GeoIP fits below this rung is on the [GeoIP page](geoip.md#precedence).

## Apply a change without a restart

With **`riptide.config.reload-interval`** set, the config file is hashed on every poll and both tables are re-bound whenever its content changes; see [config hot-reload](../guides/hot-reload.md).
The new tables are validated with the startup rules first, then swapped in as one unit.
Each lookup reads one snapshot, old or new.
A flow enriched during the swap can still take its prefix from the old table and its AS name from the new one.

Expected log line after an edit that validates:

```text
org.riptide.config.ConfigFileReloader    : Config reloaded from /etc/riptide/config.yaml: 0 credential set(s), 0 polling profile(s) serving
```

A rejected edit keeps the running tables:

```text
org.riptide.config.ConfigFileReloader    : Config reload failed — keeping the running configuration: riptide.routing.prefixes: '10.10.2.0/24' and '10.10.2.7/24' are the same prefix block (10.10.2.0/24) — matching between them would be arbitrary. Keep one.
```

Check the reload counters:

```bash
curl -s http://localhost:8080/metrics | grep '^config_reload'
```

Expected output while a rejected edit is still on disk:

```text
config_reload_dead 0.0
config_reload_stale 1.0
config_reload_failures 1.0
config_reload_partial 0.0
config_reload_successes 2.0
```

`config_reload_stale` returns to `0.0` once a later edit commits and its inventory rebuild publishes.
A commit whose inventory rebuild is still pending counts on `config_reload_partial` and keeps the gauge at `1.0`; the [reload metrics](metrics.md#configuration-and-inventory-reload) table has the full contract.

## Verify the mapping

Send flows whose addresses fall inside a configured prefix and read the AS columns back:

```sql
SELECT IPv6NumToString(srcAddr) AS src, srcAs, srcAsOrg,
       IPv6NumToString(dstAddr) AS dst, dstAs, dstAsOrg,
       count() AS flows
FROM riptide.flows
WHERE receivedAt >= now() - INTERVAL 5 MINUTE
GROUP BY ALL
ORDER BY flows DESC
```

Expected output, from a [replayed capture](../develop/run-and-debug.md) whose exporter sent `0` for every AS field, with `10.10.1.0/24` mapped to `{ asn: 64500, org: "Example Carrier" }`, `10.10.2.0/24` to `{ asn: 64501 }` and `64501` named `Example IX`:

```text
   ┌─src───────────────┬─srcAs─┬─srcAsOrg────────┬─dst────────────────────┬─dstAs─┬─dstAsOrg────────┬─flows─┐
1. │ ::ffff:10.10.1.10 │ 64500 │ Example Carrier │ ::ffff:10.10.2.10      │ 64501 │ Example IX      │   652 │
2. │ ::ffff:10.10.2.10 │ 64501 │ Example IX      │ ::ffff:10.10.1.10      │ 64500 │ Example Carrier │   640 │
3. │ ::ffff:10.10.1.1  │ 64500 │ Example Carrier │ ::ffff:255.255.255.255 │     0 │ ᴺᵁᴸᴸ            │    10 │
   └───────────────────┴───────┴─────────────────┴────────────────────────┴───────┴─────────────────┴───────┘
```

The broadcast destination matches no prefix, so its side stays `0` and `NULL`.

## Error catalog

Both errors fail startup, and reject the edit under hot reload.

| Message | Probable cause | Recovery |
| --- | --- | --- |
| `riptide.routing.prefixes: '<key>' is not a valid prefix` | The key is not an IPv4 or IPv6 address or prefix. | Fix the key. A key without a prefix length is a single address. |
| `riptide.routing.prefixes: '<key>' and '<key>' are the same prefix block (<block>) — matching between them would be arbitrary. Keep one.` | Two keys canonicalise to the same block, such as `10.0.0.0/24` and `10.0.0.5/24`. | Delete one of the two entries. |

Expected output at startup, without the stack trace:

```text
o.s.boot.SpringApplication               : Application run failed

org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'configFileReloader' defined in URL [...]: Unsatisfied dependency expressed through constructor parameter 2: Error creating bean with name 'riptide.routing-org.riptide.routing.RoutingConfig': Invocation of init method failed
Caused by: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'riptide.routing-org.riptide.routing.RoutingConfig': Invocation of init method failed
Caused by: java.lang.IllegalStateException: riptide.routing.prefixes: 'not-a-prefix!' is not a valid prefix
```
