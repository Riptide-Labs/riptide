# Releasing Riptide

Riptide follows [Semantic Versioning](https://semver.org/). The version lives in
`pom.xml` and nowhere else — everything downstream (packages, image tags,
release title) is derived from it.

Which part to bump follows from the Conventional Commit types since the last
tag: a `!` or `BREAKING CHANGE:` footer means major, `feat` means minor,
anything else patch.

## Cutting a release

**Write the release notes first.** They are `docs/docs/release-notes/vX.Y.Z.md`, committed like any
other file, and they become both the GitHub release body and a page on the docs site. The release
job refuses a stable tag without them, so writing them after tagging is not an option —
[What a release note must say](#what-a-release-note-must-say) has the shape and the constraints.

`main` is protected, so the version bump goes through a pull request like any
other change. Example for 1.0.0:

```bash
git checkout main && git pull
git checkout -B release
git push -u origin release
$EDITOR docs/docs/release-notes/v1.0.0.md   # before the next line, not after
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
| `riptide-X.Y.Z-sbom-report.html` (self-contained SBOM report, works offline) | GitHub Release |
| `*.sigstore.json` (cosign bundles) | GitHub Release |
| Multi-arch image (`linux/amd64`, `linux/arm64`) | `ghcr.io/riptide-labs/riptide` |
| Build provenance | attached to the release artifacts and pushed to GHCR |

The release is published immediately — it is not a draft, and its body is taken from
`docs/docs/release-notes/vX.Y.Z.md` in this repository. **Write that file before you tag**: the
release job checks for it first and fails in seconds if it is missing, so a tag without notes never
reaches a build.

That file is also published on the docs site, so it is the notes — not a draft of them. There is no
step after the tag; `gh release edit` is only needed to correct something already published.

### What a release note must say

A release note earns its place by answering three things, which is what makes
[v0.10.0](https://riptide.space/docs/release-notes/v0.10.0) and
[v0.11.0](https://riptide.space/docs/release-notes/v0.11.0) useful and is the part no template supplies:

- **Who is affected.** Riptide's two deployment shapes need different things, and most releases
  affect only one. Say which, in a heading the reader can match themselves against.
- **How they can tell.** A query or a command that answers "is this me?" without them having to
  reason it out.
- **What to run.** The exact invocation, not a description of it.

The file has two renderers and they disagree, so keep it to what both accept.

The loud one: HTML comments (`<!-- … -->`) are invisible on GitHub and a **build failure** in MDX,
as are raw `<` and `{`. You find these by running `make docs`.

The quiet one: **GFM renders every newline as a line break; MDX joins them.** A paragraph
hard-wrapped at 100 columns — as this file is — displays as a narrow ragged column in the release
body and reads normally on the docs site, with nothing failing. Write one line per paragraph and one
line per bullet.

The file also needs Docusaurus frontmatter to be a docs page:

```
---
sidebar_position: 1
title: Riptide X.Y.Z — what changed, in one line
sidebar_label: vX.Y.Z
---
```

`sidebar_position` runs newest-first, so a new release takes `1` and every existing note moves down
one. `title` is the page heading, so do **not** repeat it as an `# H1` in the body — Docusaurus
renders it for you, and GitHub already shows the tag above the release body. The job strips the
frontmatter before publishing, so none of it reaches GitHub.

Prereleases (`vX.Y.Z-rc1`) need no file. The job synthesises a placeholder body, because a release
candidate is built to be thrown away and the audience for an upgrade procedure is an operator, not
whoever is testing the candidate.


The attached SBOM contains post-generation first-party license assertions: syft cannot read our license from a deb control file or attach one to the scanned directory, so `make sbom-assert` sets `licenseDeclared: GPL-3.0-or-later` (read from `nfpm.yaml`) on those two entries before the report is rendered and the file is signed.
A release that fails at the "Assert first-party license facts" step means the SBOM shape drifted (typically after a syft upgrade) and the selectors in `deployment/sbom/assert_licenses.py` no longer match exactly one entry each — fix the selector, never ship `NOASSERTION`.
The same step also sets `licenseConcluded` on a reviewed allowlist of third-party packages syft cannot identify (`deployment/sbom/concluded-licenses.json`).
A failure naming an allowlist entry means a dependency bump or a syft change invalidated it: re-review the new version's license and update the entry (purl, evidence, review date), or remove it if syft now identifies the package.
Never fix that failure by deleting the check.

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

The one exception is the notes check, which runs before anything is built or published. It fails in
seconds and leaves nothing behind, so write the file, delete the tag and re-tag rather than burning
a version:

```bash
git push origin --delete vX.Y.Z && git tag -d vX.Y.Z
```

## Changing the pipeline

If you change what the release produces, change this file in the same PR. A
`RELEASING.md` that describes last year's pipeline is worse than none.
