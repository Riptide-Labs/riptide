# Riptide Flows

NetFlow analysis engine (Java 25, Spring Boot, Maven).

## Source file license headers

This repo is licensed GPL-3.0-or-later. Every Java source file MUST begin with exactly this header, placed above the `package` declaration with one blank line after the closing `*/`:

```java
/*
 * Copyright 2026 Riptide Labs, <https://github.com/Riptide-Labs>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
```

- This is the default for all new and edited source files in this repo; it overrides any other header template (in particular, do NOT use the OpenNMS Apache-2.0 header here).
- For new files, use the current year in the `Copyright` line; do not bump the year on later edits.
- For non-Java source files (shell, YAML, etc.), use the same two lines with the language's comment syntax.
- Do not add headers to test fixtures, generated files, or data files (JSON, `.dat`, Markdown).

## Working rules

Each of these was paid for. They come from the epic 2 and epic #470 retrospectives, and every one of them was written after the opposite behaviour shipped a defect. Check them before opening a PR.

### Claims must name their limits

**When you claim a gate, name what it does not cover.** `make jar` runs no `*IT` class. "Verify passed" is not the same statement as "the integration tests passed", and reporting the first while meaning the second is how an untested path reaches a reviewer as a tested one.

**Before reporting a measurement, state what could falsify it.** If nothing could, report it as unavailable rather than producing a number. A figure derived from a model nobody can contradict reads exactly like a figure measured from a fleet, and the reader cannot tell them apart unless you say so.

**For anything that renders, verify the rendered output, not the source.** SQL in a doc gets run against a server. A generated file gets read back. A dashboard gets loaded. Source that looks right is not evidence.

### Tests must be shown to fail

**Run mutations per property, against only the test that names it, with the baseline confirmed green first.** An aggregate run cannot tell a killed mutation from an already-failing one.

**A passing mutation test is not proof until you have read the assertion that caught it.** An assertion can match the right string for the wrong reason. `hasMessageContaining("'A'")` passes on a message that merely echoes its input; the mutation survived and the suite stayed green.

### Changes must reach every site

**When adding a guard, enumerate every sibling call site emitting the same statement, and fix or justify each in the same commit.** Fixing only where the report pointed has caused repeated regressions here. Grep the capability, not the filename you were handed.

**Prefer removing a condition over adding a fourth place that remembers it.** If a rule is encoded in three places, the fix for the fourth bug is deleting two of them, not writing another.

### Specs must name their consumer

**Any change adding an operator-settable key must name the consumer that reads it and the test proving the read.** A key nothing consumes is a key that silently does nothing, and it will be found by an operator rather than by CI.
