---
sidebar_position: 8
title: Secret references
description: The URI schemes a credential setting accepts, where each is resolved, the resolver settings, and every error a reference can raise.
---

# Secret references

Every credential setting takes a URI that names a secret, not the secret itself.
Riptide resolves it through the scheme's resolver at the moment the consumer needs the value.

## Schemes

| Scheme | Form | Resolves to | Needs |
| --- | --- | --- | --- |
| **`env://`** | `env://RIPTIDE_SNMP_COMMUNITY` | the environment variable | nothing |
| **`file://`** | `file:///run/secrets/community` | the file content, trimmed | the file readable by the riptide user |
| **`file://` with key** | `file:///etc/riptide/sec.properties#snmp.community` | one key of a Java properties file, declared exactly once | same |
| **`vault://`** | `vault://secret/snmp/core-router#community` | field `community` of the KV v2 secret `snmp/core-router` on mount `secret` | `riptide.secrets.vault.uri` and `token`; the `#key` is mandatory |
| **`sops://`** | `sops:///etc/riptide/secrets.yaml#snmp.community` | the dot-separated key of the decrypted YAML or JSON document; without `#key`, the whole decrypted content | the `sops` binary and its decryption key |
| none | `public` | the literal string | nothing; meant for tests and migration only |

A literal is logged as `plain://***`. Every other reference is logged as written.

## Settings that take a reference

| Setting | Resolved | On failure |
| --- | --- | --- |
| `riptide.snmp.credentials.<set>.community`, `auth-passphrase`, `priv-passphrase` | at every SNMP poll | the walk fails and the agent is backed off; a warning names the agent once, on its transition to unreachable, not on every retry; flows are stored without SNMP enrichment and never dropped |
| `riptide.clickhouse.username`, `password` | at startup | startup fails |
| `riptide.mcp.clickhouse.username`, `password` | at startup | startup fails |
| `riptide.discovery.token` | at startup, then on every HTTP request | startup fails; later, that poll fails and the last good inventory keeps serving |
| `riptide.mcp.auth.tokens[n]` | when the MCP server starts | the token is logged as an error and skipped |
| `--admin-password`, `--writer-secret`, `--reader-secret` of `riptide onboard` | when the command runs | the command fails before any statement runs |

A failing reference is not a configuration error.
The message is raised at resolve time, so an agent whose community cannot be read shows up as a poll warning, not at startup.

## Settings

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| **`riptide.secrets.allowed-paths`** | comma-separated paths | unset, no restriction | Directories `file://` may read from. Symlinks are resolved before the check, so a link out of the sandbox is refused. |
| **`riptide.secrets.vault.uri`** | URL | unset, `vault://` disabled | Vault address. Setting it enables the `vault://` resolver. |
| **`riptide.secrets.vault.token`** | string | required when `uri` is set | Vault token. `${VAULT_TOKEN}` reads it from the environment, for example a Vault Agent sink. |
| **`riptide.secrets.sops.command`** | command line | `sops` | The decrypting command, split on whitespace. |
| **`riptide.secrets.sops.age-key-file`** | path | unset | Exported to `sops` as `SOPS_AGE_KEY_FILE`. |

```properties
riptide.secrets.allowed-paths=/run/secrets,/etc/riptide
riptide.secrets.vault.uri=https://vault.example.com:8200
riptide.secrets.vault.token=${VAULT_TOKEN}
riptide.secrets.sops.command=sops
riptide.secrets.sops.age-key-file=/etc/riptide/age.key
```

## Rotation

When a new secret value takes effect depends on the scheme and on when the consumer resolves.

| Scheme | New value reaches a per-poll or per-request consumer | Reaches a startup consumer |
| --- | --- | --- |
| `file://` | next poll or request, no reload; after a failed resolve, the next poll comes after the agent's back-off | restart |
| `vault://` | next poll or request, no reload; nothing is cached; same back-off after a failed resolve | restart |
| `sops://` | next [config hot-reload](../guides/hot-reload.md) or restart; decrypted files are cached until then | restart |
| `env://` | restart; a process environment is immutable | restart |

Changing the reference itself is a configuration change and follows the reload rules of the file it lives in.

## Duplicate keys in a `file://` reference

A `#key` lookup reads the file as Java properties.
Properties keeps the last declaration of a repeated key and says nothing, so riptide counts declarations and refuses a key declared more than once:

```text
Key 'community' is declared 2 times for secret ref file:///etc/riptide/secrets.properties#community — riptide will not guess which is meant. Keep one, or put this secret in its own file.
```

Properties strips indentation, so a nested YAML file collides on bare keys:

```yaml
snmp:
  core:
    community: core-secret
  edge:
    community: edge-secret
```

Both `community` lines answer to `#community`, and the reference that was correct when written breaks at the next SNMP poll after the second site is added.
Give each secret its own file, such as `file:///run/secrets/core-community`, or use `sops://`, whose dot-separated keys address a nested document without ambiguity.

## Store credentials in Vault

Prerequisites: a KV v2 engine, here mounted at `secret`, and a token riptide can read from its environment.

1. Write the fields of one agent as one secret:

   ```bash
   vault kv put secret/snmp/core-router community=public authPassphrase=auth-secret privPassphrase=priv-secret
   ```

   Expected output:

   ```text
   ======== Secret Path ========
   secret/data/snmp/core-router

   ======= Metadata =======
   Key                Value
   ---                -----
   created_time       2026-09-23T10:14:39.120166789Z
   custom_metadata    <nil>
   deletion_time      n/a
   destroyed          false
   version            1
   ```

2. Point riptide at Vault and reference the fields:

   ```properties
   riptide.secrets.vault.uri=https://vault.example.com:8200
   riptide.secrets.vault.token=${VAULT_TOKEN}
   riptide.snmp.credentials.core-v3.version=v3
   riptide.snmp.credentials.core-v3.security-name=monitoring
   riptide.snmp.credentials.core-v3.auth-passphrase=vault://secret/snmp/core-router#authPassphrase
   riptide.snmp.credentials.core-v3.priv-passphrase=vault://secret/snmp/core-router#privPassphrase
   ```

3. Verify with a startup consumer, so a wrong reference fails immediately instead of at the first poll:

   ```bash
   vault kv put secret/riptide/clickhouse password=riptide
   VAULT_TOKEN=... java -jar /usr/share/riptide/riptide.jar --riptide.secrets.vault.uri=https://vault.example.com:8200 \
     '--riptide.secrets.vault.token=${VAULT_TOKEN}' '--riptide.clickhouse.password=vault://secret/riptide/clickhouse#password'
   ```

   Expected output, among the startup log lines:

   ```text
   org.riptide.RiptideApplication           : Started RiptideApplication in 1.586 seconds (process running for 1.863)
   ```

   With a field name that does not exist the process exits with code 1 and the log carries the cause:

   ```text
   Key 'nope' not found for secret ref vault://secret/riptide/clickhouse#nope
   ```

The path after the mount is passed to the KV v2 data API as is, so `secret/snmp/core-router` reads `secret/data/snmp/core-router`.

## Store credentials with SOPS

Prerequisites: the `sops` binary on the riptide host, and an age key file readable by the riptide user.

1. Write the plaintext file:

   ```yaml
   # /etc/riptide/secrets.yaml
   snmp:
     community: s3cret
     auth-passphrase: also-s3cret
   ```

2. Encrypt it in place:

   ```bash
   sops -e -i --age age1sn6cvzhly3ad55uk6cqyudrs5stf4p02xp73zy89pkrdjwuemvmqgazfha /etc/riptide/secrets.yaml
   head -3 /etc/riptide/secrets.yaml
   ```

   Expected output:

   ```text
   snmp:
       community: ENC[AES256_GCM,data:5SAM8CVq,iv:1lFzgAmalv5Q4zUAzLK1p8TfEuwspW/8PxF4VzFx5BY=,tag:OZ1CbVAzC7lEKOqFOkthJg==,type:str]
       auth-passphrase: ENC[AES256_GCM,data:ocYXvrasBNUmWP0=,iv:72zHYQwgsGW+doh0q+fCLlz+tag6gXzWyhwrU5XLvBo=,tag:qKbmZ7XGV+YwgTbM2iJoCg==,type:str]
   ```

3. Reference the keys:

   ```properties
   riptide.secrets.sops.age-key-file=/etc/riptide/age.key
   riptide.snmp.credentials.core-v2c.version=v2c
   riptide.snmp.credentials.core-v2c.community=sops:///etc/riptide/secrets.yaml#snmp.community
   ```

Riptide runs `sops -d <file>`, gives it 30 seconds, and closes its stdin so it cannot prompt.
A document that does not parse as YAML or JSON, such as the SOPS binary format, serves whole-content references only.

## Error catalog

Every message is raised as an `IllegalArgumentException` at resolve time. The consumer decides whether that fails startup, a command, a poll or a request, see the table above.

| Message | Probable cause | Recovery |
| --- | --- | --- |
| `No secret resolver for scheme 'vault' (secret ref ...)` | A `vault://` reference with `riptide.secrets.vault.uri` unset | Set the URI and token |
| `No secret resolver for scheme '...'` | Typo in the scheme | Use one of the schemes above |
| `Secret reference must not be blank`, `Secret reference '...' has no value part` | Empty value, or a scheme with nothing after `://` | Fix the reference |
| `Environment variable 'X' is not set (secret ref env://X)` | Variable absent from the riptide process environment | Export it in the unit or compose file |
| `Cannot read secret ref file://...` | Missing file or no read permission | Check path and mode for the riptide user |
| `Path of secret ref ... is outside riptide.secrets.allowed-paths` | The real path, after symlinks, is not under an allowed directory | Move the file or widen `allowed-paths` |
| `Key 'k' is declared N times for secret ref ...` | Repeated key in a properties or indented YAML file | One file per secret, or `sops://` |
| `Key 'k' not found for secret ref ...` | The key is absent from the file, Vault secret or decrypted document | Check the key; SOPS keys are dot-separated paths |
| `Cannot parse properties for secret ref ...` | The file cannot be read as properties | Use a whole-file reference or a properties file |
| `Secret ref ... is missing the #key selecting a field of the secret` | `vault://` without `#key` | Add the field name |
| `Secret ref ... must have the form vault://<mount>/<path>#<key>` | No `/` between mount and path | Fix the reference |
| `riptide.secrets.vault.uri is set but riptide.secrets.vault.token is missing` | Token blank at startup | Set the token; startup fails until it is |
| `Cannot resolve secret ref ... from Vault: ...` | Vault unreachable, token rejected, or permission denied | See the wrapped Vault message |
| `No secret found for ref ...` | Path exists on no KV v2 secret | Check mount and path |
| `Cannot run 'sops' for secret ref ...` | Binary not on the `PATH` of the riptide process | Install it or set `riptide.secrets.sops.command` |
| `Decrypting secret ref ... failed (exit N): ...` | Wrong or missing decryption key | See the `sops` stderr in the message |
| `Decrypting secret ref ... timed out after 30s` | `sops` waited on a KMS or a prompt | Provide the key non-interactively |

## Open questions

- The SOPS path was verified with `sops` 3.10.2 in a container, not with riptide resolving through it on this host.
