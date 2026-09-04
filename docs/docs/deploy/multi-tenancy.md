---
sidebar_position: 6
title: Multi-tenancy
---

# Multi-tenant provisioning

Run many riptide processes — one per isolated network, several per network — writing into **one
ClickHouse cluster**, with **hard isolation** between tenants and organisations and **soft
filtering** by zone and system. A monitoring provider can collect NetFlow/IPFIX/sFlow from many
customers' isolated networks (overlapping RFC 1918 space included) and keep each customer's data
provably separate, on both the write and the read side.

This page is the operator runbook. The write barrier's mechanism is explained on the
[ClickHouse](../configuration/clickhouse.md#write-isolation-multi-tenant) page; here we give the
end-to-end onboarding recipe, the read-side hardening, topology guidance, and the scaling ceiling.

## The identity model

Every persisted flow carries four identity columns (see
[Identity columns](../configuration/clickhouse.md#identity-columns)):

| ID | Nature | Isolation | Enforced by |
|---|---|---|---|
| `tenant` | ownership | **hard** (read + write) | server-validated: `CHECK tenant = getSetting('SQL_tenant')` vs the CH user's `CONST` setting |
| `organisation` | ownership subdivision | **hard** | second `CHECK` column — the isolation unit is `(tenant, organisation)`, so credentials are per-`(tenant, org)` |
| `zone` | network placement | soft (filter) | payload column, unvalidated (the isolated network / "network zone") |
| `system` | collector provenance | soft (filter) | payload column, unvalidated (per-instance identity) |

The **hard/soft split is a placement decision**: hard IDs are anchored to the authenticated
ClickHouse credential — identity comes from *authentication, never from a client claim* — so a
tampered collector config cannot cross-write. Soft IDs are ordinary unvalidated payload used only
for filtering. All four are riptide-populated columns.

## Prerequisites

- ClickHouse with **replicated access storage** so users, roles, and row policies exist on every
  node — otherwise a credential provisioned on one node is unknown on another. The default config
  stores SQL-created users in a node-local `local_directory`, so the snippet must **replace** the
  whole `user_directories` block (not merge into it) — keep `users_xml` for the bootstrap admin,
  drop `local_directory`, and add `replicated` so new users land in Keeper:

  ```xml
  <!-- /etc/clickhouse-server/config.d/access-storage.xml -->
  <clickhouse>
      <user_directories replace="replace">
          <users_xml>
              <path>users.xml</path>
          </users_xml>
          <replicated>
              <zookeeper_path>/clickhouse/access/</zookeeper_path>
          </replicated>
      </user_directories>
  </clickhouse>
  ```

  Without `replace="replace"` the snippet appends to the default block, `local_directory` stays,
  and SQL-created users are written there — node-local, exactly the failure this prevents.

- The `SQL_` custom-settings prefix enabled (write-barrier requirement) — see the
  [server requirement](../configuration/clickhouse.md#server-requirement).
- Riptide running in [validate mode](../configuration/clickhouse.md#schema-ownership)
  (`manage-schema=false`). On a fresh **single-node** server, `onboard --create-schema` bootstraps
  the database and `flows` table itself (see [What it provisions](#what-it-provisions)) — no manual
  DDL. On a **replicated cluster**, pre-create the `flows` table admin-side (e.g.
  `ReplicatedMergeTree`, `ON CLUSTER`) and run `onboard` *without* `--create-schema`: the bootstrap
  DDL is single-node (`MergeTree()`, no `ON CLUSTER`) and would create a node-local table on
  whichever replica the admin client hits, while the roles and grants replicate.

## Onboard a tenant

Use the `onboard` subcommand — it runs the whole recipe idempotently with one substituted
`(tenant, org)` and prints the config stanza for the tenant's collector. It runs with **admin**
credentials passed at invocation (never the collector's scoped credential) and, because it needs no
Spring context, the running collector never contains any provisioning code.

```bash
java -jar riptide.jar onboard \
  --admin-url https://clickhouse:8443 --admin-user admin --admin-password env://CH_ADMIN_PW \
  --tenant acme --org acme-eu \
  --writer-secret env://ACME_WRITER_PW --reader-secret env://ACME_READER_PW
```

```
# printed to stdout — paste into the tenant's collector config:
riptide.clickhouse.username=writer_acme@riptide
riptide.clickhouse.password=env://ACME_WRITER_PW
riptide.identity.tenant=acme
riptide.identity.organisation=acme-eu
```

Secret references resolve through the built-in resolvers (`plain`, `env://`, `file://`);
`--writer-secret`/`--reader-secret` are the passwords for the tenant's writer and BI users. Add
`riptide.clickhouse.manage-schema=false` and `riptide.identity.zone` to the collector config as
needed.

On a fresh single-node server, add **`--create-schema`** to the first onboard: it creates the
database and `flows` table (with `--ttl-days N` retention, default 30, max 10950 — ClickHouse's
`DateTime` ends in 2106) before the grants and constraints that need them. `--ttl-days` applies
only to a table this run creates: it requires `--create-schema`, and a re-run against an existing
table warns that retention is unchanged. Without the flag, a missing database or table **fails before any
statement runs** — so a typo'd `--database` can never silently provision a phantom database — and
the run sends no `CREATE` statement at all, which keeps a least-privilege admin working (ClickHouse
checks `CREATE` privileges even when `IF NOT EXISTS` would no-op).

### Admin privileges

| mode | minimum privileges for the admin credential |
|---|---|
| default (schema exists) | `CREATE USER`/`CREATE ROLE`/`CREATE QUOTA`/`CREATE ROW POLICY`, `ALTER USER`/`ALTER ROLE`, `DROP USER`/`DROP ROW POLICY` (offboard), `ALTER TABLE` on `<db>.flows`, `INSERT`, `SELECT` on `<db>.flows` plus `SELECT` on `system.databases/tables/columns` **with grant option** (they are granted onward to the roles), and **`SHOW USERS ON *.*`** (see the caution below) |
| re-running against a database another admin provisioned | the above, plus **`ROLE ADMIN`** — granting a role it did not itself create requires it, and the per-database roles were created by whichever admin ran the first `onboard` there |
| `--create-schema` | the above, plus `CREATE DATABASE ON <db>.*`, `CREATE TABLE ON <db>.*` (the `flows` table and the rollup targets) and `CREATE VIEW ON <db>.*` (the rollups' materialized views) |
| `revoke-legacy` (standalone — it needs none of the rows above) | `INSERT`, `SELECT` on `<db>.*` **with grant option** (revoking a role's privilege needs it; `ROLE ADMIN` does not), plus `SELECT` on `system.grants` and `system.row_policies` — see [Revoking the pre-rename roles](#revoking-the-pre-rename-roles-on-a-migrated-database) |

`onboard` is safe to re-run: it reconciles the writer/reader **passwords** to the current secret, so
rotating a secret and re-running updates ClickHouse (the users' `CONST` settings are preserved; the
row policies are **re-asserted** to match the recipe — a policy `TO` list widened by hand is
reverted on the next run, so route extra grantees through provisioning, not manual DDL. Reverting
is not a neutral act: a grantee removed from a policy while it still holds `SELECT` is left governed
by no policy and reads *every* tenant's rows, so a hand-added grantee is silently widened rather
than trimmed). To remove a tenant: `offboard --admin-url … --tenant acme --yes` (drops its users — the `@<database>` accounts **and** the pre-rename unqualified ones — and its row policy from `flows` **and every rollup**; the database's roles/constraints/quota are left in place, shared as they are by every tenant in that database — if you removed the last one, drop them by hand).

### Adding rollups to an existing deployment

A database provisioned before the rollups existed has a perfectly good `flows` table, so the
schema check passes while the rollups are simply absent. `onboard` checks for them separately and
refuses to run without `--create-schema`:

```
database 'riptide' is missing the 1-minute rollup tables or their materialized views — re-run
with --create-schema to add them. …
```

Re-running with `--create-schema` adds them in place. This creates tables and materialized views
only — **the `flows` table and its data are untouched**. The check covers each rollup's target
*and* its view, so an interrupted bootstrap that left targets without views is detected rather
than reading as healthy (which would leave the rollups silently empty).

Because a materialized view does not backfill, rollups added this way cover traffic from creation
onward. See [Rollups](../configuration/clickhouse.md#rollups) for the table layout and how to
backfill if you need the history.

**After an upgrade that adds a rollup dimension, re-running `onboard` is required, not optional.** A validate-mode collector issues no DDL, so until it is run riptide declines all four rollups and answers every long-range query from raw `flows` — correct, but truncated at the raw retention window.

Re-running `onboard` brings existing rollups up to the running version's shape, appending any dimension a release has added. It is idempotent and safe to re-run: the statements no-op once a rollup is current, and no aggregation is interrupted. This is the only path a provisioned deployment has, since its collector runs in validate mode and issues no DDL.

**Then restart the collector.** Which rollups are usable is decided once, at startup — a schema does not change under a running collector — so one that declined them at boot keeps answering from raw `flows` until it restarts, no matter how complete the repair was. Without the restart the operator sees a successful `onboard` and no change in behaviour.

If `onboard` reports a rollup as *left as it is*, that rollup is deliberately not repaired and gets no materialized view. The rest of the run proceeds normally — roles, users and password rotation are unaffected — and the named rollup stays out of the query path until the state its message describes is fixed.

`onboard` reads each rollup's live sorting key first and applies the same rule the collector does, so a change that would *shrink* a key is refused rather than applied. That guard is not optional: ClickHouse itself accepts such a shrink on an upgraded table, because the primary key was frozen at the narrower shape, so nothing below riptide would stop a rollup's grain changing in place.

### What it provisions

The recipe is **role-based**: the schema, the grants, the reader hardening, the CHECK barrier, and
the quota are one-time **per-database** objects, so per-tenant reduces to the scoped users + role
grants + one row policy per table (`flows` and each rollup, all sharing the tenant-literal
predicate). `onboard` ensures the per-database objects on first run and adds the per-tenant part:

```sql
-- Only with --create-schema, and only when the schema is actually missing (a default run emits
-- no CREATE statement, so it needs no CREATE privileges). IF NOT EXISTS never replaces a table.
CREATE DATABASE IF NOT EXISTS riptide;
CREATE TABLE IF NOT EXISTS riptide.flows (…);  -- single-node MergeTree, TTL from --ttl-days (default 30)
-- The 1-minute rollups: targets first, then the materialized views that feed them (a view cannot
-- be created before its TO table). TTL is 365 days — the aggregates outlive the raw rows.
CREATE TABLE IF NOT EXISTS riptide.flows_by_application_1m (…);          -- and three more
CREATE MATERIALIZED VIEW IF NOT EXISTS riptide.flows_by_application_1m_mv
  TO riptide.flows_by_application_1m AS SELECT … FROM riptide.flows AS f GROUP BY …;
-- Once per database (idempotent): roles carry every per-tenant grant and the reader hardening.
-- Users, roles and quotas are instance-wide objects, so their names carry the database.
CREATE ROLE IF NOT EXISTS `flow_writer@riptide`;
GRANT INSERT ON riptide.flows TO `flow_writer@riptide`;
-- The writer also reads flows: a materialized view runs as the inserting user, so pushing a row
-- into a rollup requires SELECT on the view's source table.
GRANT SELECT ON riptide.flows TO `flow_writer@riptide`;
CREATE ROLE IF NOT EXISTS `flow_reader@riptide`;
GRANT SELECT ON riptide.flows TO `flow_reader@riptide`;
GRANT SELECT ON system.databases TO `flow_reader@riptide`;
GRANT SELECT ON system.tables    TO `flow_reader@riptide`;
GRANT SELECT ON system.columns   TO `flow_reader@riptide`;   -- the catalog a query builder needs
-- readonly = 2 blocks writes and DDL while tolerating the read-only settings an HTTP client sends
-- per query; readonly = 1 would reject those and break the connection.
ALTER ROLE `flow_reader@riptide` SETTINGS readonly = 2, allow_ddl = 0;
-- The write barrier: each row's tenant/org must equal the writer credential's pinned CONST setting.
ALTER TABLE riptide.flows ADD CONSTRAINT IF NOT EXISTS tenant_pinned CHECK tenant = getSetting('SQL_tenant');
ALTER TABLE riptide.flows ADD CONSTRAINT IF NOT EXISTS org_pinned    CHECK organisation = getSetting('SQL_org');
-- One quota keyed by user gives every writer its own bucket (written_bytes — written_rows is not a metric).
CREATE QUOTA IF NOT EXISTS `flow_ingest@riptide` FOR INTERVAL 1 hour MAX written_bytes = 50000000000
  KEYED BY user_name TO `flow_writer@riptide`;
-- Every rollup gets the same treatment as flows, for both roles.
GRANT INSERT ON riptide.flows_by_application_1m TO `flow_writer@riptide`;   -- and the other three
GRANT SELECT ON riptide.flows_by_application_1m TO `flow_reader@riptide`;

-- Per tenant (the residual): two scoped users + role grants + one row policy per table.
CREATE USER IF NOT EXISTS `writer_acme@riptide` IDENTIFIED WITH sha256_password BY '…'
  SETTINGS SQL_tenant = 'acme' CONST, SQL_org = 'acme-eu' CONST;
GRANT `flow_writer@riptide` TO `writer_acme@riptide`;
CREATE USER IF NOT EXISTS `bi_acme@riptide` IDENTIFIED WITH sha256_password BY '…'
  SETTINGS SQL_tenant = 'acme' CONST, SQL_org = 'acme-eu' CONST;
GRANT `flow_reader@riptide` TO `bi_acme@riptide`;
-- The writer is named on the flows policy alongside the reader to CONSTRAIN it, not to enable it:
-- a policy is NOT deny-by-default for a user it does not name (see the note below), so an unnamed
-- writer would read every tenant's flows. Its predicate is the same tenant the CHECK barrier
-- already pins, so it grants no extra row and removes every foreign one.
-- The policy name is NOT qualified: a policy's identity is `name ON db.table`, so it is already
-- scoped by the table it hangs on.
CREATE ROW POLICY OR REPLACE acme_iso ON riptide.flows
  FOR SELECT USING tenant = 'acme' TO `bi_acme@riptide`, `writer_acme@riptide`;
-- The rollup policies name the reader only. The writer holds no SELECT on a rollup target at all,
-- so being unnamed there exposes nothing; it reaches a rollup by INSERT through its materialized
-- view, which no row policy filters.
CREATE ROW POLICY OR REPLACE acme_iso ON riptide.flows_by_application_1m
  FOR SELECT USING tenant = 'acme' TO `bi_acme@riptide`;                    -- and the other three
```

### Object names carry their database

ClickHouse users, roles and quotas live in one flat namespace across the whole server — a database is not a scope for them.
So every one of those names is composed as `<name>@<database>`, and it is the database that makes the tenant's account unique on the instance.

This matters as soon as one server holds two riptide databases.
Without the qualifier, onboarding tenant `acme` into a second database rewrote the first `writer_acme`'s password, and one shared `flow_writer` role gave every writer `INSERT` on every provisioned database's `flows` and rollups.
With it, the second onboarding creates a separate account, and a cross-database write is refused with `ACCESS_DENIED` because the grant simply is not there.
The `CHECK` barrier cannot substitute for this: `tenant_pinned` passes whenever both databases carry the same tenant id.

`@` is the delimiter because tenant and database names are both restricted to `[A-Za-z0-9_-]+`, which cannot produce it.
An underscore would be ambiguous — tenant `foo` in database `bar_baz` and tenant `foo_bar` in database `baz` would both spell `writer_foo_bar_baz`, and the second onboarding would take over the first's account.
Names containing `@` must be backticked in SQL, and they authenticate normally over both HTTP basic auth and the native protocol.

:::caution[Percent-encode the `@` in any URL that embeds the username]

Riptide's own `riptide.clickhouse.username` is a plain field and takes the name verbatim — no encoding.
But anywhere the username is embedded *inside a URL* — a JDBC URL's userinfo, `http://user:pass@host:8123/`, Grafana's ClickHouse datasource when configured by URL, `clickhouse-client --url` — the `@` terminates the userinfo component and the connection fails with an opaque host-resolution or authentication error.
Write it as `%40`:

```
http://writer_acme%40riptide:secret@clickhouse:8123/
jdbc:clickhouse://clickhouse:8123/riptide?user=writer_acme%40riptide
```

`clickhouse-client --user 'writer_acme@riptide'` and Grafana's separate *Username* field need no encoding, because neither parses the value as part of a URL.

:::

Row policies are the exception, and deliberately so.
A policy's identity is already `name ON db.table`, so `acme_iso ON db_a.flows` and `acme_iso ON db_b.flows` are two distinct objects.
Their names are left alone.

### Upgrading a deployment onboarded before the rename

Nothing breaks on upgrade, and nothing is removed for you.
An instance provisioned under the old naming keeps its unqualified `writer_<tenant>` / `bi_<tenant>` users and its `flow_writer` / `flow_reader` roles, and its collector keeps working.

Re-running `onboard` **adds** the qualified account alongside the old one — it does not rename or drop it, because the tenant's collector is still authenticating as the old one until you paste the new stanza.
It also **keeps the old account named on the tenant's row policies** for as long as that account exists.
That is not cosmetic: the policy name is unchanged by the rename, so the run rewrites the *existing* policy's `TO` list, and an account dropped from it would be named by no policy and start reading every tenant's rows (see [A row policy is not deny-by-default](#what-it-provisions), further down this page).
Once you retire the old account, the next `onboard` stops naming it.

The run prints a warning naming the leftover account and the `DROP USER` to run:

```
warning: the pre-rename account 'writer_acme' still exists on this server, and it is instance-wide
— it is not specific to database 'riptide'. It holds the old instance-wide write role, so it can
still INSERT into every database provisioned before the rename. This run kept it on this tenant's
row policies, so it stays filtered to its own rows meanwhile. Retire it only once EVERY database's
collector and datasource for tenant 'acme' has moved to the qualified account — dropping it sooner
takes those other databases offline. Then: DROP USER `writer_acme`, and re-run onboard here so the
policies stop naming it.
```

The order that avoids downtime, per tenant:

1. Re-run `onboard` for the tenant, **in every database on this server where it is provisioned**. Each gets its own qualified account, created with the same secret.
2. Update every collector config to the username from that database's new stanza, and every Grafana/MCP datasource to the matching `bi_<tenant>@<database>`. Restart them.
3. Only now drop the old accounts: ``DROP USER `writer_acme`;`` and ``DROP USER `bi_acme`;``.
4. Re-run `onboard` once more in each database, so the policies stop naming the accounts you just dropped.
5. Once a database has no pre-rename account left serving it, run [`revoke-legacy`](#revoking-the-pre-rename-roles-on-a-migrated-database) there. Steps 1–4 migrate a *tenant*; only this closes the *database* to the old roles, which other tenants may still be using elsewhere on the server.

Step 1 must cover every database **before** step 3, because the old accounts carry no database in their name — they are one object shared by all of them.
Until step 3 the old account still holds the old instance-wide role, so it can still write to every database provisioned before the rename — that is the whole reason to finish.

:::caution[`offboard` also drops the pre-rename accounts, and that reaches every database]

`offboard` drops both namings, so it revokes a tenant whether or not that tenant has been migrated — but the unqualified `writer_<tenant>` / `bi_<tenant>` it drops are keyed on the tenant alone.
`offboard --database db_b --tenant acme` therefore removes the same credential that an unmigrated `db_a` is still ingesting with.
This is inherent to the old naming, not new behaviour, and dropping them is still correct: leaving them would report a revocation that did not happen.

If any **other** database on this server still has that tenant on the old naming, it has just lost its credential. Re-onboard it to give it a `@<database>` account of its own.
Migrating every database first (step 1 above) avoids the situation entirely.

:::

### Revoking the pre-rename roles on a migrated database

Step 4 above finishes the migration *for a tenant*. It does not close the database, and that gap is worth stating plainly: the pre-rename `flow_writer` / `flow_reader` roles are instance-wide, and re-onboarding never took away the `INSERT` / `SELECT` they already hold on the databases they covered.
So `db_a` can be fully migrated — every tenant re-onboarded, every legacy account of those tenants dropped — while a legacy account belonging to some **other**, unmigrated tenant still reaches it.
Measured on the pinned image: that account inserts into the migrated database's `flows`.

`revoke-legacy` takes those grants back, one database at a time:

Start with `--dry-run`, which runs every check and prints the exact statements without executing one:

```bash
java -jar riptide.jar revoke-legacy \
  --admin-url https://clickhouse:8443 --admin-user admin --admin-password env://CH_ADMIN_PW \
  --database db_a --dry-run
```

Then replace `--dry-run` with `--yes` to apply it. `--yes` is required, for the reason `offboard` requires it: this takes privileges away from a live server, and `--database` defaults to `riptide`.

Per-database is what makes it safe. `REVOKE … ON db_a.flows FROM flow_writer` leaves the same account still writing to an unmigrated `db_b` through the same role, so closing one database never takes another's ingest down.
The revoke names that database's `flows`, every rollup target and every rollup materialized view — the mirror of what `onboard` grants there — and nothing else.
It takes back `INSERT`, `SELECT` and `SHOW TABLES`; the `_mv` views carry only the last of those, and it matters, because a `SELECT` on a rollup view reads around the row policy attached to its target.
The **roles are not dropped** — they carry no database in their name, so dropping one would revoke every database still on the old naming.

Run it in each database as you finish migrating it. A second run on the same database is a no-op and says so.

#### When it refuses

It changes nothing in any of these cases. Every one of them is a state where the checks would otherwise have found nothing and read that as a clean answer:

- **The database or its `flows` table does not exist.** A typo'd `--database` matches no grant and no policy, so without this it would report an all-clear over an exposure it never looked at.
- **A pre-rename grantee still serves this database.** The check reads the row policies on `db_a`'s tables: `onboard` keeps every live pre-rename account named there and stops naming one you have retired, so a name carrying no `@db_a` means that grantee still depends on this database. Finish steps 1–4 for its tenant and run again. Note it may be a **role** rather than a user — ClickHouse puts both in a policy's grantee list — so check which before removing anything.
- **The database has no row policies at all.** Zero policies is not "nobody is served", it is "nothing to reason from", and it is equally what a hand-provisioned database looks like — one whose collector may still be authenticating as a pre-rename account. Run `onboard` for each of its tenants first; that is what creates the policies this check reads.
- **A policy applies to `ALL`.** Such a policy stores an *empty* grantee list, so the check would see no names and conclude nobody is served on a database where everyone is. Re-issue it naming its grantees explicitly, as `onboard` does.
- **A pre-rename role holds a grant wider than these tables** (`ON db_a.*`, or `ON *.*`). Running would leave the role holding everything else in the database while reporting it closed, and no re-run would ever be the promised no-op. Revoke the wider grant by hand, then re-run.
- **It cannot read the catalog.** ClickHouse *refuses* `system.grants` and `system.row_policies` to an admin without the privilege rather than filtering the rows away, so "nothing found" and "nothing to find" are the same answer. Guessing either way is a real failure — one leaves the exposure open while reporting it closed, the other revokes a credential a running collector is still using — so the run aborts and names the grant to add.

One failure it cannot pre-empt: if a `REVOKE` fails part-way through, the database is left **half-revoked** and the message says so rather than claiming nothing changed. Re-running finishes the job — the statements are idempotent. The usual cause is the one privilege no catalog read can check, `GRANT OPTION`.

#### Dropping the roles

Once no database needs the roles any more **and no user holds them**, drop them and the orphaned quota by hand. Checking that second condition needs its own grant, because `system.role_grants` is refused — not filtered — to an admin without it:

```sql
GRANT SELECT ON system.role_grants TO <your admin user>;   -- otherwise the query below is refused
SELECT * FROM system.role_grants WHERE granted_role_name IN ('flow_writer', 'flow_reader');
-- only when that returns nothing:
DROP ROLE IF EXISTS flow_writer, flow_reader;
DROP QUOTA IF EXISTS flow_ingest;
```

Without the grant an under-privileged operator sees an empty result and concludes it is safe to drop roles another tenant still holds. That blindness is why the per-database `revoke-legacy` above exists: it closes each database as you migrate it, so the instance-wide drop is a tidy-up rather than the only defence.

:::caution[`revoke-legacy`'s privileges, in full]

It needs **only these** — not `CREATE USER`/`CREATE ROLE`/`CREATE ROW POLICY`, and not `SHOW USERS`, none of which it uses:

```sql
GRANT INSERT, SELECT ON db_a.* TO <your admin user> WITH GRANT OPTION;
GRANT SELECT ON system.grants       TO <your admin user>;
GRANT SELECT ON system.row_policies TO <your admin user>;
```

`GRANT OPTION` is what lets an admin revoke a role's privilege at all — `ROLE ADMIN` is *not* required for that, and it is also the one requirement neither catalog read can detect, so omitting it fails on the first `REVOKE` rather than up front.
`SHOW USERS` covers neither read, and neither `SHOW ROLES` nor `SHOW ROW POLICIES` covers `system.grants`.
The refusal names the missing one:

```
Code: 497. DB::Exception: min_admin: Not enough privileges. To execute this query,
it's necessary to have the grant SELECT for at least one column on system.grants. (ACCESS_DENIED)
```

:::

:::caution[The legacy-account check needs `SHOW USERS`, and `onboard` refuses to run without it]

`onboard` finds pre-rename accounts by reading `system.users`, and ClickHouse **refuses** that query outright to an admin that lacks the privilege — it does not filter the rows away:

```
Code: 497. DB::Exception: min_admin: Not enough privileges. To execute this query,
it's necessary to have the grant SELECT ON system.users. (ACCESS_DENIED)
```

`CREATE USER` / `DROP USER` do **not** imply it. Grant it explicitly:

```sql
GRANT SHOW USERS ON *.* TO <your admin user>;
```

Without it the run **aborts before executing anything** and tells you this. That is deliberate, and it is not merely a missing warning: `onboard` re-issues `<tenant>_iso`, and an admin that cannot see whether a pre-rename account exists cannot keep it on the policy — so continuing would leave that account governed by no policy, reading every tenant's rows, and exit `0`. Failing costs a re-run; succeeding wrongly costs a cross-tenant leak.

Reading the existing policy instead does not avoid the grant: `system.row_policies` and `SHOW CREATE ROW POLICY` are refused to the same admin.

:::

:::warning[A row policy is not deny-by-default for users it does not name]

ClickHouse ships `users_without_row_policies_can_read_rows` set to `true` in its default `config.xml`, and has done since the setting was introduced; grep that name rather than a line number, which moves between releases.
A user holding `SELECT` on a table and named by **no** policy on that table therefore reads **every row**, not none.
Measured on the pinned image, not inferred: with `acme` and `other` rows present, a granted user named by the policy returned only `acme`; a granted user named by no policy returned both.

Two consequences run through this page.
Naming a principal on a policy *restricts* it — it never grants access the principal would otherwise lack.
And removing a principal from a policy while it still holds `SELECT` *widens* it to every tenant, which is why `onboard` keeps a live pre-rename account on the policies it rewrites (see [Upgrading a deployment onboarded before the rename](#upgrading-a-deployment-onboarded-before-the-rename), earlier on this page).

If you set this to `false`, the recipe still works; the failure mode simply flips from "reads too much" to "reads nothing".

:::

:::note[Why `OR REPLACE` rather than `IF NOT EXISTS`]

For the same reason `onboard` re-issues `ALTER USER` for passwords: a policy left over from an
earlier run keeps its old `TO` list, so a re-run would not pick up a changed grantee. `OR REPLACE`
makes the policy match the recipe every time.

:::

:::note

A single shared row policy scoped by `getSetting('SQL_tenant')` does **not** work — ClickHouse
raises `UNKNOWN_SETTING` whenever a principal without that setting evaluates it. The row policy must
stay a per-tenant literal.

:::

### What the reader guarantees

The hardened BI credential is a real boundary, not just a filter (proven by `TenantQueryIsolationIT`
and, through the subcommand, `TenantOnboardingIT`):

- **Reads stay in-tenant** — the row policy limits `bi_acme@riptide` to `tenant = 'acme'` rows, even
  against a shared table holding every tenant. The rollups carry the same policy, so a
  pre-aggregated query is bounded exactly as the raw one is — a rollup is not a way around the
  boundary.
- **Reads stay in-database** — and this half is the *grant*, not the policy. The reader holds
  `flow_reader@<database>`, which carries `SELECT` on that database's tables and no other's, so a
  query against another database is refused with `ACCESS_DENIED`. A policy could not have done
  this: the other database has no policy naming this reader, and an unnamed user reads everything
  (see the warning above). Before the roles were qualified, a `bi_*` account held `SELECT` on every
  provisioned database and read in full any of them where its tenant had never been onboarded.
- **Cannot write** — the `flow_reader@riptide` role grants no `INSERT` and pins `readonly`, so a
  write is rejected (`ACCESS_DENIED`).
- **Cannot change schema** — `allow_ddl = 0` rejects any DDL, so a compromised dashboard credential
  cannot alter or drop the table.
- **Query builder still works** — the `system.databases/tables/columns` grants let Grafana's query
  builder introspect the schema.

## Grafana topology

The isolation boundary in Grafana **OSS** is **one Grafana org (or one Grafana instance) per
tenant**, each with a datasource that authenticates as that tenant's `bi_<tenant>@<database>` user. The ClickHouse
row policy does the enforcing; Grafana just holds the right credential.

What is **not** a boundary on OSS:

- **Per-tenant datasources inside one shared org** — any user in that org can query any datasource,
  so this leaks across tenants. Datasource-level permissions are a Grafana **Enterprise** feature.
- **Dashboard-variable tenant filtering** (a `$tenant` template variable) — never a boundary at
  all: a viewer can edit the variable to any value. Use it for UX within a tenant, never for
  isolation.

The guarantee comes from the ClickHouse credential + row policy, so it holds regardless of what a
dashboard sends.

## Scaling ceiling

Per-tenant ClickHouse users and row policies work comfortably into the **low hundreds of tenants**.
Beyond that, the per-user access objects become the bottleneck and the model pivots to a **shared
BI user keyed by `quota_key`** with tenant scoping applied at the application/query layer rather
than one CH user per tenant.

This is a known future migration, **out of scope** for the current release — documented here so it
is a planned step, not a surprise. Nothing in the per-tenant model above blocks it: the identity
columns and row-policy predicates carry over unchanged.
