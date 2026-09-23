---
sidebar_position: 4
title: Pull requests
description: The steps from issue to merged pull request, the checks a pull request must pass and how to run each locally, what the build gate enforces, the tests-with-the-change rule, commit trailers and the licence header.
---

# Open a pull request

## Prerequisites

- A build from [Set up a development environment](environment.md).
- A GitHub account with a fork or a branch of [Riptide-Labs/riptide](https://github.com/Riptide-Labs/riptide).

## Steps

1. Open an issue with the [bug](https://github.com/Riptide-Labs/riptide/issues/new?template=bug.yml) or [enhancement](https://github.com/Riptide-Labs/riptide/issues/new?template=enhancement.yml) template, so the pull request has somewhere to be discussed and can close it.
   Every claim carries its evidence inline: the command, the output excerpt, the measurement.
   Never cite a local working document such as research notes or a gitignored scratch file; it does not exist for anyone else.
   If the evidence matters, paste it; if it is too long, attach it.
   A bug report with a [capture](run-and-debug.md) is the fastest path to a fix.
   Report a vulnerability through a [private security advisory](https://github.com/Riptide-Labs/riptide/security/advisories/new), never in a public issue.

2. Branch from `main` and make one focused change, with [its tests](#land-tests-with-the-change) and the [licence header](#add-the-licence-header) on every new source file.

3. Commit with **`git commit -s`** and a Conventional Commits subject; see [Write the commit](#write-the-commit).

4. Run the gates locally:

   ```bash
   make
   make e2e
   ```

   `make docs` when the change touches `docs/`, `make lint-actions` when it touches `.github/workflows/`.

5. Push and open the pull request.
   The template asks three things:

   | Section | Write |
   | --- | --- |
   | What does this change? | The behaviour that is different afterwards, and why. `Closes #<issue>`. |
   | How was it verified? | The command you ran and what it showed: `make`, `make e2e`, a [pcap replay](run-and-debug.md), a dashboard screenshot. "CI is green" is enough when CI covers the change. |
   | Anything reviewers should look at closely? | Trade-offs, open doubts, follow-ups left out. |

6. Verify: the four [required checks](#checks) are green on the pull request's current head.

## Checks

Branch protection requires these four on the pull request's current head:

| Check | Workflow | Enforces | Run locally |
| --- | --- | --- | --- |
| **`build`** | `build.yml` | `make`, after the fixture tests of the repository's checker scripts; see [What `make` enforces](#what-make-enforces). | `make` |
| **`e2e`** | `build.yml` | `make e2e` in [full mode](testing.md#run-full-mode). | `make e2e` |
| **`lint`** | `lint-actions.yml` | actionlint and zizmor over the workflows, and the README contributor table in sync with `.all-contributorsrc`. | `make lint-actions`, `make contributors-check` |
| **`build-cost-docs`** | `build.yml` | A change under `src/main/java/org/riptide/classification/internal/decision/` also changes `docs/docs/deploy/operations.md` or carries a `Cost-Unchanged:` commit trailer. A changed dashboard JSON bumps the dashboards version. | `make build-cost-docs`, `make dashboards-version-check DASHBOARDS_BASE_REF=origin/main` |

These run as well, by path or on every pull request, and are not required by branch protection:

| Check | Runs when | Enforces | Run locally |
| --- | --- | --- | --- |
| **`analyze`** (CodeQL) | every pull request | The `security-and-quality` query suite over the Java sources. | not available |
| **`review`** (dependency review) | every pull request | No new dependency with a known vulnerability of severity high or above. | not available |
| **`build`** (Docs) | `docs/`, `landing/`, `Makefile`, `docs.yml` | The site builds with broken links and anchors as errors, and no rendered page shows admonition markup as body copy. | `make docs` |
| **`compose-smoke`** | `deployment/riptide/`, `deployment/clickhouse/`, `Makefile` | The shipped compose stack starts and its ClickHouse and Grafana wiring works. | `make compose-smoke` |
| **`packages`** | `nfpm.yaml`, `deployment/package/`, `Makefile` | The DEB and RPM build and install. | `make packages packages-smoke` |
| **`nix`** | `flake.nix`, `flake.lock`, `nix/`, `pom.xml` | The flake package builds and the NixOS module evaluates. | `make nix-check` |

A `pom.xml` change makes `mvnHash` in `nix/package.nix` stale.
On a branch in this repository the failed `nix` run pushes the corrected hash to the branch and re-runs; on a fork, or to skip that round trip, run `make nix-hash` and commit the result.

## What `make` enforces

| Gate | Rule | Configuration |
| --- | --- | --- |
| Checkstyle | Fails on any error, test sources included. Runs at `validate`, before compilation. | `config/checkstyle.xml` |
| Error Prone | Compile-time bug patterns, with a named set promoted to errors; generated sources are excluded. | the compiler arguments in `pom.xml` |
| Unit tests | Every `*Test` class and the fuzz seed replay. | |
| SpotBugs | Effort `Max`; any reported bug fails the build. | `config/spotbugs-exclude.xml` documents the deliberate exclusions |
| Coverage floor | 65 % instruction, 55 % branch. | the `jacoco:check` rule in `pom.xml` |

## Write documentation that passes the gate

Write **`:::type[Title]`** with no space before the bracket and nothing after it, and close the container with as many colons as opened it.
An admonition whose syntax is not exactly right is not a directive at all: it renders as literal `:::` text, with no build error or warning, and `make docs` reads the built HTML to catch it.
The checker names the page and quotes the text.
To show a wrong spelling in prose, put it in a code span, which the checker ignores.

## Land tests with the change

New behaviour lands with tests, and a bug fix lands with a test that fails without it.
The coverage floor is a backstop, not the policy: it catches a shortfall in aggregate and does not excuse an untested feature.

| Change | Test |
| --- | --- |
| Pure logic | A unit test |
| The repository layer | An `*IT` class against a real ClickHouse |
| The ingestion path | An e2e case driven by nl6 |
| A parser edge case | A seed input for the [fuzz harness](testing.md#fuzz-harnesses) |

## Write the commit

- **Conventional Commits**: `<type>[scope]: <description>` with a type such as `feat`, `fix`, `perf`, `docs`, `refactor`, `test`, `chore`, `ci` or `build`.
  A breaking change appends `!`.
- **DCO sign-off**: commit with **`git commit -s`**.
  The trailer certifies the [Developer Certificate of Origin](https://developercertificate.org/) with your own identity.
- **AI assistance is declared**: add an `Assisted-by: <Agent>:<model>` trailer above the sign-off.
  The human who signs off remains responsible for reviewing the change and for its licence compliance, and the sign-off is never an AI's name.

```text
feat(flows): decode IPFIX option templates

Assisted-by: ClaudeCode:claude-opus-4-8
Signed-off-by: Jane Doe <jane@example.org>
```

## Add the licence header

Riptide is GPL-3.0-or-later.
Every new source file starts with this header, above the `package` declaration:

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
```

Shell, YAML and other non-Java sources carry the same two lines in their comment syntax.
Test fixtures, generated files and data files carry none.
