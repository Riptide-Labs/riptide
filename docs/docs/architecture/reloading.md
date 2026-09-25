---
title: How configuration reloads work
sidebar_position: 5
description: Content-hash polling of the main configuration, the inventory file and the classification rules, why a bad file never wins, what a skipped cycle does to the gauges, and why the classification gauges read -1 before the first publish.
---

# How configuration reloads work

Three things reload without a restart, each on its own opt-in schedule: the main configuration file (`config.yaml`), the inventory file, and the classification rules resource.
All three share one posture: the running configuration is replaced only by a candidate that passed the same validation as startup, and a candidate that cannot be read decides nothing.
Enabling them is in [Enable configuration hot reload](../operations/hot-reload.md) and [Write a classification rule](../operations/classification-rules.md); the series are in the [metrics reference](../reference/metrics.md).

## Content-hash polling

The path is re-resolved and the content hashed every cycle, rather than watched with inotify, so bind mounts, Kubernetes ConfigMap symlink swaps, and mtime-insensitive writers are all picked up.
Unchanged bytes rebuild nothing: the hash decides, not the clock.

Layering is preserved.
Environment-variable overrides keep their precedence over the file, exactly as at boot, and a file created after startup slots in beneath the environment as well.

## Bad configuration never wins

Candidates run the same validation as startup.
A failing reload keeps the running configuration, logs a warning naming the problem, and raises `config.reload.failures` plus the `config.reload.stale` gauge.

A missing, empty or whitespace-only file skips the cycle.
Deletion is indistinguishable from an atomic replacement in progress, and a shell `>` redirect truncates before writing, so the running config is kept and nothing is counted as a failure.
A file holding nothing but a UTF-8 byte-order mark counts as empty: the mark is removed when the file is read, so an editor that truncates a file it had BOM-prefixed skips like any other truncation instead of committing an empty configuration.
Both the config file and the inventory file behave this way.
The skip warns once per episode, not once per poll.

A skipped cycle leaves the gauges where they were.
A skip decides nothing about whether disk and serving agree, so `*.reload.stale` is not recomputed and not latched.
A file that has been truncated for an hour therefore reads `stale=0`.
The stale gauge answers "did the last file we could read commit", not "is the file on disk serving".
Alert on the once-per-episode warning, or on the absence of successful reloads.

On a successful reload the SNMP interface cache and the SOPS decrypted-file cache refresh; exporter-pushed interface names are kept, because they describe devices rather than configuration.
Reloads trigger on config-file changes only, so after rotating a SOPS secrets file, touch or edit `config.yaml`.

`config.reload.partial` counts a commit whose inventory rebuild against it is still pending, a subset of `successes`, counted once per edit; the stale gauge stays 1 until a retry or a newer edit publishes.

## The gauges exist only while reloading is enabled

`config.reload.stale`, `inventory.reload.stale` and the dead-schedule gauges are absent when reloading is disabled.
They are registered by the first start and are deliberately not removed by a stop, so a stopped schedule stays visible rather than vanishing.
That cuts both ways: after a stop, `*.reload.dead` reads 1 while `*.reload.stale` freezes at whatever it last computed, commonly `0`.
Neither gauge is meaningful once the schedule has stopped; read `dead` first.
Absence means "not watching".
Alert on absence separately if hot reload is mandatory in your deployment.

`config.reload.dead` and `inventory.reload.dead` read 1 if the poll schedule stopped and will never run again; the realistic cause is an `Error` such as OOM on an oversized file mid-read.
Alert on `> 0`; the only recovery is a restart.
A deliberate shutdown reads 1 too, because the gauge says the schedule is not running, not why, so scope that alert to processes you expect to be up.

Shutdown counts nothing.
An interrupt landing mid-poll during an orderly stop is not a reload failure: no counter moves and no stale latch is set.

Limitations: profile-activated YAML documents and nested `spring.config.import` inside the reloaded file are boot-only, and `env://` secret references cannot rotate in-process because the environment is immutable per process.
Those need a restart.

A whitespace-only `config.yaml` used to increment `config.reload.failures` and latch `config.reload.stale`; it is now a skip, matching what the inventory file has always done.
An alert on `config.reload.failures` meant to catch a `> config.yaml` truncation stops firing, because the truncation surfaces as the once-per-episode warning and as reloads that stop happening.

## Classification rule reloads {/* #classification-rule-reloads */}

The classification rules are a separate family with a separate posture and their own opt-in schedule, `riptide.classification.reload-interval`.

The resource named by `riptide.classification.rules` is parsed once, eagerly, while the context starts: an unreadable or unparseable resource fails the boot there, naming the parse error.
For an `http(s)://` resource that eager parse is a network fetch, so a rules server that is down is a startup outage.
Weigh that against the convenience of serving one ruleset to a fleet; a local file with a configuration-management tool writing it has no such coupling.
The engine then loads those rules into its decision tree on a background thread, and on that first load "background" does not mean "invisible": every thread that tries to classify blocks until it finishes.
That build is the cost that grows with the size of your ruleset, and it is bounded; see [Classification tree build cost](classification-build-cost.md).
Afterwards, nothing re-reads the resource unless an interval is configured.
Point the interval at a file or a URL, not at the bundled `classpath:` default: a classpath resource inside the packaged jar cannot change, so the schedule polls it forever and never has anything to apply.

The semantics are the config reloader's, with a source that can be a URL:

- Content-hash polling: the resource is re-resolved and its bytes hashed every cycle. A cycle that finds no change costs one fetch and no work; a cycle that finds one costs two, because the engine re-reads the resource itself when it rebuilds. Startup costs three (the eager parse, the engine's first load, and the schedule's own baseline).
- A remote fetch is bounded end to end: 10 seconds to connect, 10 seconds for each read, and 10 seconds for the whole response. The last of those is the one that matters: a server sending one byte at a time resets a per-read timer forever, so only a deadline across the response ends the cycle. Worst case is roughly twice the bound, because a read already blocked when the deadline passes still has to time out on its own. A response larger than 8 MiB is refused unread rather than buffered.
- Only a 200 is a ruleset: any other status is a failure naming the code, so a redirect to another scheme (same-scheme redirects are followed), a 5xx, or a proxy's error page served as HTML never reaches the CSV parser. The one exception is 404, which is absence and skips.
- A source that is not there skips: a 404, or a deleted file. The last good rules keep classifying, nothing is counted as a failure, and the skip warns once per episode. So does a response with an empty or whitespace-only body: an empty ruleset is never committed. A local file that is present but unreadable (a permission denial) is a failure, not a skip, because telling an operator to make a file reappear when it is already there would send them the wrong way.
- A failed fetch or a failed load keeps the last good rules serving. Flows keep being classified by whatever loaded last, and nothing is thrown at a flow.
- A ruleset that failed to load is attempted once, not once per interval. Bytes that would not parse this cycle will not parse next cycle, so a retry loop would rebuild nothing and bury the first, real failure under one per interval. The failure stays counted and holds the stale gauge at 1; fix the ruleset and the next poll picks the fix up as an ordinary change.
- No authentication: no credentials are sent, no conditional `GET`, no ETag or `Last-Modified` handling; the endpoint must answer an unconditional `GET`. Protect it at the network layer. Credentials embedded in the location (`http://user:token@…`) are not a supported way to authenticate; they are redacted wherever riptide logs the location, but they still travel in the clear.

A rejected rule is not a failed reload.
A condition column that is not empty but resolves to nothing, and an `exporterFilter` column that carries a value, each get the rule rejected; the rules for writing one are in [Write a classification rule](../operations/classification-rules.md).
The rest of the ruleset serves, `classification.reload.successes` moves and `classification.reload.stale` stays 0, so every other metric reads healthy.
Every load that publishes logs how many rules it published and a WARN naming any rule the engine could not use, up to the first 20 before summarising as a count; the ERROR beside it names the column and value.
`classification.rules.rejected > 0` is the one series that says part of an edit is classifying nothing.
Neither the gauge nor the log line depends on the interval: the ruleset loaded at startup is reported the same way whether or not a schedule is configured.

### Why the rule gauges read `-1` and not `0`

`-1` is "no ruleset has ever been published", which is not the same as "nothing was rejected": a `0` in that state would claim a ruleset that loaded cleanly.
Read `classification.reload.stale` alongside to tell the two cases apart.

- `-1` with `stale=0`: the boot load has not published yet. The engine submits it asynchronously, so this window is normal and resolves on its own. It is not free while it lasts: until the initial load publishes, every thread calling into classification blocks, so the window is as long as the first tree build takes.
- `-1` with `stale=1`: the initial load failed. There is no publication at all and the collector is classifying nothing. This one does not resolve until a reload succeeds.

Neither fires an alert on `rejected > 0`, which is deliberate: a collector with no rules at all is what `stale` is for.
Like `classification.reload.stale` and unlike `classification.reload.dead`, these gauges are registered whether or not a reload interval is configured, because a rejected rule is reported at boot either way.

### One family, two layers

Fetching the rules and loading them are done by different parts: the reload schedule fetches, the engine loads.
They report on the same three series rather than on two families that could disagree, and they cannot double-count: a fetch that fails never reaches the engine, and a ruleset that fails to parse was fetched successfully.
`classification.reload.stale` covers both halves, so 1 means "the rules that are serving are not the rules the source has", whichever half is at fault; the log line names which.

A skipped cycle leaves the gauges where they were, exactly as for the config reloader: an endpoint that has answered 404 for an hour reads `stale=0`, and so does a ruleset that has been empty all day.
It matters more here because the advice is to alert on `stale`.
Alert on the once-per-episode warning as well, or on the absence of successful reloads.

`classification.reload.dead` reads 1 if the poll schedule stopped and will never run again, including after a deliberate shutdown; alert on `> 0`, and the only recovery is a restart.

Unlike `config.reload.stale` and `inventory.reload.stale`, `classification.reload.stale` is registered unconditionally, including when no interval is configured.
It claims less than they do: they assert a relationship between a file on disk and what is serving, so a permanent 0 would falsely read "in sync", while this one asserts only "the last attempt failed and has not recovered".
With no interval, 0 is simply true.
`classification.reload.dead` follows the other reloaders instead and is absent with no interval configured, because a dead-schedule gauge reading 0 would claim there is a schedule.

### What a failure does

- Rules already serving: nothing an operator or a flow can see changes. A rebuild publishes atomically, so a failed one leaves the previous rules classifying, complete. The failure is reported by a WARN naming the cause, plus the counter and the gauge. This is the case where the gauge is the only durable signal: no flow fails and no error is logged.
- No rules ever loaded: classification is unavailable. Every flow's classification throws and an ERROR is logged. Reaching this needs the resource to become unreadable between the eager startup parse and the background load a moment later; the window is narrow, but the context starts normally and stays up, so the signals are the ERROR, `classification.reload.stale` at 1, and both rule gauges at `-1`. With a reload interval configured this recovers on its own in the ordinary case: the schedule could not read a baseline either, so the first poll that reads anything hands it to the engine. Only if the resource became readable in the window between the schedule taking its baseline and the next poll does recovery wait for the rules to change, because from then on the hash decides. Restarting once the resource is readable resolves both.

Shutdown counts nothing here either: a reload interrupted or refused during an orderly stop moves no counter and latches no gauge.

### Classification rules that loaded before may be rejected

Rules that loaded before may start being rejected after an upgrade.
There are two cases, and they were misbehaving in opposite directions.

The column resolved to nothing at all: `protocol=tpc`, `protocol=6` (a number where a keyword belongs), `protocol=*`, or a stray `,` in a protocol, port or address column.
The condition was dropped: the rule matched every protocol, port or address, silently and with nothing logged.
Such a rule was matching far more traffic than it named, and rejecting it is a straight improvement.

The column resolved in part: `protocol=tcp,tpc`.
This was not widened: the rule matched TCP and nothing else, so it was quietly matching less than it named.
It is now refused outright, so it matches nothing at all until you fix the typo.
This is a regression for that rule, and the only one in this change: traffic it used to classify stops being classified.
Fix the keyword and it returns.

Either way the WARN names the rule; the ERROR beside it names the column and the offending value.

## Related

- [Enable configuration hot reload](../operations/hot-reload.md)
- [Write a classification rule](../operations/classification-rules.md)
- [Metrics reference](../reference/metrics.md)
