---
sidebar_position: 6
title: Upgrading across versions
---

# Upgrading across versions

Each release's notes tell you what that release asks of you. They cannot tell you how those asks
combine, because no release can see the ones after it — so an upgrade spanning several versions
reads as several separate procedures when it is usually one.

This page carries only that: how the steps collapse. For what any individual release changed, read
its notes — [v0.11.0](release-notes/v0.11.0.md), [v0.10.0](release-notes/v0.10.0.md),
[v0.9.0](release-notes/v0.9.0.md).

## Manage mode has never needed anything

If `riptide.clickhouse.manage-schema` is `true` — the default, and what the Docker Compose
quickstart and the single-node package install both use — no release so far has required a manual
step. Riptide owns the schema, repairs it on the next start, and that is the whole procedure:
**upgrade and restart.**

Everything below is for provisioned deployments only.

## Provisioned deployments: one `onboard`, whatever the span

If you followed the [multi-tenant provisioning guide](deploy/multi-tenancy.md) and set
`manage-schema: false`, riptide connects as a restricted user that cannot change the schema. Two
releases so far have needed you to act:

| Upgrading across | What that release's notes ask for |
| --- | --- |
| v0.10.0 | `GRANT SHOW TABLES` on the four rollup views, **or** re-run `riptide onboard` |
| v0.11.0 | re-run `riptide onboard`, then restart the collector |

Read in sequence, that looks like two or three procedures. It is one:

```
                 what the notes say, release by release
  ≤0.9  → 0.10   grant SHOW TABLES on 4 _mv   ┐
  ≤0.10 → 0.11   riptide onboard, restart     ├──▶   run onboard once, then restart
  ≤0.11 → next   riptide onboard, restart     ┘
```

`riptide onboard` is idempotent, and it both re-applies the grants and repairs the rollups. So it
**supersedes** the v0.10.0 grant step rather than adding to it. However many versions you are
jumping, the procedure is:

1. Run `riptide onboard` with your admin credentials.
2. Restart the collector.

Step 2 is the one that gets missed. The decision about which rollups are usable is made once, at
startup, so a collector that was running while you onboarded keeps its old verdict until it
restarts.

## Why restarting matters more than it looks

A collector that has not restarted still considers its rollups unusable, and long-range queries
fall back to raw `flows` — which is retained for far less. The answer comes back short.

Since the release that added coverage reporting, a short answer says so: the result carries a
`coverage_warning` naming the table, how far back it actually reaches, and how much of your range
that covers. If you see one after an upgrade, the restart is the first thing to check.

## Skipping releases is fine

Nothing here needs to be applied version by version. Onboarding once at the end of the jump does
everything the intermediate releases asked for, because every step is idempotent and none of them
depend on having run an earlier one.
