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
