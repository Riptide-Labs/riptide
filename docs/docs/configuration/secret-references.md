---
sidebar_position: 3
title: Secret references
---

# Secret references

SNMP credentials (`community`, `auth-passphrase`, `priv-passphrase`) are **references to
secrets, never the secrets themselves**. A reference is a URI resolved at poll time by a
pluggable resolver — so a single secure store (HashiCorp Vault, SOPS, files, or the
environment) backs all credentials, and plaintext never lands in configuration.

| Scheme | Example | Resolves |
|---|---|---|
| `env://` | `env://RIPTIDE_SNMP_COMMUNITY` | environment variable |
| `file://` | `file:///run/secrets/community` | file content, trimmed |
| `file://` + key | `file:///etc/riptide/sec.properties#snmp.community` | key in a properties file |
| `vault://` | `vault://secret/snmp/core-router#community` | HashiCorp Vault KV v2: `vault://<mount>/<path>#<key>` |
| `sops://` | `sops:///etc/riptide/secrets.yaml#snmp.community` | SOPS-encrypted YAML/JSON, dot-separated key |

A bare string (no scheme) is treated as a literal — intended for test fixtures and
migration only. Log output redacts literals as `plain://***`.

An **unresolvable reference degrades gracefully**: the flow is persisted without SNMP
enrichment and a warning is logged — a configuration mistake never drops flows.

## A key must be declared once

`file://` resolves `#key` by reading the file as properties, which collapses a repeated key to the
last one and says nothing. Riptide now refuses that instead:

```
Key 'community' is declared 2 times for secret ref file:///etc/riptide/secrets.yaml#community
— riptide will not guess which is meant. Keep one, or put this secret in its own file.
```

This matters most in a nested file, which properties reads by stripping the indentation, so two
secrets under two different parents both answer to the same bare key:

```yaml
snmp:
  core:
    community: core-secret     # <- #community resolved here...
  edge:
    community: edge-secret     # <- ...until this site was added
```

The reference is correct when written and stays correct for as long as the file declares the key
once. What breaks it is an unrelated later edit — and riptide resolves secrets per SNMP walk, so
the wrong value would go out on the next poll, with no restart in between to notice at.

Give each secret its own file (`file:///run/secrets/core-community`) if you want per-site
credentials, or use `sops://`, whose dot-separated keys address a nested document unambiguously.

## `file://` sandbox

Optionally restrict which paths the file resolver may read (symlink-safe):

```properties
riptide.secrets.allowed-paths=/run/secrets,/etc/riptide
```

## HashiCorp Vault

The `vault://` resolver activates when a Vault URI is configured. Secrets are read from
a KV v2 engine at poll time (not boot time). Authentication is token-based — with a
Vault Agent sidecar, point the token at the agent's sink:

```properties
riptide.secrets.vault.uri=https://vault.example.com:8200
riptide.secrets.vault.token=${VAULT_TOKEN}
```

Store the credentials as fields of one secret:

```bash
vault kv put secret/snmp/core-router \
  community=... authPassphrase=... privPassphrase=...
```

and reference them as `vault://secret/snmp/core-router#community` etc.

## SOPS

The `sops://` resolver decrypts files with the [sops](https://getsops.io) binary (age or
cloud KMS keys) and looks up dot-separated keys in the decrypted YAML/JSON document.
Decrypted content is cached in memory for the lifetime of the process.

```properties
riptide.secrets.sops.command=sops                       # default: sops on the PATH
riptide.secrets.sops.age-key-file=/etc/riptide/age.key  # sets SOPS_AGE_KEY_FILE
```

With a secrets file like:

```yaml
# secrets.yaml (encrypted with: sops -e -i secrets.yaml)
snmp:
  community: s3cret
  auth-passphrase: also-s3cret
```

reference `sops:///etc/riptide/secrets.yaml#snmp.community`. A `sops://` reference
without a `#key` yields the whole decrypted content — useful for binary-format SOPS
files.
