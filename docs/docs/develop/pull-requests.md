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

Run the Maven-side gates locally before pushing: `make` (= `mvn verify`).

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
