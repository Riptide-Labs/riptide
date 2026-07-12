---
sidebar_position: 5
title: Routing & AS mapping
---

# Routing & AS mapping

A static BGP/routing mapping — the [enrichment ladder's](../enrichment.md#the-enrichment-ladder)
middle rung for AS data. Two composable shapes:

```yaml
riptide:
  routing:
    prefixes:                    # longest prefix wins, over src AND dst addresses
      "203.0.113.0/24": { asn: 64500, org: "Example Carrier" }
      "2001:db8::/32":  { asn: 64501, org: "Example IX" }
    as-names:                    # names for AS numbers, wherever they came from
      64500: "Example Carrier"
      65001: "Peering Partner"
```

## Semantics

- **`prefixes`** fills `srcAs`/`dstAs` **only when the exporter sent `0` or nothing** —
  a nonzero exporter-provided AS number always wins (the router's own BGP view is
  authoritative when it speaks). The matched entry's `org` is persisted as
  `srcAsOrg`/`dstAsOrg`.
- **`as-names`** names whatever AS number the flow ends up with — exporter-provided or
  prefix-filled. It never overwrites an org the prefix table already set.
- Longest prefix wins, IPv4 and IPv6 alike. Keys are canonicalized to their prefix
  block at startup (`10.0.0.5/24` means `10.0.0.0/24`); two keys resolving to the same
  block fail startup with both keys named.
- An invalid prefix fails startup with the offending key named.
- With neither map configured the enricher is a no-op.
- In `.properties` form, prefix keys contain dots and need bracket escaping:
  `riptide.routing.prefixes.[203.0.113.0/24].asn=64500` — prefer YAML for this section.

Use `prefixes` when your exporters don't fill AS fields; use `as-names` when they do and
you just want readable names in dashboards.
