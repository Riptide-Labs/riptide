# Security Policy

## Reporting a vulnerability

**Please do not open a public issue for a security problem.**

Report it through GitHub's private vulnerability reporting:
[**Report a vulnerability**](https://github.com/Riptide-Labs/riptide/security/advisories/new).
Only the maintainers see the report until an advisory is published.

Useful things to include, as far as you have them: the affected version
(`riptide --version` or the image tag), what an attacker gains, and the
smallest reproduction you can manage — a pcap that triggers the problem is
ideal for anything in the flow-parsing path.

You can expect an acknowledgement within a few days. Riptide is maintained by a
small team, so please allow reasonable time for a fix before disclosing
publicly; we will keep you updated and credit you in the advisory unless you
prefer otherwise.

## Supported versions

Fixes go onto `main` and ship in the next release. Only the latest release is
supported — there are no maintenance branches for older versions.

## Verifying what you run

Every release is signed with [cosign](https://docs.sigstore.dev/) keyless
signing and carries SLSA build provenance, so you can check that a binary or
image really came from this repository's release workflow rather than from
someone else:

```bash
# container image
cosign verify ghcr.io/riptide-labs/riptide:<version> \
  --certificate-identity-regexp '^https://github.com/Riptide-Labs/riptide/\.github/workflows/release\.yml@refs/tags/v.*$' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com

# a downloaded artifact, against its .sigstore.json bundle from the release
cosign verify-blob riptide-flows-<version>.jar \
  --bundle riptide-flows-<version>.jar.sigstore.json \
  --certificate-identity-regexp '^https://github.com/Riptide-Labs/riptide/\.github/workflows/release\.yml@refs/tags/v.*$' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com

# build provenance — which workflow, commit and inputs produced it
gh attestation verify riptide-flows-<version>.jar --repo Riptide-Labs/riptide
```

What is available depends on the release:

| Release | Image signature | Artifact bundles | SBOM | Provenance |
|---|---|---|---|---|
| 0.4.9 – 0.4.11 | ❌ deleted ([#294](https://github.com/Riptide-Labs/riptide/issues/294)) | — | — | — |
| 0.5.0 | ✅ | — | — | — |
| next release onwards | ✅ | ✅ | ✅ | ✅ |

The 0.4.9–0.4.11 images themselves are unchanged; a cleanup step on `main`
deleted their signature objects, so they can no longer be verified. Prefer
0.5.0 or later if signature verification matters to you.

And the obvious caveat: a signature proves origin, not the absence of bugs.
