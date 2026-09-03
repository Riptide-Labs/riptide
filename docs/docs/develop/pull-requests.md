---
sidebar_position: 4
title: Pull requests
---

# Pull requests

## Quality gates

Every PR must pass, all wired through `make` in CI:

| Gate | What it enforces |
|---|---|
| Checkstyle | formatting/style rules (`config/checkstyle.xml`) — fails on error |
| Error Prone | compile-time bug patterns (javac plugin; generated sources excluded) |
| Unit tests | the full suite |
| SpotBugs | bytecode analysis, `effort=Max` (`config/spotbugs-exclude.xml` documents the deliberate exclusions) |
| Coverage floor | ≥ 65% instruction / ≥ 55% branch (JaCoCo `check`) |
| CodeQL | security-and-quality analysis (separate check) |
| e2e | the nl6 flow-ingestion tier, full mode included |
| Docs | broken links, broken anchors, and admonition markup left as body copy |

Run the Maven-side gates locally before pushing: `make` (= `mvn verify`).
The documentation gates run under `make docs`, and only on PRs that touch `docs/`, `landing/`, `Makefile` or the docs workflow.

The documentation gates exist because the failures they catch are silent.
A broken anchor published without complaint until `onBrokenAnchors` was set to `throw`.
An admonition whose syntax is not exactly right is not parsed as a directive at all, so it renders as literal `:::` body copy: no build error, no build warning, and almost no visual difference in a diff.
That is how a warning about default passwords shipped as ordinary paragraph text.

Write `:::type[Title]`.
No space before the bracket, nothing after it, and close the container with as many colons as opened it.
`make docs` checks the built HTML rather than the source, so it catches every way of getting this wrong, including a container opened with four colons and closed with three.
It names the page and quotes the text.
Prose that needs to *show* a wrong spelling can do so inside a code span, which the checker ignores.

## Tests are part of the change

**New functionality lands with tests, and a bug fix lands with a test that fails without it.**
A PR that adds behaviour without covering it — or fixes a bug without a regression test — will be
asked to add one before merge. The coverage floor above is a backstop, not the policy: it catches
a shortfall in aggregate but does not excuse an untested feature. Match the tier to the change —
a unit test for pure logic, an `*IT` against real ClickHouse for the repository layer, an e2e/nl6
case for the ingestion path, a fuzz seed for a parser edge case.

## Commits

- **Conventional Commits**: `<type>[scope]: <description>` — `feat`, `fix`, `docs`,
  `refactor`, `test`, `chore`, `ci`, `build`, … Breaking changes append `!`.
- **DCO sign-off required**: commit with `git commit -s`. The sign-off certifies the
  [Developer Certificate of Origin](https://developercertificate.org/) with your own
  identity.
- **AI assistance is declared**: if a coding assistant helped, add an
  `Assisted-by: <Agent>:<model>` trailer above the sign-off, e.g.
  `Assisted-by: ClaudeCode:claude-opus-4-8`. It records provenance — the human who
  signs off is still responsible for reviewing the change and for its licence
  compliance, and the sign-off is never an AI's name.

## License

Riptide is GPL-3.0-or-later. Every new source file starts with the SPDX header used
throughout the codebase:

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
```

## Flow

Branch from `main`, keep PRs focused, make CI green — reviews happen on GitHub. Bug
reports with a pcap (see [Run & debug](run-and-debug.md)) are the fastest path to a fix.

## Cutting issues

Every claim in an issue carries its evidence **inline**: the command, the output excerpt, the measurement. Never cite local working documents (research notes, benchmark scratch files, anything gitignored) as the source: they do not exist for anyone else, and an issue that says "see the research doc" is unreviewable the moment the branch is gone. If the evidence matters, paste it; if it is too long, attach it.
