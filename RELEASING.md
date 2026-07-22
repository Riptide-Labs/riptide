# Releasing Riptide

Riptide follows [Semantic Versioning](https://semver.org/). The version lives in
`pom.xml` and nowhere else — everything downstream (packages, image tags,
release title) is derived from it.

Which part to bump follows from the Conventional Commit types since the last
tag: a `!` or `BREAKING CHANGE:` footer means major, `feat` means minor,
anything else patch.

## Cutting a release

`main` is protected, so the version bump goes through a pull request like any
other change. Example for 1.0.0:

```bash
git checkout main && git pull
git checkout -B release
git push -u origin release
make release RELEASE_VERSION=1.0.0
```

`checkout -B` starts the branch fresh from up-to-date `main` even when a local
`release` from the previous cycle is still around. `make release` refuses to
run unless you are on the `release` branch, in sync with its upstream (hence
the `git push -u` above), with a clean tree and a version tag that does not
exist yet. It then:

1. sets the release version in `pom.xml`
2. commits it and creates the annotated tag `v1.0.0`
3. sets `pom.xml` to the next snapshot version
4. commits that

Nothing has left your machine at this point. Add `PUSH_RELEASE=true` to push the
commits and the tag in the same step, or push them yourself once you are happy:

```bash
git push origin HEAD
git push origin v1.0.0
```

Open a PR to merge `release` into `main` and squash-merge it as usual. The
branch is deleted automatically on merge; the next release recreates it fresh
off `main`. An abandoned attempt (PR closed without merging) leaves a stale
`origin/release` behind — delete it before starting over:
`git push origin --delete release`.

**The tag push is what releases.** Everything below happens in
[`release.yml`](.github/workflows/release.yml) with no further input.

## What the pipeline produces

The workflow triggers on tags matching `v*.*.*` and refuses to run if the tag
disagrees with the version in `pom.xml`, or if that version is a `SNAPSHOT`.

| Artifact | Where it lands |
|---|---|
| `riptide-flows-X.Y.Z.jar` | GitHub Release |
| `riptide_X.Y.Z_all.deb`, `riptide-X.Y.Z-1.noarch.rpm` | GitHub Release |
| `riptide-X.Y.Z.spdx.json` (SBOM) | GitHub Release |
| `*.sigstore.json` (cosign bundles) | GitHub Release |
| Multi-arch image (`linux/amd64`, `linux/arm64`) | `ghcr.io/riptide-labs/riptide` |
| Build provenance | attached to the release artifacts and pushed to GHCR |

The release is published immediately — it is not a draft. Write the release
notes afterwards with `gh release edit vX.Y.Z --notes-file notes.md`.

### Container image tags

| Tag | Points at |
|---|---|
| `X.Y.Z` | that exact release, forever |
| `X.Y` | newest patch of that minor |
| `latest` | newest **stable** release |
| `rc` | current `main`, rebuilt on every push — not a release |

A prerelease tag (`v1.0.0-rc1`) is marked as a prerelease on GitHub and gets
**only** its exact `X.Y.Z` tag. It never moves `X.Y` or `latest`.

## Verifying a release

Everything is signed with [cosign](https://docs.sigstore.dev/) keyless signing —
no key to distribute, the signing identity *is* the release workflow.

```bash
# container image
cosign verify ghcr.io/riptide-labs/riptide:<version> \
  --certificate-identity-regexp '^https://github.com/Riptide-Labs/riptide/\.github/workflows/release\.yml@refs/tags/v.*$' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com

# a downloaded artifact, against its bundle from the same release
cosign verify-blob riptide-flows-<version>.jar \
  --bundle riptide-flows-<version>.jar.sigstore.json \
  --certificate-identity-regexp '^https://github.com/Riptide-Labs/riptide/\.github/workflows/release\.yml@refs/tags/v.*$' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com

# build provenance: which workflow, commit and inputs produced this
gh attestation verify riptide-flows-<version>.jar --repo Riptide-Labs/riptide
```

Not every release carries all of these — see the table in
[SECURITY.md](SECURITY.md#verifying-what-you-run). In particular, images
0.4.9–0.4.11 have no valid signature at all
([#294](https://github.com/Riptide-Labs/riptide/issues/294)).

## After the tag

Watch the run and confirm it before announcing anything:

```bash
gh run watch                                  # release workflow to green
gh release view vX.Y.Z                        # artifacts + SBOM + bundles attached
cosign verify ghcr.io/riptide-labs/riptide:X.Y.Z …   # signature is real
```

`latest` and `X.Y` should now resolve to the new version. If the workflow fails
partway, fix forward with a new patch version rather than re-pushing the tag —
the release is already partly public.

## Changing the pipeline

If you change what the release produces, change this file in the same PR. A
`RELEASING.md` that describes last year's pipeline is worse than none.
