---
sidebar_position: 2
title: SNMP agents
---

# SNMP agent configuration

Which devices riptide polls, and how it talks to them.
Two trees carry it, in two files:

- **Named credential sets and polling profiles** live in the main configuration (`riptide.snmp.credentials.<name>`, `riptide.snmp.polling.<name>`).
- **Agent ranges** live in the inventory file named by `riptide.inventory.file`, and reference the sets and profiles by name.

The split is deliberate.
Credentials are few, sensitive, and change rarely; ranges are many and change often.
The inventory file is direct-parsed, so it scales to tens of thousands of entries without binding cost, and it hot-reloads on content change.

:::warning[The inventory file is a file, full stop]
Agent ranges and [enrichment entries](exporter-enrichment.md) cannot be supplied through environment variables or `spring.config.import`.
The file named by `riptide.inventory.file` is read directly, never property-bound.
A set-but-missing file fails startup; an unset `riptide.inventory.file` means an empty inventory, which is valid and enriches nothing.
:::

## Credential sets

```properties
riptide.snmp.credentials.corp-v3.version=v3
riptide.snmp.credentials.corp-v3.security-name=monitoring
riptide.snmp.credentials.corp-v3.auth-protocol=hmac192sha256
riptide.snmp.credentials.corp-v3.auth-passphrase=vault://secret/snmp/corp#authPassphrase
riptide.snmp.credentials.corp-v3.priv-protocol=aes256
riptide.snmp.credentials.corp-v3.priv-passphrase=vault://secret/snmp/corp#privPassphrase

riptide.snmp.credentials.legacy-v2c.version=v2c
riptide.snmp.credentials.legacy-v2c.community=env://RIPTIDE_SNMP_COMMUNITY
```

Shapes are validated at startup, per set: no version, v1/v2c without a community, v3 without a security name, a set carrying the other version's fields, or an incomplete auth/priv pair each fail loudly naming the set.
The v3 security level is implicit in which fields you configure (noAuthNoPriv, authNoPriv, authPriv), and the engine ID is never configured; it is discovered at runtime (RFC 3414).

Credential values should be [secret references](secret-references.md); a scheme-less value binds as a plain literal (kept for migration and tests), so nothing stops plaintext, but nothing excuses it either.
Rotation differs by scheme.
`file://` is re-read on every poll, so rotating the file content reaches a polled agent with **no configuration change and no reload**.
`env://` is also re-read, but a process environment is immutable, so rotating it means a restart.
`sops://` decrypted content is cached for the process lifetime and refreshed only by a main-config reload.

### Protocol values

| Setting | Values |
|---|---|
| `auth-protocol` | `md5` · `sha1` · `hmac128sha224` · `hmac192sha256` · `hmac256sha384` · `hmac384sha512` |
| `priv-protocol` | `des` · `_3des` · `aes128` · `aes192` · `aes256` · `aes192with3DESKeyExtension` · `aes256with3DESKeyExtension` |

Recommended pairing: `hmac192sha256` (or stronger) with `aes256`.

## Polling profiles

```properties
riptide.snmp.polling.brisk.refresh-interval=PT1M
riptide.snmp.polling.brisk.snapshot-expiry=PT30M
riptide.snmp.polling.slow.refresh-interval=PT30M
riptide.snmp.polling.slow.snapshot-expiry=PT90M
riptide.snmp.polling.slow.timeout=3000
riptide.snmp.polling.slow.retries=2
```

Each profile carries the walk cadence (`refresh-interval`, at most one day), how long a walked snapshot keeps serving (`snapshot-expiry`), and the per-request `timeout` (ms) and `retries`.
A range that names no profile uses `default`: yours if you define one, otherwise the built-in (10 min refresh, 30 min expiry, 500 ms, 1 retry).
Serving continues from the last snapshot while it is stale-but-unexpired, which is why refresh and expiry are separate settings.

## Agent ranges (inventory file)

```yaml
riptide:
  snmp:
    agents:
      "10.20.30.7":              # single host
        credentials: corp-v3
      "10.20.40.0/24":           # whole segment: zero-touch onboarding
        credentials: corp-v3
        polling: brisk
      "10.20.30.8":
        credentials: corp-v3
        port: 1161               # default 161
      "10.99.0.0/24":
        enabled: false           # carve-out: matched, deliberately not polled
```

A device inside a credentialed range is polled from its first flow, without being named anywhere.
Longest prefix wins, so a host entry overrides the segment it sits in, and `enabled: false` carves an address or a sub-range out of a wider range.
Keys are strict: unknown keys, malformed addresses (`10.0.1.5/24` with host bits, leading zeros, wildcards, `inet_aton` shorthand, netmask spellings) and unresolvable credential or profile references all fail the load naming the entry.
A failed load reports every bad entry it reached, not just the first, so a file with six mistakes fails once instead of six times.
Each entry contributes its first problem; an exporter's interface pins count one problem each, capped at five per entry.
Past twenty bad entries the rest are counted rather than named.
Two entries that collide on the same coverage are reported once the other problems are fixed, because that check runs only on a file that is otherwise clean.
IPv6 zone ids (`fe80::1%eth0`) are rejected too: matching ignores zones, so a zoned entry would silently match flows from any interface, and the error names the zone-free form to write instead.

:::danger[Cleartext communities do not travel on wide ranges]
A v1/v2c credential set on a range wider than a single address **fails startup**.
The community would otherwise be sent to any in-range address that emits a flow, including addresses nobody enumerated.
Either list the devices as single addresses, or move the segment to v3.
The rule fires regardless of `enabled`, so a disabled wide v2c range is also rejected; leave such a range credential-less until it is enumerated.
:::

### Sizing the walker pool for sparse ranges

Registration follows flows: declaring `10.0.0.0/16` does not pre-walk 65k addresses, it walks the devices that actually send flows, when they first do.
Fleet-wide concurrency is bounded by `riptide.snmp.poll.pool-width` regardless of how many devices register, and `riptide.snmp.poll.max-exporters` caps the registration count outright (at the cap, new exporters are rejected, never evicted).
Size `pool-width` for the number of *active* devices you expect and their profile cadences, not for the address space you declared; the `snmp.poller.exporters` gauge and `rejectedLookups` meter tell you where you actually are.

## Hot reload

With [config hot-reload](../deploy/operations.md#config-hot-reload) enabled, the inventory file reloads on content change: new ranges take effect, a carve-out stops an already-polled agent without a restart, and a rejected file (parse error, unknown key, dangling reference) keeps the last good inventory serving while `inventory.reload.stale` goes to 1.
Write the file atomically (write a temp file, then `mv`): the reloader also refuses a file in which a previously populated `agents` or `exporters` tree is simply absent, because that is what a partially written file looks like.
To deliberately empty a tree, write it as an explicit empty mapping (`agents: {}`, `exporters: {}`, or `riptide: {}` for a whole empty inventory) — truncation can never produce that spelling, so it is read as authored.
The protection is against total loss of a tree; a tree that shrinks without vanishing publishes normally.
Editing **credential sets or polling profiles** in the main config also propagates on reload: the inventory is rebuilt against the new profiles and already-registered agents re-resolve, so a rotated community reaches running walks without a restart.
