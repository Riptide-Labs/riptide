---
sidebar_position: 8
---

# Outbound TLS

Riptide reads two things over HTTP: the discovery endpoint (`riptide.discovery.url`) and the classification ruleset (`riptide.classification.rules`, when it is an `http://` or `https://` location).
Both verify the server's certificate against the platform's certificate authorities.

An endpoint served by an internal authority needs that authority added.

| Key | Default | Meaning |
| --- | --- | --- |
| `riptide.http.ca-bundle` | unset | A PEM file holding one or more certificate authorities to trust **in addition** to the platform's own. |

```yaml
riptide:
  http:
    ca-bundle: /etc/riptide/internal-ca.pem
```

One key for both consumers, not one each: an operator has one internal authority, and two keys would always hold the same value.
A PEM file may hold several certificates, which is how you cover two endpoints behind two different authorities.

## In addition, never instead

A configured bundle widens what is trusted.
An endpoint served by a public authority stays readable while an internal bundle is configured, so adding one for NetBox cannot silently break another endpoint.

An unreadable bundle, or one holding no certificate, fails startup naming the key and the file.
It does not quietly fall back to the platform's authorities: that would leave you believing an internal authority was configured, and you would find out from a failed read that names neither.

## There is no way to turn verification off

Riptide has no `skip-verify` key, deliberately.

These connections carry a credential. An unverified peer is one anything on the path can impersonate, and a setting that disables the check outlives the afternoon that motivated it.

If an endpoint's certificate does not verify and you have no authority file, trust that certificate directly:

```shell
openssl s_client -showcerts -connect netbox.example.com:443 </dev/null \
  | openssl x509 -outform PEM > /etc/riptide/netbox.pem
```

Point `riptide.http.ca-bundle` at the result.
That configuration states what is trusted; disabling verification states nothing.

## What the bundle does not reach

Two outbound clients do not use Riptide's own HTTP reader, so this key does not apply to them:

| Client | Configured by |
| --- | --- |
| ClickHouse (`riptide.clickhouse.endpoint`) | The ClickHouse client's own TLS settings |
| Vault (`riptide.secrets.vault.uri`) | Spring Vault's own TLS settings |

Both accept an `https://` endpoint, so this boundary is worth knowing before you conclude the key is not working.

## Rotation

The bundle is read once, at startup, so a rotated authority needs a restart.

A rotated **credential** does not: `riptide.discovery.token` is resolved on every request, so rewriting the file a `file://` reference names takes effect on the next one.
The asymmetry is deliberate. A certificate authority rotates on a certificate's lifetime, and rebuilding the trust material every poll would spend real work on a file that almost never changes; a token rotates on an operational cadence, and resolving one is a file read.

Per **request**, not per poll, and the difference matters for `vault://`.
The `netbox-api` source walks pages, so a fleet spanning ten pages resolves the token ten times per poll, which is ten Vault reads.
`file://`, `env://` and plain references cost nothing worth counting.

If a credential reference stops resolving while Riptide is running, the poll fails, is counted, and the last good inventory keeps serving.
The request is never sent without the credential: an endpoint that answers an unauthenticated read could hand back a different fleet, and every guard downstream would treat that as a legitimate change.
