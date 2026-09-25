---
sidebar_position: 9
title: Outbound TLS
description: The one key that adds an internal certificate authority to riptide's HTTP reads, which connections it reaches, and every error it can raise.
---

# Outbound TLS reference

Riptide reads two things over HTTP: the discovery endpoints at `riptide.discovery.url` or `riptide.discovery.urls` and the classification ruleset at `riptide.classification.rules` when that is an `http://` or `https://` location.
Both verify the server certificate against the platform's certificate authorities.
An endpoint served by an internal authority needs that authority added.

## Settings

| Name | Type | Default | Description |
| --- | --- | --- | --- |
| **`riptide.http.ca-bundle`** | path to a PEM file | unset | One or more certificate authorities to trust in addition to the platform's own. Read once at startup. An unreadable file, or one holding no certificate, fails startup. |

```yaml
riptide:
  http:
    ca-bundle: /etc/riptide/internal-ca.pem
```

There is no key that disables verification.
These connections carry a credential, and an unverified peer is one that anything on the path can impersonate.

## What the bundle reaches

| Connection | Setting | Uses `riptide.http.ca-bundle` |
| --- | --- | --- |
| Discovery endpoint | `riptide.discovery.url`, `riptide.discovery.urls` | yes |
| Classification ruleset over HTTP | `riptide.classification.rules` | yes |
| ClickHouse | `riptide.clickhouse.endpoint` | no; the ClickHouse client's own TLS settings apply |
| Vault | `riptide.secrets.vault.uri` | no; Spring Vault's own TLS settings apply |

A configured bundle widens trust and never replaces it.
A peer is accepted when either the platform's authorities or the bundle verify it, so adding a bundle for one endpoint cannot break another served by a public authority.
The platform's key managers stay in place too, so a client certificate configured through `javax.net.ssl.keyStore` is still presented.

## Trust one endpoint's certificate directly

When there is no authority file, trust the served certificate itself.

1. Save it:

   ```bash
   openssl s_client -showcerts -connect netbox.example.com:443 </dev/null 2>/dev/null \
     | openssl x509 -outform PEM > /etc/riptide/netbox.pem
   grep -c 'BEGIN CERTIFICATE' /etc/riptide/netbox.pem
   ```

   Expected output:

   ```text
   1
   ```

2. Point **`riptide.http.ca-bundle`** at the file and restart.

A PEM file may hold several certificates, which covers two endpoints behind two authorities with one key.

## Rotation

| What rotates | Takes effect |
| --- | --- |
| The bundle | At the next restart. It is read once at startup. |
| `riptide.discovery.token` | At the next request. It is resolved per request, so a `file://` reference picks up a rewritten file without a restart, and a `vault://` reference costs one Vault read per page of a paged walk. |

A credential reference that stops resolving fails that poll, which is counted, and the last good inventory keeps serving.
The request is never sent without the credential.

## Error catalog

Each fails startup.

| Message | Probable cause | Recovery |
| --- | --- | --- |
| `riptide.http.ca-bundle could not be read: /path: ... It must name a readable PEM file holding one or more certificate authorities.` | Missing file, no read permission, or a PEM or DER body that does not parse | Fix the path or the file |
| `riptide.http.ca-bundle holds no certificate: /path. A PEM file with one or more CERTIFICATE blocks is expected. Leave the key unset to trust only the platform's authorities.` | Empty file, or a file with no `CERTIFICATE` block, such as a key file or plain text | Put the authority's PEM in it, or unset the key |
| `riptide.http.ca-bundle could not be used: /path: ...` | The certificates parsed but no trust manager could be built, or the platform key store named by `javax.net.ssl.keyStore` could not be opened: missing file, wrong `keyStorePassword` or `keyStoreType` | See the wrapped message; with mutual TLS check the key store properties before the bundle |
