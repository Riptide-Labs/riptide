# Contributing to Riptide

Thanks for considering a contribution! 🌊

The contributor guide lives in the documentation:

**https://riptide.space/docs/develop/environment**

In short:

- [Environment](https://riptide.space/docs/develop/environment) — clone, `make`, IDE setup
- [Run & debug](https://riptide.space/docs/develop/run-and-debug) — local stack, pcap replay, nl6
- [Testing](https://riptide.space/docs/develop/testing) — unit / e2e / full-mode tiers
- [Pull requests](https://riptide.space/docs/develop/pull-requests) — CI quality gates,
  **DCO sign-off (`git commit -s`)**, Conventional Commits

## Working from an issue

Open an issue before the pull request — a [bug report](https://github.com/Riptide-Labs/riptide/issues/new?template=bug.yml)
or an [enhancement](https://github.com/Riptide-Labs/riptide/issues/new?template=enhancement.yml).
It gives the change somewhere to be discussed before you have written it, and
lets the PR close it with `Closes #123`.

## Sign-off and AI assistance

Every commit needs a `Signed-off-by` trailer from a human identity, created with
`git commit -s`. It certifies the [Developer Certificate of Origin](https://developercertificate.org/):
you have the right to submit the code under this project's licence.

If an AI coding assistant helped write the change, add an `Assisted-by` trailer
naming the agent and model:

```
feat(flows): decode IPFIX option templates

Assisted-by: ClaudeCode:claude-opus-4-8
Signed-off-by: Jane Doe <jane@example.org>
```

The trailer is a statement of provenance, not a disclaimer. **The human who
signs off remains responsible for the change** — for reviewing it as if they had
written it by hand, and for its licence compliance. An `Assisted-by` trailer
never appears without a `Signed-off-by` next to it, and the sign-off is never an
AI's name.

## Security problems

Please don't open a public issue for a vulnerability — see [SECURITY.md](SECURITY.md)
for private reporting.

Riptide is licensed [GPL-3.0-or-later](LICENSE).
